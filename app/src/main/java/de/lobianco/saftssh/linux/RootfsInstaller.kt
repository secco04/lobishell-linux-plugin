package de.lobianco.saftssh.linux

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Per-userland proot-based Ubuntu/Debian rootfs manager.
 *
 * Each "userland" is keyed by an id (the main app passes the connection id) so every connection can
 * have its OWN isolated rootfs. The big rootfs download is fetched once into the shared cache and
 * then extracted into each userland; proot's shared libs live once under `usr/lib`.
 *
 * ## Layout
 * ```
 * filesDir/
 *   userlands/<id>/
 *     rootfs/            ← the guest root  (rootfsDir)
 *       bin/bash         ← install sentinel
 *       .lobishell_arch  ← arch marker
 *     tmp/               ← proot --bind /tmp
 *   usr/lib/             ← proot's libtalloc / libandroid-shmem (shared)
 * cacheDir/rootfs-<arch>.tar.gz  ← shared download, reused for new userlands
 * ```
 */
object RootfsInstaller {

    private const val TAG = "RootfsInstaller"

    /**
     * Ubuntu Base 24.04.4 (Noble) — official, stable, working `apt`. `<ARCH>` → Ubuntu arch token
     * (arm64 / amd64 / armhf) via [ubuntuArch]. Note: 24.04 has no i386 build.
     */
    private const val ROOTFS_URL: String =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-<ARCH>.tar.gz"

    /** Optional per-arch SHA-256 (lowercase hex) of the downloaded rootfs. Empty = skip. */
    private val ROOTFS_SHA256: Map<String, String> = emptyMap()

    // ── Paths ───────────────────────────────────────────────────────────────

    private fun userlandsRoot(context: Context): File = File(context.filesDir, "userlands")

    /** Sanitize an id into a safe single directory name. */
    private fun safeId(id: String): String {
        val s = id.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        return s.ifBlank { "default" }
    }

    fun userlandDir(context: Context, id: String): File = File(userlandsRoot(context), safeId(id))
    fun rootfsDir(context: Context, id: String): File = File(userlandDir(context, id), "rootfs")
    fun tmpDir(context: Context, id: String): File = File(userlandDir(context, id), "tmp")
    private fun archMarker(context: Context, id: String): File = File(rootfsDir(context, id), ".lobishell_arch")

    // ── State ───────────────────────────────────────────────────────────────

    /** True once this userland's rootfs is installed (matching the current ABI). */
    fun isInstalled(context: Context, id: String): Boolean =
        File(rootfsDir(context, id), "bin/bash").exists() &&
            runCatching { archMarker(context, id).readText().trim() == arch() }.getOrDefault(false)

    /** Ids of all installed userlands (subdirs of `userlands/` with a usable rootfs). */
    fun listUserlandIds(context: Context): List<String> =
        userlandsRoot(context).listFiles()
            ?.filter { it.isDirectory && File(it, "rootfs/bin/bash").exists() }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()

    /** Total bytes used by one userland (rootfs + tmp). */
    fun userlandSize(context: Context, id: String): Long = dirSize(userlandDir(context, id))

    /** Delete one userland entirely. Returns bytes freed. */
    fun clear(context: Context, id: String): Long {
        val dir = userlandDir(context, id)
        val freed = dirSize(dir)
        runCatching { dir.deleteRecursively() }
        Log.i(TAG, "Cleared userland '$id' — freed $freed bytes")
        return freed
    }

    private fun dirSize(f: File): Long = when {
        !f.exists() -> 0L
        f.isFile -> f.length()
        else -> f.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }

