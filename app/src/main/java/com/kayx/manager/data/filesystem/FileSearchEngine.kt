package com.kayx.manager.data.filesystem

import com.kayx.manager.domain.model.FileItem
import com.kayx.manager.domain.model.SearchMode
import com.kayx.manager.domain.model.SortBy
import java.io.File
import java.nio.file.Files
import java.util.LinkedHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Unified, cancellable on-device search engine.
 *
 * Search is scoped to the directory selected by the user. It indexes metadata only (never reads
 * file bodies), avoids symlink loops, skips unreadable directories, and reuses a short-lived
 * bounded index while the query is edited.
 */
enum class FileSearchCategory(val label: String, val extensions: Set<String>) {
    ALL("All", emptySet()),
    IMAGES("Images", setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "svg", "heic", "heif", "avif", "tif", "tiff", "ico", "raw", "dng", "psd")),
    VIDEOS("Videos", setOf("mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v", "mpeg", "mpg", "ts", "mts", "m2ts", "wmv", "flv")),
    DOCUMENTS("Documents", setOf("pdf", "doc", "docx", "docm", "dot", "dotx", "xls", "xlsx", "xlsm", "xltx", "ods", "odt", "ppt", "pptx", "pptm", "ppsx", "odp", "epub", "mobi", "txt", "md", "rtf", "csv")),
    AUDIO("Audio", setOf("mp3", "m4a", "wav", "ogg", "oga", "opus", "flac", "aac", "wma", "aiff", "mid", "midi", "amr", "awb"))
}

data class FileSearchRequest(
    val root: File,
    val query: String,
    val showHidden: Boolean = false,
    val mode: SearchMode = SearchMode.CONTAINS,
    val modifiedAfter: Long = 0L,
    val modifiedBefore: Long = Long.MAX_VALUE,
    val minSizeBytes: Long = 0L,
    val maxSizeBytes: Long = Long.MAX_VALUE,
    val includeDirectories: Boolean = true,
    val allowedExtensions: Set<String> = emptySet(),
    val sortBy: SortBy = SortBy.NAME,
    val maxDepth: Int = 64,
    val maxResults: Int = 10_000
)

data class FileSearchResult(
    val items: List<FileItem>,
    val scannedEntries: Int,
    val truncated: Boolean
)

