package de.lobianco.saftssh.linux

import android.content.Context
import java.io.File

/**
 * Read-only root detection — NOT wired to anything yet. Prep for a future, separate "root tier":
 * if root + a kernel with real namespaces/cgroups is available, the userland could use a real
 * chroot instead of proot, unlocking things proot fundamentally cannot do (Docker/systemd — see
 * the design note on [LinuxSessionService]). That tier is a distinct, explicitly-opt-in code path
 * from the no-root proot userland this plugin ships today; this object only answers "is root
 * present", it does not attempt to use it.
 */
object RootDetector {

    private val SU_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su",
        "/system/sd/xbin/su", "/system/bin/failsafe/su", "/data/local/xbin/su", "/data/local/bin/su",
    )

    private const val MAGISK_PACKAGE = "com.topjohnwu.magisk"

    /** Best-effort — false negatives are expected (root can hide itself); this is informational
     *  only, never a security boundary. */
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
}
