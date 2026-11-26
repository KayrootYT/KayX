package com.kayx.manager.feature.filebrowser

import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.kayx.manager.data.filesystem.ExternalOps
import com.kayx.manager.data.filesystem.StorageLocation
import com.kayx.manager.data.filesystem.FileOps
import com.kayx.manager.data.settings.SettingsStore
import com.kayx.manager.domain.model.FileItem
import com.kayx.manager.domain.model.SortBy
import com.kayx.manager.domain.model.Tool
import com.kayx.manager.ui.glass.GlassCard
import com.kayx.manager.ui.glass.glassBackground
import com.kayx.manager.ui.theme.Accent
import com.kayx.manager.ui.theme.Fg
import com.kayx.manager.ui.theme.LocalPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/*
 * Dual-pane file manager screen: two panes ("tabs") you can copy / move between.
 *
 *  - Split mode (default): both panes side by side, exactly like the reference recording.
 *  - Tab mode: each pane is full screen and you swipe left/right between the two tabs.
 *  - Select files in one pane, open the action menu and tap "Copy ->" / "Move ->": the selection goes straight
 *    into the folder shown in the other pane after a confirm dialog. Long-press those two entries for the
 *    classic single-window clipboard copy/move + paste.
 */

private val FreshGreen = Color(0xFF35C24A)
private val dxDate by lazy { SimpleDateFormat("yy-MM-dd HH:mm", Locale.getDefault()) }

private val DX_ARCHIVE = setOf("zip", "7z", "rar", "tar", "gz", "bz2", "xz", "tgz", "zst", "jar", "aar", "war", "iso")
private val DX_IMAGE = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "svg", "heic", "heif", "avif", "tif", "tiff", "ico")
private val DX_AUDIO = setOf("mp3", "m4a", "wav", "ogg", "oga", "opus", "flac", "aac", "wma", "amr", "mid", "midi")
private val DX_VIDEO = setOf("mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v", "mpeg", "mpg", "wmv", "flv")
private val DX_CODE = setOf("kt", "kts", "java", "smali", "js", "jsx", "ts", "tsx", "py", "rb", "go", "rs", "c", "h", "cpp", "hpp", "cs", "php", "swift", "sh", "css", "scss", "xml", "json", "toml", "gradle", "sql", "yml", "yaml")

private class Kind(val color: Color, val icon: ImageVector)

private fun kindOf(f: FileItem): Kind {
    if (f.isDir) return Kind(Color(0xFF1E1E22), Icons.Default.Folder)
    val e = f.name.substringAfterLast('.', "").lowercase()
    return when (e) {
        "xls", "xlsx", "xlsm", "xltx", "ods", "csv", "tsv" -> Kind(Color(0xFF1E8E1E), Icons.Default.TableChart)
        "pdf" -> Kind(Color(0xFFC62828), Icons.Default.PictureAsPdf)
        "html", "htm", "xhtml" -> Kind(Color(0xFF0277BD), Icons.Default.Public)
        "doc", "docx", "docm", "dot", "dotx", "odt", "rtf", "txt", "md", "log" -> Kind(Color(0xFF3F51B5), Icons.Default.Description)
        "ppt", "pptx", "pptm", "ppsx", "odp" -> Kind(Color(0xFFD35400), Icons.Default.Description)
        "apk", "apks", "xapk", "apkm" -> Kind(Color(0xFF2E7D32), Icons.Default.Android)
        else -> when {
            e in DX_ARCHIVE -> Kind(Color(0xFF795548), Icons.Default.Archive)
            e in DX_IMAGE -> Kind(Color(0xFF00838F), Icons.Default.Image)
            e in DX_AUDIO -> Kind(Color(0xFF8E24AA), Icons.Default.AudioFile)
            e in DX_VIDEO -> Kind(Color(0xFFEF6C00), Icons.Default.VideoFile)
            e in DX_CODE -> Kind(Color(0xFF546E7A), Icons.Default.Code)
            else -> Kind(Color(0xFF616161), Icons.AutoMirrored.Filled.InsertDriveFile)
        }
    }
}

