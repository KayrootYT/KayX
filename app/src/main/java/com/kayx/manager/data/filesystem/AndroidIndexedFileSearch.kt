package com.kayx.manager.data.filesystem

import android.content.Context
import android.provider.MediaStore
import com.kayx.manager.domain.model.FileItem
import com.kayx.manager.domain.model.SearchMode
import com.kayx.manager.domain.model.SortBy
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * MediaStore-first search, with the bounded filesystem scanner as a fallback for files that
 * have not yet been indexed by Android.
 */
class AndroidIndexedFileSearch(private val context: Context) {
    private val fallback = FileSearchEngine()

    suspend fun search(request: FileSearchRequest): FileSearchResult = withContext(Dispatchers.IO) {
        if (request.query.isBlank()) return@withContext FileSearchResult(emptyList(), 0, false)
        val indexed = queryMediaStore(request)
        if (indexed.items.isNotEmpty() || request.mode == SearchMode.REGEX) indexed
        else fallback.search(request)
    }

    suspend fun searchContents(request: FileSearchRequest): FileSearchResult =
        fallback.searchContents(request)

    fun invalidate() = fallback.invalidate()

    private suspend fun queryMediaStore(request: FileSearchRequest): FileSearchResult {
        val root = request.root.canonicalFile
        if (!root.exists() || !root.isDirectory) return FileSearchResult(emptyList(), 0, false)
        val pattern = mediaStoreLikePattern(request.query, request.mode)
            ?: return FileSearchResult(emptyList(), 0, false)

        val rootPrefix = root.path.trimEnd(File.separatorChar) + File.separator
        val projection = arrayOf(
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED
        )
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? ESCAPE '\\' AND " +
            "${MediaStore.MediaColumns.DATA} LIKE ? ESCAPE '\\'"
        val args = arrayOf(pattern, escapeLike(rootPrefix) + "%")
        val uri = MediaStore.Files.getContentUri("external")
        val matches = ArrayList<FileItem>()
        var scanned = 0
        val extensions = request.allowedExtensions.map { it.removePrefix(".").lowercase(Locale.ROOT) }.toSet()

        context.contentResolver.query(uri, projection, selection, args, null)?.use { cursor ->
            val pathColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
            val nameColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val modifiedColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
            check(pathColumn >= 0 && nameColumn >= 0 && sizeColumn >= 0 && modifiedColumn >= 0) {
                "Android file index is missing required columns"
            }
            val cap = request.maxResults.coerceIn(1, 100_000)
            while (cursor.moveToNext()) {
                if (++scanned % 128 == 0) currentCoroutineContext().ensureActive()
                val path = cursor.getString(pathColumn) ?: continue
                val name = cursor.getString(nameColumn) ?: File(path).name
                val file = File(path)
                if (!file.isFile || (!request.showHidden && name.startsWith("."))) continue
                val size = cursor.getLong(sizeColumn)
                val modified = cursor.getLong(modifiedColumn) * 1000L
                if (modified < request.modifiedAfter || modified > request.modifiedBefore) continue
                if (size < request.minSizeBytes || size > request.maxSizeBytes) continue
                if (extensions.isNotEmpty() && name.substringAfterLast('.', "").lowercase(Locale.ROOT) !in extensions) continue
                matches += FileItem(path, name, false, size, modified, name.startsWith("."))
                if (matches.size >= cap) break
            }
        } ?: throw IllegalStateException("Android file index is unavailable")

        val sorted = when (request.sortBy) {
            SortBy.NAME -> matches.sortedBy { it.name.lowercase(Locale.ROOT) }
            SortBy.TYPE -> matches.sortedBy { it.name.substringAfterLast('.', "").lowercase(Locale.ROOT) }
            SortBy.SIZE -> matches.sortedByDescending { it.size }
            SortBy.DATE -> matches.sortedByDescending { it.modified }
        }
        return FileSearchResult(sorted, scanned, matches.size >= request.maxResults.coerceIn(1, 100_000))
    }
}

/** Maps MT-style name syntax to a parameterized SQL LIKE pattern. */
internal fun mediaStoreLikePattern(query: String, mode: SearchMode): String? {
    val value = query.trim()
    if (value.isEmpty() || mode == SearchMode.REGEX) return null
    val normalized = when {
        value.startsWith("*.") -> value
        value.startsWith(".") -> "*$value"
        else -> value
    }
    val escaped = buildString {
        normalized.forEach { ch ->
            when (ch) {
                '%', '_', '\\' -> { append('\\'); append(ch) }
                '*' -> append('%')
                '?' -> append('_')
                else -> append(ch)
            }
        }
    }
    return when {
        mode == SearchMode.EXACT -> escaped
        '*' in normalized || '?' in normalized || normalized.startsWith(".") -> escaped
        else -> "%$escaped%"
    }
}

private fun escapeLike(value: String): String = buildString {
    value.forEach { ch ->
        if (ch == '%' || ch == '_' || ch == '\\') append('\\')
        append(ch)
    }
}
