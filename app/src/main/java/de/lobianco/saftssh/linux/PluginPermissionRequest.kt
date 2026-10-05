package de.lobianco.saftssh.linux

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Runtime permissions this plugin must ask for itself — LobiShell's own grants do not cover a
 * separate app:
 *  - POST_NOTIFICATIONS (Android 13+): the foreground-service notice while a session runs.
 *  - ACCESS_LOCAL_NETWORK (Android 17+): without it every connection to a host on the local network
 *    is blocked, so an RDP / VNC / RustDesk / LAN session "opens" and then dies without a frame.
 *
 * LobiShell opens this plugin with [EXTRA_REQUEST] when the permission is missing; the activity
 * then asks, reports the outcome as its result and closes again.
 */
internal object PluginPermissionRequest {
    const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    private const val EXTRA_REQUEST = "de.lobianco.saftssh.extra.REQUEST_PLUGIN_PERMISSIONS"
    private const val REQUEST_CODE = 4701

    private fun requestedByLobiShell(activity: Activity) =
        activity.intent?.getBooleanExtra(EXTRA_REQUEST, false) == true

    private fun missing(activity: Activity): Array<String> = buildList {
        fun lacks(p: String) = activity.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= 33 && lacks(Manifest.permission.POST_NOTIFICATIONS)) add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 37 && lacks(LOCAL_NETWORK)) add(LOCAL_NETWORK)
    }.toTypedArray()

    /** Call from onCreate after super. Returns true when the activity has finished (return right away). */
    fun onCreate(activity: Activity): Boolean {
        val missing = missing(activity)
        if (missing.isNotEmpty()) {
            activity.requestPermissions(missing, REQUEST_CODE)
            return false
        }
        if (requestedByLobiShell(activity)) {
            activity.setResult(Activity.RESULT_OK)
            activity.finish()
            return true
        }
        return false
    }

    fun onResult(activity: Activity, requestCode: Int, permissions: Array<out String>, results: IntArray) {
        if (requestCode != REQUEST_CODE) return
        val i = permissions.indexOf(LOCAL_NETWORK)
        val lanDenied = i >= 0 && results.getOrNull(i) != PackageManager.PERMISSION_GRANTED
        if (lanDenied && !activity.shouldShowRequestPermissionRationale(LOCAL_NETWORK)) {
            // Denied for good, so the dialog will not come again — only the settings page can change it.
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null))
            )
        }
        if (requestedByLobiShell(activity)) {
            activity.setResult(if (lanDenied) Activity.RESULT_CANCELED else Activity.RESULT_OK)
            activity.finish()
        }
    }
}
