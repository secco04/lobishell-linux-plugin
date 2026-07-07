package de.lobianco.saftssh.linux

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.util.Collections

private const val TAG = "LinuxSessionService"
private const val NOTIF_CHANNEL_ID = "linux_session"
private const val NOTIF_ID = 1

/**
 * Bound AIDL service that forkpty()'s a shell and hands the PTY master fd back to the main LobiShell
 * app over Binder.
 *
 * If a real Ubuntu rootfs is installed, it launches `/bin/bash --login` inside it via proot
 * (GPL-2.0-or-later — see LICENSE); otherwise it falls back to `/system/bin/sh`.
 */
class LinuxSessionService : Service() {

    /**
     * Thread-safe list of all open sessions so we can clean up in onDestroy().
     */
    private val openSessions: MutableList<SessionImpl> =
        Collections.synchronizedList(mutableListOf())

    /** userlandId -> the persistent sshd session running in it, if any. Distinct from the
     *  interactive sessions above so it can be looked up by userlandId, but the SessionImpl is
     *  ALSO added to [openSessions] so foreground-service tracking covers it for free. */
    private val sshdSessions: MutableMap<String, SessionImpl> =
        Collections.synchronizedMap(mutableMapOf())

    override fun onBind(intent: Intent?): IBinder = serviceStub

    override fun onDestroy() {
        super.onDestroy()
        // Kill every open session when the service is unbound / destroyed.
        synchronized(openSessions) {
            openSessions.forEach { it.destroySilently() }
            openSessions.clear()
        }
        Log.i(TAG, "onDestroy: all sessions cleaned up")
    }

    // ── Foreground promotion ───────────────────────────────────────────────
    // A plain bound service has no priority protection: once BOTH the main app and this plugin
    // are backgrounded, the OS kills this process under memory pressure and the shell dies
    // silently ("Linux exited"). Running in the foreground while ≥1 session is open protects it,
    // same as any terminal/SSH app with a long-lived background session.

