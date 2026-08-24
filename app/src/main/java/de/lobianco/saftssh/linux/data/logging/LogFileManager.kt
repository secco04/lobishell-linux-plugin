package de.lobianco.saftssh.linux.data.logging

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-app debug log file the user can share for support purposes — ported from the main LobiShell
 * app's identical class (`de.lobianco.saftssh.data.logging.LogFileManager`); see that file for the
 * full design rationale (direct-write instead of `exec("logcat")`, since some OEMs block that for
 * third-party apps).
 *
 * This plugin has no dependency-injection framework, so unlike the main app's `@Singleton` Hilt
 * class this is a plain `object` — [init] must be called once, from
 * [de.lobianco.saftssh.linux.LinuxPluginApplication.onCreate], before [log] does anything useful.
 */
object LogFileManager {
    private const val TAG = "LogFileManager"
    private const val LOG_DIR = "logs"
    private const val LOG_FILE = "linux_plugin_debug.log"
    private const val MAX_BYTES = 2_000_000L
    private const val KEEP_BYTES = 1_000_000L

    private lateinit var appContext: Context
    lateinit var logFile: File
        private set
    private val writeCount = AtomicInteger(0)
    private var initialized = false

    /** Call once, as early as possible (Application.onCreate()). Writes the session header. */
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        logFile = File(File(appContext.filesDir, LOG_DIR).also { it.mkdirs() }, LOG_FILE)
        initialized = true
        rotateIfNeeded()
        writeSessionHeader()
        Log.d(TAG, "Log capture started -> ${logFile.absolutePath}")
    }

    /** Write one log line directly to the file. Called by [AppLog] on every log statement. */
    @Synchronized
    fun log(level: String, tag: String, message: String) {
        if (!initialized) return
        runCatching {
            val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            logFile.appendText("$ts $level/$tag: $message\n")
            // Check rotation every 200 writes to avoid frequent stat() calls
            if (writeCount.incrementAndGet() % 200 == 0 && logFile.length() > MAX_BYTES) rotateNow()
        }
    }

    /** FileProvider URI suitable for ACTION_SEND / share sheet. */
    fun getShareUri() = FileProvider.getUriForFile(
        appContext, "${appContext.packageName}.fileprovider", logFile
    )!!

    /** Human-readable file size for the UI. */
    fun fileSizeKb(): Long = if (::logFile.isInitialized && logFile.exists()) logFile.length() / 1024 else 0L

    /** Wipes the log file (user-triggered). */
    fun clearLog() {
        runCatching { logFile.delete() }
        writeSessionHeader()
    }

    // ── Session header ────────────────────────────────────────────────────────

    private fun writeSessionHeader() {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)
        val line = "═".repeat(60)
        // PackageManager rather than BuildConfig — works regardless of whether the module has
        // buildFeatures.buildConfig enabled, with no per-repo build.gradle.kts change needed.
        val versionName = runCatching {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        val text = buildString {
            appendLine()
            appendLine(line)
            appendLine("LobiShell Linux Plugin Debug Log — Session Start")
            appendLine("Time:           ${sdf.format(Date())}")
            appendLine("Plugin version: $versionName")
            appendLine("Device:         ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("Android:        ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("ABI:            ${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}")
            appendLine(line)
        }
        runCatching { logFile.appendText(text) }
    }

    // ── Rotation ──────────────────────────────────────────────────────────────

    private fun rotateIfNeeded() {
        if (logFile.exists() && logFile.length() > MAX_BYTES) rotateNow()
    }

    private fun rotateNow() {
        runCatching {
            val content = logFile.readText()
            if (content.length > KEEP_BYTES) {
                logFile.writeText("…[older entries trimmed]\n" + content.substring(content.length - KEEP_BYTES.toInt()))
            }
        }
    }
}