private fun dxSize(b: Long): String {
    if (b < 1024) return "${b}B"
    val u = arrayOf("K", "M", "G", "T")
    var v = b / 1024.0
    var i = 0
    while (v >= 1024 && i < u.lastIndex) { v /= 1024; i++ }
    return String.format(Locale.US, "%.2f%s", v, u[i])
}

private fun dxGb(b: Long) = String.format(Locale.US, "%.2fG", b / 1073741824.0)

private fun diskText(path: String): String? = runCatching {
    val st = runCatching { StatFs(path) }.getOrElse { StatFs(Environment.getExternalStorageDirectory().path) }
    val total = st.totalBytes
    "${dxGb(total - st.availableBytes)}/${dxGb(total)}"
}.getOrNull()

/** Entry point used by FileBrowserScreen whenever dual pane is on. Renders its own Scaffold. */
@Composable
internal fun DualPaneBrowser(
    s: BrowserState,
    vm: FileBrowserViewModel,
    snack: SnackbarHostState,
    onExit: () -> Unit,
    onSettings: () -> Unit,
    onBrowseSaf: () -> Unit,
    onOpenFile: (String, Tool) -> Unit,
    onDialog: (Dlg) -> Unit,
    onOpenItem: (FileItem) -> Unit,
    onOpenWith: (FileItem) -> Unit,
    onProperties: (FileItem) -> Unit,
    onItemMenu: (FileItem, Int) -> Unit = { _, _ -> }
) {
    var actionMenu by remember { mutableStateOf(false) }
    val nothingSelected = s.selected.isEmpty()
    LaunchedEffect(nothingSelected) { if (nothingSelected) actionMenu = false }
    Scaffold(
        modifier = Modifier.glassBackground(), containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snack) },
        topBar = { DualHeader(s, vm, onExit, onSettings, onBrowseSaf, onOpenFile, onDialog) },
        bottomBar = { DualBottomBar(s, vm, onDialog, actionMenu, { actionMenu = it }, onOpenWith, onProperties) }
    ) { pad ->
        PanePager(s, vm, pad, onOpenItem, onItemMenu)
    }
}

// ---------------------------------------------------------------------------------------------
// Header: path of the active pane + "Folders / Files / Selected / Disk" line
// ---------------------------------------------------------------------------------------------