    fun arch(): String = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a"   -> "aarch64"
        "armeabi-v7a" -> "arm"
        "x86_64"      -> "x86_64"
        "x86"         -> "i686"
        else          -> "aarch64"
    }

    private fun ubuntuArch(): String = when (arch()) {
        "aarch64" -> "arm64"
        "arm"     -> "armhf"
        "x86_64"  -> "amd64"
        "i686"    -> "i386"
        else      -> "amd64"
    }

    // ── Install ─────────────────────────────────────────────────────────────

    /**
     * Install this userland's rootfs (blocking — call from a binder/worker thread). The rootfs
     * download is fetched once into the shared cache and reused for every userland.
     */
    fun install(context: Context, id: String, progress: (String) -> Unit = {}): Result<Unit> = runCatching {
        if (isInstalled(context, id)) {
            Log.i(TAG, "Userland '$id' already installed — skipping")
            return@runCatching
        }
        Log.i(TAG, "Installing userland '$id' (arch=${arch()})")
        progress("Setting up Linux userland (${ubuntuArch()})…")

        extractRootfs(context, id, progress)
        progress("Installing proot dependencies…")
        installProotLibs(context)
        tmpDir(context, id).mkdirs()
        archMarker(context, id).writeText(arch())

        Log.i(TAG, "Userland '$id' ready: ${rootfsDir(context, id).absolutePath}")
        progress("Userland ready.")
    }.onFailure {
        Log.e(TAG, "Install failed for '$id': ${it.message}", it)
        progress("Install failed: ${it.message}")
    }

    /**
     * Write DNS + apt sources + group ids into this userland's rootfs so networking/apt work under
     * proot. Cheap & idempotent — call on every launch (fixes existing installs).
     */
    fun configureRootfs(context: Context, id: String) {
        val rootfs = rootfsDir(context, id)
        if (!rootfs.exists()) return
        runCatching {
            File(rootfs, "etc").mkdirs()
            // proot has no resolver; delete first because /etc/resolv.conf is often a dangling symlink.
            File(rootfs, "etc/resolv.conf").apply { delete(); writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n") }
            File(rootfs, "etc/hosts").apply { delete(); writeText("127.0.0.1 localhost\n::1 localhost\n") }

            // Full Ubuntu Noble sources for the right arch (amd64/i386 → archive; others → ports).
            val u = ubuntuArch()
            val ports = u != "amd64" && u != "i386"
            val main = if (ports) "http://ports.ubuntu.com/ubuntu-ports" else "http://archive.ubuntu.com/ubuntu"
            val sec  = if (ports) "http://ports.ubuntu.com/ubuntu-ports" else "http://security.ubuntu.com/ubuntu"
            File(rootfs, "etc/apt").mkdirs()
            File(rootfs, "etc/apt/sources.list").writeText(
                "deb $main noble main restricted universe multiverse\n" +
                "deb $main noble-updates main restricted universe multiverse\n" +
                "deb $sec noble-security main restricted universe multiverse\n"
            )
            File(rootfs, "etc/apt/sources.list.d/ubuntu.sources").delete()

            // Silence "groups: cannot find name for group ID …" by adding the app's process GIDs.
            val gids = linkedSetOf(Os.getgid(), Os.getuid())
            runCatching {
                File("/proc/self/status").readLines()
                    .firstOrNull { it.startsWith("Groups:") }
                    ?.removePrefix("Groups:")?.trim()
                    ?.split(Regex("\\s+"))?.mapNotNull { it.toIntOrNull() }
                    ?.let { gids += it }
            }
            val groupFile = File(rootfs, "etc/group")
            val existing = groupFile.takeIf { it.exists() }?.readText() ?: ""
            val add = gids.filter { !existing.contains(":x:$it:") }
                .joinToString("") { "aid_$it:x:$it:\n" }
            if (add.isNotEmpty()) groupFile.appendText(add)

            // Bash only flushes history to $HISTFILE on a CLEAN exit. Users normally just close the
            // app/tab, which SIGKILLs the shell (LinuxSessionService.destroy()) before that ever runs
            // — history is silently lost. Force a flush after every command instead. The minimal
            // ubuntu-base rootfs ships no ~/.bashrc/~/.profile for root, but /etc/profile (from
            // base-files) always sources /etc/profile.d/*.sh, so that's the reliable anchor point.
            File(rootfs, "etc/profile.d").mkdirs()
            File(rootfs, "etc/profile.d/00-lobishell-history.sh").writeText(
                "export HISTFILE=\"\${HOME:-/root}/.bash_history\"\n" +
                "export HISTSIZE=10000\n" +
                "export HISTFILESIZE=20000\n" +
                "export HISTCONTROL=ignoredups\n" +
                "shopt -s histappend\n" +
                "export PROMPT_COMMAND=\"history -a\${PROMPT_COMMAND:+; \$PROMPT_COMMAND}\"\n"
            )
        }.onFailure { Log.w(TAG, "configureRootfs('$id') failed: ${it.message}") }
    }

    // ── Rootfs extraction ───────────────────────────────────────────────────

    private fun extractRootfs(context: Context, id: String, progress: (String) -> Unit) {
        val rootfs = rootfsDir(context, id)
        val staging = File(userlandDir(context, id), "rootfs-staging")
        staging.deleteRecursively()
        staging.mkdirs()

        progress("Extracting rootfs…")
        openTarballStream(context, progress).buffered().use { buffered ->
            // Detect compression by magic bytes: xz = FD 37 7A; gzip = 1F 8B.
            buffered.mark(16)
            val magic = ByteArray(6)
            val n = buffered.read(magic)
            buffered.reset()
            val decompressed: InputStream = when {
                n >= 3 && magic[0] == 0xFD.toByte() && magic[1] == 0x37.toByte() && magic[2] == 0x7A.toByte() ->
                    XZInputStream(buffered)
                n >= 2 && magic[0] == 0x1F.toByte() && magic[1] == 0x8B.toByte() ->
                    GzipCompressorInputStream(buffered)
                else -> { Log.w(TAG, "Unrecognized compression magic — treating as raw tar"); buffered }
            }
            decompressed.use { ds ->
                TarArchiveInputStream(ds).use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
                        val name = entry.name.removePrefix("./").removePrefix("/")
                        if (name.isEmpty()) { entry = tar.nextEntry; continue }
                        val target = safeChild(staging, name)
                        when {
                            entry.isDirectory -> target.mkdirs()
                            entry.isSymbolicLink -> {
                                target.parentFile?.mkdirs()
                                runCatching {
                                    if (target.exists() || isSymlink(target)) target.delete()
                                    Os.symlink(entry.linkName, target.absolutePath)
                                }.onFailure { Log.w(TAG, "symlink ${target.path} -> ${entry.linkName}: ${it.message}") }
                            }
                            entry.isLink -> {
                                target.parentFile?.mkdirs()
                                val src = safeChild(staging, entry.linkName.removePrefix("./").removePrefix("/"))
                                if (src.exists()) src.copyTo(target, overwrite = true)
                                else Log.w(TAG, "Hard link source missing: ${entry.linkName} — skipping $name")
                            }
                            else -> {
                                target.parentFile?.mkdirs()
                                target.outputStream().use { tar.copyTo(it) }
                                applyMode(target, entry.mode)
                            }
                        }
                        entry = tar.nextEntry
                    }
                }
            }
        }

        if (rootfs.exists()) rootfs.deleteRecursively()
        if (!staging.renameTo(rootfs)) error("Could not move staging → ${rootfs.absolutePath}")
        Log.i(TAG, "Rootfs extracted to ${rootfs.absolutePath}")
    }

    /** Tarball stream: bundled asset if present, else the shared cached download (fetched once). */
    private fun openTarballStream(context: Context, progress: (String) -> Unit): InputStream {
        val a = arch()
        tryOpenAsset(context, "rootfs-$a.tar.xz")?.let { return it.first }
        val cached = File(context.cacheDir, "rootfs-$a.tar.gz")
        if (cached.exists() && cached.length() > 0L) {
            progress("Using cached rootfs (${"%.1f".format(cached.length() / 1_048_576.0)} MB)…")
        } else {
            downloadFile(ROOTFS_URL, cached, ubuntuArch(), "rootfs", ROOTFS_SHA256, progress)
        }
        return cached.inputStream()
    }

    private fun installProotLibs(context: Context) {
        val a = arch()
        val libDir = File(context.filesDir, "usr/lib").apply { mkdirs() }
        for (lib in listOf("libtalloc.so.2", "libandroid-shmem.so")) {
            if (File(libDir, lib).exists()) continue   // shared — install once
            val res = tryOpenAsset(context, "$lib-$a") ?: run { Log.w(TAG, "Missing lib asset $lib-$a"); continue }
            val dest = File(libDir, lib)
            res.first.use { it.copyTo(dest.outputStream()) }
            dest.setReadable(true, false)
            Log.i(TAG, "proot dependency installed: ${dest.absolutePath}")
        }
    }

    // ── Download / verify / utils ───────────────────────────────────────────

    private fun downloadFile(
        urlTemplate: String, destFile: File, arch: String, label: String,
        sha256Map: Map<String, String>, progress: (String) -> Unit = {},
    ): File {
        require(urlTemplate.isNotBlank()) { "No bundled $label asset and no URL configured" }
        val firstUrl = urlTemplate.replace("<ARCH>", arch)
        destFile.delete()
        var target = firstUrl
        var redirects = 0
        while (true) {
            val conn = (URL(target).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 120_000
                setRequestProperty("User-Agent", "lobishell-linux-plugin")
            }
            try {
                when (val code = conn.responseCode) {
                    in 300..399 -> {
                        val loc = conn.getHeaderField("Location") ?: error("Redirect $code without Location")
                        if (++redirects > 5) error("Too many redirects for $label")
                        target = URL(URL(target), loc).toString()
                    }
                    200 -> {
                        val total = conn.contentLengthLong
                        val totalMb = if (total > 0) "%.1f".format(total / 1_048_576.0) else "?"
                        progress("Downloading $label ($arch) from ${URL(target).host} — $totalMb MB")
                        conn.inputStream.use { input ->
                            destFile.outputStream().use { out ->
                                val buf = ByteArray(1 shl 16)
                                var done = 0L; var lastPct = -1
                                while (true) {
                                    val n = input.read(buf); if (n < 0) break
                                    out.write(buf, 0, n); done += n
                                    if (total > 0) {
                                        val pct = (done * 100 / total).toInt()
                                        if (pct >= lastPct + 5) {
                                            lastPct = pct
                                            progress("  $label: $pct%  (${"%.1f".format(done / 1_048_576.0)} / $totalMb MB)")
                                        }
                                    }
                                }
                            }
                        }
                        verifyChecksum(destFile, arch, sha256Map, label)
                        return destFile
                    }
                    else -> error("Download of $label failed HTTP $code")
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun verifyChecksum(file: File, arch: String, sha256Map: Map<String, String>, label: String) {
        val expected = sha256Map[arch]?.lowercase() ?: return
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        val actual = md.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) { file.delete(); error("$label SHA-256 mismatch") }
    }

    private fun safeChild(base: File, name: String): File {
        val child = File(base, name)
        val basePath = base.canonicalPath
        val childPath = child.canonicalPath
        if (childPath != basePath && !childPath.startsWith(basePath + File.separator)) {
            error("Refusing tar entry that escapes staging dir: $name")
        }
        return child
    }

    private fun applyMode(file: File, mode: Int) {
        file.setReadable(true, false)
        file.setWritable(true, true)
        if (mode and 0b001_001_001 != 0) file.setExecutable(true, false)
    }

    private fun tryOpenAsset(context: Context, assetName: String): Pair<InputStream, String>? =
        runCatching { Pair(context.assets.open(assetName), assetName) }.getOrNull()

    private fun isSymlink(file: File): Boolean = runCatching { Os.lstat(file.absolutePath); true }.getOrDefault(false)
}
