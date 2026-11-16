package com.kayx.manager.data.storage

import android.content.Context
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

class CleanupAnalysis(
    val large: List<ScanFile>, val duplicates: List<ScanFile>, val oldVideos: List<ScanFile>,
    val whatsappJunk: List<ScanFile>, private val byCategory: Map<Category, List<ScanFile>>
) {
    val duplicateBytes: Long get() = duplicates.sumOf { it.size }
    val oldVideoBytes: Long get() = oldVideos.sumOf { it.size }
    val whatsappBytes: Long get() = whatsappJunk.sumOf { it.size }
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

    fun folders(ctx: Context, dirs: List<File>): Int = dirs.filter { it.isDirectory }.sumOf { d ->
        runCatching { d.walkTopDown().maxDepth(6).count { it.isFile } }.getOrDefault(0)
    }
}

object StorageScanner {
    private const val LARGE_BYTES = 100L * 1024 * 1024
    private const val OLD_VIDEO_MS = 180L * 24 * 3600 * 1000
    private val lock = Any()
    @Volatile private var entries: List<ScanFile>? = null
    @Volatile private var analysis: CleanupAnalysis? = null

    @Volatile private var appCtx: Context? = null
    fun init(ctx: Context) { appCtx = ctx.applicationContext }

    /** Whole files table in one query (what the system file manager does): instant even for 100k files. */
    private fun fromMediaStore(root: File): List<ScanFile>? {
        val ctx = appCtx ?: return null
        return runCatching {
            val out = ArrayList<ScanFile>(20_000)
            val dataColumn = "_data"
            val sizeColumn = "_size"
            val modifiedColumn = "date_modified"

            ctx.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                arrayOf(dataColumn, sizeColumn, modifiedColumn),
                "$sizeColumn > 0 AND $dataColumn LIKE ?",
                arrayOf(root.path + "/%"),
                null
            )?.use { q ->
                val ip = q.getColumnIndexOrThrow(dataColumn)
                val isz = q.getColumnIndexOrThrow(sizeColumn)
                val id = q.getColumnIndexOrThrow(modifiedColumn)
                val skip = root.path + "/Android/"
                while (q.moveToNext()) {
                    val path = q.getString(ip) ?: continue
                    if (path.startsWith(skip) || path.contains("/.")) continue
                    out.add(ScanFile(path, q.getLong(isz), q.getLong(id) * 1000, Category.of(path.substringAfterLast('/'))))
                }
            }
            out.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    fun externalRoot(): File = Environment.getExternalStorageDirectory()

    fun invalidate() { synchronized(lock) { entries = null; analysis = null } }

    fun cachedAnalysis(): CleanupAnalysis? = analysis

    /** One fast pass over shared storage; size and mtime come from the directory walk itself (no extra stat per file). */
    private fun allEntries(): List<ScanFile> = entries ?: synchronized(lock) {
        entries ?: run {
            val root = externalRoot()
            fromMediaStore(root)?.let { entries = it; return@run it }
            val out = ArrayList<ScanFile>(20_000)
            runCatching {
                Files.walkFileTree(root.toPath(), emptySet(), 20, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, a: BasicFileAttributes): FileVisitResult {
                        val n = dir.fileName?.toString() ?: return FileVisitResult.CONTINUE
                        val top = dir.parent == root.toPath() && n == "Android"
                        return if (top || n.startsWith(".thumb") || n == ".Trash" || n == ".trashed") FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
                    }
                    override fun visitFile(file: Path, a: BasicFileAttributes): FileVisitResult {
                        if (a.isRegularFile) {
                            val name = file.fileName.toString()
                            out.add(ScanFile(file.toString(), a.size(), a.lastModifiedTime().toMillis(), Category.of(name)))
                        }
                        return FileVisitResult.CONTINUE
                    }
                    override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult = FileVisitResult.CONTINUE
                })
            }
            out.also { entries = it }
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
        val dupes = findDuplicates(scanned)
        return CleanupAnalysis(large, dupes, old, junk, cats).also { analysis = it }
    }

    private fun findDuplicates(all: List<ScanFile>): List<ScanFile> {
        val out = ArrayList<ScanFile>()
        all.filter { it.size >= 10 * 1024 }.groupBy { it.size }.values.filter { it.size > 1 }.forEach { group ->
            val byHash = HashMap<String, MutableList<ScanFile>>()
            for (s in group) hash(File(s.path))?.let { byHash.getOrPut(it) { ArrayList() }.add(s) }
            byHash.values.filter { it.size > 1 }.forEach { same ->
                out += same.sortedBy { it.modified }.drop(1)
            }
        }
        return out.sortedByDescending { it.size }
    }

    private fun hash(f: File): String? = runCatching {
        val md = MessageDigest.getInstance("MD5")
        f.inputStream().buffered().use { ins ->
            val buf = ByteArray(1 shl 16); var n: Int
            while (ins.read(buf).also { n = it } > 0) md.update(buf, 0, n)
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    fun deviceStorage(): Pair<Long, Long>? = runCatching {
        val st = StatFs(externalRoot().path)
        st.totalBytes to st.availableBytes
    }.getOrNull()

    fun delete(ctx: Context, list: List<ScanFile>): Pair<Int, Long> {
        var n = 0; var freed = 0L
        val removed = ArrayList<String>()
        for (s in list) {
            val f = File(s.path)
            val size = f.length()
            if (f.isFile && f.delete()) { n++; freed += size; removed += s.path }
        }
        if (removed.isNotEmpty()) MediaScannerConnection.scanFile(ctx, removed.toTypedArray(), null, null)
        invalidate()
        return n to freed
    }
}
