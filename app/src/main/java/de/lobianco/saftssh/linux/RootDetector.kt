package de.lobianco.saftssh.linux

import android.content.Context
import java.io.File

/**
 * Root detection. [isDeviceRooted] is a cheap, non-blocking, best-effort HINT for UI display;
 * [hasWorkingRootAccess] is the authoritative, functional check [LinuxSessionService] actually
 * gates the root-chroot tier on (see the design note there — a real `chroot` instead of proot,
 * for a genuine root shell rather than proot's ptrace-emulated one). That tier is a distinct,
 * explicitly-opt-in code path from the no-root proot userland this plugin also ships.
 */
object RootDetector {

    private val SU_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su",
        "/system/sd/xbin/su", "/system/bin/failsafe/su", "/data/local/xbin/su", "/data/local/bin/su",
    )

    private const val MAGISK_PACKAGE = "com.topjohnwu.magisk"

    /** Best-effort — false negatives are expected (root can hide itself, e.g. modern Magisk hides
     *  its own `su` mount from unprivileged processes and offers a "randomize app name" setting
     *  specifically to defeat the package-name check below). This is a cheap, non-blocking,
     *  informational HINT only — safe to call on the main thread — never a gate for an actual
     *  root-requiring code path (see [hasWorkingRootAccess] for that). */
    fun isDeviceRooted(context: Context): Boolean {
        if (SU_PATHS.any { File(it).exists() }) return true
        val isMagiskInstalled = try {
            context.packageManager.getApplicationInfo(MAGISK_PACKAGE, 0)
            true
        } catch (_: Exception) {
            false
        }
        if (isMagiskInstalled) return true
        val tags = android.os.Build.TAGS
        return tags != null && tags.contains("test-keys")
    }

    /**
     * Authoritative, functional root check: actually runs `su -c id` and reports whether it
     * succeeded — unlike [isDeviceRooted]'s static heuristics (file-path/package-name sniffing),
     * this can't be fooled by a hidden `su` mount or a renamed Magisk app, and is what
     * [LinuxSessionService] gates the root-chroot path on. [ProcessBuilder] does its own $PATH
     * search, so plain "su" resolves correctly regardless of which root solution is installed
     * (unlike [LinuxSessionService]'s native forkAndExec, which uses execve and needs an absolute
     * path). BLOCKING — a superuser prompt may need real user interaction; never call on the main
     * thread outside of a deliberate, foreground, user-initiated moment (see [InfoActivity]'s
     * "Grant Root Access" button, and the rationale in its comments for why that's a cleaner su
     * prompt trigger than a silent background call).
     */
    fun hasWorkingRootAccess(): Boolean = try {
        val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        process.waitFor() == 0
    } catch (_: Exception) {
        false
    }
}
