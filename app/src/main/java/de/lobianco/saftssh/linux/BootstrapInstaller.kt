package de.lobianco.saftssh.linux

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Installs a Termux-style bootstrap (the Linux userland: bash, coreutils, apt, …) into the plugin's
 * private `$PREFIX` so it can be exec'd via the linker-exec trick.
 *
 * The bootstrap is a Termux `bootstrap-<arch>.zip` placed in the plugin's `assets/`. The user (or a
 * future downloader) supplies the arch-matched zip — we cannot bundle the GPL binaries from this
 * build environment (see PHASE2.md). The zip layout matches Termux's:
 *   - regular entries are files relative to `$PREFIX` (e.g. `bin/bash`, `lib/libc++_shared.so`);
 *   - a special `SYMLINKS.txt` entry lists `target←linkpath` pairs (arrow = U+2190) to recreate.
 *
 * Extraction is staged then atomically renamed, and is idempotent (skipped once `$PREFIX/bin/bash`
 * exists). Mirrors Termux's TermuxInstaller.
 */
object BootstrapInstaller {

    private const val TAG = "BootstrapInstaller"

    /**
     * Download fallback. If no `bootstrap-<arch>.zip` asset is bundled, the installer downloads from
     * this URL with `<ARCH>` replaced by aarch64 / arm / x86_64 / i686. Blank = download disabled
     * (asset-only). Point it at a bootstrap release you trust — e.g. a Termux bootstrap release:
     *   "https://github.com/termux/termux-packages/releases/download/bootstrap-2025.01.13-r1%2Bapt-android-7/bootstrap-<ARCH>.zip"
     * NOTE: the plugin needs the INTERNET permission (declared in its manifest) for this path.
     */
    private const val BOOTSTRAP_URL_TEMPLATE = ""

    /**
     * Optional SHA-256 (lowercase hex) per arch token to verify a DOWNLOADED zip. Leave an arch out
     * (or the whole map empty) to skip verification. Strongly recommended when downloading code.
     * Keys: "aarch64" / "arm" / "x86_64" / "i686".
     */
    private val BOOTSTRAP_SHA256: Map<String, String> = emptyMap()

    /** `$PREFIX` — the userland root (`…/files/usr`). */
    fun prefixDir(context: Context): File = File(context.filesDir, "usr")

    /** `$HOME` — the userland home (`…/files/home`). */
    fun homeDir(context: Context): File = File(context.filesDir, "home")

    /** True once a usable bootstrap is installed. */
    fun isInstalled(context: Context): Boolean =
        File(prefixDir(context), "bin/bash").exists()

