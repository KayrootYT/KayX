package com.kayx.manager.data.filesystem

import android.os.Environment
import java.io.File

/** Recycle bin in shared storage with a crash-recoverable TSV index and 30-day retention. */
object RecycleBin {
    private const val RETENTION_DAYS = 30L
    private const val DAY_MS = 24L * 60 * 60 * 1000

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
        if (src.isDirectory) check(src.copyRecursively(dest, overwrite = false)) { "Copy failed: ${src.name}" }
        else src.copyTo(dest, overwrite = false)
        check(src.deleteRecursively()) { "Could not remove ${src.name}" }
    }

    private fun rawEntries(): List<Triple<String, String, Long>> {
        val f = index()
        if (!f.isFile) return emptyList()
        val root = Environment.getExternalStorageDirectory()
        val bin = dir()
        return runCatching { f.readLines() }.getOrDefault(emptyList()).mapNotNull { line ->
            val p = line.split('\t')
            if (p.size != 3 || !isSafeRecycleEntryId(p[0])) return@mapNotNull null
            val deletedAt = p[2].toLongOrNull() ?: return@mapNotNull null
            if (!isSafeRecycleOriginal(p[1], root, bin)) return@mapNotNull null
            Triple(p[0], p[1], deletedAt)
        }
    }

    /** Atomic replacement prevents a crash during index writing from truncating the only index. */
    private fun writeIndex(entries: List<Triple<String, String, Long>>) {
        val target = index()
        val temp = File(target.parentFile, "index.tsv.tmp")
        java.io.FileOutputStream(temp).use { stream ->
            stream.write(entries.joinToString("") { "${it.first}\t${it.second}\t${it.third}\n" }.toByteArray(Charsets.UTF_8))
            stream.flush()
            stream.fd.sync()
        }
        val backup = File(target.parentFile, "index.tsv.bak")
        backup.delete()
        val hadIndex = target.exists()
        if (hadIndex) check(target.renameTo(backup)) { "Could not preserve recycle-bin index" }
        try {
            check(temp.renameTo(target)) { "Could not finalize recycle-bin index" }
            backup.delete()
        } catch (t: Throwable) {
            if (hadIndex) check(backup.renameTo(target)) { "Could not restore recycle-bin index" }
            throw t
        }
    }

    fun list(): List<Entry> {
        val d = dir()
        return rawEntries().filter { File(d, it.first).exists() }.map { (id, path, ts) ->
            val f = File(d, id)
            Entry(id, path, ts, f.isDirectory, if (f.isFile) f.length() else 0L)
        }.sortedByDescending { it.deletedAt }
    }

    @Synchronized
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
            val deletedAt = System.currentTimeMillis()
            val id = System.nanoTime().toString(36) + "_" + f.name.take(60)
            val record = Triple(id, f.absolutePath, deletedAt)
            // Commit intent first: after a crash the index still knows the original path.
            writeIndex(rawEntries() + added + record)
            val target = payload(id)
            try {
                move(f, target)
                target.setLastModified(deletedAt)
                added += record
            } catch (t: Throwable) {
                writeIndex(rawEntries().filterNot { it.first == id })
                throw t
            }
        }
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

    @Synchronized
    fun purge(e: Entry): Result<Unit> = runCatching {
        payload(e.id).deleteRecursively()
        writeIndex(rawEntries().filter { it.first != e.id })
    }

    /**
     * Permanently removes entries after 30 days. Also cleans old orphan payloads left by a crash
     * between moving a file and committing the index. Unknown/new payloads are never purged early.
     * Call on app start/resume; operation is safe to repeat.
     */
    @Synchronized
    fun purgeExpired(now: Long = System.currentTimeMillis()): Result<Int> = runCatching {
        val bin = dir().canonicalFile
        val entries = rawEntries()
        val expired = entries.filter { isRecycleExpired(it.third, now) }
        var removed = 0
        val keep = ArrayList<Triple<String, String, Long>>()
        val indexedIds = entries.mapTo(HashSet()) { it.first }

        for (entry in entries) {
            val item = payload(entry.first)
            if (isRecycleExpired(entry.third, now)) {
                if (!item.exists() || item.deleteRecursively()) removed++
                else keep += entry
            } else if (item.exists()) keep += entry
            // Missing payloads are stale index records and can be dropped safely.
        }

        bin.listFiles().orEmpty().forEach { item ->
            if (item.name == "index.tsv" || item.name == "index.tsv.tmp" || item.name in indexedIds) return@forEach
            val canonical = runCatching { item.canonicalFile }.getOrNull() ?: return@forEach
            if (canonical.parentFile != bin || !isSafeRecycleEntryId(item.name)) return@forEach
            if (isRecycleExpired(item.lastModified(), now) && item.deleteRecursively()) removed++
        }

        if (keep.size != entries.size || expired.isNotEmpty()) writeIndex(keep)
        removed
    }

    @Synchronized
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

internal fun isRecycleExpired(deletedAt: Long, now: Long, retentionDays: Long = 30L): Boolean {
    if (deletedAt <= 0L || deletedAt > now || retentionDays <= 0L) return false
    val age = now - deletedAt
    val retention = runCatching { Math.multiplyExact(retentionDays, 24L * 60 * 60 * 1000) }.getOrDefault(Long.MAX_VALUE)
    return age >= retention
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
