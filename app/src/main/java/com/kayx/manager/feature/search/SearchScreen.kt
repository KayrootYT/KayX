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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kayx.manager.data.filesystem.FileSearchCategory
import com.kayx.manager.data.filesystem.AndroidIndexedFileSearch
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
    val engine = remember(context) { AndroidIndexedFileSearch(context.applicationContext) }
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
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Compact navigation and editorial header.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                onClick = onBack,
                shape = CircleShape,
                color = Fg.copy(alpha = .07f),
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.ArrowBack, "Back", tint = Fg, modifier = Modifier.size(21.dp))
                }
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("DISCOVER", color = Accent, style = MaterialTheme.typography.labelSmall, letterSpacing = 2.sp)
                Text("Search your space", color = Fg, style = MaterialTheme.typography.headlineSmall)
            }
            Surface(shape = CircleShape, color = Accent.copy(alpha = .12f)) {
                Icon(Icons.Default.AutoAwesome, "KayX search", tint = Accent, modifier = Modifier.padding(11.dp).size(20.dp))
            }
        }

        // High-contrast glass search field with a subtle gradient rim.
        Box(
            Modifier.fillMaxWidth()
                .shadow(16.dp, RoundedCornerShape(25.dp), ambientColor = Accent.copy(.12f), spotColor = Accent.copy(.18f))
                .background(
                    Brush.linearGradient(listOf(Accent.copy(.24f), Color.White.copy(.10f), Color(0xFF8B7CFF).copy(.16f))),
                    RoundedCornerShape(25.dp)
                )
                .padding(1.dp)
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Fg.copy(alpha = .055f), RoundedCornerShape(24.dp))
                    .padding(start = 15.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Search, null, tint = Accent, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(11.dp))
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    placeholder = { Text("Search files, folders…", color = TextMuted.copy(alpha = .85f)) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Close, "Clear search", tint = TextMuted)
                            }
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
                if (!activeSearch) {
                    Surface(
                        shape = CircleShape,
                        color = Accent.copy(alpha = .14f),
                        onClick = { keyboard?.hide(); focus.requestFocus() }
                    ) {
                        Icon(Icons.Default.Tune, "Search options", tint = Accent, modifier = Modifier.padding(10.dp).size(19.dp))
                    }
                }
            }
        }

        if (!activeSearch) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                        Row(
                            Modifier.fillMaxWidth().padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.size(58.dp).background(
                                    Brush.linearGradient(listOf(Accent.copy(.30f), Color(0xFF8B7CFF).copy(.18f))),
                                    RoundedCornerShape(19.dp)
                                ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.FolderOpen, null, tint = Accent, modifier = Modifier.size(30.dp))
                            }
                            Spacer(Modifier.width(15.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Find anything, instantly", color = Fg, style = MaterialTheme.typography.titleMedium)
                                Text("Search by name, extension or file type", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (history.isNotEmpty()) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Recent searches", color = Fg, style = MaterialTheme.typography.titleMedium)
                                Text("Pick up where you left off", color = TextMuted, style = MaterialTheme.typography.labelSmall)
                            }
                            IconButton(onClick = { history = emptyList(); saveList(context, HISTORY_KEY, history) }) {
                                Icon(Icons.Default.DeleteOutline, "Clear search history", tint = TextMuted)
                            }
                        }
                        Spacer(Modifier.height(7.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            history.chunked(2).forEach { line ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    line.forEach { term ->
                                        Surface(
                                            onClick = { query = term },
                                            shape = RoundedCornerShape(15.dp),
                                            color = Fg.copy(alpha = .065f),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.History, null, tint = Accent, modifier = Modifier.size(17.dp))
                                                Spacer(Modifier.width(8.dp))
                                                Text(term, color = Fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                                Icon(Icons.Default.NorthWest, null, tint = TextMuted, modifier = Modifier.size(14.dp))
                                            }
                                        }
                                    }
                                    if (line.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
                if (recent.isNotEmpty()) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Recently opened", color = Fg, style = MaterialTheme.typography.titleMedium)
                                Text("Your latest files and folders", color = TextMuted, style = MaterialTheme.typography.labelSmall)
                            }
                            IconButton(onClick = { recent = emptyList(); saveList(context, RECENT_KEY, recent) }) {
                                Icon(Icons.Default.DeleteOutline, "Clear recent files", tint = TextMuted)
                            }
                        }
                    }
                    items(recent, key = { it }) { path ->
                        val file = remember(path) { File(path) }
                        val exists = remember(path) { file.exists() }
                        GlassCard(
                            Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            onClick = {
                                if (exists) {
                                    val item = FileItem(path, file.name, file.isDirectory, file.length(), file.lastModified(), file.isHidden)
                                    if (file.isDirectory) onOpenDirectory(path) else onOpenFile(item)
                                }
                            }
                        ) {
                            Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(45.dp).background(searchFileTint(file.name, file.isDirectory).copy(.15f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                                    Icon(searchFileIcon(file.name, file.isDirectory), null, tint = if (exists) searchFileTint(file.name, file.isDirectory) else TextMuted, modifier = Modifier.size(25.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(if (exists) file.name else "File unavailable", color = if (exists) Fg else TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(if (exists) file.parent ?: path else path, color = TextMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Icon(Icons.Default.ChevronRight, null, tint = TextMuted)
                            }
                        }
                    }
                }
                if (history.isEmpty() && recent.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(top = 42.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                Box(Modifier.size(78.dp).background(Accent.copy(.12f), CircleShape), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Search, null, tint = Accent, modifier = Modifier.size(38.dp))
                                }
                                Text("Your storage, one search away", color = Fg, style = MaterialTheme.typography.titleMedium)
                                Text("Try a filename or an extension like .mp3", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                RESULT_TABS.forEach { tab ->
                    val selected = category == tab
                    Surface(
                        onClick = { category = tab },
                        shape = CircleShape,
                        color = if (selected) Accent.copy(.20f) else Fg.copy(.055f)
                    ) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(tab.label, color = if (selected) Accent else TextMuted, style = MaterialTheme.typography.labelLarge)
                            if (selected) {
                                Spacer(Modifier.width(6.dp))
                                Box(Modifier.size(6.dp).background(Accent, CircleShape))
                            }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        loading -> "SEARCHING"
                        searchError != null -> "SEARCH ERROR"
                        else -> "RESULTS"
                    },
                    color = Accent,
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.5.sp
                )
                Spacer(Modifier.weight(1f))
                Box {
                    TextButton(onClick = { showSort = true }) {
                        Icon(Icons.Default.Sort, null, tint = TextMuted, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(if (sort == SortBy.NAME) "Name" else sort.name.lowercase().replaceFirstChar { it.uppercase() }, color = Fg)
                    }
                    DropdownMenu(showSort, { showSort = false }) {
                        listOf(SortBy.NAME, SortBy.DATE, SortBy.SIZE, SortBy.TYPE).forEach { option ->
                            DropdownMenuItem(text = { Text(option.name.lowercase().replaceFirstChar { it.uppercase() }) }, onClick = { sort = option; showSort = false })
                        }
                    }
                }
                Box {
                    TextButton(onClick = { showFilter = true }) {
                        Icon(Icons.Default.FilterList, null, tint = if (showHidden || !includeFolders || searchContents || minSizeBytes > 0L || modifiedAfter > 0L) Accent else TextMuted, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Filter", color = Fg)
                    }
                    DropdownMenu(showFilter, { showFilter = false }) {
                        DropdownMenuItem(text = { Text(if (showHidden) "Hide hidden files" else "Show hidden files") }, onClick = { showHidden = !showHidden; showFilter = false })
                        DropdownMenuItem(text = { Text(if (includeFolders) "Hide folders" else "Include folders") }, onClick = { includeFolders = !includeFolders; showFilter = false })
                        DropdownMenuItem(text = { Text(if (searchContents) "Search file names" else "Search inside text files") }, onClick = { searchContents = !searchContents; showFilter = false })
                        DropdownMenuItem(text = { Text("Match: ${mode.label}") }, onClick = { mode = when (mode) { SearchMode.CONTAINS -> SearchMode.EXACT; SearchMode.EXACT -> SearchMode.REGEX; SearchMode.REGEX -> SearchMode.CONTAINS }; showFilter = false })
                        DropdownMenuItem(text = { Text(if (minSizeBytes == 0L) "Size: Any" else "Size: 100 MB+") }, onClick = { minSizeBytes = if (minSizeBytes == 0L) 100L * 1024 * 1024 else 0L; showFilter = false })
                        DropdownMenuItem(text = { Text(if (modifiedAfter == 0L) "Date: Any time" else "Date: Last 7 days") }, onClick = { modifiedAfter = if (modifiedAfter == 0L) System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000 else 0L; showFilter = false })
                        DropdownMenuItem(text = { Text("Clear all filters") }, onClick = { showHidden = false; includeFolders = true; searchContents = false; mode = SearchMode.CONTAINS; minSizeBytes = 0L; modifiedAfter = 0L; showFilter = false })
                    }
                }
            }
            GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.padding(horizontal = 15.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            when {
                                loading -> "Looking through your storage"
                                searchError != null -> "Something went wrong"
                                else -> "${String.format(Locale.getDefault(), "%,d", results.size)} matches found"
                            },
                            color = Fg,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text("${String.format(Locale.getDefault(), "%,d", scanned)} items scanned", color = TextMuted, style = MaterialTheme.typography.labelSmall)
                    }
                    if (loading) CircularProgressIndicator(Modifier.size(23.dp), color = Accent, strokeWidth = 2.dp)
                    else Icon(Icons.Default.Bolt, null, tint = Accent, modifier = Modifier.size(22.dp))
                }
            }
            if (truncated) Text("Showing the first results. Add a filter to narrow your search.", color = TextMuted, style = MaterialTheme.typography.labelSmall)
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                items(results, key = { it.path }) { item ->
                    GlassCard(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(19.dp),
                        onClick = {
                            if (item.isDir) onOpenDirectory(item.path) else {
                                rememberFile(item)
                                onOpenFile(item)
                            }
                        }
                    ) {
                        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(49.dp).background(searchFileTint(item.name, item.isDir).copy(.16f), RoundedCornerShape(15.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(searchFileIcon(item.name, item.isDir), null, tint = searchFileTint(item.name, item.isDir), modifier = Modifier.size(27.dp))
                            }
                            Spacer(Modifier.width(13.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(item.name, color = Fg, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                Text(item.path.substringBeforeLast('/').ifEmpty { "/" }, color = TextMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(shape = CircleShape, color = Fg.copy(.07f)) {
                                        Text(if (item.isDir) "FOLDER" else formatSearchSize(item.size), Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = TextMuted, style = MaterialTheme.typography.labelSmall)
                                    }
                                    Spacer(Modifier.width(7.dp))
                                    Text(android.text.format.DateFormat.format("MMM dd, yyyy", item.modified).toString(), color = TextMuted.copy(.85f), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Icon(Icons.Default.ChevronRight, null, tint = TextMuted)
                        }
                    }
                }
                if (results.isEmpty() && !loading) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(top = 42.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(if (searchError != null) Icons.Default.ErrorOutline else Icons.Default.SearchOff, null, tint = TextMuted, modifier = Modifier.size(40.dp))
                                Text(if (searchError != null) "Search couldn't finish" else "No matching files", color = Fg, style = MaterialTheme.typography.titleMedium)
                                Text(if (searchError != null) searchError.orEmpty() else "Try another name, extension or category.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                            }
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
