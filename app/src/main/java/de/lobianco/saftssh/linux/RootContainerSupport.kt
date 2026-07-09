package de.lobianco.saftssh.linux

import android.util.Log

/**
 * Root-only alternative to the proot userland: a REAL `chroot` (with the host's /dev, /sys, /proc
 * bind-mounted in) instead of proot's ptrace-based emulation. Active whenever this is the
 * `root`-flavored build ([de.lobianco.saftssh.linux.BuildConfig.SUPPORTS_ROOT_CONTAINERS]) AND
 * [RootDetector] finds working root access — installing that flavor's APK on a rooted device IS
 * the opt-in, no separate in-app toggle. The `standard` flavor never enables this, so it stays
 * additive on top of the normal no-root proot experience, not a replacement for it.
 *
 * WHY this exists: proot is ptrace-based, which has real syscall overhead and (on devices with
 * 16 KB memory pages) can't mmap Ubuntu's prebuilt 4 KB-aligned binaries at all. A real `chroot`
 * runs the kernel's own ELF loader directly, sidestepping both. It's a genuine root shell, not a
 * faked one — real apt/dpkg, real uid 0, no ptrace layer in between.
 *
 * NAMESPACES: only a MOUNT namespace (`unshare -m`, CLONE_NEWNS), and only if the kernel supports
 * it (capability-tested at launch, falls back to a plain chroot if not). This isn't required for
 * a plain chroot to work — it's here so the bind mounts below live in the session's own namespace
 * and the kernel tears them down automatically on exit, instead of leaking into the host mount
 * table (though [unmountSubmountsBlocking] still cleans up the plain-chroot fallback path).
 *
 * WHAT THIS DELIBERATELY DOES NOT DO:
 * - No PID/UTS/IPC/user namespace: not needed for a plain root shell, and some of them EINVAL on
 *   this class of kernel anyway. Cosmetic: `ps` in the chroot also shows host processes.
 * - No verification that any of this actually works on a given device — it depends on kernel
 *   config and the specific root solution (Magisk/KernelSU/etc.) far too much to be guaranteed;
 *   this is a best-effort code path, not a supported feature with device compatibility guarantees.
 */
object RootContainerSupport {

    private const val TAG = "RootContainerSupport"

    /**
     * Builds the one-shot setup + launch script run as root via `su -c`. Mirrors the shape of
     * [LinuxSessionService]'s proot scripts (idempotent binds, then `exec` into the login shell so
     * its PID persists for the session's lifetime), just using a real chroot instead of proot.
     *
     * Deliberately NO `unshare` (see the class doc) — the mounts land in the host mount table and
     * are cleaned up explicitly by [unmountSubmountsBlocking] on delete/reinstall. `mount --bind`
     * is used (confirmed working on-device in an earlier test run where these binds succeeded).
     *
     * Everything up to and including the `chroot` itself runs in Android's OWN userspace (toybox,
     * not util-linux/glibc) — `apt-get`, `bash`, and the rest of a real distro only exist AFTER
     * the chroot. [innerScript] is the part that runs post-chroot (package-install prompt, then
     * `exec /bin/bash --login`). It's passed through an env var, never interpolated into the
     * command line, so its own quotes never need escaping here — same trick as
     * LOBISHELL_SSHD_SECRET/LOBISHELL_ROOT_SCRIPT elsewhere in this plugin.
     */
    fun buildRootChrootScript(rootfs: String, tmp: String, innerScript: String): String {
        // The mount+chroot body, run INSIDE a mount namespace (see the unshare wrapper below).
        val body =
            "mkdir -p '$tmp'; " +
            "mountpoint -q '$rootfs/dev'  || mount --bind /dev  '$rootfs/dev'; " +
            // `mount --bind /dev` is NON-recursive, so the host's separate devpts mount under
            // /dev/pts is NOT carried in — /dev/pts ends up empty and posix_openpt() fails with
            // ENODEV. Bind the host's REAL /dev/pts so /dev/ptmx (5:2) and /dev/pts share the exact
            // same devpts instance (the one Android itself uses). --bind is the form proven on-device.
            "mkdir -p '$rootfs/dev/pts'; " +
            "mountpoint -q '$rootfs/dev/pts' || mount --bind /dev/pts '$rootfs/dev/pts'; " +
            "mkdir -p '$rootfs/dev/shm'; " +
            "mountpoint -q '$rootfs/dev/shm' || mount -t tmpfs tmpfs '$rootfs/dev/shm'; " +
            "mountpoint -q '$rootfs/sys'  || mount --bind /sys  '$rootfs/sys'; " +
            "mountpoint -q '$rootfs/proc' || mount --bind /proc '$rootfs/proc'; " +
            "mountpoint -q '$rootfs/tmp'  || mount --bind '$tmp' '$rootfs/tmp'; " +
            "exec chroot '$rootfs' /usr/bin/env -i " +
            "HOME=/root TERM=xterm-256color LANG=C.UTF-8 " +
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin " +
            "LOBISHELL_INNER_SCRIPT=" + shellSingleQuote(innerScript) + " " +
            "/bin/bash -c 'eval \"\$LOBISHELL_INNER_SCRIPT\"'"

        // Run the body inside a MOUNT namespace only (CLONE_NEWNS), so the bind mounts above live
        // in the session's own namespace and the kernel tears them all down automatically when it
        // exits — nothing leaks into the host mount table. CLONE_NEWNS is essentially always
        // available on Android (it's used per-app); we still capability-test `unshare -m true`
        // first and fall back to running without a namespace if even that fails (shell still
        // works; mounts leak but are cleaned up by unmountSubmountsBlocking on delete). `unshare
        // PROG` execve()s PROG with no shell to parse a VAR=val prefix, so route env vars through
        // `env` (as elsewhere here).
        val nsScript = shellSingleQuote(body)
        return "if unshare -m true 2>/dev/null; then " +
            "exec unshare -m env LOBISHELL_NS_SCRIPT=$nsScript /system/bin/sh -c 'eval \"\$LOBISHELL_NS_SCRIPT\"'; " +
            "else " +
            "env LOBISHELL_NS_SCRIPT=$nsScript /system/bin/sh -c 'eval \"\$LOBISHELL_NS_SCRIPT\"'; " +
            "fi"
    }