class FileSearchEngine(
    private val indexTtlMs: Long = 12_000L,
    private val maxIndexedEntries: Int = 100_000,
    private val defaultMaxDepth: Int = 64
) {
    private data class Index(val createdAt: Long, val entries: List<FileItem>, val scanned: Int, val truncated: Boolean)

    private val indexes = object : LinkedHashMap<String, Index>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Index>?): Boolean = size > 3
    }
    private val lock = Any()

    fun invalidate() = synchronized(lock) { indexes.clear() }

    suspend fun search(request: FileSearchRequest): FileSearchResult = withContext(Dispatchers.IO) {
        val root = request.root
        val query = request.query.trim()
        if (!root.exists() || !root.isDirectory || query.isEmpty()) return@withContext FileSearchResult(emptyList(), 0, false)

        val extension = query.removePrefix("*").takeIf { it.startsWith(".") && it.length > 1 }
        val glob = if (extension == null && ('*' in query || '?' in query)) {
            runCatching {
                Regex("^" + query.map { c ->
                    when (c) {
                        '*' -> ".*"
                        '?' -> "."
                        else -> Regex.escape(c.toString())
                    }
                }.joinToString("") + "$", RegexOption.IGNORE_CASE)
            }.getOrNull()
        } else null
        val regex = if (request.mode == SearchMode.REGEX && extension == null && glob == null) {
            runCatching { Regex(query, RegexOption.IGNORE_CASE) }.getOrNull()
                ?: return@withContext FileSearchResult(emptyList(), 0, false)
        } else null

        val rootPath = runCatching { root.canonicalPath }.getOrElse { root.absolutePath }
        val cacheKey = "$rootPath|hidden=${request.showHidden}"
        val now = System.currentTimeMillis()
        val index = synchronized(lock) { indexes[cacheKey]?.takeIf { now - it.createdAt <= indexTtlMs } }
            ?: buildIndex(root, request.showHidden, request.maxDepth.coerceIn(1, 128), rootPath).also {
                synchronized(lock) { indexes[cacheKey] = it }
            }

        val extensions = request.allowedExtensions.map { it.removePrefix(".").lowercase() }.filter { it.isNotBlank() }.toSet()
        val cap = request.maxResults.coerceIn(1, 100_000)
        val matches = ArrayList<FileItem>(minOf(cap, 512))
        for (item in index.entries) {
            currentCoroutineContext().ensureActive()
            if (item.modified < request.modifiedAfter || item.modified > request.modifiedBefore) continue
            if (item.isDir && (!request.includeDirectories || extension != null || extensions.isNotEmpty() || request.minSizeBytes > 0L || request.maxSizeBytes < Long.MAX_VALUE)) continue
            if (!item.isDir && (item.size < request.minSizeBytes || item.size > request.maxSizeBytes)) continue
            if (extensions.isNotEmpty() && !item.isDir && item.name.substringAfterLast('.', "").lowercase() !in extensions) continue
            val matched = when {
                extension != null -> !item.isDir && item.name.endsWith(extension, ignoreCase = true)
                glob != null -> glob.matches(item.name)
                regex != null -> regex.containsMatchIn(item.name)
                request.mode == SearchMode.EXACT -> item.name.equals(query, ignoreCase = true)
                else -> item.name.contains(query, ignoreCase = true)
            }
            if (matched) {
                if (matches.size == cap) break
                matches += item
            }
        }
        val sorted = matches.sortedWith { a, b ->
            if (a.isDir != b.isDir) {
                if (a.isDir) -1 else 1
            } else when (request.sortBy) {
                SortBy.NAME -> a.name.compareTo(b.name, ignoreCase = true)
                SortBy.TYPE -> a.name.substringAfterLast('.', "").compareTo(b.name.substringAfterLast('.', ""), ignoreCase = true)
                SortBy.SIZE -> b.size.compareTo(a.size)
                SortBy.DATE -> b.modified.compareTo(a.modified)
            }
        }
        FileSearchResult(sorted, index.scanned, index.truncated || matches.size >= cap)
    }

    /** Content search shares the same indexed traversal, cancellation and permission handling. */
    suspend fun searchContents(
        request: FileSearchRequest,
        ignoreCase: Boolean = true,
        maxBytesPerFile: Long = 2_000_000L,
        isActive: () -> Boolean = { true }
    ): FileSearchResult {
        if (request.query.isBlank()) return FileSearchResult(emptyList(), 0, false)
        val candidates = search(request.copy(query = "*", mode = SearchMode.CONTAINS, includeDirectories = false, maxResults = 100_000))
        val matches = ArrayList<FileItem>()
        val needle = request.query
        for (item in candidates.items) {
            currentCoroutineContext().ensureActive()
            if (!isActive()) break
            if (item.isDir || item.size > maxBytesPerFile) continue
            val file = File(item.path)
            val found = runCatching {
                file.inputStream().use { input ->
                    val head = ByteArray(1024)
                    val count = input.read(head)
                    if (count > 0 && head.take(count).any { it == 0.toByte() }) return@use false
                    input.bufferedReader().useLines { lines ->
                        lines.any { line ->
                            if (ignoreCase) line.contains(needle, ignoreCase = true) else line.contains(needle)
                        }
                    }
                }
            }.getOrDefault(false)
            if (found) matches += item
            if (matches.size >= request.maxResults.coerceIn(1, 100_000)) break
        }
        return FileSearchResult(matches, candidates.scannedEntries, candidates.truncated)
    }

    private suspend fun buildIndex(root: File, showHidden: Boolean, maxDepth: Int, rootPath: String): Index {
        val entries = ArrayList<FileItem>(2048)
        val queue = ArrayDeque<Pair<File, Int>>()
        val visited = HashSet<String>()
        queue.add(root to 0)
        visited.add(rootPath)
        var scanned = 0
        var truncated = false

        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (directory, depth) = queue.removeFirst()
            if (depth >= maxDepth) {
                if (directory.listFiles()?.isNotEmpty() == true) truncated = true
                continue
            }
            val children = directory.listFiles() ?: continue
            for (child in children) {
                if (++scanned % 128 == 0) currentCoroutineContext().ensureActive()
                if (!showHidden && child.isHidden) continue
                val symbolic = runCatching { Files.isSymbolicLink(child.toPath()) }.getOrDefault(false)
                val isDirectory = runCatching { child.isDirectory }.getOrDefault(false)
                if (isDirectory && !symbolic) {
                    val canonical = runCatching { child.canonicalPath }.getOrNull()
                    if (canonical == null || !visited.add(canonical)) continue
                }
                if (entries.size >= maxIndexedEntries) {
                    truncated = true
                    break
                }
                entries += FileItem(
                    path = child.absolutePath,
                    name = child.name,
                    isDir = isDirectory,
                    size = if (isDirectory) 0L else runCatching { child.length() }.getOrDefault(0L),
                    modified = runCatching { child.lastModified() }.getOrDefault(0L),
                    hidden = runCatching { child.isHidden }.getOrDefault(false)
                )
                if (isDirectory && !symbolic) queue.add(child to depth + 1)
            }
            if (truncated && entries.size >= maxIndexedEntries) break
        }
        return Index(System.currentTimeMillis(), entries, scanned, truncated)
    }
}
