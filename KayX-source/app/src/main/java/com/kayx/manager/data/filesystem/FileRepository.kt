package com.kayx.manager.data.filesystem

import com.kayx.manager.domain.model.FileItem
import com.kayx.manager.domain.model.SearchMode
import com.kayx.manager.domain.model.SortBy
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import java.util.LinkedHashMap

class FileRepository {

    // Reuse a directory index while the user edits a query. A short TTL keeps results fresh
    // when files are changed by other apps; mutations in KayX explicitly invalidate it.
    private data class SearchIndex(val createdAt: Long, val items: List<FileItem>)
    private val searchIndexes = object : LinkedHashMap<String, SearchIndex>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SearchIndex>?): Boolean = size > 2
    }
    private val searchIndexLock = Any()
    private val searchIndexTtlMs = 15_000L

    fun invalidateSearchCache() = synchronized(searchIndexLock) { searchIndexes.clear() }

    suspend fun list(dir: File, showHidden: Boolean = false, sort: SortBy = SortBy.NAME): List<FileItem> = withContext(Dispatchers.IO) {
        if (!dir.exists() || !dir.isDirectory) return@withContext emptyList()
        var files = dir.listFiles()
        if (dir.absolutePath == "/") {
            // Android often hides parts of "/" from apps: merge in the well-known top-level folders so the root is never blank.
            val have = files.orEmpty().map { it.name }.toSet()
            val extra = listOf("storage", "sdcard", "system", "system_ext", "vendor", "product", "odm", "data", "cache", "mnt", "etc", "apex", "oem", "metadata", "config", "dev", "proc", "sys", "acct", "linkerconfig", "bin", "lib")
                .filter { it !in have }.map { File("/", it) }.filter { it.exists() }
            files = (files.orEmpty().toList() + extra).toTypedArray()
        }
        if (files == null) return@withContext emptyList()
        files.asSequence()
            .filter { showHidden || !it.isHidden }
            .map { f ->
                FileItem(
                    path = f.absolutePath,
                    name = f.name,
                    isDir = f.isDirectory,
                    size = if (f.isFile) f.length() else 0L,
                    modified = f.lastModified(),
                    hidden = f.isHidden
                )
            }
            .sortedWith { a, b ->
                if (a.isDir != b.isDir) return@sortedWith if (a.isDir) -1 else 1
                when (sort) {
                    SortBy.NAME -> a.name.compareTo(b.name, ignoreCase = true)
                    SortBy.TYPE -> a.name.substringAfterLast('.', "").compareTo(b.name.substringAfterLast('.', ""), ignoreCase = true)
                    SortBy.SIZE -> b.size.compareTo(a.size)
                    SortBy.DATE -> b.modified.compareTo(a.modified)
                }
            }
            .toList()
    }

    suspend fun search(
        dir: File, query: String, showHidden: Boolean = false,
        mode: SearchMode = SearchMode.CONTAINS, maxAgeMs: Long = 0L
    ): List<FileItem> = withContext(Dispatchers.IO) {
        if (!dir.exists() || !dir.isDirectory || query.isBlank()) return@withContext emptyList()
        val regex = if (mode == SearchMode.REGEX) runCatching { Regex(query, RegexOption.IGNORE_CASE) }.getOrNull() ?: return@withContext emptyList() else null
        val since = if (maxAgeMs > 0) System.currentTimeMillis() - maxAgeMs else 0L
        val key = dir.absolutePath + "|hidden=$showHidden"
        val now = System.currentTimeMillis()
        val cached = synchronized(searchIndexLock) { searchIndexes[key]?.takeIf { now - it.createdAt < searchIndexTtlMs } }
        val index = cached?.items ?: buildSearchIndex(dir, showHidden).also { built ->
            synchronized(searchIndexLock) { searchIndexes[key] = SearchIndex(System.currentTimeMillis(), built) }
        }

        index.asSequence()
            .filter { since == 0L || it.modified >= since }
            .filter {
                when (mode) {
                    SearchMode.CONTAINS -> it.name.contains(query, ignoreCase = true)
                    SearchMode.EXACT -> it.name.equals(query, ignoreCase = true)
                    SearchMode.REGEX -> regex!!.containsMatchIn(it.name)
                }
            }
            .sortedWith { a, b ->
                if (a.isDir != b.isDir) if (a.isDir) -1 else 1
                else a.name.compareTo(b.name, ignoreCase = true)
            }
            .toList()
    }

    /** One cancellable breadth-first scan builds a reusable index for all subsequent queries. */
    private suspend fun buildSearchIndex(root: File, showHidden: Boolean): List<FileItem> {
        val out = ArrayList<FileItem>(2048)
        val pending = ArrayDeque<File>()
        pending.add(root)
        val visitedDirectories = HashSet<String>()
        runCatching { visitedDirectories.add(root.canonicalPath) }
        var visited = 0
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val dir = pending.removeFirst()
            val children = dir.listFiles() ?: continue
            for (file in children) {
                if (++visited % 128 == 0) currentCoroutineContext().ensureActive()
                if (!showHidden && file.isHidden) continue
                // Avoid traversing Android's virtual kernel filesystems when the user searches from '/'.
                if (dir.absolutePath == File.separator && file.name in setOf("proc", "sys", "dev", "acct")) continue
                val isDir = file.isDirectory
                if (isDir) {
                    val canonical = runCatching { file.canonicalPath }.getOrNull()
                    if (canonical == null || !visitedDirectories.add(canonical)) continue
                }
                out += FileItem(
                    path = file.absolutePath,
                    name = file.name,
                    isDir = isDir,
                    size = if (isDir) 0L else file.length(),
                    modified = file.lastModified(),
                    hidden = file.isHidden
                )
                if (isDir) pending.add(file)
            }
        }
        return out
    }

    suspend fun createFolder(parent: File, name: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val folder = File(parent, name)
            check(isSafeName(name)) { "Invalid folder name" }
            check(!folder.exists()) { "Folder already exists" }
            check(folder.mkdirs()) { "Could not create folder" }
        }
    }

    suspend fun createFile(parent: File, name: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            check(isSafeName(name)) { "Invalid file name" }
            val file = File(parent, name)
            check(!file.exists()) { "File already exists" }
            check(file.createNewFile()) { "Could not create file" }
        }
    }

    suspend fun delete(files: List<File>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            for (f in files) {
                if (f.exists()) check(f.deleteRecursively()) { "Could not delete ${f.name}" }
            }
        }
    }

    suspend fun rename(file: File, newName: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            check(isSafeName(newName)) { "Invalid file name" }
            val dest = File(file.parentFile, newName)
            check(!dest.exists()) { "Target name already exists" }
            check(file.renameTo(dest)) { "Rename failed" }
        }
    }

    suspend fun copy(files: List<File>, targetDir: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            for (f in files) {
                check(f.exists()) { "Source not found: ${f.name}" }
                val dest = File(targetDir, f.name)
                check(!dest.exists()) { "Already exists: ${f.name}" }
                if (f.isDirectory) {
                    val sourcePath = f.canonicalPath.trimEnd(File.separatorChar) + File.separator
                    val targetPath = dest.canonicalPath
                    check(!targetPath.startsWith(sourcePath)) { "Cannot copy a folder into itself" }
                    check(f.copyRecursively(dest, overwrite = false)) { "Copy failed: ${f.name}" }
                } else f.copyTo(dest, overwrite = false)
            }
        }
    }

    suspend fun move(files: List<File>, targetDir: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            for (f in files) {
                check(f.exists()) { "Source not found: ${f.name}" }
                val dest = File(targetDir, f.name)
                check(!dest.exists()) { "Already exists: ${f.name}" }
                if (f.isDirectory) {
                    val sourcePath = f.canonicalPath.trimEnd(File.separatorChar) + File.separator
                    val targetPath = dest.canonicalPath
                    check(!targetPath.startsWith(sourcePath)) { "Cannot move a folder into itself" }
                    check(f.copyRecursively(dest, overwrite = false)) { "Move copy failed: ${f.name}" }
                    check(f.deleteRecursively()) { "Could not remove source: ${f.name}" }
                } else {
                    f.copyTo(dest, overwrite = false)
                    check(f.delete()) { "Could not remove source: ${f.name}" }
                }
            }
        }
    }

    private fun isSafeName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." &&
            !name.contains('/') && !name.contains('\\') && !name.any { it.code < 32 }

    suspend fun compareFiles(file1: File, file2: File): ComparisonResult = withContext(Dispatchers.IO) {
        val sameSize = file1.length() == file2.length()
        val sameName = file1.name == file2.name
        var identicalContent = false

        if (sameSize) {
            identicalContent = try {
                file1.inputStream().use { input1 ->
                    file2.inputStream().use { input2 ->
                        val buf1 = ByteArray(64 * 1024)
                        val buf2 = ByteArray(64 * 1024)
                        var equal = true
                        while (equal) {
                            val r1 = input1.read(buf1)
                            val r2 = input2.read(buf2)
                            if (r1 != r2) { equal = false; break }
                            if (r1 == -1) break
                            if (!buf1.copyOf(r1).contentEquals(buf2.copyOf(r2))) {
                                equal = false
                                break
                            }
                        }
                        equal
                    }
                }
            } catch (e: Exception) {
                false
            }
        }

        ComparisonResult(
            file1Name = file1.name,
            file2Name = file2.name,
            file1Size = file1.length(),
            file2Size = file2.length(),
            sameSize = sameSize,
            sameName = sameName,
            identicalContent = identicalContent
        )
    }
}

data class ComparisonResult(
    val file1Name: String,
    val file2Name: String,
    val file1Size: Long,
    val file2Size: Long,
    val sameSize: Boolean,
    val sameName: Boolean,
    val identicalContent: Boolean
)
