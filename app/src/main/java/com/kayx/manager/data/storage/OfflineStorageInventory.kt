package com.kayx.manager.data.storage

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.kayx.manager.data.filesystem.SafRepository
import java.io.File
import java.security.MessageDigest
import java.util.ArrayDeque
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Bounded, read-only inventory over readable shared storage and persisted SAF tree grants. */
object OfflineStorageInventory {
    const val DEFAULT_MAX_ENTRIES = 50_000
    const val DEFAULT_MAX_DEPTH = 24
    const val DEFAULT_LARGE_BYTES = 100L * 1024 * 1024
    const val DEFAULT_OLD_AGE_DAYS = 180L
    private const val MIN_DUPLICATE_BYTES = 10L * 1024
    private const val MAX_HASH_CANDIDATES = 500
    private const val MAX_HASHED_BYTES = 512L * 1024 * 1024

    data class Entry(
        val key: String,
        val path: String,
        val name: String,
        val size: Long,
        val modified: Long,
        val category: Category?,
        val saf: Boolean
    )

    data class Result(
        val entries: List<Entry>,
        val largeFiles: List<Entry>,
        val oldFiles: List<Entry>,
        val duplicateCandidates: List<Entry>,
        val totalBytes: Long,
        val scannedRoots: Int,
        val inaccessibleRoots: Int,
        val truncated: Boolean
    )

    suspend fun scan(
        context: Context,
        maxEntries: Int = DEFAULT_MAX_ENTRIES,
        maxDepth: Int = DEFAULT_MAX_DEPTH,
        largeBytes: Long = DEFAULT_LARGE_BYTES,
        oldAgeDays: Long = DEFAULT_OLD_AGE_DAYS,
        onProgress: (Int) -> Unit = {}
    ): Result {
        require(maxEntries in 1..DEFAULT_MAX_ENTRIES)
        require(maxDepth in 1..DEFAULT_MAX_DEPTH)
        require(largeBytes > 0 && oldAgeDays > 0)
        val app = context.applicationContext
        val found = ArrayList<Entry>(minOf(maxEntries, 8_192))
        var rootsScanned = 0
        var inaccessibleRoots = 0
        var truncated = false
        val localRoot = Environment.getExternalStorageDirectory()

        if (localRoot.isDirectory && localRoot.canRead()) {
            rootsScanned++
            val pending = ArrayDeque<Pair<File, Int>>()
            pending.add(localRoot to 0)
            val visited = HashSet<String>()
            while (pending.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val (directory, depth) = pending.removeLast()
                val canonical = runCatching { directory.canonicalPath }.getOrNull() ?: continue
                if (!visited.add(canonical)) continue
                val children = directory.listFiles()
                if (children == null) { inaccessibleRoots++; continue }
                for (child in children) {
                    currentCoroutineContext().ensureActive()
                    if (java.nio.file.Files.isSymbolicLink(child.toPath())) continue
                    if (child.isDirectory) {
                        if (depth < maxDepth && child.name !in setOf("Android", ".Trash", ".trashed")) {
                            pending.add(child to depth + 1)
                        }
                    } else if (child.isFile && child.canRead()) {
                        if (found.size >= maxEntries) { truncated = true; break }
                        found += Entry(child.absolutePath, child.absolutePath, child.name, child.length(),
                            child.lastModified(), Category.of(child.name), saf = false)
                        if (found.size % 128 == 0) onProgress(found.size)
                    }
                }
                if (truncated) break
            }
        } else inaccessibleRoots++

        // Avoid double-counting the primary shared-storage tree when its filesystem root is readable.
        for (treeUri in SafRepository.persistedTrees(app)) {
            currentCoroutineContext().ensureActive()
            val primaryTree = runCatching {
                DocumentsContract.getTreeDocumentId(treeUri).substringBefore(':').equals("primary", true)
            }.getOrDefault(false)
            if (primaryTree && localRoot.canRead()) continue
            val root = SafRepository.tree(app, treeUri)
            if (root == null || !root.isDirectory) { inaccessibleRoots++; continue }
            rootsScanned++
            val pending = ArrayDeque<Triple<DocumentFile, String, Int>>()
            pending.add(Triple(root, "", 0))
            val visited = HashSet<String>()
            while (pending.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val (directory, relative, depth) = pending.removeLast()
                if (!visited.add(directory.uri.toString())) continue
                val children = try { directory.listFiles() } catch (_: SecurityException) {
                    inaccessibleRoots++
                    continue
                }
                for (child in children) {
                    currentCoroutineContext().ensureActive()
                    val name = child.name ?: continue
                    if (name == "." || name == ".." || name.contains('/') || name.contains('\\')) continue
                    val childPath = if (relative.isEmpty()) name else relative + "/" + name
                    if (child.isDirectory) {
                        if (depth < maxDepth && name !in setOf("Android", ".Trash", ".trashed")) {
                            pending.add(Triple(child, childPath, depth + 1))
                        }
                    } else if (child.isFile) {
                        if (found.size >= maxEntries) { truncated = true; break }
                        val key = child.uri.toString()
                        val size = runCatching { child.length() }.getOrDefault(0L).coerceAtLeast(0L)
                        val modified = runCatching { child.lastModified() }.getOrDefault(0L)
                        found += Entry(key, treeUri.toString() + "/" + childPath, name, size, modified,
                            Category.of(name), saf = true)
                        if (found.size % 128 == 0) onProgress(found.size)
                    }
                }
                if (truncated) break
            }
            if (truncated) break
        }

        currentCoroutineContext().ensureActive()
        val now = System.currentTimeMillis()
        val oldCutoff = now - oldAgeDays * 24L * 60L * 60L * 1000L
        val large = found.asSequence().filter { it.size >= largeBytes }
            .sortedByDescending { it.size }.take(100).toList()
        val old = found.asSequence().filter { it.modified in 1 until oldCutoff }
            .sortedBy { it.modified }.take(100).toList()
        val duplicates = findDuplicateCandidates(app, found)
        onProgress(found.size)
        return Result(found, large, old, duplicates, found.sumOf { it.size },
            rootsScanned, inaccessibleRoots, truncated)
    }

