package com.kayx.manager.data.filesystem

import android.os.Environment
import java.io.File

/** Simple recycle bin in /sdcard/.KayXRecycleBin with a tab-separated index (id, original path, deleted time). */
object RecycleBin {
    data class Entry(val id: String, val original: String, val deletedAt: Long, val isDir: Boolean, val size: Long) {
        val name: String get() = original.substringAfterLast('/')
    }

    private fun dir(): File = File(Environment.getExternalStorageDirectory(), ".KayXRecycleBin").apply { mkdirs() }
    private fun index() = File(dir(), "index.tsv")

    private fun payload(id: String): File {
        require(isSafeRecycleEntryId(id)) { "Invalid recycle-bin entry" }
        val bin = dir().canonicalFile
        val item = File(bin, id).canonicalFile
        require(item.parentFile == bin) { "Invalid recycle-bin path" }
        return item
    }

    private fun move(src: File, dest: File) {
        if (src.renameTo(dest)) return
        if (src.isDirectory) check(src.copyRecursively(dest, overwrite = false)) { "Copy failed: ${src.name}" } else src.copyTo(dest, overwrite = false)
        check(src.deleteRecursively()) { "Could not remove ${src.name}" }
    }

    private fun rawEntries(): List<Triple<String, String, Long>> {
        val f = index()
        if (!f.exists()) return emptyList()
        val root = Environment.getExternalStorageDirectory()
        val bin = dir()
        return f.readLines().mapNotNull { l ->
            val p = l.split('\t')
            if (p.size != 3 || !isSafeRecycleEntryId(p[0])) return@mapNotNull null
            val deletedAt = p[2].toLongOrNull() ?: return@mapNotNull null
            if (!isSafeRecycleOriginal(p[1], root, bin)) return@mapNotNull null
            Triple(p[0], p[1], deletedAt)
        }
    }

    private fun writeIndex(list: List<Triple<String, String, Long>>) {
        index().writeText(list.joinToString("") { "${it.first}\t${it.second}\t${it.third}\n" })
    }

    fun list(): List<Entry> {
        val d = dir()
        return rawEntries().filter { File(d, it.first).exists() }.map { (id, path, ts) ->
            val f = File(d, id)
            Entry(id, path, ts, f.isDirectory, if (f.isFile) f.length() else 0L)
        }.sortedByDescending { it.deletedAt }
    }

    fun trash(files: List<File>): Result<Unit> = runCatching {
        val d = dir()
        val added = ArrayList<Triple<String, String, Long>>()
        for (f in files) {
            if (!f.exists()) continue
            val safeSource = isSafeRecycleOriginal(f.absolutePath, Environment.getExternalStorageDirectory(), d)
            check(safeSource) { "Cannot move files outside shared storage to the recycle bin: ${f.name}" }
            require(!f.absolutePath.contains('\t') && !f.absolutePath.contains('\n') && !f.absolutePath.contains('\r')) {
                "File path contains unsupported control characters"
            }
            val id = System.nanoTime().toString(36) + "_" + f.name.take(60)
            move(f, File(d, id))
            added += Triple(id, f.absolutePath, System.currentTimeMillis())
        }
        if (added.isNotEmpty()) writeIndex(rawEntries() + added)
    }

    fun restore(e: Entry): Result<File> = runCatching {
        val src = payload(e.id)
        check(src.exists()) { "Item no longer in recycle bin" }
        check(isSafeRecycleOriginal(e.original, Environment.getExternalStorageDirectory(), dir())) { "Original path is outside shared storage" }
        val orig = File(e.original)
        orig.parentFile?.mkdirs()
        val dest = if (orig.exists()) FileOps.uniqueName(orig.parentFile ?: orig, orig.name) else orig
        move(src, dest)
        writeIndex(rawEntries().filter { it.first != e.id })
        dest
    }

    fun purge(e: Entry): Result<Unit> = runCatching {
        payload(e.id).deleteRecursively()
        writeIndex(rawEntries().filter { it.first != e.id })
    }

    fun empty(): Result<Unit> = runCatching {
        dir().listFiles()?.forEach { if (it.name != "index.tsv") it.deleteRecursively() }
        index().delete()
        Unit
    }
}


/** Rejects index-controlled names that could escape the recycle-bin directory. */
internal fun isSafeRecycleEntryId(id: String): Boolean {
    val separator = id.indexOf('_')
    return id != "index.tsv" && separator > 0 && separator < id.lastIndex &&
        id.substring(0, separator).all { it in '0'..'9' || it in 'a'..'z' } &&
        !id.contains('/') && !id.contains('\\') &&
        !id.contains('\t') && !id.contains('\n') && !id.contains('\r')
}

/** Recycle-bin originals must be real paths inside shared storage, never the bin itself. */
internal fun isSafeRecycleOriginal(original: String, sharedRoot: File, binDir: File): Boolean = runCatching {
    val root = sharedRoot.canonicalFile
    val bin = binDir.canonicalFile
    val target = File(original).canonicalFile
    val rootPrefix = root.path.trimEnd(File.separatorChar) + File.separator
    val binPrefix = bin.path.trimEnd(File.separatorChar) + File.separator
    target.path.startsWith(rootPrefix) && target.path != bin.path && !target.path.startsWith(binPrefix)
}.getOrDefault(false)