@Composable
private fun DualHeader(
    s: BrowserState, vm: FileBrowserViewModel,
    onExit: () -> Unit, onSettings: () -> Unit, onBrowseSaf: () -> Unit,
    onOpenFile: (String, Tool) -> Unit, onDialog: (Dlg) -> Unit
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val p = LocalPalette.current
    val activePath = if (s.activePane == 1) s.secondaryDir else s.dir
    val shown = if (s.activePane == 1) s.secondaryItems else s.items
    val folders = remember(shown) { shown.count { it.isDir } }
    val disk = remember(activePath) { if (StorageLocation.fromStableKey(activePath) is StorageLocation.Saf) null else diskText(activePath) }
    val pathScroll = rememberScrollState()
    LaunchedEffect(activePath, pathScroll.maxValue) { pathScroll.scrollTo(pathScroll.maxValue) }
    var navOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var sortPage by remember { mutableStateOf(false) }
    val info = buildString {
        append("Folders: $folders  Files: ${shown.size - folders}")
        if (s.selected.isNotEmpty()) append("  Selected: ${s.selected.size}")
        if (disk != null) append("  Disk: $disk")
    }
    Column(Modifier.fillMaxWidth().background(p.surface).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                IconButton({ navOpen = true }) { Icon(Icons.Default.Menu, "Menu") }
                DropdownMenu(navOpen, { navOpen = false }) {
                    DropdownMenuItem({ Text("Home") }, { navOpen = false; onExit() })
                    SettingsStore.bookmarks.forEach { b ->
                        DropdownMenuItem({ Text("★  " + b.substringAfterLast('/').ifEmpty { "/" }) }, { navOpen = false; vm.jump(b) })
                    }
                    DropdownMenuItem({ Text("Browse SD card / SAF storage…") }, { navOpen = false; onBrowseSaf() })
                    DropdownMenuItem({ Text("Recycle bin") }, { navOpen = false; onOpenFile("", Tool.RECYCLE) })
                    DropdownMenuItem({ Text("Settings") }, { navOpen = false; onSettings() })
                }
            }
            if (s.searching) TextField(
                value = s.query,
                onValueChange = vm::setQuery,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Search") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search files") },
                trailingIcon = {
                    if (s.query.isNotEmpty()) IconButton(onClick = { vm.setQuery("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear search")
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    vm.reload()
                    keyboardController?.hide()
                }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                )
            ) else Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                val safLocation = StorageLocation.fromStableKey(activePath) as? StorageLocation.Saf
                Text(
                    if (safLocation != null) "SD Card · " + safLocation.relativePath.ifBlank { "Storage root" }
                    else activePath.trimEnd('/') + "/",
                    Modifier.horizontalScroll(pathScroll).clickable { if (safLocation == null) onDialog(Dlg.Jump) },
                    maxLines = 1, softWrap = false, style = MaterialTheme.typography.titleMedium
                )
                Text(info, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = Fg.copy(.62f))
            }
            // Keep one clear (X) inside the active search field; show search launcher only when closed.
            if (!s.searching) IconButton(vm::toggleSearch) {
                Icon(Icons.Default.Search, "Search")
            }
            Box {
                IconButton({ menuOpen = true }) { Icon(Icons.Default.MoreVert, "More options") }
                DropdownMenu(menuOpen, { menuOpen = false; sortPage = false }) {
                    if (sortPage) {
                        DropdownMenuItem({ Text("‹  Back") }, { sortPage = false })
                        HorizontalDivider()
                        SortBy.values().forEach { b ->
                            DropdownMenuItem({ Text(b.name.lowercase().replaceFirstChar { it.uppercase() } + if (s.sort == b) "  ✓" else "") }, { menuOpen = false; sortPage = false; vm.setSort(b) })
                        }
                    } else {
                        if (s.undoAvailable) {
                            DropdownMenuItem({ Text("Undo last file operation") }, { menuOpen = false; vm.undoLastOperation() })
                            HorizontalDivider()
                        }
                        // View
                        DropdownMenuItem({ Text(if (s.tabMode) "Split view (both panes)" else "Swipe tabs (full screen)") }, { menuOpen = false; vm.toggleTabMode() })
                        DropdownMenuItem({ Text("Swap panes") }, { menuOpen = false; vm.swapPanes() })
                        DropdownMenuItem({ Text("Single pane") }, { menuOpen = false; vm.toggleDualPane() })
                        DropdownMenuItem({ Text(if (s.showHidden) "Hide hidden files" else "Show hidden files") }, { menuOpen = false; vm.toggleHidden() })
                        DropdownMenuItem({ Text("Sort by: ${s.sort.name.lowercase()}  ›") }, { sortPage = true })
                        HorizontalDivider()
                        // Navigate
                        DropdownMenuItem({ Text("Jump to path…") }, { menuOpen = false; onDialog(Dlg.Jump) })
                        DropdownMenuItem({ Text("Add bookmark") }, {
                            menuOpen = false
                            vm.notify(if (SettingsStore.addBookmark(activePath)) "Bookmark added" else "Already bookmarked")
                        })
                        DropdownMenuItem({ Text("Set as Home") }, { menuOpen = false; SettingsStore.updateHomePath(activePath); vm.notify("Home folder set") })
                        DropdownMenuItem({ Text("Refresh") }, { menuOpen = false; vm.reload() })
                        HorizontalDivider()
                        // Select & search
                        if (!s.searching) DropdownMenuItem({ Text("Search") }, { menuOpen = false; vm.toggleSearch() })
                        DropdownMenuItem({ Text("Select all") }, { menuOpen = false; vm.selectAll() })
                        HorizontalDivider()
                        DropdownMenuItem({ Text("Open terminal here") }, { menuOpen = false; onOpenFile(activePath, Tool.TERMINAL) })
                    }
                }
            }
        }
        s.transferProgress?.let { pr ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "${if (pr.moving) "Moving" else "Copying"}: ${pr.currentName} · " +
                        if (pr.totalBytes > 0L) "${dxSize(pr.completedBytes)} / ${dxSize(pr.totalBytes)} · ${dxSize(pr.bytesPerSecond)}/s" +
                            (pr.etaSeconds?.let { " · ETA ${it / 60}m ${it % 60}s" } ?: "")
                        else "${pr.completed}/${pr.total} items",
                    style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                LinearProgressIndicator(progress = { pr.fraction }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The two panes (pager)
// ---------------------------------------------------------------------------------------------

@Composable
private fun PanePager(s: BrowserState, vm: FileBrowserViewModel, pad: PaddingValues, onOpenItem: (FileItem) -> Unit, onItemMenu: (FileItem, Int) -> Unit) {
    val tab = s.tabMode
    val pager = rememberPagerState(initialPage = s.activePane) { 2 }

    // Tab mode: swiping the pager makes that tab the active pane.
    LaunchedEffect(tab) {
        if (tab) {
            pager.scrollToPage(vm.state.value.activePane)
            snapshotFlow { pager.settledPage }.collect { vm.setActivePane(it) }
        }
    }
    // Tapping a tab title / swapping panes: bring the pager along.
    LaunchedEffect(s.activePane) {
        if (tab && pager.currentPage != s.activePane) pager.animateScrollToPage(s.activePane)
    }

    Column(Modifier.fillMaxSize().padding(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding())) {
        if (tab) TabStrip(s, { vm.setActivePane(it) }, vm::addTab)
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val half = maxWidth / 2
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize(),
                pageSize = if (tab) PageSize.Fill else PageSize.Fixed(half),
                userScrollEnabled = tab,
                beyondViewportPageCount = 1
            ) { page ->
                PaneList(
                    dir = if (page == 0) s.dir else s.secondaryDir,
                    items = if (page == 0) s.items else s.secondaryItems,
                    query = if (s.searching && s.activePane == page && !s.grep) s.query else "",
                    selected = if (page == 0) s.primarySelected else s.secondarySelected,
                    recent = s.recent,
                    active = s.activePane == page,
                    loading = s.loading,
                    showDivider = page == 1 && !tab,
                    onActivate = { vm.setActivePane(page) },
                    onUp = { vm.setActivePane(page); vm.goUp() },
                    onClick = { f ->
                        vm.setActivePane(page)
                        if (vm.state.value.selected.isNotEmpty()) vm.toggleSelect(f.path)
                        else if (f.isDir) vm.openActive(f.path)
                        else onOpenItem(f)
                    },
                    onLong = { f -> vm.setActivePane(page); onItemMenu(f, page) },
                    onSwipe = { f -> vm.swipeSelect(f.path, page) },
                    swipeEnabled = !tab, // in swipe-tabs mode horizontal drags belong to the pager
                    onRefresh = { vm.setActivePane(page); vm.reload() }
                )
            }
        }
    }
}

