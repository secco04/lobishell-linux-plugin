package de.lobianco.saftssh.linux.data.logging

import android.util.Log

/**
 * Drop-in replacement for [android.util.Log] that additionally writes every message directly to
 * [LogFileManager]'s debug log file — ported from the main LobiShell app's identical class.
 *
 * **Migration:** replace `import android.util.Log` with
 * `import de.lobianco.saftssh.linux.data.logging.AppLog` and rename `Log.` → `AppLog.`
 * — the method signatures are identical.
 */
object AppLog {

    fun v(tag: String, msg: String)                { Log.v(tag, msg);     file("V", tag, msg) }
    fun v(tag: String, msg: String, tr: Throwable) { Log.v(tag, msg, tr); file("V", tag, fmt(msg, tr)) }

    fun d(tag: String, msg: String)                { Log.d(tag, msg);     file("D", tag, msg) }
    fun d(tag: String, msg: String, tr: Throwable) { Log.d(tag, msg, tr); file("D", tag, fmt(msg, tr)) }

    fun i(tag: String, msg: String)                { Log.i(tag, msg);     file("I", tag, msg) }
    fun i(tag: String, msg: String, tr: Throwable) { Log.i(tag, msg, tr); file("I", tag, fmt(msg, tr)) }

    fun w(tag: String, msg: String)                { Log.w(tag, msg);     file("W", tag, msg) }
    fun w(tag: String, msg: String, tr: Throwable) { Log.w(tag, msg, tr); file("W", tag, fmt(msg, tr)) }
    fun w(tag: String, tr: Throwable)              { Log.w(tag, tr);      file("W", tag, Log.getStackTraceString(tr)) }

    fun e(tag: String, msg: String)                { Log.e(tag, msg);     file("E", tag, msg) }
    fun e(tag: String, msg: String, tr: Throwable) { Log.e(tag, msg, tr); file("E", tag, fmt(msg, tr)) }

    private fun file(level: String, tag: String, msg: String) = LogFileManager.log(level, tag, msg)
    private fun fmt(msg: String, tr: Throwable) = "$msg\n${Log.getStackTraceString(tr)}"
}
