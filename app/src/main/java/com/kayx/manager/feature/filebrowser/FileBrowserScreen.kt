package com.kayx.manager.feature.filebrowser
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import com.kayx.manager.ui.theme.Fg

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.kayx.manager.data.archive.MultiExtract
import com.kayx.manager.data.filesystem.ExternalOps
import com.kayx.manager.data.filesystem.SafRepository
import com.kayx.manager.data.filesystem.StorageLocation
import android.content.Intent
import android.net.Uri
import com.kayx.manager.data.filesystem.FileOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import com.kayx.manager.data.storage.OfflineStorageInventory
import kotlinx.coroutines.withContext
import com.kayx.manager.data.filesystem.FileSearchCategory
import java.io.File
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kayx.manager.data.settings.SettingsStore
import com.kayx.manager.domain.model.FileItem
import com.kayx.manager.domain.model.SortBy
import com.kayx.manager.domain.model.SearchMode
import com.kayx.manager.domain.model.Tool
import com.kayx.manager.ui.glass.GlassCard
import com.kayx.manager.ui.glass.glassBackground
import com.kayx.manager.ui.theme.Accent
import java.text.DateFormat
import java.util.Date

private val ARCHIVE_EXTENSIONS = setOf("zip", "7z", "rar", "tar", "gz", "bz2", "xz", "tgz", "tbz", "tbz2", "txz", "zst", "lz4", "lz", "lzma", "cab", "iso", "img", "dmg", "apks", "apkm", "xapk", "aar", "jar", "war")
private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "svg", "heic", "heif", "avif", "tif", "tiff", "ico", "raw", "dng", "psd")
private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "wav", "ogg", "oga", "opus", "flac", "aac", "wma", "aiff", "mid", "midi")
private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v", "mpeg", "mpg", "ts", "mts", "m2ts", "wmv", "flv")
private val CODE_EXTENSIONS = setOf("kt", "kts", "java", "class", "dex", "smali", "js", "jsx", "ts", "tsx", "py", "rb", "go", "rs", "c", "h", "cpp", "hpp", "cs", "php", "swift", "sh", "bat", "html", "css", "scss", "xml", "json", "toml", "gradle", "sql", "db", "sqlite")
private val TEXT_EXTENSIONS = setOf("txt", "md", "markdown", "log", "ini", "cfg", "conf", "properties", "yaml", "yml", "csv", "tsv", "rtf", "srt", "ass", "vtt")
private val FONT_EXTENSIONS = setOf("ttf", "otf", "woff", "woff2")
private val OFFICE_EXTENSIONS = setOf("pdf", "doc", "docx", "docm", "dot", "dotx", "xls", "xlsx", "xlsm", "xltx", "ods", "odt", "ppt", "pptx", "pptm", "ppsx", "odp", "epub", "mobi")

internal sealed interface Dlg {
    data class NewFolder(val initialName: String = "") : Dlg
    data class NewFile(val initialName: String = "") : Dlg
    data class Rename(val path: String, val name: String) : Dlg
    data object Delete : Dlg
    data object Zip : Dlg
    data object Batch : Dlg
    data object Pattern : Dlg
    data class Symlink(val target: String) : Dlg
    data object Jump : Dlg
    data object Tag : Dlg
    data class ConfirmTransfer(val cut: Boolean) : Dlg
    data object ConfirmPaste : Dlg
    data object Tools : Dlg
    data object MultiProps : Dlg
    data class OrganizationPreview(val plan: OfflineOrganizationPlan) : Dlg
    data class DuplicateReview(val groups: List<OfflineStorageInventory.DuplicateGroup>) : Dlg
}