    /** Termux arch token for the current device, used to pick the asset zip. */
    private fun termuxArch(): String = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a"    -> "aarch64"
        "armeabi-v7a"  -> "arm"
        "x86_64"       -> "x86_64"
        "x86"          -> "i686"
        else           -> "aarch64"
    }

    /** Asset file name expected in `assets/` (e.g. `bootstrap-aarch64.zip`). */
    fun assetName(): String = "bootstrap-${termuxArch()}.zip"

    /**
     * Extract the bootstrap into `$PREFIX` (idempotent). Returns failure if the asset is missing or
     * extraction fails — the caller then falls back to the plain system shell.
     */
    fun install(context: Context): Result<Unit> {
        return runCatching {
            if (isInstalled(context)) return@runCatching

            val prefix = prefixDir(context)
            val staging = File(context.filesDir, "usr-staging")
            // Fresh staging dir.
            staging.deleteRecursively()
            staging.mkdirs()
            homeDir(context).mkdirs()

            // Pending symlinks (target, linkPath) created after all files are extracted.
            val symlinks = ArrayList<Pair<String, File>>()

            obtainBootstrapStream(context).use { raw ->
                ZipInputStream(raw.buffered()).use { zin ->
                    var entry = zin.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        if (name == "SYMLINKS.txt") {
                            // Each line: "target←linkPath" (U+2190). Defer creation until extracted.
                            zin.bufferedReader().forEachLine { line ->
                                val parts = line.split('←')
                                if (parts.size == 2) {
                                    val target = parts[0]
                                    val link = safeChild(staging, parts[1])
                                    link.parentFile?.mkdirs()
                                    symlinks += target to link
                                }
                            }
                        } else if (entry.isDirectory) {
                            safeChild(staging, name).mkdirs()
                        } else {
                            val out = safeChild(staging, name)
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zin.copyTo(it) }
                            // Bootstrap binaries/libs must be executable by the owner.
                            out.setReadable(true, true)
                            out.setWritable(true, true)
                            out.setExecutable(true, true)
                        }
                        zin.closeEntry()
                        entry = zin.nextEntry
                    }
                }
            }

            // Create symlinks after extraction (targets may be other extracted files).
            for ((target, link) in symlinks) {
                runCatching {
                    if (link.exists()) link.delete()
                    Os.symlink(target, link.absolutePath)
                }.onFailure { Log.w(TAG, "symlink ${link.path} -> $target failed: ${it.message}") }
            }

            // Atomically swap staging → $PREFIX.
            if (prefix.exists()) prefix.deleteRecursively()
            if (!staging.renameTo(prefix)) {
                // Cross-dir rename can fail; fall back to a recursive move would be heavy — error out.
                error("Could not move staging bootstrap into place")
            }
            Log.i(TAG, "Bootstrap installed into ${prefix.absolutePath}")
        }.onFailure { Log.w(TAG, "Bootstrap install failed: ${it.message}") }
    }

    /** Resolve [name] under [base], rejecting zip-slip paths that escape [base] (e.g. `../`). */
    private fun safeChild(base: File, name: String): File {
        val child = File(base, name)
        val basePath = base.canonicalPath
        val childPath = child.canonicalPath
        if (childPath != basePath && !childPath.startsWith(basePath + File.separator)) {
            error("Refusing bootstrap entry outside staging dir: $name")
        }
        return child
    }

    /** Bootstrap zip stream: the bundled asset if present, otherwise a fresh download. */
    private fun obtainBootstrapStream(context: Context): InputStream {
        runCatching { return context.assets.open(assetName()) }
            .onFailure { Log.i(TAG, "No bundled ${assetName()} asset — trying download") }
        return downloadBootstrap(context).inputStream()
    }

    /**
     * Download the arch-matched bootstrap zip to the cache dir (following redirects, e.g. GitHub →
     * objects.githubusercontent). Verifies SHA-256 if configured. Throws on any failure.
     */
    private fun downloadBootstrap(context: Context): File {
        require(BOOTSTRAP_URL_TEMPLATE.isNotBlank()) {
            "No bundled bootstrap asset and no BOOTSTRAP_URL_TEMPLATE configured"
        }
        val arch = termuxArch()
        val firstUrl = BOOTSTRAP_URL_TEMPLATE.replace("<ARCH>", arch)
        val out = File(context.cacheDir, "bootstrap-$arch.zip")
        out.delete()

        // Manual redirect loop (HttpURLConnection won't auto-follow across protocols/hosts reliably).
        var target = firstUrl
        var redirects = 0
        while (true) {
            val conn = (URL(target).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "lobishell-linux-plugin")
            }
            try {
                when (val code = conn.responseCode) {
                    in 300..399 -> {
                        val loc = conn.getHeaderField("Location")
                            ?: error("Redirect ($code) without Location header")
                        if (++redirects > 5) error("Too many redirects")
                        target = URL(URL(target), loc).toString()  // resolve relative redirects
                    }
                    200 -> {
                        conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
                        Log.i(TAG, "Downloaded bootstrap ($arch, ${out.length()} bytes)")
                        verifyChecksum(out, arch)
                        return out
                    }
                    else -> error("Download failed HTTP $code for $target")
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    /** Verify the downloaded zip against [BOOTSTRAP_SHA256] for [arch] (no-op if none configured). */
    private fun verifyChecksum(file: File, arch: String) {
        val expected = BOOTSTRAP_SHA256[arch]?.lowercase() ?: return
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf); if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        val actual = md.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            file.delete()
            error("Bootstrap SHA-256 mismatch for $arch: expected $expected, got $actual")
        }
    }
}