    private suspend fun findDuplicateCandidates(context: Context, entries: List<Entry>): List<Entry> {
        val buckets = entries.asSequence().filter { it.size >= MIN_DUPLICATE_BYTES }
            .groupBy { it.size }.values.filter { it.size > 1 }
        val digestGroups = LinkedHashMap<String, MutableList<Entry>>()
        var hashedCount = 0
        var hashedBytes = 0L
        for (bucket in buckets) {
            for (entry in bucket) {
                currentCoroutineContext().ensureActive()
                if (hashedCount >= MAX_HASH_CANDIDATES || hashedBytes >= MAX_HASHED_BYTES) break
                val digest = digest(context, entry) ?: continue
                hashedCount++
                hashedBytes += entry.size
                digestGroups.getOrPut(entry.size.toString() + ":" + digest) { ArrayList() }.add(entry)
            }
            if (hashedCount >= MAX_HASH_CANDIDATES || hashedBytes >= MAX_HASHED_BYTES) break
        }
        return digestGroups.values.filter { it.size > 1 }.flatten()
            .sortedByDescending { it.size }.take(100)
    }

    private suspend fun digest(context: Context, entry: Entry): String? {
        val input = try {
            if (entry.saf) context.contentResolver.openInputStream(Uri.parse(entry.key))
            else File(entry.key).takeIf { it.isFile && it.canRead() }?.inputStream()
        } catch (_: SecurityException) { null } ?: return null
        return input.use { stream ->
            val md = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = stream.read(buffer)
                if (count < 0) break
                if (count > 0) md.update(buffer, 0, count)
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
