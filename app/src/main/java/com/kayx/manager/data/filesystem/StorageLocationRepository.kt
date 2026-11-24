package com.kayx.manager.data.filesystem

import android.content.Context
import android.net.Uri
import com.kayx.manager.domain.model.FileItem
import com.kayx.manager.domain.model.SortBy
import com.kayx.manager.domain.model.SearchMode
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Lists local and SAF-backed directories using one location contract.
 *
 * SAF entries receive opaque stable keys rather than fake filesystem paths. Callers
 * must resolve those keys through this repository / SafRepository and must never
 * pass them to java.io.File.
 */
class StorageLocationRepository {
    suspend fun list(
        context: Context,
        location: StorageLocation,
        showHidden: Boolean = false,
        sort: SortBy = SortBy.NAME
    ): List<FileItem> = withContext(Dispatchers.IO) {
        when (location) {
            is StorageLocation.Local -> location.directory.listFiles()
                ?.asSequence()
                ?.filter { showHidden || !it.isHidden }
                ?.map { file ->
                    FileItem(
                        path = file.absolutePath,
                        name = file.name,
                        isDir = file.isDirectory,
                        size = if (file.isFile) file.length().coerceAtLeast(0L) else 0L,
                        modified = file.lastModified(),
                        hidden = file.isHidden
                    )
                }
                ?.sortedWith(fileItemComparator(sort))
                ?.toList()
                .orEmpty()

            is StorageLocation.Saf -> {
                val tree = Uri.parse(location.treeUri)
                check(SafRepository.persistedTrees(context).contains(tree)) {
                    "Storage permission was revoked. Select the storage folder again."
                }
                check(SafRepository.tree(context, tree)?.exists() == true) {
                    "Storage is disconnected or no longer available."
                }
                SafRepository.entries(context, tree, location.relativePath)
                    .asSequence()
                    .filter { showHidden || !it.name.startsWith('.') }
                    .map { entry ->
                        FileItem(
                            path = location.child(entry.name).stableKey(),
                            name = entry.name,
                            isDir = entry.isDirectory,
                            size = if (entry.isDirectory) 0L else entry.size.coerceAtLeast(0L),
                            modified = entry.lastModified,
                            hidden = entry.name.startsWith('.')
                        )
                    }
                    .sortedWith(fileItemComparator(sort))
                    .toList()
            }
        }
    }

    suspend fun searchSaf(
        context: Context,
        location: StorageLocation.Saf,
        query: String,
        showHidden: Boolean,
        sort: SortBy,
        mode: SearchMode,
        allowedExtensions: Set<String> = emptySet(),
        maxResults: Int = 5000
    ): List<FileItem> = withContext(Dispatchers.IO) {
        val tree = Uri.parse(location.treeUri)
        check(SafRepository.persistedTrees(context).contains(tree)) { "Storage permission was revoked. Select the storage folder again." }
        val root = SafRepository.findByRelativePath(context, tree, location.relativePath)
            ?.takeIf { it.isDirectory } ?: error("Storage folder is unavailable")
        val regex = if (mode == SearchMode.REGEX) runCatching { Regex(query, RegexOption.IGNORE_CASE) }.getOrNull() else null
        val queue = java.util.ArrayDeque<Pair<DocumentFile, String>>()
        queue.add(root to location.relativePath.trim('/'))
        val visited = mutableSetOf<String>()
        val results = mutableListOf<FileItem>()
        while (queue.isNotEmpty() && results.size < maxResults) {
            val (folder, parentPath) = queue.removeFirst()
            val folderId = folder.uri.toString()
            if (!visited.add(folderId)) continue
            val children = folder.listFiles()
            for (child in children) {
                val name = child.name ?: continue
                if (!showHidden && name.startsWith('.')) continue
                val relative = listOf(parentPath, name).filter { it.isNotBlank() }.joinToString("/")
                val extension = name.substringAfterLast('.', "").lowercase()
                val matches = when (mode) {
                    SearchMode.CONTAINS -> name.contains(query, ignoreCase = true)
                    SearchMode.EXACT -> name.equals(query, ignoreCase = true)
                    SearchMode.REGEX -> regex?.containsMatchIn(name) == true
                }
                if (matches && (allowedExtensions.isEmpty() || child.isDirectory || extension in allowedExtensions)) {
                    results += FileItem(
                        path = StorageLocation.Saf(location.treeUri, relative).stableKey(),
                        name = name,
                        isDir = child.isDirectory,
                        size = if (child.isDirectory) 0L else child.length().coerceAtLeast(0L),
                        modified = child.lastModified(),
                        hidden = name.startsWith('.')
                    )
                    if (results.size >= maxResults) break
                }
                if (child.isDirectory) queue.addLast(child to relative)
            }
        }
        results.sortedWith(fileItemComparator(sort))
    }

    fun resolve(key: String): StorageLocation? = StorageLocation.fromStableKey(key)

    private fun fileItemComparator(sort: SortBy): Comparator<FileItem> =
        Comparator { a, b ->
            if (a.isDir != b.isDir) return@Comparator if (a.isDir) -1 else 1
            when (sort) {
                SortBy.NAME -> a.name.compareTo(b.name, ignoreCase = true)
                SortBy.TYPE -> a.name.substringAfterLast('.', "")
                    .compareTo(b.name.substringAfterLast('.', ""), ignoreCase = true)
                SortBy.SIZE -> b.size.compareTo(a.size)
                SortBy.DATE -> b.modified.compareTo(a.modified)
            }
        }
}