@Composable
private fun TabStrip(s: BrowserState, onPick: (Int) -> Unit, onAddTab: (String) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().background(p.surface), verticalAlignment = Alignment.CenterVertically) {
        listOf(s.dir, s.secondaryDir).forEachIndexed { i, d ->
            val active = s.activePane == i
            val count = if (i == 0) s.primarySelected.size else s.secondarySelected.size
            Column(Modifier.weight(1f).clickable { onPick(i) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    d.substringAfterLast('/').ifEmpty { "/" } + if (count > 0) "  ($count)" else "",
                    Modifier.padding(horizontal = 8.dp, vertical = 9.dp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (active) Accent else Fg.copy(.6f), style = MaterialTheme.typography.labelLarge
                )
                Box(Modifier.fillMaxWidth().height(2.dp).background(if (active) Accent else Color.Transparent))
            }
        }
        IconButton(onClick = { onAddTab(if (s.activePane == 1) s.secondaryDir else s.dir) }) {
            Icon(Icons.Default.Add, "New independent tab")
        }
    }
}

@Composable
private fun PaneList(
    dir: String, items: List<FileItem>, query: String, selected: Set<String>, recent: Set<String>,
    active: Boolean, loading: Boolean, showDivider: Boolean,
    onActivate: () -> Unit, onUp: () -> Unit, onClick: (FileItem) -> Unit, onLong: (FileItem) -> Unit,
    onSwipe: (FileItem) -> Unit, swipeEnabled: Boolean, onRefresh: () -> Unit
) {
    Row(Modifier.fillMaxSize()) {
        if (showDivider) VerticalDivider(Modifier.fillMaxHeight(), thickness = 1.dp, color = Fg.copy(.16f))
        Column(
            Modifier.weight(1f).fillMaxHeight()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onActivate)
        ) {
            Box(Modifier.fillMaxWidth().height(2.dp).background(if (active) Accent else Color.Transparent))
            PullHost(dual = true, onSelectPane = onActivate, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 5.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    if (dir != "/" && dir.isNotEmpty()) item(key = "..") { UpRow(onUp) }
                    if (items.isEmpty()) item(key = "empty") {
                        Box(Modifier.fillMaxWidth().padding(top = 40.dp), Alignment.Center) {
                            Text(if (loading) "Loading…" else "Empty", color = Fg.copy(.42f), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    items(items, key = { it.path }, contentType = { if (it.isDir) "folder" else "file" }) { f ->
                        PaneRow(f, f.path in selected, f.path in recent, query, { onClick(f) }, { onLong(f) }, { onSwipe(f) }, swipeEnabled)
                    }
                }
            }
        }
    }
}

@Composable
private fun UpRow(onClick: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), onClick = onClick) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).background(Color(0xFF1E1E22), RoundedCornerShape(7.dp)), Alignment.Center) {
                Icon(Icons.Default.Folder, null, Modifier.size(19.dp), tint = Color.White)
            }
            Spacer(Modifier.width(8.dp))
            Text("..", style = MaterialTheme.typography.bodyMedium, color = Fg)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PaneRow(f: FileItem, selected: Boolean, fresh: Boolean, query: String, onClick: () -> Unit, onLong: () -> Unit, onSwipe: () -> Unit, swipeEnabled: Boolean) {
    val haptics = LocalHapticFeedback.current
    val kind = remember(f.name, f.isDir) { kindOf(f) }
    // A directory always uses a folder glyph, independent of the files it contains.
    val rowIcon = if (f.isDir) Icons.Default.Folder else kind.icon
    val rowColor = if (f.isDir) Color(0xFFFFB02E) else kind.color
    val meta = remember(f.size, f.modified) {
        dxDate.format(java.util.Date(f.modified)) + if (f.isDir) "" else "  " + dxSize(f.size)
    }
    GlassCard(
        Modifier.fillMaxWidth().swipeToSelect(swipeEnabled) { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onSwipe() },
        shape = RoundedCornerShape(18.dp),
        tint = if (selected) Accent else Fg,
        onClick = onClick,
        onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onLong() }
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (com.kayx.manager.data.settings.SettingsStore.icons3d && selected) {
                com.kayx.manager.ui.Icon3D(Icons.Default.Check, Accent, size = 32.dp, flip = true)
            } else if (com.kayx.manager.data.settings.SettingsStore.icons3d && f.isDir) {
                com.kayx.manager.ui.FolderArtwork(f.path, f.modified, size = 36.dp)
            } else if (com.kayx.manager.data.settings.SettingsStore.icons3d) {
                com.kayx.manager.ui.Icon3D(rowIcon, rowColor, size = 32.dp)
            } else Box(Modifier.size(32.dp).background(if (selected) Accent else rowColor, RoundedCornerShape(7.dp)), Alignment.Center) {
                Icon(if (selected) Icons.Default.Check else rowIcon, null, Modifier.size(19.dp), tint = Color.White)
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                HighlightedFileName(f.name, query, color = if (fresh) FreshGreen else Fg, maxLines = 2)
                Text(
                    meta, maxLines = 1, style = MaterialTheme.typography.labelSmall,
                    color = if (fresh) FreshGreen.copy(alpha = .8f) else Fg.copy(.5f)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Bottom bar + selection action menu
// ---------------------------------------------------------------------------------------------

@Composable
private fun DualBottomBar(
    s: BrowserState, vm: FileBrowserViewModel, onDialog: (Dlg) -> Unit,
    actionOpen: Boolean, setActionOpen: (Boolean) -> Unit,
    onOpenWith: (FileItem) -> Unit, onProperties: (FileItem) -> Unit
) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().background(p.surface).navigationBarsPadding()) {
        HorizontalDivider(color = p.separator)
        when {
            s.transferProgress != null -> Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                val control = s.transferControl
                val paused = control?.state == com.kayx.manager.data.filesystem.FileOps.TransferState.PAUSED
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${if (s.transferProgress.moving) "Moving" else "Copying"}: ${s.transferProgress.currentName} · " +
                                if (s.transferProgress.totalBytes > 0L) "${dxSize(s.transferProgress.completedBytes)} / ${dxSize(s.transferProgress.totalBytes)} · ${dxSize(s.transferProgress.bytesPerSecond)}/s" +
                                    (s.transferProgress.etaSeconds?.let { " · ETA ${it / 60}m ${it % 60}s" } ?: "")
                                else "${s.transferProgress.completed}/${s.transferProgress.total} items",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium
                        )
                        LinearProgressIndicator(
                            progress = { s.transferProgress.fraction },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        )
                    }
                    TextButton(onClick = { if (paused) vm.resumeTransfer() else vm.pauseTransfer() }) {
                        Text(if (paused) "Resume" else "Pause")
                    }
                    TextButton(onClick = vm::cancelTransfer) { Text("Cancel") }
                }
            }
            s.retryTransfer != null -> Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Transfer failed", Modifier.weight(1f), color = Fg.copy(.72f))
                TextButton(onClick = vm::retryTransfer) { Text("Retry") }
            }
            s.selected.isNotEmpty() -> Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(vm::selectAll, Modifier.weight(1f)) { Icon(Icons.Default.SelectAll, "Select all", tint = p.fg) }
                IconButton(vm::invertSelection, Modifier.weight(1f)) { Icon(Icons.Default.FlipToFront, "Invert selection", tint = p.fg) }
                IconButton(vm::clearSelection, Modifier.weight(1f)) { Icon(Icons.Default.Close, "Clear selection", tint = p.fg) }
                IconButton(vm::toggleTabMode, Modifier.weight(1f)) { Icon(Icons.Default.ViewColumn, "Split / swipe tabs", tint = p.fg) }
                IconButton({ setActionOpen(!actionOpen) }, Modifier.weight(1f)) { Icon(Icons.Default.Checklist, "Actions", tint = p.fg) }
            }
            s.clipboard.isNotEmpty() -> Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${s.clipboard.size} item(s) ${if (s.cut) "to move" else "to copy"}", Modifier.weight(1f))
                TextButton(vm::clearClipboard) { Text("Cancel") }
                Button({ onDialog(Dlg.ConfirmPaste) }) { Icon(Icons.Default.ContentPaste, null); Spacer(Modifier.width(6.dp)); Text("Paste here") }
            }
            else -> Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(vm::goBack, Modifier.weight(1f), enabled = s.canBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                IconButton(vm::goForward, Modifier.weight(1f), enabled = s.canForward) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Forward") }
                Box(Modifier.weight(1f), Alignment.Center) {
                    var addMenu by remember { mutableStateOf(false) }
                    IconButton({ addMenu = true }) { Icon(Icons.Default.Add, "New") }
                    DropdownMenu(addMenu, { addMenu = false }) {
                        DropdownMenuItem({ Text("New folder") }, { addMenu = false; onDialog(Dlg.NewFolder) })
                        DropdownMenuItem({ Text("New file") }, { addMenu = false; onDialog(Dlg.NewFile) })
                    }
                }
                IconButton(vm::swapPanes, Modifier.weight(1f)) { Icon(Icons.Default.SwapHoriz, "Swap panes") }
                IconButton(vm::goUp, Modifier.weight(1f)) { Icon(Icons.Default.ArrowUpward, "Parent folder") }
            }
        }
        if (actionOpen && s.selected.isNotEmpty()) ActionPopup(s, vm, { setActionOpen(false) }, onDialog, onOpenWith, onProperties)
    }
}

