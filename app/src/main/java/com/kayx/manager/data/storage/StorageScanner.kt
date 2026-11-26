package com.kayx.manager.data.storage

import android.content.Context
import com.kayx.manager.core.NativeCore
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale

enum class Category(val label: String, val exts: Set<String>) {
    IMAGES("Images", setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")),
    VIDEOS("Videos", setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "flv", "m4v")),
    AUDIO("Audio", setOf("mp3", "m4a", "wav", "flac", "ogg", "aac", "opus", "amr")),
    DOCUMENTS("Documents", setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "odt", "ods", "odp", "rtf", "csv", "epub")),
    APKS("APKs", setOf("apk", "apks", "xapk", "apkm")),
    ARCHIVES("Archives", setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "jar"));

    companion object {
        fun of(fileName: String): Category? {
            val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return values().firstOrNull { ext in it.exts }
        }
    }
}

data class ScanFile(val path: String, val size: Long, val modified: Long, val category: Category?) {
    val name: String get() = path.substringAfterLast('/')
}

fun formatBytes(b: Long): String {
    if (b < 1024) return "$b B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = b.toDouble(); var i = -1
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
}

/** Only regular files canonically contained in shared storage are eligible for permanent deletion. */
internal fun isSafeSharedStorageFile(file: File, sharedRoot: File): Boolean = runCatching {
    val root = sharedRoot.canonicalFile
    val target = file.canonicalFile
    val rootPath = root.path.trimEnd(File.separatorChar) + File.separator
    !Files.isSymbolicLink(file.toPath()) &&
        target.path != root.path &&
        target.path.startsWith(rootPath) &&
        target.isFile
}.getOrDefault(false)

class CleanupAnalysis(
    val large: List<ScanFile>, val duplicates: List<ScanFile>, val oldVideos: List<ScanFile>,
    val whatsappJunk: List<ScanFile>, val temporaryFiles: List<ScanFile>,
    private val byCategory: Map<Category, List<ScanFile>>
) {
    val duplicateBytes: Long get() = duplicates.sumOf { it.size }
    val oldVideoBytes: Long get() = oldVideos.sumOf { it.size }
    val whatsappBytes: Long get() = whatsappJunk.sumOf { it.size }
    val temporaryBytes: Long get() = temporaryFiles.sumOf { it.size }
    fun of(c: Category): List<ScanFile> = byCategory[c].orEmpty()
}

object QuickAccess {
    class Target(val name: String, val dirs: List<File>)
    private val root get() = StorageScanner.externalRoot()
    val targets: List<Target> by lazy {
        fun t(n: String, vararg rel: String) = Target(n, rel.map { File(root, it) })
        listOf(
            t("WhatsApp", "Android/media/com.whatsapp/WhatsApp", "WhatsApp"),
            t("Telegram", "Telegram", "Android/media/org.telegram.messenger/Telegram"),
            t("Screenshots", "Pictures/Screenshots", "DCIM/Screenshots"),
            t("Downloads", Environment.DIRECTORY_DOWNLOADS),
            t("Recorder", "Recordings", "Music/Recordings", "Sounds", "Recorder"),
            t("Bluetooth", "Bluetooth", "Download/Bluetooth")
        )
    }
    fun firstExisting(t: Target): File? = t.dirs.firstOrNull { it.isDirectory }
}

object MediaCounts {
    fun category(ctx: Context, c: Category): Int? = runCatching { StorageScanner.count(c) }.getOrNull()

    fun folders(ctx: Context, dirs: List<File>): Int = dirs.filter { it.isDirectory }.sumOf { directory ->
        runCatching { walkFilesBounded(directory, maxDepth = 6, parallelism = 2).size }.getOrDefault(0)
    }
}

object StorageScanner {
    private const val LARGE_BYTES = 100L * 1024 * 1024
    private const val OLD_VIDEO_MS = 180L * 24 * 3600 * 1000
    private val lock = Any()
    @Volatile private var entries: List<ScanFile>? = null
    @Volatile private var analysis: CleanupAnalysis? = null
    @Volatile private var appCtx: Context? = null
    @Volatile private var scanThread: Thread? = null
    /** Files found so far by the running scan, shown as live progress. */
    val progress = java.util.concurrent.atomic.AtomicInteger(0)
    /** Which source produced the list (or why MediaStore was skipped); handy for diagnosing. */
    @Volatile var status: String = ""

    fun externalRoot(): File = Environment.getExternalStorageDirectory()