    private fun ensureNotifChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(NOTIF_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    NOTIF_CHANNEL_ID, "Linux session",
                    NotificationManager.IMPORTANCE_MIN
                ).apply { description = "Keeps the Linux terminal session running in the background" }
            )
        }
    }

    private fun promoteToForeground() {
        ensureNotifChannel()
        val notification = Notification.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("Linux session running")
            .setContentText("Tap to return to LobiShell")
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setOngoing(true)
            .apply {
                packageManager.getLaunchIntentForPackage("de.lobianco.saftssh")?.let { launch ->
                    setContentIntent(
                        PendingIntent.getActivity(
                            this@LinuxSessionService, 0, launch,
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        )
                    )
                }
            }
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    /** Called whenever a session closes; drops foreground once none remain. */
    private fun demoteFromForegroundIfIdle() {
        if (synchronized(openSessions) { openSessions.isEmpty() }) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    // ── AIDL stub ──────────────────────────────────────────────────────────

    private val serviceStub = object : ILinuxSessionService.Stub() {

        override fun createSession(
            cols: Int, rows: Int, cwd: String?, userlandId: String?, callback: ILinuxSessionCallback?,
        ): ILinuxSession? {
            // Forward setup progress to the client terminal (best-effort; oneway, can't block us).
            val progress: (String) -> Unit = { line -> runCatching { callback?.onProgress(line) } }
            val id = userlandId?.takeIf { it.isNotBlank() } ?: "default"
            return try {
                val session = buildSession(cols, rows, cwd, id, progress)
                openSessions.add(session)
                promoteToForeground()
                Log.i(TAG, "createSession[$id]: pid=${session.pidValue} masterFd=${session.masterFd}")
                session
            } catch (e: Exception) {
                Log.e(TAG, "createSession failed", e)
                runCatching { callback?.onProgress("Error: ${e.message}") }
                null
            }
        }

        override fun listUserlandIds(): Array<String> =
            RootfsInstaller.listUserlandIds(this@LinuxSessionService).toTypedArray()

        override fun userlandSize(userlandId: String?): Long =
            RootfsInstaller.userlandSize(this@LinuxSessionService, userlandId ?: "default")

        override fun clearUserland(userlandId: String?): Long =
            RootfsInstaller.clear(this@LinuxSessionService, userlandId ?: "default")

        override fun startSshd(userlandId: String?, port: Int, authMode: String?, secret: String?): Boolean =
            this@LinuxSessionService.startSshdInternal(
                userlandId?.takeIf { it.isNotBlank() } ?: "default",
                port,
                if (authMode == "pubkey") "pubkey" else "password",
                secret ?: ""
            )

        override fun stopSshd(userlandId: String?): Boolean =
            this@LinuxSessionService.stopSshdInternal(userlandId?.takeIf { it.isNotBlank() } ?: "default")

        override fun isSshdRunning(userlandId: String?): Boolean =
            sshdSessions.containsKey(userlandId?.takeIf { it.isNotBlank() } ?: "default")
    }

    // ── Persistent sshd (independent of any interactive session) ───────────

    /** Builds the one-shot setup + launch script run inside the userland. Runs entirely as root
     *  (proot `--root-id`) before `exec`-ing into sshd itself, so the same PID persists as sshd
     *  for the session's whole lifetime. The secret is passed via an env var (never interpolated
     *  into the script text) so it can never break out of its quoted `"$LOBISHELL_SSHD_SECRET"`
     *  expansion regardless of its contents. */
    private fun buildSshdSetupScript(port: Int, authMode: String): String {
        val authLines = if (authMode == "pubkey")
            "PasswordAuthentication no\\nPubkeyAuthentication yes\\n"
        else
            "PasswordAuthentication yes\\nPubkeyAuthentication yes\\n"
        val applySecret = if (authMode == "pubkey")
            "mkdir -p /root/.ssh && chmod 700 /root/.ssh && " +
            "printf '%s\\n' \"\$LOBISHELL_SSHD_SECRET\" > /root/.ssh/authorized_keys && " +
            "chmod 600 /root/.ssh/authorized_keys && "
        else
            "echo \"root:\$LOBISHELL_SSHD_SECRET\" | chpasswd && "
        // Critical steps are chained with && (not ;): if openssh-server fails to install (e.g. no
        // network yet, broken mirror) the script now stops there instead of plowing ahead into a
        // config write + exec of a binary that was never actually installed — that silent fall-
        // through was masking install failures as a plain "connection refused" with nothing in
        // logcat to explain why. Everything is logged to a file inside the rootfs so it survives
        // even though this proot invocation has no progress callback back to the main app.
        return "exec >/tmp/lobishell-sshd-setup.log 2>&1; " +
            "mkdir -p /etc/ssh && " +
            "{ [ -e /etc/ssh/ssh_host_rsa_key ] || ssh-keygen -A; } && " +
            // NEEDRESTART_MODE=a (automatic) + Dpkg::Use-Pty=0: openssh-server's postinst pulls in
            // needrestart on modern Ubuntu, which prompts an interactive "restart these services?"
            // dialog of its own — NOT fully suppressed by DEBIAN_FRONTEND=noninteractive since it
            // has its own interactivity check. With no real TTY in this non-interactive proot
            // invocation, that prompt just hangs forever: apt-get install never returns, sshd is
            // never reached, and nothing gets logged because the process is still "alive", just
            // stuck. This is very likely why installing openssh-server manually (in a real
            // interactive session, which behaves differently) worked while the automatic path didn't.
            "{ [ -x /usr/sbin/sshd ] || (apt-get update && " +
            "DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=a NEEDRESTART_SUSPEND=1 " +
            "apt-get install -y -o Dpkg::Use-Pty=0 openssh-server); } && " +
            // sshd's privilege-separation dir (/run/sshd) is normally created by the init system
            // at boot (or openssh-server's own init script). There is no init running inside proot,
            // so without this sshd exits immediately with "Missing privilege separation directory:
            // /run/sshd" — never binding the port at all — which is exactly why sshd showed up as
            // "started" in this service while ss/netstat outside saw nothing listening.
            "mkdir -p /run/sshd && " +
            applySecret +
            "printf 'Port $port\\nListenAddress 0.0.0.0\\nPermitRootLogin yes\\n${authLines}UsePAM no\\n' > /etc/ssh/sshd_config && " +
            "exec /usr/sbin/sshd -D -p $port"
    }

    /** Starts (idempotent) a persistent sshd for [userlandId] on [port], as its OWN proot
     *  invocation — separate `--kill-on-exit` scope from any interactive createSession() PTY, so
     *  starting/stopping it never affects a concurrently open interactive tab on the same
     *  userland, or vice versa. */
    private fun startSshdInternal(userlandId: String, port: Int, authMode: String, secret: String): Boolean {
        sshdSessions[userlandId]?.let { existing ->
            val alive = try {
                android.system.Os.kill(existing.pidValue, 0)  // signal 0: existence check only
                true
            } catch (e: android.system.ErrnoException) {
                false
            }
            if (alive) return true   // genuinely already running — nothing to do
            // A dead entry lingering here (e.g. sshd exited earlier because of a bug this build
            // just fixed) would otherwise block every future start attempt forever — the map only
            // clears on stopSshd() or process death, so simply re-toggling SSH access or reopening
            // the userland could look like "the fix didn't take" when actually it never even ran.
            Log.w(TAG, "startSshd[$userlandId]: stale session (pid=${existing.pidValue} no longer " +
                "alive) — clearing it so this call actually restarts sshd instead of no-op'ing")
            sshdSessions.remove(userlandId)
            openSessions.remove(existing)
            demoteFromForegroundIfIdle()
        }
        if (!RootfsInstaller.isInstalled(this, userlandId)) {
            Log.w(TAG, "startSshd[$userlandId]: rootfs not installed yet")
            return false
        }
        return try {
            // See the identical check + comment in buildSession() — same proot/16KB-page limitation.
            val hostPageSize = android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)
            if (hostPageSize > 4096) {
                Log.w(TAG, "startSshd[$userlandId]: host uses ${hostPageSize / 1024} KB pages — proot/Ubuntu unsupported here")
                return false
            }
            RootfsInstaller.configureRootfs(this, userlandId)
            val rootfs = RootfsInstaller.rootfsDir(this, userlandId).absolutePath
            val tmp    = RootfsInstaller.tmpDir(this, userlandId).apply { mkdirs() }.absolutePath
            val libDir = File(filesDir, "usr/lib").absolutePath
            val nativeLib = applicationInfo.nativeLibraryDir
            val prootBin = "$nativeLib/libproot.so"
            val loader   = "$nativeLib/libproot-loader.so"

            val script = buildSshdSetupScript(port, authMode)
            val args = arrayOf(
                "proot",
                "--kill-on-exit",
                "--root-id",
                "--link2symlink",
                "-r", rootfs,
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys",
                "-b", "$tmp:/tmp",
                "-w", "/root",
                "/usr/bin/env", "-i",
                "HOME=/root",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "TERM=xterm-256color",
                "LANG=C.UTF-8",
                "LOBISHELL_SSHD_SECRET=$secret",
                "/bin/bash", "-c", script,
            )
            val env = arrayOf(
                "PROOT_TMP_DIR=$tmp",
                "PROOT_LOADER=$loader",
                "LD_LIBRARY_PATH=$libDir:$nativeLib",
            )
            val result = PtyLauncher.forkAndExec(prootBin, args, env, 80, 24, "") ?: return false
            val session = SessionImpl(result[0], result[1])
            sshdSessions[userlandId] = session
            openSessions.add(session)
            promoteToForeground()
            Log.i(TAG, "startSshd[$userlandId]: pid=${session.pidValue} port=$port mode=$authMode")
            // forkAndExec only proves the fork+exec syscalls themselves succeeded — it says nothing
            // about whether the SCRIPT then actually installed/started sshd (apt-get can fail from
            // no network yet, a broken mirror, held packages, etc.), so a caller checking
            // isSshdRunning() right after this returns true would otherwise get a false "yes" while
            // sshd never actually bound the port ("connection refused" from outside with no visible
            // error in the app). Recheck liveness once apt/config would realistically have finished
            // and self-heal the map so isSshdRunning() reflects reality afterward.
            checkSshdLivenessLater(userlandId, session.pidValue)
            true
        } catch (e: Exception) {
            Log.e(TAG, "startSshd[$userlandId] failed", e)
            false
        }
    }

    /** Background liveness recheck — see the comment at its call site in [startSshdInternal]. */
    private fun checkSshdLivenessLater(userlandId: String, pid: Int) {
        Thread {
            Thread.sleep(15_000L)
            val stillTracked = sshdSessions[userlandId]?.pidValue == pid
            if (!stillTracked) return@Thread   // stopped/replaced in the meantime — nothing to do
            val alive = try {
                android.system.Os.kill(pid, 0)  // signal 0: existence/permission check only
                true
            } catch (e: android.system.ErrnoException) {
                false
            }
            if (!alive) {
                Log.w(TAG, "startSshd[$userlandId]: pid=$pid died before the port ever came up " +
                    "— see /tmp/lobishell-sshd-setup.log inside the rootfs for why (apt-get " +
                    "install of openssh-server likely failed, or its config was rejected)")
                sshdSessions.remove(userlandId)?.let { openSessions.remove(it); demoteFromForegroundIfIdle() }
            }
        }.apply { isDaemon = true; name = "sshd-liveness-$userlandId" }.start()
    }

    private fun stopSshdInternal(userlandId: String): Boolean {
        val session = sshdSessions.remove(userlandId) ?: return false
        session.destroy()
        Log.i(TAG, "stopSshd[$userlandId]: stopped")
        return true
    }

    // ── Session factory ────────────────────────────────────────────────────

    private fun buildSession(cols: Int, rows: Int, cwd: String?, userlandId: String, progress: (String) -> Unit): SessionImpl {
        val binary: String
        val args: Array<String>
        val env: Array<String>
        val linker: String

        // Preferred path: a real Ubuntu/Debian via proot (full apt). Best-effort install — a no-op
        // once installed. proot's loader (in nativeLibraryDir) mmaps the guest binaries, bypassing
        // data-dir noexec.
        RootfsInstaller.install(this, userlandId, progress)

        if (RootfsInstaller.isInstalled(this, userlandId)) {
            // proot ptrace-passes-through raw kernel syscalls to the GUEST's own dynamic linker,
            // which mmaps Ubuntu's official prebuilt libc/coreutils (built with the standard 4 KB
            // p_align). On a host kernel enforcing real 16 KB pages, mmap() then rejects those
            // segments as misaligned — this is not a proot bug or ours to patch; it needs a rootfs
            // rebuilt with -Wl,-z,max-page-size=16384, which Ubuntu 24.04 doesn't ship. Fail with a
            // clear message instead of the confusing raw glibc crash trace the user would otherwise see.
            val hostPageSize = android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)
            if (hostPageSize > 4096) {
                error(
                    "This device uses ${hostPageSize / 1024} KB memory pages. The Linux userland " +
                    "(Ubuntu via proot) currently only supports 4 KB-page devices — Ubuntu's " +
                    "prebuilt libc isn't built for larger pages yet."
                )
            }
            // (Re)write DNS + apt sources every launch so networking/apt work (and existing installs
            // get fixed without a re-download).
            RootfsInstaller.configureRootfs(this, userlandId)
            progress("Starting Ubuntu (proot)…")
            val rootfs = RootfsInstaller.rootfsDir(this, userlandId).absolutePath
            val tmp    = RootfsInstaller.tmpDir(this, userlandId).apply { mkdirs() }.absolutePath
            val libDir = File(filesDir, "usr/lib").absolutePath
            // proot + its loader ship as native libs in the exec-allowed nativeLibraryDir.
            val nativeLib = applicationInfo.nativeLibraryDir
            val prootBin  = "$nativeLib/libproot.so"
            val loader    = "$nativeLib/libproot-loader.so"

            // On the very first launch, offer to install a curated set of everyday packages (the
            // marker /etc/.lobishell_setup is created afterwards so we only ask once). $ans is a
            // shell var (escaped); $pkgs is substituted by Kotlin.
            // openssh-server is included here so it's already installed by the time anyone ever
            // flips the "Remote SSH access" toggle — startSshdInternal()'s own on-demand
            // `apt-get install` then just short-circuits on `[ -x /usr/sbin/sshd ]` instead of
            // needing a full network install at toggle-time (which is where the earlier
            // needrestart-hang bug bit hardest).
            val pkgs = "curl wget ca-certificates git python3 python3-pip nano vim less htop " +
                "unzip zip iputils-ping iproute2 net-tools dnsutils openssh-client openssh-server " +
                "gnupg sudo file tree"
            val firstRun =
                "if [ ! -e /etc/.lobishell_setup ]; then " +
                "printf '\\n\\033[1mInstall recommended packages?\\033[0m\\n(%s)\\n[Y/n] ' '$pkgs'; " +
                "read ans; case \"\$ans\" in " +
                "n|N) echo 'Skipped — install later with: apt install <pkg>';; " +
                // Same NEEDRESTART_MODE=a / Dpkg::Use-Pty=0 as the sshd-toggle install — openssh-server's
                // postinst can hang on an interactive needrestart prompt even with a real PTY, since
                // our own terminal emulator doesn't render whiptail dialogs.
                "*) apt-get update && DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=a NEEDRESTART_SUSPEND=1 " +
                "apt-get install -y -o Dpkg::Use-Pty=0 $pkgs;; " +
                "esac; touch /etc/.lobishell_setup; fi; exec /bin/bash --login"

            binary = prootBin
            args = arrayOf(
                "proot",
                "--kill-on-exit",
                "--root-id",          // fake uid 0 so apt/dpkg work
                "--link2symlink",     // emulate hard links (apt/dpkg need them) on noexec fs
                "-r", rootfs,
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys",
                "-b", "$tmp:/tmp",
                "-w", "/root",
                "/usr/bin/env", "-i",
                "HOME=/root",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "TERM=xterm-256color",
                "LANG=C.UTF-8",
                "/bin/bash", "-c", firstRun,
            )
            // Run proot DIRECTLY from nativeLibraryDir (exec-allowed) — no linker-exec needed. proot
            // execs its loader (PROOT_LOADER, also in nativeLibraryDir) which mmaps the guest binaries,
            // sidestepping data-dir noexec entirely.
            linker = ""
            env = buildList {
                add("PROOT_TMP_DIR=$tmp")
                add("PROOT_LOADER=$loader")
                add("LD_LIBRARY_PATH=$libDir:$nativeLib")
                add("TERM=xterm-256color")
                add("COLUMNS=$cols")
                add("LINES=$rows")
            }.toTypedArray()
        } else {
            // Fallback: plain /system/bin/sh, direct exec (proot rootfs install failed/unavailable).
            // Start in the plugin's OWN files dir — the cwd passed by the main app points at the
            // main app's data dir, which this plugin (different UID) cannot access (→ "/" + EACCES).
            val home = filesDir.apply { mkdirs() }.absolutePath
            val launchCmd = "cd '${home.replace("'", "'\\''")}' 2>/dev/null; exec /system/bin/sh -i"
            binary = "/system/bin/sh"
            args = arrayOf("sh", "-c", launchCmd)
            linker = ""
            env = arrayOf(
                "TERM=xterm-256color",
                "HOME=$home",
                "PWD=$home",
                "TMPDIR=$home",
                "PATH=/system/bin:/system/xbin",
                "COLUMNS=$cols",
                "LINES=$rows",
            )
        }

        val result = PtyLauncher.forkAndExec(binary, args, env, cols, rows, linker)
            ?: error("forkAndExec returned null — forkpty() failed in native layer")
        return SessionImpl(result[0], result[1])
    }

    // ── ILinuxSession implementation ───────────────────────────────────────

    private inner class SessionImpl(
        /** The PTY master fd held by the service for resize/kill. */
        val masterFd: Int,
        // Named `pidValue` (not `pid`) so it doesn't auto-generate getPid() and clash with the
        // explicit override fun getPid() below that satisfies the AIDL interface.
        val pidValue: Int,
    ) : ILinuxSession.Stub() {

        override fun getPid(): Int = pidValue

        // Guards against a double close of masterFd: adoptFd(masterFd).close() releases the fd
        // NUMBER, which the OS can immediately reuse — a second close would then hit an unrelated
        // fd. The flag makes destroy()/destroySilently() idempotent.
        @Volatile private var destroyed = false

        /**
         * fd HANDOVER STRATEGY
         * ────────────────────
         * The service must RETAIN masterFd to service subsequent resize() and destroy() calls.
         * But ParcelFileDescriptor.adoptFd() takes ownership and will close the fd on GC/close.
         * Solution: dup(masterFd) → give the dup to the client via adoptFd().
         *
         *   Client side: reads/writes to the dup'd fd, closes PFD when done.
         *   Service side: masterFd stays valid until destroy() is called.
         *
         * The dup is performed in native dupFd() to avoid an extra FileDescriptor JNI round-trip.
         */
        override fun getPtyFd(): ParcelFileDescriptor {
            val clientFd = PtyLauncher.dupFd(masterFd)
            check(clientFd >= 0) { "dupFd($masterFd) failed" }
            // adoptFd takes ownership: when the PFD is closed/GC'd it closes clientFd.
            return ParcelFileDescriptor.adoptFd(clientFd)
        }

        override fun resize(cols: Int, rows: Int) {
            if (destroyed) return
            PtyLauncher.resizePty(masterFd, cols, rows)
        }

        /**
         * DESTROY STRATEGY
         * ────────────────
         * 1. Close the retained masterFd first: wrapping it in adoptFd() and immediately
         *    closing gives us the correct close(2) without adding a separate JNI closeFd().
         *    Once the master side is closed the child's read() on the slave side gets EIO and
         *    the shell exits naturally.
         * 2. SIGKILL the child as a backstop in case natural exit doesn't happen fast enough.
         * 3. Remove from the open-session list.
         */
        override fun destroy() {
            if (destroyed) { openSessions.remove(this); demoteFromForegroundIfIdle(); return }
            destroyed = true
            runCatching {
                // Close the service's copy of masterFd.
                ParcelFileDescriptor.adoptFd(masterFd).close()
            }.onFailure { Log.w(TAG, "destroy: close masterFd=$masterFd failed", it) }

            runCatching {
                PtyLauncher.killProcess(pidValue)
            }.onFailure { Log.w(TAG, "destroy: killProcess pid=$pidValue failed", it) }

            openSessions.remove(this)
            demoteFromForegroundIfIdle()
            Log.i(TAG, "destroy: pid=$pidValue masterFd=$masterFd cleaned up")
        }

        /** Called from onDestroy() without touching the session list (already being cleared). */
        fun destroySilently() {
            if (destroyed) return
            destroyed = true
            runCatching { ParcelFileDescriptor.adoptFd(masterFd).close() }
            runCatching { PtyLauncher.killProcess(pidValue) }
        }
    }
}
