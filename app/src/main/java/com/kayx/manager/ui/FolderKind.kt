package com.kayx.manager.ui

import android.util.LruCache
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/** What a folder mostly contains; drives the folder's icon (photos folder -> photo icon, music folder -> music icon …). */
enum class FolderKind(val icon: ImageVector, val color: Color, val spriteIndex: Int) {
    NONE(Icons.Default.Folder, Color(0xFFFFB02E), 0),
    MIXED(Icons.Default.Folder, Color(0xFFFFB02E), 1),
    PHOTO(Icons.Default.PhotoLibrary, Color(0xFF00ACC1), 2),
    MUSIC(Icons.Default.LibraryMusic, Color(0xFFAB47BC), 3),
    VIDEO(Icons.Default.VideoLibrary, Color(0xFFFF7043), 4),
    DOCS(Icons.Default.Description, Color(0xFF5C6BF5), 5),
    PDF(Icons.Default.Description, Color(0xFFC62828), 6),
    SPREADSHEET(Icons.Default.Description, Color(0xFF2E8B57), 7),
    PRESENTATION(Icons.Default.Description, Color(0xFFE87924), 8),
    TEXT(Icons.Default.Description, Color(0xFF607D8B), 9),
    ARCHIVE(Icons.Default.FolderZip, Color(0xFFA1887F), 10),
    APK(Icons.Default.Android, Color(0xFF43A047), 11),
    CODE(Icons.Default.Code, Color(0xFF607D8B), 12)
}

object FolderKinds {
    private val IMG = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "svg", "heic", "heif", "avif", "tif", "tiff", "ico", "raw", "dng", "psd")
    private val AUD = setOf("mp3", "m4a", "wav", "ogg", "oga", "opus", "flac", "aac", "wma", "aiff", "mid", "midi", "amr")
    private val VID = setOf("mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v", "mpeg", "mpg", "mts", "m2ts", "wmv", "flv")
    private val DOC = setOf("doc", "docx", "docm", "dot", "dotx", "odt", "epub", "mobi", "rtf")
    private val PDF = setOf("pdf")
    private val SHEET = setOf("xls", "xlsx", "xlsm", "xltx", "ods", "csv", "tsv")
    private val PRESENTATION = setOf("ppt", "pptx", "pptm", "ppsx", "odp")
    private val TEXT = setOf("txt", "md")
    private val ZIP = setOf("zip", "7z", "rar", "tar", "gz", "bz2", "xz", "tgz", "zst", "iso", "jar")
    private val APKS = setOf("apk", "apks", "xapk", "apkm")
    private val CODE = setOf("kt", "kts", "java", "smali", "js", "jsx", "ts", "tsx", "py", "rb", "go", "rs", "c", "h", "cpp", "hpp", "cs", "php", "swift", "sh", "html", "css", "scss", "xml", "json", "toml", "gradle", "sql", "yml", "yaml")

    private const val SCAN_LIMIT = 400
    private val cache = LruCache<String, FolderKind>(4000)
    private val gate = Semaphore(2)

    private fun key(path: String, modified: Long) = "$path|$modified"
    fun peek(path: String, modified: Long): FolderKind? = cache.get(key(path, modified))

    suspend fun load(path: String, modified: Long): FolderKind {
        peek(path, modified)?.let { return it }
        val kind = withContext(Dispatchers.IO) { gate.withPermit { scan(path) } }
        cache.put(key(path, modified), kind)
        return kind
    }

    /** Looks at the folder's direct files (first [SCAN_LIMIT]) and returns the most common media/document type. */
    private fun scan(path: String): FolderKind {
        val names = runCatching { File(path).list() }.getOrNull() ?: return FolderKind.NONE
        val counts = IntArray(FolderKind.values().size)
        for (n in names.take(SCAN_LIMIT)) {
            val e = n.substringAfterLast('.', "").lowercase()
            if (e.isEmpty()) continue
            val k = when (e) {
                in IMG -> FolderKind.PHOTO
                in AUD -> FolderKind.MUSIC
                in VID -> FolderKind.VIDEO
                in APKS -> FolderKind.APK
                in ZIP -> FolderKind.ARCHIVE
                in PDF -> FolderKind.PDF
                in SHEET -> FolderKind.SPREADSHEET
                in PRESENTATION -> FolderKind.PRESENTATION
                in TEXT -> FolderKind.TEXT
                in DOC -> FolderKind.DOCS
                in CODE -> FolderKind.CODE
                else -> null
            } ?: continue
            counts[k.ordinal]++
        }
        val populatedKinds = FolderKind.values().count { it != FolderKind.NONE && it != FolderKind.MIXED && counts[it.ordinal] > 0 }
        if (populatedKinds > 1) return FolderKind.MIXED
        var best = FolderKind.NONE; var bestN = 0
        for (k in FolderKind.values()) if (k != FolderKind.NONE && k != FolderKind.MIXED && counts[k.ordinal] > bestN) {
            best = k; bestN = counts[k.ordinal]
        }
        return best
    }
}

@Composable
fun rememberFolderKind(path: String, modified: Long, enabled: Boolean): FolderKind {
    if (!enabled) return FolderKind.NONE
    val initial = remember(path, modified) { FolderKinds.peek(path, modified) ?: FolderKind.NONE }
    val kind by produceState(initial, path, modified) { value = FolderKinds.load(path, modified) }
    return kind
}
