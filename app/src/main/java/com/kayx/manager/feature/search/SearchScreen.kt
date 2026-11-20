package com.kayx.manager.feature.search

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kayx.manager.data.filesystem.FileSearchCategory
import com.kayx.manager.data.filesystem.FileSearchEngine
import com.kayx.manager.data.filesystem.FileSearchRequest
import com.kayx.manager.data.settings.SettingsStore
import com.kayx.manager.domain.model.FileItem
import com.kayx.manager.domain.model.SearchMode
import com.kayx.manager.domain.model.SortBy
import com.kayx.manager.ui.glass.GlassCard
import com.kayx.manager.ui.glass.glassBackground
import com.kayx.manager.ui.theme.Accent
import com.kayx.manager.ui.theme.Fg
import com.kayx.manager.ui.theme.TextMuted
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val PREFS = "kayx_search"
private const val HISTORY_KEY = "queries"
private const val RECENT_KEY = "recent_files"
private val RESULT_TABS = listOf(
    FileSearchCategory.ALL, FileSearchCategory.IMAGES, FileSearchCategory.VIDEOS,
    FileSearchCategory.DOCUMENTS, FileSearchCategory.AUDIO
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenDirectory: (String) -> Unit,
    onOpenFile: (FileItem) -> Unit
) {
    val context = LocalContext.current
    val root = remember { android.os.Environment.getExternalStorageDirectory() }
    val engine = remember { FileSearchEngine() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(FileSearchCategory.ALL) }
    var results by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var scanned by remember { mutableIntStateOf(0) }
    var truncated by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(loadList(context, HISTORY_KEY)) }
    var recent by remember { mutableStateOf(loadList(context, RECENT_KEY)) }
    var sort by remember { mutableStateOf(SortBy.NAME) }
    var showSort by remember { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }
    var showHidden by remember { mutableStateOf(false) }
    var includeFolders by remember { mutableStateOf(true) }
    var searchContents by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(SettingsStore.searchMode) }
    var minSizeBytes by remember { mutableLongStateOf(0L) }
    var modifiedAfter by remember { mutableLongStateOf(0L) }
    val activeSearch = query.isNotBlank()

    fun rememberQuery(value: String) {
        val clean = value.trim()
        if (clean.isEmpty()) return
        history = (listOf(clean) + history.filterNot { it.equals(clean, true) }).take(12)
        saveList(context, HISTORY_KEY, history)
    }

    fun rememberFile(item: FileItem) {
        if (item.isDir) return
        recent = (listOf(item.path) + recent.filterNot { it == item.path }).take(12)
        saveList(context, RECENT_KEY, recent)
    }

    LaunchedEffect(query, category, sort, showHidden, includeFolders, searchContents, mode, minSizeBytes, modifiedAfter) {
        if (query.isBlank()) {
            results = emptyList()
            scanned = 0
            truncated = false
            loading = false
        } else {
            loading = true
            searchError = null
            try {
                delay(220)
                val request = FileSearchRequest(
                    root = root,
                    query = query,
                    showHidden = showHidden,
                    mode = mode,
                    includeDirectories = includeFolders && !searchContents,
                    allowedExtensions = category.extensions,
                    minSizeBytes = minSizeBytes,
                    modifiedAfter = modifiedAfter,
                    sortBy = sort,
                    maxDepth = 96,
                    maxResults = 20_000
                )
                val found = withContext(Dispatchers.IO) {
                    if (searchContents) engine.searchContents(request.copy(includeDirectories = false))
                    else engine.search(request)
                }
                results = found.items
                scanned = found.scannedEntries
                truncated = found.truncated
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                results = emptyList()
                searchError = failure.message ?: failure.javaClass.simpleName
            } finally {
                loading = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().glassBackground().statusBarsPadding()
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassCard(
                Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(30.dp)
            ) {
                Row(Modifier.fillMaxSize().padding(horizontal = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Search, null, tint = TextMuted, modifier = Modifier.size(23.dp))
                    Spacer(Modifier.width(10.dp))
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f).focusRequester(focus),
                        singleLine = true,
                        placeholder = { Text("Search files and folders", color = TextMuted) },
                        trailingIcon = {
                            if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Close, "Clear search")
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            rememberQuery(query)
                            keyboard?.hide()
                        }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = Accent
                        )
                    )
                }
            }
            TextButton(onClick = { keyboard?.hide(); onBack() }) { Text("Cancel", color = Accent) }
        }

        if (!activeSearch) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 22.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (history.isNotEmpty()) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Search history", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = TextMuted)
                            IconButton(onClick = { history = emptyList(); saveList(context, HISTORY_KEY, history) }) {
                                Icon(Icons.Default.DeleteOutline, "Clear search history", tint = TextMuted)
                            }
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            history.chunked(4).forEach { line ->
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    line.forEach { term ->
                                        Surface(
                                            onClick = { query = term },
                                            shape = CircleShape,
                                            color = Fg.copy(alpha = .09f)
                                        ) {
                                            Text(term, Modifier.padding(horizontal = 17.dp, vertical = 9.dp), color = Fg)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (recent.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Recent file searches", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = TextMuted)
                            IconButton(onClick = { recent = emptyList(); saveList(context, RECENT_KEY, recent) }) {
                                Icon(Icons.Default.DeleteOutline, "Clear recent files", tint = TextMuted)
                            }
                        }
                    }
                    items(recent, key = { it }) { path ->
                        val file = remember(path) { File(path) }
                        val exists = remember(path) { file.exists() }
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .clickable(enabled = exists) {
                                    val item = FileItem(path, file.name, file.isDirectory, file.length(), file.lastModified(), file.isHidden)
                                    if (file.isDirectory) onOpenDirectory(path) else onOpenFile(item)
                                }.padding(horizontal = 4.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(searchFileIcon(file.name, file.isDirectory), null,
                                tint = if (exists) searchFileTint(file.name, file.isDirectory) else TextMuted.copy(.35f), modifier = Modifier.size(30.dp))
                            Spacer(Modifier.width(13.dp))
                            Column(Modifier.weight(1f)) {
                                Text(if (exists) file.name else "File expired", color = if (exists) Fg else TextMuted.copy(.45f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (exists) Text(file.parent ?: path, color = TextMuted, style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                if (history.isEmpty() && recent.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(top = 90.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.Search, null, tint = TextMuted, modifier = Modifier.size(42.dp))
                                Text("Search your storage", color = TextMuted, style = MaterialTheme.typography.titleMedium)
                                Text("Search history and recent files will appear here.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(top = 20.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(26.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RESULT_TABS.forEach { tab ->
                    val selected = category == tab
                    Column(Modifier.clickable { category = tab }.padding(bottom = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(tab.label, color = if (selected) Fg else TextMuted, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(7.dp))
                        Box(Modifier.width(if (selected) 34.dp else 1.dp).height(3.dp)
                            .clip(CircleShape).background(if (selected) Accent else Color.Transparent))
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    TextButton(onClick = { showSort = true }) {
                        Text(if (sort == SortBy.NAME) "Default" else sort.name.lowercase().replaceFirstChar { it.uppercase() }, color = Fg)
                        Icon(Icons.Default.UnfoldMore, null, tint = Fg)
                    }
                    DropdownMenu(showSort, { showSort = false }) {
                        listOf(SortBy.NAME, SortBy.DATE, SortBy.SIZE, SortBy.TYPE).forEach { option ->
                            DropdownMenuItem(text = { Text(option.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                onClick = { sort = option; showSort = false })
                        }
                    }
                }
                Box {
                    TextButton(onClick = { showFilter = true }) {
                        Text("Filter", color = Fg)
                        Icon(Icons.Default.UnfoldMore, null, tint = Fg)
                    }
                    DropdownMenu(showFilter, { showFilter = false }) {
                        DropdownMenuItem(text = { Text(if (showHidden) "Hide hidden files" else "Show hidden files") },
                            onClick = { showHidden = !showHidden; showFilter = false })
                        DropdownMenuItem(text = { Text(if (includeFolders) "Hide folders" else "Include folders") },
                            onClick = { includeFolders = !includeFolders; showFilter = false })
                        DropdownMenuItem(text = { Text(if (searchContents) "Search file names" else "Search inside text files") },
                            onClick = { searchContents = !searchContents; showFilter = false })
                        DropdownMenuItem(text = { Text("Match: ${mode.label}") },
                            onClick = { mode = when (mode) { SearchMode.CONTAINS -> SearchMode.EXACT; SearchMode.EXACT -> SearchMode.REGEX; SearchMode.REGEX -> SearchMode.CONTAINS }; showFilter = false })
                        DropdownMenuItem(text = { Text(if (minSizeBytes == 0L) "Size: Any" else "Size: 100 MB+") },
                            onClick = { minSizeBytes = if (minSizeBytes == 0L) 100L * 1024 * 1024 else 0L; showFilter = false })
                        DropdownMenuItem(text = { Text(if (modifiedAfter == 0L) "Date: Any time" else "Date: Last 7 days") },
                            onClick = { modifiedAfter = if (modifiedAfter == 0L) System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000 else 0L; showFilter = false })
                        DropdownMenuItem(text = { Text("Clear all filters") },
                            onClick = { showHidden = false; includeFolders = true; searchContents = false; mode = SearchMode.CONTAINS; minSizeBytes = 0L; modifiedAfter = 0L; showFilter = false })
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { includeFolders = !includeFolders }) {
                    Icon(if (includeFolders) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                        if (includeFolders) "Folders included" else "Folders excluded", tint = Accent)
                }
            }
            Text(
                when {
                    loading -> "Searching storage…"
                    searchError != null -> "Search failed: $searchError"
                    else -> "${results.size} files found · ${String.format(Locale.getDefault(), "%,d", scanned)} scanned"
                },
                Modifier.padding(start = 8.dp, bottom = 8.dp),
                color = TextMuted,
                style = MaterialTheme.typography.bodySmall
            )
            if (truncated) Text("Showing first results; narrow your search to find more.", Modifier.padding(start = 8.dp, bottom = 6.dp),
                color = TextMuted, style = MaterialTheme.typography.labelSmall)
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                items(results, key = { it.path }) { item ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (item.isDir) onOpenDirectory(item.path) else {
                                rememberFile(item)
                                onOpenFile(item)
                            }
                        }.padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(searchFileIcon(item.name, item.isDir), null,
                            tint = searchFileTint(item.name, item.isDir), modifier = Modifier.size(39.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(item.name, color = Fg, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyLarge)
                            Text("${item.path.substringBeforeLast('/')}  ·  ${if (item.isDir) "Folder" else formatSearchSize(item.size)}  ·  ${android.text.format.DateFormat.format("MM/dd/yyyy, HH:mm", item.modified)}",
                                color = TextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    HorizontalDivider(color = Fg.copy(.08f))
                }
                if (results.isEmpty() && !loading) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(top = 64.dp), contentAlignment = Alignment.Center) {
                            Text("No files found", color = TextMuted)
                        }
                    }
                }
            }
        }
    }
}

private fun loadList(context: Context, key: String): List<String> =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, "").orEmpty()
        .split('\n').filter { it.isNotBlank() }

private fun saveList(context: Context, key: String, values: List<String>) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, values.joinToString("\n")).apply()
}

private fun formatSearchSize(size: Long): String {
    if (size < 1024) return "$size B"
    var value = size.toDouble()
    val units = arrayOf("KB", "MB", "GB", "TB")
    var index = -1
    while (value >= 1024 && index < units.lastIndex) { value /= 1024; index++ }
    return "%.1f %s".format(Locale.US, value, units[index])
}

@Composable
private fun searchFileTint(name: String, directory: Boolean): Color {
    if (directory) return Color(0xFFFFC247)
    return when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "zip", "7z", "rar", "tar", "gz", "bz2", "xz", "apks", "xapk", "apkm" -> Color(0xFFE0B82F)
        "apk" -> Color(0xFF36C982)
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic" -> Color(0xFF4A90F5)
        "mp3", "m4a", "wav", "ogg", "opus", "flac", "aac" -> Color(0xFF8995AA)
        "mp4", "mkv", "webm", "mov", "avi", "3gp" -> Color(0xFFEF5B75)
        else -> Accent
    }
}

private fun searchFileIcon(name: String, directory: Boolean): androidx.compose.ui.graphics.vector.ImageVector {
    if (directory) return Icons.Default.Folder
    return when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "zip", "7z", "rar", "tar", "gz", "bz2", "xz", "apks", "xapk", "apkm" -> Icons.Default.FolderZip
        "apk" -> Icons.Default.Android
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic" -> Icons.Default.Image
        "mp3", "m4a", "wav", "ogg", "opus", "flac", "aac" -> Icons.Default.AudioFile
        "mp4", "mkv", "webm", "mov", "avi", "3gp" -> Icons.Default.VideoFile
        "pdf" -> Icons.Default.PictureAsPdf
        "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv" -> Icons.Default.Description
        else -> Icons.Default.InsertDriveFile
    }
}
