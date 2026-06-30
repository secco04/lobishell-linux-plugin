package de.lobianco.saftssh.linux

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.util.Collections

private const val TAG = "LinuxSessionService"

/**
 * Bound AIDL service that forkpty()'s a shell and hands the PTY master fd back to the main LobiShell
 * app over Binder.
 *
 * If a userland bootstrap is installed (Phase 2) it launches `$PREFIX/bin/bash --login` via the
 * linker-exec trick; otherwise it falls back to `/system/bin/sh` (Phase 1b). See [BootstrapInstaller].
 */
class LinuxSessionService : Service() {

    /**
     * Thread-safe list of all open sessions so we can clean up in onDestroy().
     */
    private val openSessions: MutableList<SessionImpl> =
        Collections.synchronizedList(mutableListOf())

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
            val pkgs = "curl wget ca-certificates git python3 python3-pip nano vim less htop " +
                "unzip zip iputils-ping iproute2 net-tools dnsutils openssh-client gnupg sudo file tree"
            val firstRun =
                "if [ ! -e /etc/.lobishell_setup ]; then " +
                "printf '\\n\\033[1mInstall recommended packages?\\033[0m\\n(%s)\\n[Y/n] ' '$pkgs'; " +
                "read ans; case \"\$ans\" in " +
                "n|N) echo 'Skipped — install later with: apt install <pkg>';; " +
                "*) apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y $pkgs;; " +
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
        } else if (run { BootstrapInstaller.install(this); BootstrapInstaller.isInstalled(this) }) {
            val prefix = BootstrapInstaller.prefixDir(this).absolutePath
            val home = BootstrapInstaller.homeDir(this).apply { mkdirs() }.absolutePath
            File("$prefix/tmp").mkdirs()

            binary = "$prefix/bin/bash"
            args = arrayOf("bash", "--login")
            // App-data binaries are noexec on API 29+, so route the first exec through the system
            // linker. The termux-exec LD_PRELOAD shim (shipped in the bootstrap) then applies the
            // same trick to every child execve(), so `ls`, `apt`, … work transparently.
            linker = if (Build.VERSION.SDK_INT >= 29) linkerPath() else ""

            val preload = File("$prefix/lib/libtermux-exec.so")
            env = buildList {
                add("TERM=xterm-256color")
                add("HOME=$home")
                add("PREFIX=$prefix")
                add("TMPDIR=$prefix/tmp")
                add("PATH=$prefix/bin")
                add("LD_LIBRARY_PATH=$prefix/lib")
                add("LANG=en_US.UTF-8")
                add("COLUMNS=$cols")
                add("LINES=$rows")
                if (preload.exists()) add("LD_PRELOAD=${preload.absolutePath}")
                // Runtime config for the termux-exec shim (relocated prefix support).
                add("TERMUX__PREFIX=$prefix")
                add("TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE=enable")
            }.toTypedArray()
        } else {
            // Fallback: plain /system/bin/sh, direct exec (no bootstrap zip present).
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

    /** The Android system linker used for the linker-exec trick (matches process bitness). */
    private fun linkerPath(): String =
        if (android.os.Process.is64Bit()) "/system/bin/linker64" else "/system/bin/linker"

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
            if (destroyed) { openSessions.remove(this); return }
            destroyed = true
            runCatching {
                // Close the service's copy of masterFd.
                ParcelFileDescriptor.adoptFd(masterFd).close()
            }.onFailure { Log.w(TAG, "destroy: close masterFd=$masterFd failed", it) }

            runCatching {
                PtyLauncher.killProcess(pidValue)
            }.onFailure { Log.w(TAG, "destroy: killProcess pid=$pidValue failed", it) }

            openSessions.remove(this)
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
