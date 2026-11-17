package com.kayx.manager.feature.filebrowser
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import com.kayx.manager.ui.theme.Fg

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
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
import java.io.File
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kayx.manager.data.settings.SettingsStore
import com.kayx.manager.domain.model.FileItem
import com.kayx.manager.domain.model.SortBy
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
    data object NewFolder : Dlg
    data object NewFile : Dlg
    data class Rename(val path: String, val name: String) : Dlg
    data object Delete : Dlg
    data object Zip : Dlg
    data object Batch : Dlg
    data object Pattern : Dlg
    data class Symlink(val target: String) : Dlg
    data object Jump : Dlg
    data object Tag : Dlg
    data class ConfirmTransfer(val cut: Boolean) : Dlg
    data object Tools : Dlg
    data object MultiProps : Dlg
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
    var openFor by remember { mutableStateOf<FileItem?>(null) }
    var infoFor by remember { mutableStateOf<FileItem?>(null) }
    val ctx = LocalContext.current
    val snack = remember { SnackbarHostState() }
    val openItem: (FileItem) -> Unit = { f ->
        if (f.name.substringAfterLast('.', "").equals("apk", true)) ExternalOps.installApk(ctx, File(f.path))
        else if (ExternalOps.isOfficeOrPdf(File(f.path))) { if (!ExternalOps.openWith(ctx, File(f.path))) openFor = f }
        else openFor = f
    }
    LaunchedEffect(startPath) { vm.start(startPath) }
    LaunchedEffect(Unit) { vm.syncDefaults() }
    LaunchedEffect(s.message) { s.message?.let { snack.showSnackbar(it); vm.consumeMessage() } }
    BackHandler { if (!vm.up()) onExit() }

    if (s.dualPane) DualPaneBrowser(
        s, vm, snack, onExit, onSettings, onOpenFile,
        onDialog = { dlg = it }, onOpenItem = openItem, onOpenWith = { openFor = it }, onProperties = { infoFor = it }
    ) else Scaffold(
        modifier = Modifier.glassBackground(), containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            Column(Modifier.statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            GlassCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ if (!vm.up()) onExit() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    if (s.searching) TextField(
                        s.query, vm::setQuery, Modifier.weight(1f), singleLine = true, placeholder = { Text(if (s.grep) "Search text inside files" else "Search in folder") },
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                    else Column(Modifier.weight(1f)) {
                        val activePath = if (s.dualPane && s.activePane == 1) s.secondaryDir else s.dir
                        val shownItems = if (s.dualPane && s.activePane == 1) s.secondaryItems else s.items
                        val folders = remember(shownItems) { shownItems.count { it.isDir } }
                        val disk = remember(activePath) {
                            runCatching { android.os.StatFs(activePath).let { it.totalBytes to (it.totalBytes - it.availableBytes) } }
                                .recoverCatching { android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path).let { it.totalBytes to (it.totalBytes - it.availableBytes) } }
                                .getOrNull()
                        }
                        val pathScroll = rememberScrollState()
                        LaunchedEffect(activePath, pathScroll.maxValue) { pathScroll.scrollTo(pathScroll.maxValue) }
                        if (s.selected.isNotEmpty()) Text("${s.selected.size} selected", maxLines = 1, style = MaterialTheme.typography.titleMedium)
                        else Text(activePath.ifEmpty { "/" }, Modifier.horizontalScroll(pathScroll).clickable { dlg = Dlg.Jump }, maxLines = 1, softWrap = false,
                            style = MaterialTheme.typography.titleMedium)
                        Text("Folders: $folders  Files: ${shownItems.size - folders}" + (disk?.let { "  Disk: ${fmtSize(it.second)}/${fmtSize(it.first)}" } ?: ""),
                            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = Fg.copy(.5f))
                    }
                    if (s.selected.isEmpty()) {
                        IconButton(vm::toggleSearch) { Icon(if (s.searching) Icons.Default.Close else Icons.Default.Search, if (s.searching) "Close search" else "Search") }
                        Box {
                            IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "More options") }
                            DropdownMenu(menu, { menu = false; sortPage = false }) {
                                if (sortPage) {
                                    DropdownMenuItem({ Text("‹  Back") }, { sortPage = false })
                                    HorizontalDivider()
                                    SortBy.values().forEach { b -> DropdownMenuItem({ Text(b.name.lowercase().replaceFirstChar { it.uppercase() } + if (s.sort == b) "  ✓" else "") }, { vm.setSort(b); menu = false; sortPage = false }) }
                                } else {
                                    // View
                                    DropdownMenuItem({ Text(if (s.showHidden) "Hide hidden files" else "Show hidden files") }, { vm.toggleHidden(); menu = false })
                                    DropdownMenuItem({ Text("Sort by: ${s.sort.name.lowercase()}  ›") }, { sortPage = true })
                                    DropdownMenuItem({ Text(if (s.dualPane) "Single pane" else "Dual pane") }, { vm.toggleDualPane(); menu = false })
                                    if (s.dualPane) DropdownMenuItem({ Text("Swap panes") }, { vm.swapPanes(); menu = false })
                                    HorizontalDivider()
                                    // Navigate
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
                        IconButton(vm::selectAll) { Icon(Icons.Default.SelectAll, "Select all") }
                        IconButton(vm::clearSelection) { Icon(Icons.Default.Close, "Clear selection") }
                    }
                }
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
            s.transferProgress?.let { progress ->
                GlassCard(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("${if (progress.moving) "Moving" else "Copying"} ${progress.completed}/${progress.total}: ${progress.currentName}",
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
            if (s.transferProgress == null && s.retryTransfer != null) GlassCard(Modifier.navigationBarsPadding().padding(12.dp).fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Transfer failed", Modifier.weight(1f))
                    TextButton(vm::retryTransfer) { Text("Retry") }
                }
            } else if (s.selected.isNotEmpty()) GlassCard(Modifier.navigationBarsPadding().padding(12.dp).fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    IconButton({ vm.copySelected(false) }) { Icon(Icons.Default.ContentCopy, "Copy") }
                    IconButton({ vm.copySelected(true) }) { Icon(Icons.Default.ContentCut, "Move") }
                    if (s.clipboard.isNotEmpty()) IconButton({ vm.paste() }) { Icon(Icons.Default.ContentPaste, "Paste here") }
                    IconButton({ dlg = Dlg.Zip }) { Icon(Icons.Default.Archive, "Create ZIP") }
                    if (s.selected.size == 1) IconButton({ val p = s.selected.first(); dlg = Dlg.Rename(p, p.substringAfterLast('/')) }) { Icon(Icons.Default.Edit, "Rename") }
                    if (s.selected.size == 2 && s.selected.all { java.io.File(it).isFile }) IconButton({ val l = s.selected.toList(); onOpenFile(l[0] + "\u001f" + l[1], Tool.COMPARE) }) { Icon(Icons.Default.CompareArrows, "Compare files") }
                    var more by remember { mutableStateOf(false) }
                    Box {
                        IconButton({ more = true }) { Icon(Icons.Default.MoreVert, "More actions") }
                        DropdownMenu(more, { more = false }) {
                            DropdownMenuItem({ Text("Invert selection") }, { vm.invertSelection(); more = false })
                            DropdownMenuItem({ Text("Select by pattern…") }, { dlg = Dlg.Pattern; more = false })
                            DropdownMenuItem({ Text("Batch rename…") }, { dlg = Dlg.Batch; more = false })
                            DropdownMenuItem({ Text("Add tag…") }, { dlg = Dlg.Tag; more = false })
                            DropdownMenuItem({ Text("Move to Private space") }, { vm.moveToPrivate(ctx); more = false })
                            DropdownMenuItem({ Text("Duplicate") }, { vm.duplicateSelected(); more = false })
                            DropdownMenuItem({ Text("Share") }, { ExternalOps.share(ctx, s.selected.map(::File).filter { it.isFile }); more = false })
                            DropdownMenuItem({ Text("Copy path") }, { ExternalOps.copyText(ctx, s.selected.joinToString("\n")); vm.notify("Path copied"); more = false })
                            if (s.selected.size == 1) {
                                val one = s.selected.first()
                                DropdownMenuItem({ Text("Properties / permissions") }, {
                                    infoFor = (s.items + s.secondaryItems).firstOrNull { it.path == one }; more = false
                                })
                                DropdownMenuItem({ Text("Create symlink here…") }, { dlg = Dlg.Symlink(one); more = false })
                                if (MultiExtract.canExtract(one)) DropdownMenuItem({ Text("Extract here") }, { vm.extractHere(one); vm.clearSelection(); more = false })
                            }
                        }
                    }
                    IconButton({ if (SettingsStore.confirmDelete) dlg = Dlg.Delete else vm.deleteSelected() }) { Icon(Icons.Default.Delete, "Delete", tint = Color(0xFFFF6B6B)) }
                }
            } else if (s.clipboard.isEmpty()) GlassCard(Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(2.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    IconButton({ if (!vm.up()) onExit() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    IconButton({ vm.goUp() }) { Icon(Icons.Default.ArrowUpward, "Parent folder") }
                    Box {
                        var addMenu by remember { mutableStateOf(false) }
                        IconButton({ addMenu = true }) { Icon(Icons.Default.Add, "New") }
                        DropdownMenu(addMenu, { addMenu = false }) {
                            DropdownMenuItem({ Text("New folder") }, { dlg = Dlg.NewFolder; addMenu = false })
                            DropdownMenuItem({ Text("New file") }, { dlg = Dlg.NewFile; addMenu = false })
                        }
                    }
                    IconButton({ vm.toggleDualPane() }) { Icon(if (s.dualPane) Icons.Default.ViewAgenda else Icons.Default.ViewColumn, "Dual pane") }
                    IconButton({ onExit() }) { Icon(Icons.Default.Home, "Home") }
                }
            } else if (s.clipboard.isNotEmpty()) GlassCard(Modifier.navigationBarsPadding().padding(12.dp).fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${s.clipboard.size} item(s) ${if (s.cut) "to move" else "to copy"}", Modifier.weight(1f))
                    TextButton(vm::clearClipboard) { Text("Cancel") }
                    Button(vm::paste) { Icon(Icons.Default.ContentPaste, null); Spacer(Modifier.width(6.dp)); Text("Paste") }
                }
            }
        }
    ) { pad ->
        FileListPane(
            path = s.dir, items = s.items, selected = s.selected, active = true,
            modifier = Modifier.fillMaxSize().padding(top = pad.calculateTopPadding() + 4.dp, bottom = pad.calculateBottomPadding()),
            onActivate = { vm.setActivePane(0) },
            onItemClick = { f ->
                if (vm.state.value.selected.isNotEmpty()) vm.toggleSelect(f.path)
                else if (f.isDir) vm.openActive(f.path)
                else openItem(f)
            },
            onLong = { f -> vm.toggleSelect(f.path) }, onSwipe = { f -> vm.swipeSelect(f.path, 0) }, showHeader = false, loading = s.loading, onRefresh = vm::reload
        )
    }

    when (val d = dlg) {
        is Dlg.NewFolder -> NameDialog("New folder", "", { dlg = null }) { vm.newFolder(it); dlg = null }
        is Dlg.NewFile -> NameDialog("New file", "", { dlg = null }) { vm.newFile(it); dlg = null }
        is Dlg.Rename -> NameDialog("Rename", d.name, { dlg = null }) { vm.rename(d.path, it); dlg = null }
        is Dlg.Zip -> NameDialog("Create ZIP", "archive.zip", { dlg = null }) { vm.zipSelected(it); dlg = null }
        is Dlg.Delete -> AlertDialog({ dlg = null }, title = { Text("Delete ${s.selected.size} item(s)?") }, text = { Text("This cannot be undone.") },
            confirmButton = { TextButton({ vm.deleteSelected(); dlg = null }) { Text("Delete", color = Color(0xFFFF6B6B)) } },
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
        is Dlg.MultiProps -> MultiPropertiesDialog(s.selected.toList()) { dlg = null }
        null -> {}
    }
    if (s.conflicts.isNotEmpty()) ConflictDialog(s.conflicts, { vm.paste(it) }, vm::dismissConflicts)
    openFor?.let { f -> OpenWithDialog(f, vm, onOpenFile, { infoFor = f }, { openFor = null }) }
    infoFor?.let { f -> PropertiesDialog(f, { infoFor = null }, vm::reload) }
}

@Composable
private fun FileListPane(
    path: String,
    items: List<FileItem>,
    selected: Set<String>,
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
                    FileRow(f, f.path in selected, onClick = { onItemClick(f) }, onLong = { onLong(f) }, onSwipe = { onSwipe(f) })
                }
            }
        }
    }
}