    /**
     * Unmounts every host-side mount left under [rootfs] by a prior root-chroot session, so
     * [RootfsInstaller] can then delete/rename the directory without hitting a "busy" mountpoint
     * (the "could not move staging" install failure). Blocking, root-only, best-effort — always
     * call off the main thread.
     *
     * Reads the ACTUAL mount list from /proc/mounts and unmounts everything under [rootfs]
     * deepest-first, rather than assuming a fixed set of top-level binds: a plain `umount
     * rootfs/dev` fails "busy" while a nested submount like rootfs/dev/pts is still mounted, so an
     * earlier fixed-list version left mounts behind and the delete still failed. `sort -r` orders
     * the matched mountpoints so deeper paths come off before their parents; each is tried with a
     * normal umount, then a lazy `umount -l` (MNT_DETACH) fallback for anything still busy. The
     * rootfs path is app-derived (RootfsInstaller.safeId strips it to [A-Za-z0-9_-]) so it can
     * never contain shell metacharacters — safe to interpolate directly into the case pattern.
     *
     * @return true only if, after the unmount pass, NOTHING is mounted under [rootfs] anymore. A
     *   false result is a hard SAFETY signal to the caller: do NOT `rm -rf` the tree, because our
     *   /dev|/sys|/proc binds point at the LIVE host and a delete crossing into one of them would
     *   destroy host system files. Also false if su/root is unavailable (can't verify → assume
     *   unsafe). The app-uid [java.io.File.deleteRecursively] is always safe regardless (it lacks
     *   permission to delete through a root-owned bind mount — it just fails on it).
     */
    fun unmountSubmountsBlocking(rootfs: String): Boolean {
        // CRITICAL: match /proc/mounts against the CANONICAL path. Android's filesDir is under
        // /data/user/0/<pkg>/... but the kernel records the mount under the resolved /data/data/
        // <pkg>/... — matching the un-resolved path finds nothing, so a live /dev|/proc bind is
        // missed, the caller's "clean" gate passes, and a root rm then recurses into the host
        // (host device nodes deletable, and /proc error-spam → OOM). `realpath` resolves it in the
        // shell so the comparison lines up with what /proc/mounts actually contains. Quoted case
        // patterns ("$p"/*) keep the path's dots literal.
        // Match both mounts UNDER rootfs ("$p"/*) and the rootfs self-bind itself ("$p") — the
        // latter is the chroot-root self-bind buildRootChrootScript adds; sort -r unmounts the
        // deeper sub-mounts before the shallower self-bind.
        val script =
            "p=\$(realpath '$rootfs' 2>/dev/null || echo '$rootfs'); " +
            "while read d m r; do case \"\$m\" in \"\$p\"/*|\"\$p\") echo \"\$m\";; esac; done < /proc/mounts " +
            "| sort -r | while read x; do umount \"\$x\" 2>/dev/null || umount -l \"\$x\" 2>/dev/null; done; " +
            "while read d m r; do case \"\$m\" in \"\$p\"/*|\"\$p\") echo \"STILL-MOUNTED: \$m\";; esac; done < /proc/mounts"
        return try {
            val p = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            val stillMounted = out.lineSequence().any { it.startsWith("STILL-MOUNTED:") }
            if (out.isNotEmpty()) Log.w(TAG, "unmountSubmounts('$rootfs'):\n$out")
            else Log.i(TAG, "unmountSubmounts('$rootfs'): all clear")
            !stillMounted
        } catch (e: Exception) {
            // No root / no su — can't unmount OR verify. Assume unsafe (return false) so no root rm
            // runs; the app-uid deleteRecursively still runs safely regardless.
            Log.w(TAG, "unmountSubmounts('$rootfs') failed: ${e.message}")
            false
        }
    }