    /** Starts scanning in the background right away so lists are usually ready before the user taps. */
    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        synchronized(lock) {
            if (entries != null || scanThread?.isAlive == true) return
            scanThread = Thread({
                try {
                    allEntries()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } finally {
                    synchronized(lock) {
                        if (scanThread === Thread.currentThread()) scanThread = null
                    }
                }
            }, "kayx-prefetch").apply { isDaemon = true; start() }
        }
    }

    fun invalidate() {
        // Interrupt before acquiring the cache lock: an active scan may hold that lock.
        scanThread?.interrupt()
        synchronized(lock) { entries = null; analysis = null }
    }

    fun cachedAnalysis(): CleanupAnalysis? = analysis

    /** Whole files table in one query (what the system file manager does): instant even for 100k files. */
    private fun fromMediaStore(root: File): List<ScanFile>? {
        val ctx = appCtx ?: run { status = "No context"; return null }
        return try {
            val out = ArrayList<ScanFile>(20_000)
            val q = ctx.contentResolver.query(MediaStore.Files.getContentUri("external"), arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED),
                "${MediaStore.MediaColumns.SIZE} > 0 AND ${MediaStore.MediaColumns.DATA} LIKE ?", arrayOf(root.path + "/%"), null)
            if (q == null) { status = "MediaStore returned null"; return null }
            q.use {
                val ip = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA); val isz = it.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE); val id = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val skip = root.path + "/Android/"
                while (it.moveToNext()) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("Storage scan cancelled")
                    val path = it.getString(ip) ?: continue
                    if (path.startsWith(skip) || path.contains("/.")) continue
                    out.add(ScanFile(path, it.getLong(isz), it.getLong(id) * 1000, Category.of(path.substringAfterLast('/'))))
                    progress.set(out.size)
                }
            }
            if (out.size < 50) { status = "MediaStore gave only ${out.size} files, scanning folders"; null } else { status = "MediaStore"; out }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Throwable) {
            status = "MediaStore failed: ${e.javaClass.simpleName}"; null
        }
    }

    /** Rust folder walk (rayon, 4 threads). Null when the native library is unavailable. */
    private fun walkNative(root: File): List<ScanFile>? {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Storage scan cancelled")
        val lines = NativeCore.scan(root.absolutePath) ?: return null
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Storage scan cancelled")
        val out = ArrayList<ScanFile>(lines.size)
        for (l in lines) {
            val p = l.split('\t', limit = 3)
            if (p.size == 3) out.add(ScanFile(p[2], p[0].toLongOrNull() ?: 0L, p[1].toLongOrNull() ?: 0L, Category.of(p[2].substringAfterLast('/'))))
        }
        progress.set(out.size)
        status = "Rust scan"
        return out
    }

    /** Fixed-size, interruptible traversal; never creates one task per nested directory. */
    private fun walkParallel(root: File): List<ScanFile> {
        fun includeDirectory(directory: File): Boolean =
            directory.name != ".Trash" && directory.name != ".trashed" &&
                !directory.name.startsWith(".thumb") &&
                !(directory.parentFile == root && directory.name == "Android")

        val files = walkFilesBounded(root, maxDepth = 22, parallelism = 4, includeDirectory = ::includeDirectory)
        val results = ArrayList<ScanFile>(files.size)
        for (file in files) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Storage scan cancelled")
            results += ScanFile(file.absolutePath, file.length(), file.lastModified(), Category.of(file.name))
            progress.incrementAndGet()
        }
        return results
    }

    private fun allEntries(): List<ScanFile> = entries ?: synchronized(lock) {
        entries ?: run {
            progress.set(0)
            val root = externalRoot()
            val list = fromMediaStore(root) ?: walkNative(root) ?: walkParallel(root).also { status = "$status · folder scan".trim(' ', '·') }
            list.also { entries = it }
        }
    }

    /** All files that belong to a known category. */
    fun scan(): List<ScanFile> = allEntries().filter { it.category != null && File(it.path).exists() }

    fun count(c: Category): Int = allEntries().count { it.category == c }

    fun analyze(): CleanupAnalysis {
        analysis?.let { return it }
        val scanned = allEntries()
        val cats = scanned.filter { it.category != null }.groupBy { it.category!! }
        val large = scanned.filter { it.size >= LARGE_BYTES }.sortedByDescending { it.size }
        val now = System.currentTimeMillis()
        val old = cats[Category.VIDEOS].orEmpty().filter { now - it.modified > OLD_VIDEO_MS }.sortedByDescending { it.size }
        val junk = scanned.filter { s ->
            val p = s.path
            (p.contains("/WhatsApp/") || p.contains("/com.whatsapp/")) && (p.contains("/.Statuses/") || p.contains("/Sent/") || p.contains("/Cache/"))
        }
        // Conservative temp cleanup: only sizeable, old partial-download/temp files.
        // Backups, logs, app-private data and Android/data are deliberately excluded.
        val tempExt = setOf("tmp", "temp", "part", "crdownload", "download")
        val tempCutoff = now - 30L * 24 * 3600 * 1000
        val temporary = scanned.filter { s ->
            val p = s.path
            val ext = p.substringAfterLast('.', "").lowercase(Locale.ROOT)
            s.size >= 1024L * 1024 && s.modified in 1 until tempCutoff &&
                ext in tempExt && !p.contains("/Android/") && !p.contains("/.")
        }.sortedByDescending { it.size }
        val dupes = findDuplicates(scanned)
        return CleanupAnalysis(large, dupes, old, junk, temporary, cats).also { analysis = it }
    }

    private fun findDuplicates(all: List<ScanFile>): List<ScanFile> {
        NativeCore.duplicateGroups(all.filter { it.size >= 10 * 1024 }.map { it.path }.toTypedArray())?.let { groups ->
            val byPath = all.associateBy { it.path }
            val res = ArrayList<ScanFile>()
            for (g in groups) {
                val files = g.split('\u001f').mapNotNull { byPath[it] }
                // keep the oldest copy as the original
                if (files.size > 1) res += files.sortedBy { it.modified }.drop(1)
            }
            return res.sortedByDescending { it.size }
        }
        val out = ArrayList<ScanFile>()
        all.filter { it.size >= 10 * 1024 }.groupBy { it.size }.values.filter { it.size > 1 }.forEach { group ->
            val byHash = HashMap<String, MutableList<ScanFile>>()
            for (s in group) hash(File(s.path))?.let { byHash.getOrPut(it) { ArrayList() }.add(s) }
            byHash.values.filter { it.size > 1 }.forEach { same ->
                // SHA-256 is only a candidate filter; verify actual bytes before reporting duplicates.
                val exactGroups = ArrayList<MutableList<ScanFile>>()
                for (candidate in same) {
                    val matching = exactGroups.firstOrNull { group ->
                        filesHaveSameContent(File(candidate.path), File(group.first().path))
                    }
                    if (matching != null) matching += candidate else exactGroups += mutableListOf(candidate)
                }
                exactGroups.filter { it.size > 1 }.forEach { exact ->
                    // keep the oldest copy as the original
                    out += exact.sortedBy { it.modified }.drop(1)
                }
            }
        }
        return out.sortedByDescending { it.size }
    }

    private fun hash(f: File): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().buffered().use { ins ->
            val buf = ByteArray(1 shl 16); var n: Int
            while (ins.read(buf).also { n = it } > 0) md.update(buf, 0, n)
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** Total and available bytes of shared storage, or null if unreadable. */
    fun deviceStorage(): Pair<Long, Long>? = runCatching {
        val st = StatFs(externalRoot().path)
        st.totalBytes to st.availableBytes
    }.getOrNull()

    /** Permanently deletes [list]. Returns (deleted count, freed bytes). */
    fun delete(ctx: Context, list: List<ScanFile>): Pair<Int, Long> {
        var n = 0; var freed = 0L
        val removed = ArrayList<String>()
        val root = externalRoot()
        for (s in list) {
            val f = File(s.path)
            val size = f.length()
            if (isSafeSharedStorageFile(f, root) && f.delete()) { n++; freed += size; removed += s.path }
        }
        if (removed.isNotEmpty()) MediaScannerConnection.scanFile(ctx, removed.toTypedArray(), null, null)
        invalidate()
        return n to freed
    }
}


/** Byte-for-byte duplicate verification; hashes are never treated as proof of equality. */
internal fun filesHaveSameContent(left: File, right: File): Boolean = runCatching {
    if (!left.isFile || !right.isFile || left.length() != right.length()) return@runCatching false
    left.inputStream().buffered().use { a ->
        right.inputStream().buffered().use { b ->
            val ab = ByteArray(64 * 1024)
            val bb = ByteArray(64 * 1024)
            while (true) {
                fun readBlock(input: java.io.InputStream, buffer: ByteArray): Int {
                    var used = 0
                    while (used < buffer.size) {
                        val n = input.read(buffer, used, buffer.size - used)
                        if (n < 0) break
                        if (n == 0) continue
                        used += n
                    }
                    return used
                }
                val an = readBlock(a, ab)
                val bn = readBlock(b, bb)
                if (an != bn || !ab.copyOf(an).contentEquals(bb.copyOf(bn))) return@runCatching false
                if (an == 0) return@runCatching true
            }
            @Suppress("UNREACHABLE_CODE")
            true
        }
    }
}.getOrDefault(false)