@Composable
private fun FileRow(f: FileItem, selected: Boolean, onClick: () -> Unit, onLong: () -> Unit, onSwipe: () -> Unit = {}) {
    val haptics = LocalHapticFeedback.current
    val folder = f.isDir
    val fk = if (folder) com.kayx.manager.ui.rememberFolderKind(f.path, f.modified, com.kayx.manager.data.settings.SettingsStore.folderIcons) else com.kayx.manager.ui.FolderKind.NONE
    val extension = f.name.substringAfterLast('.', "").lowercase()
    val iconTint by animateColorAsState(
        targetValue = if (selected || folder) Accent else Fg.copy(.78f),
        label = "file-icon-tint"
    )
    GlassCard(
        Modifier.fillMaxWidth().animateContentSize().swipeToSelect(true) { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onSwipe() },
        shape = androidx.compose.foundation.shape.RoundedCornerShape(11.dp),
        tint = if (selected) Accent else Fg,
        onClick = onClick,
        onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onLong() }
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if ((selected || folder) && com.kayx.manager.data.settings.SettingsStore.icons3d) {
                com.kayx.manager.ui.Icon3D(
                    if (selected) Icons.Default.CheckCircle else fk.icon,
                    if (selected) Accent else fk.color,
                    size = 44.dp, flip = selected,
                    contentDescription = if (selected) "Selected" else "Folder"
                )
            } else if (selected || folder) {
            Icon(
                when {
                    selected -> Icons.Default.CheckCircle
                    folder -> fk.icon
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
                Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium, color = Fg)
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
