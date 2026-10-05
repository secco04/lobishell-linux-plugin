package de.lobianco.saftssh.linux

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.security.MessageDigest

/**
 * Is the app calling this plugin really LobiShell?
 *
 * A package-name check alone (ALLOWED_CALLER_PACKAGES) proves nothing: any app can be installed
 * as "de.lobianco.saftssh" when the real LobiShell is not, and would then be served by this plugin.
 * So the caller's signing certificate must also be one of LobiShell's. Values are SHA-256 digests
 * of the signing certificate (`apksigner verify --print-certs`).
 */
internal object CallerTrust {
    private const val TAG = "CallerTrust"

    /** LobiShell as distributed through Google Play — Play re-signs with its own app-signing key. */
    private const val CERT_PLAY = "86301e068561a7270adc56900a72853ca5dba06bc1ee995c07c05afa8c8c2c32"

    /** LobiShell built and signed locally with the developer's release (upload) key. */
    private const val CERT_RELEASE = "efb237f579dccfc8715982bc4518532601ff092e98d5b285c49d4c569229c478"

    /** The developer's debug keystore — accepted only while THIS plugin is a debug build. */
    private const val CERT_DEBUG = "1332dbaf075d28535b8555a2c8b97cfdc14f02cef6b9e6d453d7c78ecfb506cb"

    /** Per-uid verdicts, so frequent calls (every key press) do not each ask the PackageManager. */
    private val cache = HashMap<Int, Pair<Boolean, Long>>()
    private const val CACHE_MS = 60_000L

    private class Signers(val digests: List<String>, val multiple: Boolean)

    private fun trusted(context: Context): Set<String> {
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return if (debuggable) setOf(CERT_PLAY, CERT_RELEASE, CERT_DEBUG) else setOf(CERT_PLAY, CERT_RELEASE)
    }

    /** True when [packageName] (running as [uid]) is signed with one of LobiShell's certificates. */
    fun isTrusted(context: Context, uid: Int, packageName: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        synchronized(cache) { cache[uid]?.let { (ok, at) -> if (now - at < CACHE_MS) return ok } }
        val ok = try {
            val s = signers(context.packageManager, packageName)
            val trusted = trusted(context)
            // Several signers: all must be trusted. One signer: any certificate of its rotation
            // history (rotating away from our key would need our key to sign the lineage).
            s.digests.isNotEmpty() && if (s.multiple) s.digests.all { it in trusted } else s.digests.any { it in trusted }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the signature of $packageName: ${e.message}")
            false
        }
        if (!ok) Log.e(TAG, "Caller $packageName (uid=$uid) is NOT signed by LobiShell — rejecting")
        synchronized(cache) { cache[uid] = ok to now }
        return ok
    }

    private fun signers(pm: PackageManager, packageName: String): Signers {
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val si = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                ?: return Signers(emptyList(), false)
            if (si.hasMultipleSigners()) Signers(si.apkContentsSigners.map { digest(it.toByteArray()) }, true)
            else Signers(si.signingCertificateHistory.map { digest(it.toByteArray()) }, false)
        } else {
            @Suppress("DEPRECATION")
            val sigs = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures.orEmpty()
            // No rotation history before API 28: every listed signer must be trusted.
            Signers(sigs.map { digest(it.toByteArray()) }, true)
        }
    }
}