private fun fmtSize(b: Long): String {
    if (b < 1024) return "$b B"
    val u = arrayOf("KB", "MB", "GB", "TB"); var v = b / 1024.0; var i = 0
    while (v >= 1024 && i < u.lastIndex) { v /= 1024; i++ }
    return "%.1f %s".format(v, u[i])
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(startPath: String, onExit: () -> Unit, onOpenFile: (String, Tool) -> Unit, onSettings: () -> Unit = {}, vm: FileBrowserViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var dlg by remember { mutableStateOf<Dlg?>(null) }
    var menu by remember { mutableStateOf(false) }
    var sortPage by remember { mutableStateOf(false) }
    var searchModeMenu by remember { mutableStateOf(false) }
    var assistantMode by remember { mutableStateOf(false) }
    var storageScanJob by remember { mutableStateOf<Job?>(null) }
    var openFor by remember { mutableStateOf<FileItem?>(null) }
    var webFor by remember { mutableStateOf<FileItem?>(null) }
    var itemMenu by remember { mutableStateOf<Pair<FileItem, Int>?>(null) }
    var infoFor by remember { mutableStateOf<FileItem?>(null) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var moveSelectionToSaf by remember { mutableStateOf(false) }
    var safTransferProgress by remember { mutableStateOf<String?>(null) }
    var safTransferControl by remember { mutableStateOf<FileOps.TransferControl?>(null) }
    var safTransferPaused by remember { mutableStateOf(false) }
    val safLocationPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { ctx.contentResolver.takePersistableUriPermission(tree, flags) }
            if (!SafRepository.persistTreePermission(ctx, tree) && !SafRepository.hasWriteAccess(ctx, tree)) {
                scope.launch { snack.showSnackbar("Storage permission was not granted. Please select the folder again.") }
            } else vm.openLocation(StorageLocation.Saf(tree.toString(), ""))
        }
    }
    val safDestinationPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { destinationTree ->
        if (destinationTree != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { ctx.contentResolver.takePersistableUriPermission(destinationTree, flags) }
            val persisted = SafRepository.persistTreePermission(ctx, destinationTree)
            if (!persisted || !SafRepository.hasWriteAccess(ctx, destinationTree)) {
                scope.launch { snack.showSnackbar("This location is read-only or its write permission could not be saved. Select a writable folder.") }
            } else {
                vm.transferSelectedToSaf(destinationTree, moveSelectionToSaf)
                moveSelectionToSaf = false
            }
        } else moveSelectionToSaf = false
    }
    DisposableEffect(safTransferControl) {
        val active = safTransferControl
        onDispose { active?.takeIf { it.state == FileOps.TransferState.RUNNING || it.state == FileOps.TransferState.PAUSED }?.cancel() }
    }
    val keyboardController = LocalSoftwareKeyboardController.current
    val searchFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val openItem: (FileItem) -> Unit = { f ->
        val ext = f.name.substringAfterLast('.', "").lowercase()
        val location = StorageLocation.fromStableKey(f.path)
        if (location is StorageLocation.Saf) {
            val opened = runCatching {
                val doc = SafRepository.findByRelativePath(ctx, Uri.parse(location.treeUri), location.relativePath)
                    ?: error("Storage item is no longer available")
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(doc.uri, doc.type ?: "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                ctx.startActivity(intent)
            }
            if (opened.isFailure) scope.launch { snack.showSnackbar(opened.exceptionOrNull()?.message ?: "Unable to open this item") }
        } else if (!openByDefault(ctx, f, onOpenFile)) {
            if (ext == "apk") ExternalOps.installApk(ctx, File(f.path))
            else if (ExternalOps.isOfficeOrPdf(File(f.path))) { if (!ExternalOps.openWith(ctx, File(f.path))) openFor = f }
            else if (ext in WEB_TAP_EXTENSIONS) {
                when (SettingsStore.htmlTapAction) {
                    1 -> onOpenFile(f.path, Tool.TEXT)
                    2 -> onOpenFile(f.path + "\u001f" + (File(f.path).parent ?: "/"), Tool.HTML)
                    else -> { webFor = f }
                }
            }
            else openFor = f
        }
    }
    LaunchedEffect(ctx) { vm.attachSearchContext(ctx.applicationContext) }
    LaunchedEffect(startPath) { vm.start(startPath) }
    LaunchedEffect(s.searching) {
        if (s.searching) runCatching { searchFocusRequester.requestFocus() }
    }
    LaunchedEffect(Unit) { vm.syncDefaults() }
    LaunchedEffect(s.message) { s.message?.let { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_SHORT).show(); snack.showSnackbar(it); vm.consumeMessage() } }
    BackHandler {
        if (assistantMode) assistantMode = false
        else if (!vm.up()) onExit()
    }

    val runInventoryQuery: (OfflineAssistantCommand) -> Unit = { query ->
        if (storageScanJob?.isActive == true) {
            vm.notify("Storage scan is already running. Say cancel scan first.")
        } else {
            storageScanJob = scope.launch {
                vm.notify("Scanning accessible internal storage and granted SD-card folders…")
                val result = try {
                    withContext(Dispatchers.IO) {
                        OfflineStorageInventory.scan(ctx, includeDuplicates = query == OfflineAssistantCommand.FindDuplicates) { count ->
                            if (count > 0 && count % 1024 == 0) vm.notify("Storage scan progress: $count files")
                        }
                    }
                } catch (_: CancellationException) {
                    vm.notify("Storage scan cancelled")
                    null
                } catch (error: Exception) {
                    vm.notify("Storage scan failed: ${error.message ?: "storage unavailable"}")
                    null
                }
                if (result != null) {
                    val matches = when (query) {
                        OfflineAssistantCommand.FindLargeFiles -> result.largeFiles
                        OfflineAssistantCommand.FindOldFiles -> result.oldFiles
                        OfflineAssistantCommand.FindDuplicates -> result.duplicateCandidates
                        is OfflineAssistantCommand.FindCategory -> result.entries.filter { it.category == query.category }.sortedByDescending { it.size }
                        is OfflineAssistantCommand.PreviewOrganize -> result.entries.filter { it.category == query.category }.sortedByDescending { it.size }
                        else -> emptyList()
                    }
                    val heading = when (query) {
                        OfflineAssistantCommand.FindLargeFiles -> "Large files (100 MB+)"
                        OfflineAssistantCommand.FindOldFiles -> "Old files (180+ days)"
                        OfflineAssistantCommand.FindDuplicates -> "Duplicate candidates (SHA-256 match)"
                        is OfflineAssistantCommand.FindCategory -> "${query.category.label} files"
                        is OfflineAssistantCommand.FolderSize -> "Folder size: ${query.folderName}"
                        is OfflineAssistantCommand.PreviewOrganize -> "Organization preview: ${query.category.label}"
                        else -> "Storage inventory"
                    }
                    val folderMatches = if (query is OfflineAssistantCommand.FolderSize) {
                        result.entries.filter { entry ->
                            entry.path.replace("\\", "/").split('/').dropLast(1).any { it.equals(query.folderName, ignoreCase = true) }
                        }
                    } else emptyList()
                    val details = when (query) {
                        OfflineAssistantCommand.ScanStorage ->
                            "Files: ${result.entries.size} · Data: ${fmtSize(result.totalBytes)} · Large: ${result.largeFiles.size} · Old: ${result.oldFiles.size}"
                        is OfflineAssistantCommand.FolderSize ->
                            if (folderMatches.isEmpty()) "No accessible files found inside a folder named '${query.folderName}'."
                            else "Matching files: ${folderMatches.size} · Combined size: ${fmtSize(folderMatches.sumOf { it.size })}. This combines folders with the same name."
                        is OfflineAssistantCommand.PreviewOrganize -> "Preparing a confirmation-only preview."
                        else -> if (matches.isEmpty()) {
                            "No matching files found in the accessible scan."
                        } else {
                            matches.take(8).joinToString("\n") { "• ${it.name} — ${fmtSize(it.size)}" } +
                                if (matches.size > 8) "\n…and ${matches.size - 8} more" else ""
                        }
                    }
                    val coverage = "Roots: ${result.scannedRoots} · Inaccessible: ${result.inaccessibleRoots}" +
                        if (result.truncated) " · Scan capped at ${OfflineStorageInventory.DEFAULT_MAX_ENTRIES} files" else ""
                    if (query is OfflineAssistantCommand.PreviewOrganize) {
                        val activeFolder = if (s.dualPane && s.activePane == 1) s.secondaryDir else s.dir
                        val destination = if (query.destinationPath.equals("here", ignoreCase = true)) {
                            StorageLocation.fromStableKey(activeFolder)?.stableKey()
                                ?: StorageLocation.Local(File(activeFolder).canonicalFile).stableKey()
                        } else query.destinationPath
                        OfflineOrganizationPlanner.create(result.entries, query.category, destination)
                            .onSuccess { dlg = Dlg.OrganizationPreview(it) }
                            .onFailure { vm.notify(it.message ?: "Could not prepare organization preview") }
                    } else if (query == OfflineAssistantCommand.FindDuplicates) {
                        dlg = Dlg.DuplicateReview(result.duplicateGroups)
                    } else vm.notify("$heading\n$details\n$coverage")
                }
                storageScanJob = null
            }
        }
    }

    val executeAssistantCommand: (OfflineAssistantCommand) -> Unit = { command ->
                                when (command) {
                                    OfflineAssistantCommand.Help -> vm.notify(OfflineAssistantParser.HELP_TEXT)
                                    is OfflineAssistantCommand.Search -> vm.setQuery(command.query)
                                    is OfflineAssistantCommand.SearchContent -> { if (!s.grep) vm.toggleGrep(); vm.setQuery(command.query) }
                                    is OfflineAssistantCommand.OpenFolder -> {
                                        val folder = (if (s.dualPane && s.activePane == 1) s.secondaryItems else s.items)
                                            .firstOrNull { it.isDir && it.name.equals(command.name, ignoreCase = true) }
                                        if (folder != null) vm.openActive(folder.path) else vm.notify("Folder not found in this location")
                                    }
                                    is OfflineAssistantCommand.OpenItem -> {
                                        val item = (if (s.dualPane && s.activePane == 1) s.secondaryItems else s.items)
                                            .firstOrNull { it.name.equals(command.name, ignoreCase = true) }
                                        if (item == null) vm.notify("Item not found in this location")
                                        else if (item.isDir) vm.openActive(item.path) else openItem(item)
                                    }
                                    OfflineAssistantCommand.GoBack -> vm.goBack()
                                    OfflineAssistantCommand.GoUp -> vm.goUp()
                                    OfflineAssistantCommand.Refresh -> vm.reload()
                                    is OfflineAssistantCommand.ShowHidden -> if (s.showHidden != command.visible) vm.toggleHidden()
                                    is OfflineAssistantCommand.Sort -> vm.setSort(command.by)
                                    OfflineAssistantCommand.SelectAll -> vm.selectAll()
                                    OfflineAssistantCommand.ClearSelection -> vm.clearSelection()
                                    is OfflineAssistantCommand.SelectName -> {
                                        val item = (if (s.dualPane && s.activePane == 1) s.secondaryItems else s.items)
                                            .firstOrNull { it.name.equals(command.name, ignoreCase = true) }
                                        if (item != null) vm.selectPaths(setOf(item.path)) else vm.notify("Item not found in this location")
                                    }
                                    is OfflineAssistantCommand.Copy -> {
                                        if (s.selected.isEmpty()) vm.notify("Select files first")
                                        else vm.copySelected(command.cut)
                                    }
                                    OfflineAssistantCommand.Paste -> if (s.clipboard.isEmpty()) vm.notify("Clipboard is empty") else dlg = Dlg.ConfirmPaste
                                    is OfflineAssistantCommand.NewFolder -> { dlg = Dlg.NewFolder(command.name) }
                                    is OfflineAssistantCommand.NewFile -> { dlg = Dlg.NewFile(command.name) }
                                    is OfflineAssistantCommand.RenameSelected -> {
                                        if (s.selected.size != 1) vm.notify("Select exactly one item to rename")
                                        else dlg = Dlg.Rename(s.selected.first(), command.newName)
                                    }
                                    OfflineAssistantCommand.DeleteSelected -> {
                                        if (s.selected.isEmpty()) vm.notify("Select files first") else dlg = Dlg.Delete
                                    }
                                    OfflineAssistantCommand.Undo -> vm.undoLastOperation()
                                    OfflineAssistantCommand.ZipSelected -> if (s.selected.isEmpty()) vm.notify("Select files first") else dlg = Dlg.Zip
                                    OfflineAssistantCommand.ShareSelected -> {
                                        if (s.selected.isEmpty()) vm.notify("Select files first")
                                        else if (s.selected.any { StorageLocation.fromStableKey(it) is StorageLocation.Saf }) vm.notify("Sharing SAF items is not available from this menu yet")
                                        else ExternalOps.share(ctx, s.selected.map(::File))
                                    }
                                    OfflineAssistantCommand.InvertSelection -> vm.invertSelection()
                                    is OfflineAssistantCommand.SelectPattern -> vm.selectPattern(command.pattern)
                                    is OfflineAssistantCommand.Extract -> {
                                        val item = (if (s.dualPane && s.activePane == 1) s.secondaryItems else s.items)
                                            .firstOrNull { it.name.equals(command.name, ignoreCase = true) }
                                        if (item == null) vm.notify("Archive not found in this location") else vm.extractHere(item.path)
                                    }
                                    OfflineAssistantCommand.BookmarkCurrent -> {
                                        val activePath = if (s.dualPane && s.activePane == 1) s.secondaryDir else s.dir
                                        vm.notify(if (SettingsStore.addBookmark(activePath)) "Bookmark added" else "Already bookmarked")
                                    }
                                    OfflineAssistantCommand.ToggleDualPane -> vm.toggleDualPane()
                                    OfflineAssistantCommand.OpenRecycleBin -> onOpenFile("", Tool.RECYCLE)
                                    OfflineAssistantCommand.OpenTags -> onOpenFile("", Tool.TAGS)
                                    OfflineAssistantCommand.OpenPrivateSpace -> onOpenFile("", Tool.PRIVATE)
                                    OfflineAssistantCommand.TagSelected -> {
                                        if (s.selected.isEmpty()) vm.notify("Select files first")
                                        else dlg = Dlg.Tag
                                    }
                                    OfflineAssistantCommand.ScanStorage,
                                    OfflineAssistantCommand.FindLargeFiles,
                                    OfflineAssistantCommand.FindOldFiles,
                                    OfflineAssistantCommand.FindDuplicates,
                                    is OfflineAssistantCommand.FindCategory,
                                    is OfflineAssistantCommand.FolderSize,
                                    is OfflineAssistantCommand.PreviewOrganize -> runInventoryQuery(command)
                                    OfflineAssistantCommand.CancelStorageScan -> {
                                        storageScanJob?.cancel()
                                        storageScanJob = null
                                        vm.notify("Storage scan cancellation requested")
                                    }
                                    OfflineAssistantCommand.StorageSummary -> {
                                        val activePath = if (s.dualPane && s.activePane == 1) s.secondaryDir else s.dir
                                        val usage = runCatching { android.os.StatFs(activePath).let {
                                            "Storage used: ${fmtSize(it.totalBytes - it.availableBytes)} / ${fmtSize(it.totalBytes)} · Free: ${fmtSize(it.availableBytes)}"
                                        } }.getOrElse { "Storage information is unavailable for this location" }
                                        vm.notify(usage)
                                    }
                                }
                            }

    if (s.dualPane) DualPaneBrowser(
        s, vm, snack, onExit, onSettings, onBrowseSaf = { safLocationPicker.launch(null) }, onOpenFile = onOpenFile,
        onDialog = { dlg = it }, onOpenItem = openItem, onOpenWith = { openFor = it }, onProperties = { infoFor = it },
        onItemMenu = { f, pane -> itemMenu = f to pane },
        assistantMode = assistantMode,
        onToggleAssistant = { if (!s.searching) vm.toggleSearch(); assistantMode = !assistantMode },
        onAssistantCommand = executeAssistantCommand
    ) else Scaffold(
        modifier = Modifier.glassBackground(), containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            Column(Modifier.statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            val searchGlow by animateColorAsState(
                targetValue = if (s.searching) Accent.copy(alpha = if (s.query.isNotEmpty()) .34f else .20f) else Fg.copy(.08f),
                label = "search-glass-glow"
            )
            GlassCard(
                Modifier.fillMaxWidth().border(
                    androidx.compose.foundation.BorderStroke(1.dp, searchGlow),
                    RoundedCornerShape(26.dp)
                ),
                shape = RoundedCornerShape(26.dp),
                tint = if (s.searching) Accent else Fg
            ) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ if (!vm.up()) onExit() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    if (s.searching && assistantMode) OfflineAssistantBar(
                        onCommand = executeAssistantCommand
                    ) else if (s.searching) TextField(
                        value = s.query,
                        onValueChange = vm::setQuery,
                        modifier = Modifier.weight(1f).focusRequester(searchFocusRequester),
                        singleLine = true,
                        placeholder = {
                            Text("Search", color = Fg.copy(.46f), style = MaterialTheme.typography.bodyLarge)
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = "Search files",
                                tint = Accent, modifier = Modifier.size(24.dp))
                        },
                        trailingIcon = {
                            if (s.query.isNotEmpty()) IconButton(onClick = { vm.setQuery("") }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear query")
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            vm.reload()
                            keyboardController?.hide()
                        }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = Accent
                        ),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Fg)
                    )
                    else Column(Modifier.weight(1f)) {
                        val activePath = if (s.dualPane && s.activePane == 1) s.secondaryDir else s.dir
                        val shownItems = if (s.dualPane && s.activePane == 1) s.secondaryItems else s.items
                        val folders = remember(shownItems) { shownItems.count { it.isDir } }
                        val disk = remember(activePath) {
                            if (StorageLocation.fromStableKey(activePath) is StorageLocation.Saf) null
                            else runCatching { android.os.StatFs(activePath).let { it.totalBytes to (it.totalBytes - it.availableBytes) } }
                                .recoverCatching { android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path).let { it.totalBytes to (it.totalBytes - it.availableBytes) } }
                                .getOrNull()
                        }
                        if (s.selected.isNotEmpty()) {
                            Text("${s.selected.size} selected", maxLines = 1, style = MaterialTheme.typography.titleMedium)
                        } else {
                            // Local paths use tappable breadcrumbs; SAF locations display their tree-relative path.
                            val safLocation = StorageLocation.fromStableKey(activePath) as? StorageLocation.Saf
                            if (safLocation != null) {
                                Text(
                                    "SD Card · " + safLocation.relativePath.ifBlank { "Storage root" },
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleSmall, color = Accent
                                )
                            } else Row(
                                Modifier.horizontalScroll(rememberScrollState()).animateContentSize(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("⌂", Modifier.clip(RoundedCornerShape(8.dp)).clickable { vm.openActive("/") }.padding(horizontal = 6.dp, vertical = 5.dp),
                                    color = Accent, style = MaterialTheme.typography.titleMedium)
                                val parts = activePath.split('/').filter { it.isNotBlank() }
                                parts.forEachIndexed { index, part ->
                                    val target = "/" + parts.take(index + 1).joinToString("/")
                                    Text("›", color = Fg.copy(.38f), style = MaterialTheme.typography.titleSmall)
                                    Text(part, Modifier.clip(RoundedCornerShape(7.dp)).clickable { vm.openActive(target) }
                                        .padding(horizontal = 5.dp, vertical = 5.dp), maxLines = 1, softWrap = false,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = if (index == parts.lastIndex) Fg else Accent)
                                }
                            }
                        }
                        Text("Folders: $folders  Files: ${shownItems.size - folders}" + (disk?.let { "  Disk: ${fmtSize(it.second)}/${fmtSize(it.first)}" } ?: ""),
                            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = Fg.copy(.5f))
                    }
                    if (s.selected.isEmpty()) {
                        // The TextField owns the single clear (X) action while search is active.
                        if (!s.searching) IconButton(vm::toggleSearch) {
                            Icon(Icons.Default.Search, "Search")
                        }
                        IconButton(onClick = {
                            if (!s.searching) vm.toggleSearch()
                            assistantMode = !assistantMode
                        }) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = if (assistantMode) "Close offline assistant" else "Open offline assistant")
                        }
                        Box {
                            IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "More options") }
                            DropdownMenu(menu, { menu = false; sortPage = false }) {
                                if (sortPage) {
                                    DropdownMenuItem({ Text("‹  Back") }, { sortPage = false })
                                    HorizontalDivider()
                                    SortBy.values().forEach { b -> DropdownMenuItem({ Text(b.name.lowercase().replaceFirstChar { it.uppercase() } + if (s.sort == b) "  ✓" else "") }, { vm.setSort(b); menu = false; sortPage = false }) }
                                } else {
                                    if (s.undoAvailable) {
                                        DropdownMenuItem({ Text("Undo last file operation") }, { vm.undoLastOperation(); menu = false })
                                        HorizontalDivider()
                                    }
                                    // View
                                    DropdownMenuItem({ Text(if (s.showHidden) "Hide hidden files" else "Show hidden files") }, { vm.toggleHidden(); menu = false })
                                    DropdownMenuItem({ Text("Sort by: ${s.sort.name.lowercase()}  ›") }, { sortPage = true })
                                    DropdownMenuItem({ Text(if (s.dualPane) "Single pane" else "Dual pane") }, { vm.toggleDualPane(); menu = false })
                                    if (s.dualPane) DropdownMenuItem({ Text("Swap panes") }, { vm.swapPanes(); menu = false })
                                    HorizontalDivider()
                                    // Navigate
                                    DropdownMenuItem({ Text("Browse SD card / SAF storage…") }, { safLocationPicker.launch(null); menu = false })
                                    DropdownMenuItem({ Text("Jump to path…") }, { dlg = Dlg.Jump; menu = false })
                                    DropdownMenuItem({ Text("Add bookmark") }, {
                                        val here = if (s.dualPane && s.activePane == 1) s.secondaryDir else s.dir
                                        vm.notify(if (SettingsStore.addBookmark(here)) "Bookmark added" else "Already bookmarked"); menu = false
                                    })
                                    DropdownMenuItem({ Text("Set as Home") }, {
                                        SettingsStore.updateHomePath(if (s.dualPane && s.activePane == 1) s.secondaryDir else s.dir); vm.notify("Home folder set"); menu = false
                                    })
                                    DropdownMenuItem({ Text("Refresh") }, { vm.reload(); menu = false })
                                    HorizontalDivider()
                                    // Select & search
                                    DropdownMenuItem({ Text("Select all") }, { vm.selectAll(); menu = false })
                                    DropdownMenuItem({ Text(if (s.grep) "Search by file name" else "Search inside files") }, { vm.toggleGrep(); menu = false })
                                    HorizontalDivider()
                                    // Tools for this folder
                                    DropdownMenuItem({ Text("Open terminal here") }, { menu = false; onOpenFile(if (s.dualPane && s.activePane == 1) s.secondaryDir else s.dir, Tool.TERMINAL) })
                                    DropdownMenuItem({ Text("Recycle bin") }, { menu = false; onOpenFile("", Tool.RECYCLE) })
                                    HorizontalDivider()
                                    DropdownMenuItem({ Text("Settings") }, { onSettings(); menu = false })
                                    DropdownMenuItem({ Text("Exit") }, { menu = false; onExit() })
                                }
                            }
                        }
                    } else {
                        IconButton({ moveSelectionToSaf = false; safDestinationPicker.launch(null) }) {
                            Icon(Icons.Default.SdCard, "Copy selection to SD card or SAF folder")
                        }
                        IconButton({ moveSelectionToSaf = true; safDestinationPicker.launch(null) }) {
                            Icon(Icons.Default.DriveFileMove, "Move selection to SD card or SAF folder")
                        }
                        IconButton(vm::selectAll) { Icon(Icons.Default.SelectAll, "Select all") }
                        IconButton(vm::clearSelection) { Icon(Icons.Default.Close, "Clear selection") }
                        IconButton(onClick = { if (!s.searching) vm.toggleSearch(); assistantMode = !assistantMode }) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = "Open offline assistant")
                        }
                    }
                }
            }
            if (s.searching) Row(
                Modifier.fillMaxWidth().padding(top = 5.dp, start = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Box {
                    TextButton(onClick = { searchModeMenu = true }) {
                        Icon(Icons.Default.Tune, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(if (s.grep) "Text content" else SettingsStore.searchMode.label)
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                    DropdownMenu(searchModeMenu, { searchModeMenu = false }) {
                        DropdownMenuItem(text = { Text("File name contains") }, onClick = {
                            SettingsStore.updateSearchMode(SearchMode.CONTAINS); searchModeMenu = false; vm.reload()
                        })
                        DropdownMenuItem(text = { Text("Exact file name") }, onClick = {
                            SettingsStore.updateSearchMode(SearchMode.EXACT); searchModeMenu = false; vm.reload()
                        })
                        DropdownMenuItem(text = { Text("Regular expression") }, onClick = {
                            SettingsStore.updateSearchMode(SearchMode.REGEX); searchModeMenu = false; vm.reload()
                        })
                    }
                }
                TextButton(onClick = { vm.toggleGrep() }) {
                    Icon(if (s.grep) Icons.Default.TextFields else Icons.Default.FolderOpen, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(if (s.grep) "Search names" else "Search file contents")
                }
            }
            if (s.searching && !s.grep) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 2.dp, start = 3.dp, end = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FileSearchCategory.values().forEach { category ->
                        val selected = s.searchCategory == category
                        Surface(
                            onClick = { vm.setSearchCategory(category) },
                            shape = RoundedCornerShape(50),
                            color = if (selected) Accent.copy(.24f) else Fg.copy(.045f),
                            border = androidx.compose.foundation.BorderStroke(
                                if (selected) 1.4.dp else 1.dp,
                                if (selected) Accent.copy(.72f) else Fg.copy(.12f)
                            )
                        ) {
                            Text(
                                category.label,
                                Modifier.padding(horizontal = 15.dp, vertical = 9.dp),
                                color = if (selected) Accent else Fg.copy(.76f),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
                Text(
                    if (s.loading) "Searching…" else "${s.items.size} files found",
                    Modifier.padding(start = 8.dp, top = 3.dp, bottom = 2.dp),
                    color = Fg.copy(.58f),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            if (!s.dualPane) Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                s.tabs.forEachIndexed { index, tab ->
                    val selected = s.activeTab == index
                    Surface(
                        onClick = { vm.setActiveTab(index) },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(9.dp),
                        color = if (selected) Accent.copy(.18f) else Fg.copy(.05f),
                        modifier = Modifier.padding(end = 5.dp)
                    ) {
                        Row(Modifier.padding(start = 9.dp, end = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                tab.path.substringAfterLast('/').ifEmpty { "/" },
                                Modifier.padding(vertical = 7.dp),
                                maxLines = 1,
                                style = MaterialTheme.typography.labelSmall
                            )
                            if (tab.selected.isNotEmpty()) Text(" (${tab.selected.size})", style = MaterialTheme.typography.labelSmall)
                            IconButton(
                                onClick = { vm.closeTab(index) },
                                modifier = Modifier.size(30.dp),
                                enabled = s.tabs.size > 1
                            ) { Icon(Icons.Default.Close, "Close tab", modifier = Modifier.size(14.dp)) }
                        }
                    }
                }
                IconButton({ vm.addTab(s.dir) }) { Icon(Icons.Default.Add, "New tab") }
            }
            if (s.selected.size == 1 && StorageLocation.fromStableKey(s.selected.first()) !is StorageLocation.Saf && ExternalOps.isOfficeOrPdf(File(s.selected.first()))) Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val f1 = File(s.selected.first())
                @Composable fun Q(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) =
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Color(0x22FFFFFF)).clickable(onClick = onClick).padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) { Icon(icon, label); Text(label, style = MaterialTheme.typography.labelMedium) }
                Q(Icons.Default.NoteAdd, "New Doc") { dlg = Dlg.NewFile() }
                Q(Icons.Default.SwapHoriz, "Convert") {
                    if (f1.extension.lowercase() in setOf("pdf", "png", "jpg", "jpeg", "webp")) vm.convert(ctx, f1.absolutePath)
                    else vm.notify(ExternalOps.convert(ctx, f1))
                }
                Q(Icons.Default.Print, "Print") { ExternalOps.print(ctx, f1) }
            }
            s.transferProgress?.let { progress ->
                GlassCard(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        val transferDetails = if (progress.totalBytes > 0L) {
                            "${fmtSize(progress.completedBytes)} / ${fmtSize(progress.totalBytes)} · ${fmtSize(progress.bytesPerSecond)}/s" +
                                (progress.etaSeconds?.let { " · ETA ${it / 60}m ${it % 60}s" } ?: "")
                        } else "${progress.completed}/${progress.total} items"
                        Text("${if (progress.moving) "Moving" else "Copying"}: ${progress.currentName} · $transferDetails",
                            style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            val paused = s.transferControl?.state == com.kayx.manager.data.filesystem.FileOps.TransferState.PAUSED
                            TextButton({ if (paused) vm.resumeTransfer() else vm.pauseTransfer() }) { Text(if (paused) "Resume" else "Pause") }
                            TextButton(vm::cancelTransfer) { Text("Cancel") }
                        }
                    }
                }
            }
            }
        },
        bottomBar = {
            if (safTransferProgress != null) GlassCard(Modifier.navigationBarsPadding().padding(12.dp).fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(safTransferProgress.orEmpty(), Modifier.weight(1f), maxLines = 1)
                    TextButton(onClick = { if (safTransferPaused) safTransferControl?.resume() else safTransferControl?.pause(); safTransferPaused = !safTransferPaused }) { Text(if (safTransferPaused) "Resume" else "Pause") }
                    TextButton(onClick = { safTransferControl?.cancel() }) { Text("Cancel") }
                }
            } else if (s.transferProgress == null && s.retryTransfer != null) GlassCard(Modifier.navigationBarsPadding().padding(12.dp).fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Transfer failed", Modifier.weight(1f))
                    TextButton(vm::retryTransfer) { Text("Retry") }
                }
            } else if (s.selected.isNotEmpty()) GlassCard(Modifier.navigationBarsPadding().padding(12.dp).fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    @Composable fun Act(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color = Color.Unspecified, enabled: Boolean = true, onClick: () -> Unit) {
                        val interaction = remember { MutableInteractionSource() }
                        val pressed by interaction.collectIsPressedAsState()
                        val pressScale by androidx.compose.animation.core.animateFloatAsState(
                            targetValue = if (pressed) .88f else 1f,
                            animationSpec = androidx.compose.animation.core.spring(
                                dampingRatio = .58f, stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
                            ),
                            label = "selection-action-press"
                        )
                        Column(
                            Modifier.graphicsLayer { scaleX = pressScale; scaleY = pressScale }
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Never rely on Color.Unspecified here: it can resolve to a dark default
                            // inside the selection surface and disappear on Dark / AMOLED themes.
                            val actionColor = if (tint == Color.Unspecified) Fg else tint
                            Icon(icon, label, tint = if (enabled) actionColor else Fg.copy(alpha = .38f))
                            Text(label, style = MaterialTheme.typography.labelSmall,
                                color = if (enabled) Fg else Fg.copy(alpha = .38f))
                        }
                    }
                    Act(Icons.Default.Share, "Share") {
                        if (s.selected.any { StorageLocation.fromStableKey(it) is StorageLocation.Saf }) vm.notify("Sharing SAF items is not available from this menu yet")
                        else ExternalOps.share(ctx, s.selected.map(::File).filter { it.isFile })
                    }
                    Act(Icons.Default.ContentCopy, "Copy") { vm.copySelected(false) }
                    Act(Icons.Default.ContentCut, "Move") { vm.copySelected(true) }
                    Act(Icons.Default.Delete, "Delete", Color(0xFFFF6B6B)) { if (SettingsStore.confirmDelete) dlg = Dlg.Delete else vm.deleteSelected() }
                    var more by remember { mutableStateOf(false) }
                    Box {
                        Act(Icons.Default.MoreVert, "More") { more = true }
                        DropdownMenu(more, { more = false }) {
                            @Composable fun It(label: String, onClick: () -> Unit) =
                                DropdownMenuItem({ Text(label, style = MaterialTheme.typography.bodyLarge) }, { more = false; onClick() })
                            val one = if (s.selected.size == 1) s.selected.first() else null
                            val oneIsSaf = one?.let { StorageLocation.fromStableKey(it) is StorageLocation.Saf } == true
                            val oneFile = one?.takeUnless { oneIsSaf }?.let { File(it) }
                            val oneItem = one?.let { p -> (s.items + s.secondaryItems).firstOrNull { it.path == p } }
                            val isAudio = oneFile != null && oneFile.isFile && oneFile.extension.lowercase() in setOf("mp3", "m4a", "wav", "flac", "ogg", "aac", "amr", "awb", "opus")
                            val isArchive = !oneIsSaf && one != null && MultiExtract.canExtract(one)
                            if (isAudio) { It("Set as") { ExternalOps.setAs(ctx, oneFile!!) }; It("Create ringtone") { ExternalOps.createRingtone(ctx, oneFile!!) } }
                            if (!oneIsSaf) {
                                if (isArchive) It("Decompress file") { vm.extractHere(one!!); vm.clearSelection() } else It("Compress") { dlg = Dlg.Zip }
                            }
                            if (one != null) It("Rename") { dlg = Dlg.Rename(one, one.substringAfterLast('/')) }
                            It("Tags") { dlg = Dlg.Tag }
                            if (oneFile != null && oneFile.isFile && !isArchive) It("Open with") { oneItem?.let { openFor = it } }
                            It("Details") { if (oneItem != null) infoFor = oneItem else dlg = Dlg.MultiProps }
                            if (oneFile?.isFile == true) It("Home screen shortcuts") { ExternalOps.pinShortcut(ctx, oneFile) }
                            It("Move to Private space") { vm.moveToPrivate(ctx) }
                            HorizontalDivider()
                            if (s.clipboard.isNotEmpty()) It("Paste here") { dlg = Dlg.ConfirmPaste }
                            if (s.selected.size == 2 && s.selected.all { StorageLocation.fromStableKey(it) !is StorageLocation.Saf && File(it).isFile }) It("Compare files") { val l = s.selected.toList(); onOpenFile(l[0] + "\u001f" + l[1], Tool.COMPARE) }
                            It("Invert selection") { vm.invertSelection() }
                            It("Select by pattern…") { dlg = Dlg.Pattern }
                            It("Batch rename…") { dlg = Dlg.Batch }
                            It("Duplicate") { vm.duplicateSelected() }
                            It("Copy path") { ExternalOps.copyText(ctx, s.selected.joinToString("\n")); vm.notify("Path copied") }
                            if (one != null && !oneIsSaf) It("Create symlink here…") { dlg = Dlg.Symlink(one) }
                        }
                    }
                }
            } else if (s.clipboard.isEmpty()) GlassCard(Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(2.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    IconButton({ if (!vm.up()) onExit() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    IconButton({ vm.goUp() }) { Icon(Icons.Default.ArrowUpward, "Parent folder") }
                    Box {
                        var addMenu by remember { mutableStateOf(false) }
                        IconButton({ addMenu = true }) { Icon(Icons.Default.Add, "New") }
                        DropdownMenu(addMenu, { addMenu = false }) {
                            DropdownMenuItem({ Text("New folder") }, { dlg = Dlg.NewFolder(); addMenu = false })
                            DropdownMenuItem({ Text("New file") }, { dlg = Dlg.NewFile(); addMenu = false })
                        }
                    }
                    IconButton({ vm.toggleDualPane() }) { Icon(if (s.dualPane) Icons.Default.ViewAgenda else Icons.Default.ViewColumn, "Dual pane") }
                    IconButton({ onExit() }) { Icon(Icons.Default.Home, "Home") }
                }
            } else if (s.clipboard.isNotEmpty()) GlassCard(Modifier.navigationBarsPadding().padding(12.dp).fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${s.clipboard.size} item(s) ${if (s.cut) "to move" else "to copy"}", Modifier.weight(1f))
                    TextButton(vm::clearClipboard) { Text("Cancel") }
                    Button({ dlg = Dlg.ConfirmPaste }) { Icon(Icons.Default.ContentPaste, null); Spacer(Modifier.width(6.dp)); Text("Paste") }
                }
            }
        }
    ) { pad ->
        FileListPane(
            path = s.dir, items = s.items, selected = s.selected, query = if (s.searching && !s.grep) s.query else "", active = true,
            modifier = Modifier.fillMaxSize().padding(top = pad.calculateTopPadding() + 4.dp, bottom = pad.calculateBottomPadding()),
            onActivate = { vm.setActivePane(0) },
            onItemClick = { f ->
                if (vm.state.value.selected.isNotEmpty()) vm.toggleSelect(f.path)
                else if (f.isDir) vm.openActive(f.path)
                else openItem(f)
            },
            onLong = { f -> itemMenu = f to 0 }, onSwipe = { f -> vm.swipeSelect(f.path, 0) }, showHeader = false, loading = s.loading, onRefresh = vm::reload
        )
    }

    when (val d = dlg) {
        is Dlg.NewFolder -> NameDialog("New folder", d.initialName, { dlg = null }) { vm.newFolder(it); dlg = null }
        is Dlg.NewFile -> NameDialog("New file", d.initialName, { dlg = null }) { vm.newFile(it); dlg = null }
        is Dlg.Rename -> NameDialog("Rename", d.name, { dlg = null }) { vm.rename(d.path, it); dlg = null }
        is Dlg.Zip -> NameDialog("Create ZIP", "archive.zip", { dlg = null }) { vm.zipSelected(it); dlg = null }
        is Dlg.Delete -> AlertDialog({ dlg = null }, title = { Text("Delete ${s.selected.size} item(s)?") }, text = { Text("This cannot be undone.") },
            confirmButton = { TextButton({ vm.deleteSelected(); dlg = null }) { Text("Delete", color = Color(0xFFFF6B6B)) } },
            dismissButton = { TextButton({ dlg = null }) { Text("Cancel") } })
        is Dlg.ConfirmPaste -> AlertDialog({ dlg = null },
            title = { Text("${if (s.cut) "Move" else "Copy"} ${s.clipboard.size} item(s)?") },
            text = { Text("Destination:\n" + (if (s.dualPane && s.activePane == 1) s.secondaryDir else s.dir)) },
            confirmButton = { TextButton({ vm.paste(); dlg = null }) { Text(if (s.cut) "Move" else "Copy") } },
            dismissButton = { TextButton({ dlg = null }) { Text("Cancel") } })
        is Dlg.Batch -> BatchRenameDialog(s.selected.size, { dlg = null }) { f, r, rx, st -> vm.batchRename(f, r, rx, st); dlg = null }
        is Dlg.Pattern -> NameDialog("Select by pattern (e.g. *.jpg)", "*.", { dlg = null }) { vm.selectPattern(it); dlg = null }
        is Dlg.Symlink -> NameDialog("Symlink name", d.target.substringAfterLast('/') + "_link", { dlg = null }) { vm.symlink(d.target, it); dlg = null }
        is Dlg.Jump -> JumpToPathDialog(if (s.dualPane && s.activePane == 1) s.secondaryDir else s.dir, { dlg = null }) { vm.jump(it); dlg = null }
        is Dlg.Tag -> com.kayx.manager.feature.storage.AddTagDialog(s.selected.toList(), { dlg = null }) { vm.clearSelection(); vm.notify("Tag added"); dlg = null }
        is Dlg.ConfirmTransfer -> {
            val byPath = (s.items + s.secondaryItems).associateBy { it.path }
            val n = s.selected.size
            val allFiles = s.selected.all { byPath[it]?.isDir == false }
            val noun = if (allFiles) (if (n == 1) "file" else "files") else (if (n == 1) "item" else "items")
            val verb = if (d.cut) "move" else "copy"
            AlertDialog({ dlg = null }, title = { Text(if (d.cut) "Move" else "Copy") },
                text = { Text("Do you want to $verb the selected $n $noun into ${s.otherDir.trimEnd('/')}/ ?") },
                confirmButton = { TextButton({ vm.transferSelectedToOther(d.cut); dlg = null }) { Text("OK") } },
                dismissButton = { TextButton({ dlg = null }) { Text("CANCEL") } })
        }
        is Dlg.Tools -> {
            val one = if (s.selected.size == 1) s.selected.first() else null
            ToolsDialog(buildList {
                add("Invert selection" to { vm.invertSelection() })
                add("Select by pattern…" to { dlg = Dlg.Pattern })
                add("Batch rename…" to { dlg = Dlg.Batch })
                add("Add tag…" to { dlg = Dlg.Tag })
                add("Move to Private space" to { vm.moveToPrivate(ctx) })
                add("Duplicate" to { vm.duplicateSelected() })
                add("Copy path" to { ExternalOps.copyText(ctx, s.selected.joinToString("\n")); vm.notify("Path copied") })
                if (one != null) add("Create symlink here…" to { dlg = Dlg.Symlink(one) })
                if (one != null && MultiExtract.canExtract(one)) add("Extract here" to { vm.extractHere(one); vm.clearSelection() })
            }, { dlg = null })
        }
        is Dlg.OrganizationPreview -> {
            val plan = d.plan
            AlertDialog(
                onDismissRequest = { dlg = null },
                title = { Text("Copy preview · ${plan.category.label}") },
                text = {
                    Column(
                        Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("${plan.files.size} files · ${fmtSize(plan.totalBytes)}")
                        Text("Destination:\n${plan.destinationLabel}")
                        Text("No files have been changed. Confirm only if this list and destination are correct.")
                        plan.files.forEach { entry ->
                            Text("• ${entry.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text("Existing names will be kept; copies receive a new name. Original files are not deleted.")
                    }
                },
                confirmButton = {
                    TextButton({
                        if (OfflineOrganizationPlanner.isStillValid(plan)) {
                            vm.copyPreviewedFiles(plan.files, plan.destinationPath)
                        } else vm.notify("Preview is stale. Run the organize command again.")
                        dlg = null
                    }) { Text("Confirm copy") }
                },
                dismissButton = { TextButton({ dlg = null }) { Text("Cancel") } }
            )
        }
        is Dlg.DuplicateReview -> {
            val groups = d.groups
            AlertDialog(
                onDismissRequest = { dlg = null },
                title = { Text("Duplicate review · ${groups.size} groups") },
                text = {
                    Column(
                        Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (groups.isEmpty()) {
                            Text("No byte-identical duplicate groups were found in the accessible scan.")
                        } else {
                            Text("Verified using SHA-256. Potential reclaimable space if one copy per group is retained: ${fmtSize(groups.sumOf { it.reclaimableBytes })}.")
                            groups.forEachIndexed { index, group ->
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Group ${index + 1} · ${group.entries.size} copies · ${fmtSize(group.size)} each",
                                        style = MaterialTheme.typography.titleSmall)
                                    Text("SHA-256: ${group.sha256.take(20)}…",
                                        style = MaterialTheme.typography.labelSmall, color = Fg.copy(.65f))
                                    group.entries.forEach { entry ->
                                        Text("• ${entry.name}\n  ${entry.path}",
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    }
                                    Text("Potential reclaim: ${fmtSize(group.reclaimableBytes)}",
                                        style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        Text("Review only: no files have been deleted or moved. Cleanup will require a separate explicit confirmation.",
                            style = MaterialTheme.typography.bodySmall, color = Fg.copy(.7f))
                    }
                },
                confirmButton = { TextButton({ dlg = null }) { Text("Done") } }
            )
        }
        is Dlg.MultiProps -> {
            if (s.selected.any { StorageLocation.fromStableKey(it) is StorageLocation.Saf }) {
                vm.notify("Multi-item size calculation is currently available for local files only")
                dlg = null
            } else MultiPropertiesDialog(s.selected.toList()) { dlg = null }
        }
        null -> {}
    }
    if (s.conflicts.isNotEmpty()) ConflictDialog(s.conflicts, { vm.paste(it) }, vm::dismissConflicts)
    openFor?.let { f -> OpenWithDialog(f, vm, onOpenFile, onProperties = { infoFor = f }, onWeb = { webFor = f }, onDismiss = { openFor = null }) }
    webFor?.let { f ->
        WebFunctionsDialog(
            f.path,
            onEdit = { webFor = null; onOpenFile(f.path, Tool.TEXT) },
            onPreview = { root -> webFor = null; onOpenFile(f.path + "\u001f" + root, Tool.HTML) },
            onMore = { webFor = null; openFor = f },
            onDismiss = { webFor = null }
        )
    }
    itemMenu?.let { (f, pane) ->
        val only = { vm.selectOnly(f.path, pane) }
        val entries = buildList {
            add(MenuEntry(if (f.path in (if (pane == 1) s.secondarySelected else s.primarySelected)) "Deselect" else "Select", Icons.Default.CheckCircle) {
                vm.setActivePane(pane); vm.toggleSelect(f.path)
            })
            if (!f.isDir) add(MenuEntry("Open", Icons.Default.FileOpen) { openItem(f) })
            if (!f.isDir && StorageLocation.fromStableKey(f.path) !is StorageLocation.Saf) add(MenuEntry("Open with…", Icons.AutoMirrored.Filled.OpenInNew) { openFor = f })
            add(MenuEntry("Copy", Icons.Default.ContentCopy) { only(); vm.copySelected(false) })
            add(MenuEntry("Cut", Icons.Default.ContentCut) { only(); vm.copySelected(true) })
            add(MenuEntry("Rename", Icons.Default.Edit) { vm.setActivePane(pane); dlg = Dlg.Rename(f.path, f.name) })
            add(MenuEntry("Delete", Icons.Default.Delete, danger = true) { only(); if (SettingsStore.confirmDelete) dlg = Dlg.Delete else vm.deleteSelected() })
            add(MenuEntry("Compress to ZIP", Icons.Default.Archive) { only(); dlg = Dlg.Zip })
            if (!f.isDir && MultiExtract.canExtract(f.path)) add(MenuEntry("Extract here", Icons.Default.Unarchive) { vm.extractHere(f.path) })
            if (!f.isDir) add(MenuEntry("Share", Icons.Default.Share) {
                if (StorageLocation.fromStableKey(f.path) is StorageLocation.Saf) vm.notify("Sharing SAF items is not available from this menu yet")
                else ExternalOps.share(ctx, listOf(File(f.path)))
            })
            add(MenuEntry("Copy path", Icons.Default.ContentPaste) { ExternalOps.copyText(ctx, f.path); vm.notify("Path copied") })
            if (f.isDir) add(MenuEntry("Add bookmark", Icons.Default.Bookmark) { vm.notify(if (SettingsStore.addBookmark(f.path)) "Bookmark added" else "Already bookmarked") })
            add(MenuEntry("Properties", Icons.Default.Info) { infoFor = f })
        }
        ItemContextMenu(f, entries) { itemMenu = null }
    }
    infoFor?.let { f -> PropertiesDialog(f, { infoFor = null }, vm::reload) }
}

@Composable
private fun FileListPane(
    path: String,
    items: List<FileItem>,
    selected: Set<String>,
    query: String = "",
    active: Boolean,
    modifier: Modifier = Modifier,
    onActivate: () -> Unit,
    onItemClick: (FileItem) -> Unit,
    onLong: (FileItem) -> Unit,
    onSwipe: (FileItem) -> Unit = {},
    showHeader: Boolean = true,
    loading: Boolean = false,
    onRefresh: () -> Unit = {}
) {
    Column(modifier) {
        if (showHeader) Surface(
            onClick = onActivate,
            color = if (active) Accent.copy(.18f) else Fg.copy(.045f),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(9.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 4.dp)
        ) {
            Text(path.substringAfterLast('/').ifEmpty { "/" }, Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
        }
        PullHost(dual = false, onSelectPane = onActivate, onRefresh = onRefresh, modifier = Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 5.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                if (items.isEmpty()) item(key = "empty") {
                    Box(Modifier.fillMaxWidth().padding(top = 60.dp), Alignment.Center) {
                        Text(if (loading) "Loading…" else "Empty", color = Fg.copy(.42f), style = MaterialTheme.typography.labelSmall)
                    }
                }
                items(items, key = { it.path }, contentType = { if (it.isDir) "folder" else "file" }) { f ->
                    FileRow(f, f.path in selected, query, onClick = { onItemClick(f) }, onLong = { onLong(f) }, onSwipe = { onSwipe(f) })
                }
            }
        }
    }
}

@Composable
private fun FileRow(f: FileItem, selected: Boolean, query: String, onClick: () -> Unit, onLong: () -> Unit, onSwipe: () -> Unit = {}) {
    val haptics = LocalHapticFeedback.current
    val folder = f.isDir
    val extension = f.name.substringAfterLast('.', "").lowercase()
    val iconTint by animateColorAsState(
        targetValue = if (selected || folder) Accent else Fg.copy(.78f),
        label = "file-icon-tint"
    )
    GlassCard(
        Modifier.fillMaxWidth().animateContentSize().swipeToSelect(true) { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onSwipe() },
        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
        tint = if (selected) Accent else Fg,
        onClick = onClick,
        onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onLong() }
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selected && com.kayx.manager.data.settings.SettingsStore.icons3d) {
                com.kayx.manager.ui.Icon3D(
                    Icons.Default.CheckCircle,
                    Accent,
                    size = 44.dp, flip = true,
                    contentDescription = "Selected"
                )
            } else if (folder && com.kayx.manager.data.settings.SettingsStore.icons3d) {
                com.kayx.manager.ui.FolderArtwork(
                    path = f.path,
                    modified = f.modified,
                    modifier = Modifier.size(44.dp)
                )
            } else if (selected || folder) {
            Icon(
                when {
                    selected -> Icons.Default.CheckCircle
                    folder -> Icons.Default.Folder
                    extension == "apk" -> Icons.Default.Android
                    extension in ARCHIVE_EXTENSIONS -> Icons.Default.Archive
                    extension in IMAGE_EXTENSIONS -> Icons.Default.Image
                    extension in AUDIO_EXTENSIONS -> Icons.Default.AudioFile
                    extension in VIDEO_EXTENSIONS -> Icons.Default.VideoFile
                    extension == "pdf" -> Icons.Default.PictureAsPdf
                    extension in CODE_EXTENSIONS -> Icons.Default.Code
                    extension in TEXT_EXTENSIONS -> Icons.AutoMirrored.Filled.TextSnippet
                    extension in FONT_EXTENSIONS -> Icons.Default.FontDownload
                    extension in OFFICE_EXTENSIONS -> Icons.Default.TableChart
                    else -> Icons.AutoMirrored.Filled.InsertDriveFile
                },
                contentDescription = when {
                    selected -> "Selected"
                    folder -> "Folder"
                    extension == "apk" -> "APK application file"
                    extension in ARCHIVE_EXTENSIONS -> "Archive file"
                    extension in IMAGE_EXTENSIONS -> "Image file"
                    extension in AUDIO_EXTENSIONS -> "Audio file"
                    extension in VIDEO_EXTENSIONS -> "Video file"
                    extension == "pdf" -> "PDF document"
                    extension in CODE_EXTENSIONS -> "Code file"
                    extension in TEXT_EXTENSIONS -> "Text file"
                    extension in FONT_EXTENSIONS -> "Font file"
                    extension in OFFICE_EXTENSIONS -> "Office document"
                    else -> "File"
                },
                tint = iconTint,
                modifier = Modifier.size(23.dp)
            )
            } else com.kayx.manager.ui.FileThumb(f.path)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                HighlightedFileName(f.name, query, color = Fg, maxLines = 1)
                Text(
                    remember(f.size, f.modified) { (if (folder) "Folder" else fmtSize(f.size)) + "  ·  " + com.kayx.manager.ui.formatDateTime(f.modified) },
                    style = MaterialTheme.typography.labelSmall, color = Fg.copy(.53f), maxLines = 1
                )
            }
            if (folder) Icon(Icons.Default.ChevronRight, null, tint = Fg.copy(.38f), modifier = Modifier.size(18.dp))
            else if (!selected) Icon(Icons.Default.MoreVert, null, tint = Fg.copy(.35f), modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var t by remember { mutableStateOf(initial) }
    AlertDialog(onDismiss, title = { Text(title) }, text = { OutlinedTextField(t, { t = it }, singleLine = true) },
        confirmButton = { TextButton({ onOk(t) }) { Text("OK") } }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

/** "Jump to path" dialog: pre-filled with the current folder (selected, so typing replaces it) plus PASTE / CANCEL / OK. */
@Composable
private fun JumpToPathDialog(current: String, onDismiss: () -> Unit, onGo: (String) -> Unit) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val initial = current.trimEnd('/') + "/"
    var t by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(initial, androidx.compose.ui.text.TextRange(0, initial.length))) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(onDismiss, title = { Text("Jump to path") },
        text = {
            OutlinedTextField(t, { t = it }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Go),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { onGo(t.text) }))
        },
        confirmButton = {
            Row {
                TextButton({ clipboard.getText()?.text?.let { p -> t = androidx.compose.ui.text.input.TextFieldValue(p, androidx.compose.ui.text.TextRange(p.length)) } }) { Text("PASTE") }
                Spacer(Modifier.weight(1f))
                TextButton(onDismiss) { Text("CANCEL") }
                TextButton({ onGo(t.text) }) { Text("OK") }
            }
        })
}