    /**
     * Root `rm -rf` fallback for a directory a plain app-uid [java.io.File.deleteRecursively]
     * couldn't fully remove (e.g. a root-owned file a root-chroot session left behind).
     *
     * SAFETY: this SELF-GUARDS. `rm -rf` crosses into bind mounts by default, and our /dev|/sys|
     * /proc binds point at the live host, so running it while any such mount is still present under
     * [path] would delete host device nodes / system files as root. The shell script therefore
     * re-checks /proc/mounts and REFUSES (does nothing but log) if anything is still mounted under
     * [path] — belt-and-suspenders on top of the caller only invoking this when
     * [unmountSubmountsBlocking] returned true. Blocking, root-only; always call off the main thread.
     */
    fun forceRemoveBlocking(path: String) {
        // Canonicalize (realpath) before matching /proc/mounts — see the long note in
        // [unmountSubmountsBlocking]; the /data/user/0 vs /data/data mismatch is exactly what let a
        // prior version's guard pass while /proc/dev were still mounted, so rm recursed into the
        // host and OOM'd on the error spam. `mounted` is set inside a `while ... done < file` loop
        // (a redirect, NOT a pipe → runs in the current shell so the var survives). rm output is
        // sent to /dev/null so this can never slurp a huge string into memory again.
        val script =
            "p=\$(realpath '$path' 2>/dev/null || echo '$path'); " +
            "mounted=; while read d m r; do case \"\$m\" in \"\$p\"/*|\"\$p\") mounted=1;; esac; done < /proc/mounts; " +
            "if [ -n \"\$mounted\" ]; then echo \"REFUSED: mount still present under \$p\"; " +
            "else rm -rf \"\$p\" >/dev/null 2>&1; fi"
        try {
            val p = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
            // Truly bounded read: keep DRAINING the stream (so the process never blocks writing)
            // but stop ACCUMULATING past a small cap — so even if some future script change floods
            // stdout, this can never build a giant String and OOM the plugin the way readText() did.
            val reader = p.inputStream.bufferedReader()
            val sb = StringBuilder()
            val buf = CharArray(4096)
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                if (sb.length < 4096) sb.append(buf, 0, minOf(n, 4096 - sb.length))
            }
            p.waitFor()
            val out = sb.toString().trim()
            if (out.isNotEmpty()) Log.w(TAG, "forceRemove('$path'): $out")
        } catch (e: Exception) {
            Log.w(TAG, "forceRemove('$path') failed: ${e.message}")
        }
    }

    /** Wraps [s] in single quotes for safe embedding in a shell command line, escaping any
     *  embedded single quotes with the standard `'\''` technique (same pattern already used
     *  elsewhere in this codebase, e.g. ConnectionDetailScreen's cwd quoting). */
    private fun shellSingleQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
