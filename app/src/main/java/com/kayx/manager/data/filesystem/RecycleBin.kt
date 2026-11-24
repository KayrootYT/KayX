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
    private fun index(): File {
        val root = dir()
        val target = File(root, "index.tsv")
        val backup = File(root, "index.tsv.bak")
        if (!target.exists() && backup.exists()) backup.renameTo(target)
        else if (target.exists()) backup.delete()
        File(root, "index.tsv.tmp").delete()
        return target
    }

    private fun payload(id: String): File {
        require(isSafeRecycleEntryId(id)) { "Invalid recycle-bin entry" }
        val bin = dir().canonicalFile
        val item = File(bin, id).canonicalFile
        require(item.parentFile == bin) { "Invalid recycle-bin path" }
        return item
    }

    private class SourceCleanupFailed(message: String, val completedCopy: File) : java.io.IOException(message)

    private fun move(src: File, dest: File) {
        if (src.renameTo(dest)) return
        check(src.canonicalPath == src.absolutePath) { "Cannot safely copy a symbolic link into the recycle bin" }
        try {
            if (src.isDirectory) check(src.copyRecursively(dest, overwrite = false)) { "Copy failed: ${src.name}" }
            else src.copyTo(dest, overwrite = false)
        } catch (t: Throwable) {
            runCatching { dest.deleteRecursively() }
            throw t
        }
        // Keep the completed recovery copy and its index if source cleanup fails.
        if (!src.deleteRecursively()) throw SourceCleanupFailed(
            "Copied ${src.name}, but could not completely remove the original", dest
        )
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

    @Synchronized
    fun list(): List<Entry> {
        return rawEntries().mapNotNull { (id, path, ts) ->
            val f = runCatching { payload(id) }.getOrNull()?.takeIf { it.exists() } ?: return@mapNotNull null
            Entry(id, path, ts, f.isDirectory, if (f.isFile) f.length() else 0L)
        }.sortedByDescending { it.deletedAt }
    }

    @Synchronized
    fun trash(files: List<File>): Result<Unit> = runCatching {
        val d = dir()
        for (f in files) {
            if (!f.exists()) continue
            val safeSource = isSafeRecycleOriginal(f.absolutePath, Environment.getExternalStorageDirectory(), d)
            check(safeSource) { "Cannot move files outside shared storage to the recycle bin: ${f.name}" }
            require(!f.absolutePath.contains('\t') && !f.absolutePath.contains('\n') && !f.absolutePath.contains('\r')) {
                "File path contains unsupported control characters"
            }
            val deletedAt = System.currentTimeMillis()
            val id = java.util.UUID.randomUUID().toString().replace("-", "") + "_payload"
            val record = Triple(id, f.absolutePath, deletedAt)
            // Commit intent first: after a crash the index still knows the original path.
            writeIndex(rawEntries() + record)
            val target = payload(id)
            try {
                move(f, target)
                target.setLastModified(deletedAt)
            } catch (t: Throwable) {
                if (t !is SourceCleanupFailed) {
                    runCatching { target.deleteRecursively() }
                    writeIndex(rawEntries().filterNot { it.first == id })
                }
                throw t
            }
        }
    }

    @Synchronized
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
        val item = payload(e.id)
        if (item.exists()) check(item.deleteRecursively()) { "Could not permanently delete ${e.name}" }
        writeIndex(rawEntries().filter { it.first != e.id })
    }

    /**
     * Permanently removes indexed entries after 30 days and drops stale index records whose
     * payload is already missing. Unindexed legacy payloads are retained because their mtime may
     * be the original file's age, not its deletion time. Call on app start/resume; safe to repeat.
     */
    @Synchronized
    fun purgeExpired(now: Long = System.currentTimeMillis()): Result<Int> = runCatching {
        dir() // Ensure the recycle-bin directory exists before reading the index.
        val entries = rawEntries()
        val expired = entries.filter { isRecycleExpired(it.third, now) }
        var removed = 0
        val keep = ArrayList<Triple<String, String, Long>>()

        for (entry in entries) {
            val item = payload(entry.first)
            if (isRecycleExpired(entry.third, now)) {
                if (!item.exists() || item.deleteRecursively()) removed++
                else keep += entry
            } else if (item.exists()) keep += entry
            // Missing payloads are stale index records and can be dropped safely.
        }

        // Unindexed legacy payloads have no trustworthy deletion timestamp (mtime may be the
        // original file's age). Keep them for manual recovery instead of risking early data loss.
        if (keep.size != entries.size || expired.isNotEmpty()) writeIndex(keep)
        removed
    }

    @Synchronized
    fun empty(): Result<Unit> = runCatching {
        val root = dir()
        val contents = root.listFiles() ?: error("Could not read recycle bin")
        for (item in contents) {
            if (item.name != "index.tsv") check(item.deleteRecursively()) { "Could not remove ${item.name}" }
        }
        val indexFile = index()
        if (indexFile.exists()) check(indexFile.delete()) { "Could not clear recycle-bin index" }
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