/** Opens the popup right above the bar it is attached to, centred horizontally. */
private object AboveAnchor : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = ((anchorBounds.left + anchorBounds.right - popupContentSize.width) / 2).coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width))
        val y = (anchorBounds.top - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

@Composable
private fun ActionPopup(
    s: BrowserState, vm: FileBrowserViewModel, onDismiss: () -> Unit,
    onDialog: (Dlg) -> Unit, onOpenWith: (FileItem) -> Unit, onProperties: (FileItem) -> Unit
) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val byPath = remember(s.items, s.secondaryItems) { (s.items + s.secondaryItems).associateBy { it.path } }
    val sel = s.selected
    val one: FileItem? = if (sel.size == 1) byPath[sel.first()] else null

    fun go(block: () -> Unit) { onDismiss(); block() }

    Popup(popupPositionProvider = AboveAnchor, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Surface(
            shape = RoundedCornerShape(12.dp), color = p.dialog, shadowElevation = 12.dp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp).fillMaxWidth()
        ) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Menus marked with • support long press for a single-window operation",
                        Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.labelSmall, color = Fg.copy(.6f)
                    )
                    IconButton(onDismiss, Modifier.size(36.dp)) { Icon(Icons.Default.Close, "Close", Modifier.size(16.dp), tint = Fg.copy(.6f)) }
                }
                HorizontalDivider(color = p.separator)
                Row {
                    MenuCell(Icons.Default.ContentCopy, "Copy ->", Modifier.weight(1f), dot = true,
                        onLong = { go { vm.copySelected(false) } }) { go { onDialog(Dlg.ConfirmTransfer(false)) } }
                    MenuCell(Icons.Default.ContentCut, "Move ->", Modifier.weight(1f), dot = true,
                        onLong = { go { vm.copySelected(true) } }) { go { onDialog(Dlg.ConfirmTransfer(true)) } }
                }
                Row {
                    MenuCell(Icons.Default.Delete, "Delete", Modifier.weight(1f)) {
                        go { if (SettingsStore.confirmDelete) onDialog(Dlg.Delete) else vm.deleteSelected() }
                    }
                    MenuCell(Icons.Default.Edit, "Rename", Modifier.weight(1f)) {
                        go { if (sel.size == 1) onDialog(Dlg.Rename(sel.first(), sel.first().substringAfterLast('/'))) else onDialog(Dlg.Batch) }
                    }
                }
                Row {
                    MenuCell(Icons.Default.Build, "Tools", Modifier.weight(1f)) { go { onDialog(Dlg.Tools) } }
                    MenuCell(Icons.Default.Archive, "Compress", Modifier.weight(1f)) { go { onDialog(Dlg.Zip) } }
                }
                Row {
                    MenuCell(Icons.Default.Info, "Properties", Modifier.weight(1f)) {
                        go {
                            if (sel.any { StorageLocation.fromStableKey(it) is StorageLocation.Saf }) vm.notify("Detailed multi-item properties are currently available for local files only")
                            else if (one != null) onProperties(one) else onDialog(Dlg.MultiProps)
                        }
                    }
                    MenuCell(Icons.Default.Share, "Share", Modifier.weight(1f)) {
                        go {
                            if (sel.any { StorageLocation.fromStableKey(it) is StorageLocation.Saf }) vm.notify("Sharing SAF items is not available from this menu yet")
                            else ExternalOps.share(ctx, sel.map(::File).filter { it.isFile })
                        }
                    }
                }
                Row {
                    MenuCell(Icons.Default.Check, "Open with…", Modifier.weight(1f), enabled = one != null && !one.isDir && StorageLocation.fromStableKey(one.path) !is StorageLocation.Saf) {
                        go { one?.let(onOpenWith) }
                    }
                    MenuCell(Icons.Default.Bookmark, "Add bookmark", Modifier.weight(1f)) {
                        go {
                            val dirs = sel.filter { byPath[it]?.isDir == true }.ifEmpty { listOf(if (s.activePane == 1) s.secondaryDir else s.dir) }
                            val added = dirs.count { SettingsStore.addBookmark(it) }
                            vm.notify(if (added > 0) "Bookmarked $added folder(s)" else "Already bookmarked")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MenuCell(
    icon: ImageVector, label: String, modifier: Modifier,
    enabled: Boolean = true, dot: Boolean = false, onLong: (() -> Unit)? = null, onClick: () -> Unit
) {
    val a = if (enabled) 1f else .38f
    Row(
        modifier.then(if (enabled) Modifier.combinedClickable(onClick = onClick, onLongClick = onLong) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = Fg.copy(alpha = .8f * a))
        Spacer(Modifier.width(14.dp))
        Text(label, color = Fg.copy(alpha = a), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
        if (dot) {
            Spacer(Modifier.width(6.dp))
            Box(Modifier.size(5.dp).background(Accent, CircleShape))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Dialogs used only by the dual-pane flow
// ---------------------------------------------------------------------------------------------

/** "Tools" entry of the action menu: a short list of extra operations for the current selection. */
@Composable
internal fun ToolsDialog(actions: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) {
    AlertDialog(onDismiss, title = { Text("Tools") }, text = {
        Column {
            actions.forEach { (label, action) ->
                TextButton({ onDismiss(); action() }, Modifier.fillMaxWidth()) {
                    Text(label, Modifier.fillMaxWidth())
                }
            }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onDismiss) { Text("Close") } })
}

/** Properties for several selected items: counts and total size, computed off the main thread. */
@Composable
internal fun MultiPropertiesDialog(paths: List<String>, onDismiss: () -> Unit) {
    var info by remember(paths) { mutableStateOf<Triple<Int, Int, Long>?>(null) }
    LaunchedEffect(paths) {
        info = withContext(Dispatchers.IO) {
            var folders = 0; var files = 0; var size = 0L
            for (path in paths) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val f = File(path)
                if (f.isDirectory) {
                    folders++
                    val (bytes, count) = FileOps.sizeAndCountCancellable(f)
                    size += bytes
                    files += count
                } else if (f.isFile) {
                    files++
                    size += f.length()
                }
            }
            Triple(folders, files, size)
        }
    }
    AlertDialog(onDismiss, title = { Text("Properties") }, text = {
        Text(
            info?.let { "Selected: ${paths.size}\nFolders: ${it.first}\nFiles / entries inside: ${it.second}\nTotal size: ${dxSize(it.third)}" }
                ?: "Calculating…"
        )
    }, confirmButton = { TextButton(onDismiss) { Text("OK") } })
}
