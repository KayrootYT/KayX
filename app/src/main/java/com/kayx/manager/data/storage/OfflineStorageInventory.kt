package com.kayx.manager.data.storage

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.kayx.manager.data.filesystem.SafRepository
import com.kayx.manager.data.filesystem.StorageLocation
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

    data class DuplicateGroup(
        val sha256: String,
        val size: Long,
        val entries: List<Entry>
    ) {
        val reclaimableBytes: Long get() = size * (entries.size - 1L)
    }

    data class Result(
        val entries: List<Entry>,
        val largeFiles: List<Entry>,
        val oldFiles: List<Entry>,
        val duplicateCandidates: List<Entry>,
        val duplicateGroups: List<DuplicateGroup>,
        val totalBytes: Long,
        val scannedRoots: Int,
        val inaccessibleRoots: Int,
        val truncated: Boolean,
        val checkpointWarning: Boolean = false
    )

    suspend fun scan(
        context: Context,
        maxEntries: Int = DEFAULT_MAX_ENTRIES,
        maxDepth: Int = DEFAULT_MAX_DEPTH,
        largeBytes: Long = DEFAULT_LARGE_BYTES,
        oldAgeDays: Long = DEFAULT_OLD_AGE_DAYS,
        includeDuplicates: Boolean = false,
        resume: Boolean = false,
        onProgress: (Int) -> Unit = {}
    ): Result {
        require(maxEntries in 1..DEFAULT_MAX_ENTRIES)
        require(maxDepth in 1..DEFAULT_MAX_DEPTH)
        require(largeBytes > 0 && oldAgeDays > 0)
        val app = context.applicationContext
        val localRoot = Environment.getExternalStorageDirectory()
        val checkpoint = if (resume) OfflineStorageScanCheckpoint.load(app, localRoot, maxEntries, maxDepth) else null
        if (!resume || checkpoint == null) OfflineStorageScanCheckpoint.clear(app)
        val found = ArrayList<Entry>(minOf(maxEntries, 8_192)).apply { addAll(checkpoint?.entries.orEmpty()) }
        // A crash can occur after a directory has been checkpointed but before all of
        // its children are visited. Resuming that directory must not duplicate files
        // already captured in the previous checkpoint.
        val seenLocalPaths = found.asSequence().filterNot { it.saf }.map { it.path }.toHashSet()
        var rootsScanned = 0
        var inaccessibleRoots = 0
        var truncated = false
        var checkpointWarning = false
        var localComplete = checkpoint?.localComplete == true

        if (localComplete && localRoot.isDirectory && localRoot.canRead()) rootsScanned++
        if (!localComplete) {
            val pending = ArrayDeque<OfflineStorageScanCheckpoint.PendingDirectory>()
            checkpoint?.pending?.forEach(pending::addLast)
            if (pending.isEmpty() && localRoot.isDirectory && localRoot.canRead()) pending.add(localRoot.absoluteFile.let { OfflineStorageScanCheckpoint.PendingDirectory(it.path, 0) })
            if (pending.isEmpty() && (!localRoot.isDirectory || !localRoot.canRead())) inaccessibleRoots++
            else if (pending.isNotEmpty()) rootsScanned++
            var processedDirectories = 0
            while (pending.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val current = pending.removeLast()
                val directory = File(current.path)
                val depth = current.depth
                if (processedDirectories % 16 == 0) {
                    if (!OfflineStorageScanCheckpoint.save(app, OfflineStorageScanCheckpoint.State(
                        localRoot.canonicalPath, maxEntries, maxDepth, false,
                        (pending.toList() + current), found.toList()
                    ))) checkpointWarning = true
                }
                val canonical = runCatching { directory.canonicalFile }.getOrNull()
                if (canonical == null || java.nio.file.Files.isSymbolicLink(directory.toPath())) {
                    processedDirectories++
                    continue
                }
                val safeDirectory = canonical
                val children = runCatching { safeDirectory.listFiles() }.getOrNull()
                if (children == null) inaccessibleRoots++
                else for (child in children) {
                    currentCoroutineContext().ensureActive()
                    if (java.nio.file.Files.isSymbolicLink(child.toPath())) continue
                    if (child.isDirectory) {
                        if (depth < maxDepth && child.name !in setOf(".Trash", ".trashed") && !(safeDirectory.name == "Android" && child.name in setOf("data", "obb"))) {
                            pending.add(OfflineStorageScanCheckpoint.PendingDirectory(child.absolutePath, depth + 1))
                        }
                    } else if (child.isFile && child.canRead()) {
                        val childPath = child.absolutePath
                        if (seenLocalPaths.add(childPath)) {
                            if (found.size >= maxEntries) { truncated = true; break }
                            found += Entry(childPath, childPath, child.name, child.length(),
                                child.lastModified(), Category.of(child.name), saf = false)
                            if (found.size % 128 == 0) onProgress(found.size)
                        }
                    }
                }
                processedDirectories++
                if (truncated) break
            }
            localComplete = !truncated
            if (localComplete) {
                if (!OfflineStorageScanCheckpoint.save(app, OfflineStorageScanCheckpoint.State(
                    localRoot.canonicalPath, maxEntries, maxDepth, true, emptyList(), found.toList()
                ))) checkpointWarning = true
            } else OfflineStorageScanCheckpoint.clear(app)
        }

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
                val children = try { directory.listFiles() } catch (_: Exception) {
                    inaccessibleRoots++
                    continue
                }
                for (child in children) {
                    currentCoroutineContext().ensureActive()
                    val name = child.name ?: continue
                    if (name == "." || name == ".." || name.contains('/') || name.contains('\\')) continue
                    val childPath = if (relative.isEmpty()) name else relative + "/" + name
                    if (child.isDirectory) {
                        if (depth < maxDepth && name !in setOf(".Trash", ".trashed") && !(directory.name == "Android" && name in setOf("data", "obb"))) {
                            pending.add(Triple(child, childPath, depth + 1))
                        }
                    } else if (child.isFile) {
                        if (found.size >= maxEntries) { truncated = true; break }
                        val key = StorageLocation.Saf(treeUri.toString(), childPath).stableKey()
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
        val duplicateGroups = if (includeDuplicates) findDuplicateGroups(app, found) else emptyList()
        val duplicates = duplicateGroups.flatMap { it.entries }
        onProgress(found.size)
        // A completed or hard-capped scan has no resumable work left under this bounded scan contract.
        // Interrupted scans exit before this point and retain the last atomic checkpoint.
        OfflineStorageScanCheckpoint.clear(app)
        val result = Result(found, large, old, duplicates, duplicateGroups, found.sumOf { it.size },
            rootsScanned, inaccessibleRoots, truncated, checkpointWarning)
        // Keep the last completed inventory available after process death. This is a
        // bounded app-private metadata snapshot; file contents are never copied or indexed.
        if (!OfflineStorageInventorySnapshotStore(app.filesDir).save(result)) checkpointWarning = true
        return result.copy(checkpointWarning = checkpointWarning)
    }

    private suspend fun findDuplicateGroups(context: Context, entries: List<Entry>): List<DuplicateGroup> {
        val buckets = entries.asSequence().filter { it.size >= MIN_DUPLICATE_BYTES }
            .groupBy { it.size }.values.filter { it.size > 1 }
        val digestGroups = LinkedHashMap<String, MutableList<Entry>>()
        var attemptedCount = 0
        var hashedCount = 0
        var hashedBytes = 0L
        for (bucket in buckets) {
            for (entry in bucket) {
                currentCoroutineContext().ensureActive()
                if (attemptedCount >= MAX_HASH_CANDIDATES || entry.size > MAX_HASHED_BYTES - hashedBytes) break
                attemptedCount++
                val digest = digest(context, entry) ?: continue
                hashedCount++
                hashedBytes += entry.size
                digestGroups.getOrPut(entry.size.toString() + ":" + digest) { ArrayList() }.add(entry)
            }
            if (attemptedCount >= MAX_HASH_CANDIDATES || hashedBytes >= MAX_HASHED_BYTES) break
        }
        return digestGroups.entries.asSequence()
            .filter { it.value.size > 1 }
            .map { (key, files) ->
                val size = files.first().size
                DuplicateGroup(
                    sha256 = key.substringAfter(':'),
                    size = size,
                    entries = files.sortedWith(compareBy<Entry> { it.saf }.thenBy { it.path })
                )
            }
            .sortedByDescending { it.reclaimableBytes }
            .take(100)
            .toList()
    }

    private suspend fun digest(context: Context, entry: Entry): String? {
        val input = try {
            if (entry.saf) {
                val location = StorageLocation.fromStableKey(entry.key) as? StorageLocation.Saf ?: return null
                val tree = Uri.parse(location.treeUri)
                if (tree !in SafRepository.persistedTrees(context)) return null
                val document = SafRepository.findByRelativePath(context, tree, location.relativePath)
                    ?.takeIf { it.isFile } ?: return null
                context.contentResolver.openInputStream(document.uri)
            } else File(entry.key).takeIf { it.isFile && it.canRead() }?.inputStream()
        } catch (_: Exception) { null } ?: return null
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
