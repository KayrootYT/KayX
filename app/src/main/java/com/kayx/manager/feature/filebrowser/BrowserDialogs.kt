package com.kayx.manager.feature.filebrowser
import androidx.compose.material.icons.automirrored.filled.OpenInNew

import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import com.kayx.manager.ui.theme.LocalPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import android.content.Context
import android.content.pm.ResolveInfo
import android.widget.Toast
import com.kayx.manager.data.settings.SettingsStore
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import com.kayx.manager.data.archive.MultiExtract
import com.kayx.manager.data.filesystem.ExternalOps
import com.kayx.manager.domain.model.Tool
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kayx.manager.data.filesystem.Conflict
import com.kayx.manager.data.filesystem.FileOps
import com.kayx.manager.data.filesystem.StorageLocation
import com.kayx.manager.domain.model.FileItem
import com.kayx.manager.ui.theme.Accent
import com.kayx.manager.ui.theme.Fg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

private fun size(b: Long): String {
    if (b < 1024) return "$b B"
    val u = arrayOf("KB", "MB", "GB", "TB"); var v = b / 1024.0; var i = 0
    while (v >= 1024 && i < u.lastIndex) { v /= 1024; i++ }
    return "%.1f %s".format(v, u[i])
}

/** Tappable path segments (MT-style). */
@Composable
internal fun BreadcrumbBar(path: String, onGo: (String) -> Unit) {
    val parts = path.split('/').filter { it.isNotEmpty() }
    val scroll = rememberScrollState()
    LaunchedEffect(path, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    Row(Modifier.horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
        Text("/", Modifier.clickable { onGo("/") }.padding(horizontal = 3.dp), style = MaterialTheme.typography.bodySmall, color = Fg.copy(.6f))
        parts.forEachIndexed { i, p ->
            Text(p, Modifier.clickable { onGo("/" + parts.take(i + 1).joinToString("/")) }.padding(horizontal = 3.dp),
                maxLines = 1, style = MaterialTheme.typography.bodySmall, color = if (i == parts.lastIndex) Accent else Fg.copy(.6f))
            if (i < parts.lastIndex) Text("›", style = MaterialTheme.typography.bodySmall, color = Fg.copy(.4f))
        }
    }
}

@Composable
internal fun PropertiesDialog(f: FileItem, onClose: () -> Unit, onChanged: () -> Unit) {
    val safBacked = StorageLocation.fromStableKey(f.path) is StorageLocation.Saf
    val file = remember(f.path) { File(f.path) }
    var info by remember(f.path) { mutableStateOf<Pair<Long, Int>?>(if (safBacked) f.size to 0 else null) }
    var mode by remember(f.path) { mutableStateOf(if (safBacked) null else FileOps.modeOf(file)) }
    var recursive by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(f.path) {
        if (!safBacked) info = FileOps.sizeAndCountCancellable(file)
    }
    AlertDialog(onClose, title = { Text("Properties") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(f.name, style = MaterialTheme.typography.titleSmall)
            Text("Path: " + if (safBacked) "SAF storage · " + (StorageLocation.fromStableKey(f.path) as StorageLocation.Saf).relativePath else f.path, style = MaterialTheme.typography.bodySmall)
            Text("Type: ${if (f.isDir) "Folder" else f.name.substringAfterLast('.', "File").uppercase()}")
            Text("Size: " + if (safBacked && f.isDir) "Folder size not calculated" else info?.let { size(it.first) + if (f.isDir) "  (${it.second} items)" else "" } ?: "calculating…")
            Text("Modified: ${DateFormat.getDateTimeInstance().format(Date(f.modified))}")
            val m = mode
            if (m != null) {
                Text("Permissions: ${FileOps.modeString(m)} (%04o)".format(m), color = Accent)
                listOf("Owner", "Group", "Others").forEachIndexed { row, label ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.width(62.dp), style = MaterialTheme.typography.bodySmall)
                        listOf("R" to 4, "W" to 2, "X" to 1).forEach { (l, v) ->
                            val bit = v shl ((2 - row) * 3)
                            Checkbox((m and bit) != 0, { _ ->
                                val nm = m xor bit
                                runCatching { FileOps.chmod(file, nm, recursive) }
                                    .onSuccess { mode = FileOps.modeOf(file); onChanged() }
                                    .onFailure { msg = it.message ?: "chmod failed" }
                            })
                            Text(l, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                }
                if (f.isDir) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(recursive, { recursive = it }); Text("Apply recursively", style = MaterialTheme.typography.bodySmall)
                }
            }
            msg?.let { Text(it, color = Color(0xFFFF6B6B), style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { TextButton(onClose) { Text("Close") } })
}

@Composable
internal fun BatchRenameDialog(count: Int, onDismiss: () -> Unit, onOk: (String, String, Boolean, Int) -> Unit) {
    var find by remember { mutableStateOf("") }
    var rep by remember { mutableStateOf("") }
    var regex by remember { mutableStateOf(false) }
    var start by remember { mutableStateOf("1") }
    AlertDialog(onDismiss, title = { Text("Batch rename ($count)") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(find, { find = it }, singleLine = true, label = { Text("Find (empty = use template)") })
            OutlinedTextField(rep, { rep = it }, singleLine = true, label = { Text("Replace / template") })
            Text("Tokens: {n} counter, {name} base name, {ext} extension", style = MaterialTheme.typography.labelSmall, color = Fg.copy(.6f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(regex, { regex = it }); Text("Regex")
                Spacer(Modifier.width(12.dp))
                OutlinedTextField(start, { start = it.filter(Char::isDigit) }, Modifier.width(100.dp), singleLine = true, label = { Text("Start #") })
            }
        }
    }, confirmButton = { TextButton({ onOk(find, rep, regex, start.toIntOrNull() ?: 1) }) { Text("Rename") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable
internal fun ConflictDialog(names: List<String>, onPick: (Conflict) -> Unit, onCancel: () -> Unit) {
    AlertDialog(onCancel, title = { Text("${names.size} item(s) already exist") }, text = {
        Column {
            Text(names.take(5).joinToString("\n") + if (names.size > 5) "\n…" else "", style = MaterialTheme.typography.bodySmall)
            TextButton({ onPick(Conflict.REPLACE) }, Modifier.fillMaxWidth()) { Text("Replace", color = Color(0xFFFF6B6B)) }
            TextButton({ onPick(Conflict.KEEP_BOTH) }, Modifier.fillMaxWidth()) { Text("Keep both (rename)") }
            TextButton({ onPick(Conflict.SKIP) }, Modifier.fillMaxWidth()) { Text("Skip existing") }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onCancel) { Text("Cancel") } })
}


/** Extensions that count as "an HTML file" for the tap action / Web functions. */
internal val WEB_TAP_EXTENSIONS = setOf("html", "htm", "xhtml")

/**
 * Opens [f] with the default chosen for its extension (long-press an icon in "Open with…").
 * Returns false when no usable default exists, so the caller can fall back to its normal behaviour.
 */
internal fun openByDefault(ctx: Context, f: FileItem, onOpenFile: (String, Tool) -> Unit): Boolean {
    val ext = f.name.substringAfterLast('.', "").lowercase()
    if (ext.isEmpty()) return false
    val d = SettingsStore.openDefaults[ext] ?: return false
    if (d.startsWith("b:")) {
        val t = runCatching { Tool.valueOf(d.substring(2)) }.getOrNull() ?: return false
        onOpenFile(f.path, t)
        return true
    }
    if (d.startsWith("a:")) {
        val pc = d.substring(2)
        val pkg = pc.substringBefore('/'); val cls = pc.substringAfter('/', "")
        if (cls.isEmpty() || runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.isFailure) {
            SettingsStore.setDefaultOpen(ext, null) // app was uninstalled
            return false
        }
        return ExternalOps.openWithComponent(ctx, File(f.path), pkg, cls)
    }
    return false
}

// ---------------------------------------------------------------------------------------------
// Long-press popup for one file / folder (MT-Manager style): no selection is started by a long press.
// ---------------------------------------------------------------------------------------------

internal class MenuEntry(val label: String, val icon: ImageVector, val danger: Boolean = false, val action: () -> Unit)

@Composable
internal fun ItemContextMenu(f: FileItem, entries: List<MenuEntry>, onDismiss: () -> Unit) {
    val pal = LocalPalette.current
    val scope = rememberCoroutineScope()
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { show = true }
    // pop-in / pop-out spring (slight overshoot = iOS feel)
    val t by animateFloatAsState(
        if (show) 1f else 0f,
        if (show) spring(dampingRatio = 0.66f, stiffness = 420f) else tween(120),
        label = "ctx-pop"
    )
    fun close(after: (() -> Unit)? = null) {
        if (!show) return
        show = false
        scope.launch { delay(130); onDismiss(); after?.invoke() }
    }

    Dialog(
        onDismissRequest = { close() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // real background blur behind the dialog (Android 12+), harmless no-op below that
        val view = LocalView.current
        SideEffect {
            val w = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            w.setDimAmount(0.30f)
            if (Build.VERSION.SDK_INT >= 31) {
                w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                w.attributes = w.attributes.apply { blurBehindRadius = 30 }
            }
        }

        val shape = RoundedCornerShape(26.dp)
        val dark = pal.dark
        val accent = pal.accent
        val fg = pal.fg
        val panelFill = if (dark)
            Brush.verticalGradient(listOf(Color(0xFF2A3850).copy(.72f), Color(0xFF141C2B).copy(.80f)))
        else
            Brush.verticalGradient(listOf(Color.White.copy(.82f), Color(0xFFF1F4FA).copy(.74f)))
        val panelEdge = Brush.linearGradient(
            listOf(Color.White.copy(if (dark) .55f else .95f), accent.copy(.16f), Color.White.copy(.07f), accent.copy(.26f))
        )
        val sheen = Color.White.copy(if (dark) .16f else .55f)

        Column(
            Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 430.dp)
                .graphicsLayer {
                    val sc = 0.86f + 0.14f * t
                    scaleX = sc; scaleY = sc
                    alpha = t.coerceIn(0f, 1f)
                    translationY = (1f - t) * 18.dp.toPx()
                }
                .background(panelFill, shape)
                .border(1.dp, panelEdge, shape)
                .drawBehind {
                    // liquid-glass specular sheen: bright pool top-left, soft fade
                    drawRect(
                        Brush.radialGradient(
                            listOf(sheen, Color.Transparent),
                            center = Offset(size.width * .12f, 0f), radius = size.width * .75f
                        )
                    )
                }
                .padding(horizontal = 14.dp, vertical = 14.dp)
        ) {
            // header
            Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis, color = fg, style = MaterialTheme.typography.titleMedium)
                Text(
                    (if (f.isDir) "Folder" else size(f.size)) + "  ·  " +
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(f.modified)),
                    style = MaterialTheme.typography.labelSmall, color = fg.copy(.55f), maxLines = 1
                )
            }
            Spacer(Modifier.height(8.dp))
            // MT-Manager style 2-column grid
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                entries.chunked(2).forEachIndexed { r, pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        pair.forEachIndexed { c, e ->
                            GlassMenuCell(e, r * 2 + c, dark, accent, fg, Modifier.weight(1f)) { close(e.action) }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassMenuCell(
    e: MenuEntry, index: Int, dark: Boolean, accent: Color, fg: Color, modifier: Modifier, onClick: () -> Unit
) {
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(24L * index); enter.animateTo(1f, tween(240)) }
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 1f else 0f, spring(stiffness = 700f), label = "cell-press")
    val danger = Color(0xFFFF6B6B)
    val tint = if (e.danger) danger else fg
    val iconTint = if (e.danger) danger else accent
    val shape = RoundedCornerShape(16.dp)
    val idle = Color.White.copy(if (dark) .07f else .55f)
    val hot = (if (e.danger) danger else accent).copy(.28f)
    val rim = Color.White.copy(if (dark) .14f else .70f)
    Row(
        modifier
            .graphicsLayer {
                alpha = enter.value
                translationY = (1f - enter.value) * 14.dp.toPx()
                val sc = 1f - 0.05f * press
                scaleX = sc; scaleY = sc
            }
            .background(androidx.compose.ui.graphics.lerp(idle, hot, press), shape)
            .border(0.8.dp, rim, shape)
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(e.icon, null, Modifier.size(22.dp), tint = iconTint)
        Spacer(Modifier.width(10.dp))
        Text(e.label, color = tint, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------------------------------------
// Web functions
// ---------------------------------------------------------------------------------------------

/** MT-style "Web functions": pick the website root directory, see the preview path, choose the tap action, EDIT / PREVIEW. */
@Composable
internal fun WebFunctionsDialog(
    path: String, onEdit: () -> Unit, onPreview: (String) -> Unit, onMore: (() -> Unit)?, onDismiss: () -> Unit
) {
    val file = remember(path) { File(path) }
    val storage = remember { android.os.Environment.getExternalStorageDirectory().absolutePath }
    val parent = file.parent ?: "/"
    val segs = remember(path) {
        val base = if (parent == storage || parent.startsWith("$storage/")) storage else "/"
        buildList {
            add((if (base == "/") "Root Directory" else "Storage Directory") to base)
            var cur = base.trimEnd('/')
            parent.removePrefix(base).split('/').filter { it.isNotEmpty() }.forEach { cur = "$cur/$it"; add(it to cur) }
        }
    }
    var rootIdx by remember(path) { mutableStateOf(segs.lastIndex) }
    val root = segs[rootIdx].second
    val previewPath = "/" + file.absolutePath.removePrefix(root).trimStart('/')
    var menu by remember { mutableStateOf(false) }
    val tapLabels = listOf("Ask every time", "Edit", "Preview")
    AlertDialog(
        onDismiss, title = { Text("Web functions") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Select the website root directory", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    segs.forEachIndexed { i, (name, _) ->
                        if (i > 0) Text("/", color = Fg.copy(.45f), modifier = Modifier.padding(horizontal = 2.dp))
                        Surface(
                            onClick = { rootIdx = i }, shape = RoundedCornerShape(9.dp),
                            color = if (i == rootIdx) Accent.copy(.22f) else Color.Transparent
                        ) { Text(name, Modifier.padding(horizontal = 10.dp, vertical = 8.dp), maxLines = 1, softWrap = false) }
                    }
                }
                Column {
                    Text("Preview path", style = MaterialTheme.typography.labelMedium, color = Fg.copy(.55f))
                    Text(previewPath, style = MaterialTheme.typography.titleMedium)
                }
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("When tapping an HTML file", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Box {
                        TextButton({ menu = true }) {
                            Text(tapLabels[SettingsStore.htmlTapAction])
                            Icon(Icons.Default.ArrowDropDown, null)
                        }
                        DropdownMenu(menu, { menu = false }) {
                            tapLabels.forEachIndexed { i, l ->
                                DropdownMenuItem({ Text(l + if (SettingsStore.htmlTapAction == i) "  ✓" else "") }, { SettingsStore.updateHtmlTapAction(i); menu = false })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                if (onMore != null) TextButton(onMore) { Text("MORE") }
                Spacer(Modifier.weight(1f))
                TextButton(onEdit) { Text("EDIT") }
                TextButton({ onPreview(root) }) { Text("PREVIEW") }
            }
        }
    )
}

// ---------------------------------------------------------------------------------------------
// Open with…
// ---------------------------------------------------------------------------------------------

private class OpenTile(
    val label: String, val icon: ImageVector?, val color: Color, val relevant: Boolean,
    val key: String? = null, val ri: ResolveInfo? = null, val action: () -> Unit
)

@Composable
private fun ResolvedIcon(ri: ResolveInfo, modifier: Modifier) {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, ri) {
        value = withContext(Dispatchers.IO) { runCatching { ri.loadIcon(ctx.packageManager).toBitmap(108, 108).asImageBitmap() }.getOrNull() }
    }
    val b = bmp
    if (b != null) androidx.compose.foundation.Image(b, null, modifier)
    else Box(modifier.background(Fg.copy(.1f), RoundedCornerShape(14.dp)))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TileView(t: OpenTile, isDefault: Boolean, modifier: Modifier, onClick: () -> Unit, onLong: () -> Unit) {
    Column(
        modifier.combinedClickable(onClick = onClick, onLongClick = onLong).padding(vertical = 6.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(54.dp), Alignment.Center) {
            if (t.ri != null) ResolvedIcon(t.ri, Modifier.fillMaxSize())
            else Box(Modifier.fillMaxSize().background(t.color, RoundedCornerShape(14.dp)), Alignment.Center) {
                t.icon?.let { Icon(it, null, Modifier.size(28.dp), tint = Color.White) }
            }
            if (isDefault) Icon(Icons.Default.Star, "Default", Modifier.align(Alignment.TopEnd).size(17.dp), tint = Color(0xFFFFC107))
        }
        Text(t.label, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private val OPEN_TABS = listOf("Built-in", "System suggestion", "Text", "Image", "Video", "Audio", "All", "Actions")

/**
 * MT-style "Open with…": Built-in tools, System suggestion (apps for this file's type), Text / Image / Video / Audio / All app lists
 * and file Actions. Search, a help page, and long-press on an icon to make it the default for that file extension.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun OpenWithDialog(
    f: FileItem, vm: FileBrowserViewModel, onOpenFile: (String, Tool) -> Unit, onProperties: () -> Unit,
    onWeb: () -> Unit, onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val n = f.name.lowercase()
    val ext = n.substringAfterLast('.', "")
    val isApk = ext == "apk"
    val audio = ext in setOf("mp3", "m4a", "aac", "wav", "ogg", "oga", "opus", "flac", "amr", "mid", "midi", "wma")
    val video = ext in setOf("mp4", "mkv", "webm", "3gp", "avi", "mov", "m4v", "ts", "flv")
    val image = ext in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic")
    val font = ext in setOf("ttf", "otf", "ttc")
    val web = ext in setOf("html", "htm", "xhtml", "svg")
    val zip = ext in setOf("zip", "apk", "jar", "aar", "xapk", "apks", "apkm", "dex")
    val script = ext in setOf("sh", "bash")
    val xml = ext == "xml"
    val p = f.path
    fun o(t: Tool) = { onOpenFile(p, t) }
    val blue = Color(0xFF3949AB); val teal = Color(0xFF00838F); val brown = Color(0xFF795548); val red = Color(0xFFB71C1C)
    val orange = Color(0xFFD35400); val green = Color(0xFF2E7D32); val slate = Color(0xFF546E7A); val purple = Color(0xFF5E35B1); val water = Color(0xFF0277BD)
    fun bt(label: String, icon: ImageVector, color: Color, rel: Boolean, t: Tool) = OpenTile(label, icon, color, rel, key = "b:" + t.name, action = o(t))
    val builtin = buildList {
        add(bt("Text editor", Icons.Default.Description, blue, ext in setOf("txt", "smali", "json", "js", "css", "java", "kt", "md", "yml", "yaml", "properties", "gradle", "ini", "conf", "log", "csv", "sh") || web || xml, Tool.TEXT))
        add(bt("Archive viewer", Icons.Default.Archive, brown, zip, Tool.ZIP))
        add(bt("Image viewer", Icons.Default.Image, teal, image, Tool.IMAGE))
        add(bt("Hex editor", Icons.Default.Code, slate, false, Tool.HEX))
        add(bt("Music player", Icons.Default.MusicNote, red, audio, Tool.AUDIO))
        add(bt("Video player", Icons.Default.Movie, orange, video, Tool.VIDEO))
        add(bt("Xml functions", Icons.Default.Code, blue, xml, Tool.AXML))
        add(bt("Dex functions", Icons.Default.Memory, teal, ext == "dex", Tool.DEX))
        add(bt("Arsc functions", Icons.Default.ListAlt, teal, ext == "arsc" || isApk, Tool.ARSC))
        add(bt("Apk viewer", Icons.Default.Android, green, isApk, Tool.APK))
        add(bt("Script executor", Icons.Default.Terminal, blue, script, Tool.TERMINAL))
        add(bt("Font viewer", Icons.Default.TextFields, purple, font, Tool.FONT))
        add(OpenTile("Web functions", Icons.Default.Public, water, web, action = onWeb))
        add(bt("Jar functions", Icons.Default.FolderZip, brown, ext == "jar", Tool.ZIP))
        add(bt("Checksums", Icons.Default.Fingerprint, slate, false, Tool.CHECKSUM))
    }.sortedByDescending { it.relevant }
    val actions = buildList {
        if (isApk) {
            add(OpenTile("Install APK", Icons.Default.InstallMobile, green, true) { ExternalOps.installApk(ctx, File(p)) })
            add(OpenTile("Sign APK", Icons.Default.VerifiedUser, water, true) { vm.signApk(ctx, p) })
            add(OpenTile("Zipalign", Icons.Default.FormatAlignLeft, slate, true) { vm.alignZip(p) })
        }
        if (MultiExtract.canExtract(n)) add(OpenTile("Extract here", Icons.Default.Unarchive, brown, true) { vm.extractHere(p) })
        add(OpenTile("System chooser", Icons.AutoMirrored.Filled.OpenInNew, blue, true) { ExternalOps.openWith(ctx, File(p)) })
        add(OpenTile("Share", Icons.Default.Share, green, false) { ExternalOps.share(ctx, listOf(File(p))) })
        add(OpenTile("Copy path", Icons.Default.ContentCopy, slate, false) { ExternalOps.copyText(ctx, p); vm.notify("Path copied") })
        add(OpenTile("Properties", Icons.Default.Info, teal, false, action = onProperties))
    }

    var tab by remember { mutableStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    var q by remember { mutableStateOf("") }
    var help by remember { mutableStateOf(false) }
    var manage by remember { mutableStateOf(false) }

    val mimeForTab = when (tab) {
        1 -> ExternalOps.mime(File(p)); 2 -> "text/plain"; 3 -> "image/*"; 4 -> "video/*"; 5 -> "audio/*"; 6 -> "*/*"; else -> ""
    }
    val appTiles by produceState<List<OpenTile>?>(null, mimeForTab) {
        value = if (mimeForTab.isEmpty()) emptyList() else withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            val hint = if (mimeForTab == "text/plain") "text/plain" else null
            ExternalOps.appsFor(ctx, mimeForTab).map { ri ->
                val pkg = ri.activityInfo.packageName; val cls = ri.activityInfo.name
                OpenTile(ri.loadLabel(pm).toString(), null, Color.Gray, true, key = "a:$pkg/$cls", ri = ri) {
                    ExternalOps.openWithComponent(ctx, File(p), pkg, cls, hint)
                }
            }
        }
    }
    val tiles = (when (tab) { 0 -> builtin; 7 -> actions; else -> appTiles.orEmpty() }).filter { q.isBlank() || it.label.contains(q, true) }
    val currentDefault = SettingsStore.openDefaults[ext]

    AlertDialog(
        onDismiss,
        modifier = Modifier.fillMaxWidth(0.94f).padding(vertical = 24.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Open with…", Modifier.weight(1f))
                IconButton({ searching = !searching; if (!searching) q = "" }) { Icon(if (searching) Icons.Default.Close else Icons.Default.Search, "Search") }
                IconButton({ help = true }) { Icon(Icons.Default.HelpOutline, "Help") }
            }
        },
        text = {
            Column {
                if (searching) OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(bottom = 4.dp), singleLine = true, placeholder = { Text("Search") })
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    val accentColor = Accent
                    OPEN_TABS.forEachIndexed { i, name ->
                        val sel = tab == i
                        Text(
                            name, maxLines = 1, softWrap = false,
                            modifier = Modifier.clickable { tab = i }
                                .drawBehind { if (sel) drawRect(accentColor, Offset(0f, size.height - 2.dp.toPx()), Size(size.width, 2.dp.toPx())) }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            color = if (sel) Fg else Fg.copy(.55f), style = MaterialTheme.typography.titleSmall
                        )
                    }
                }
                HorizontalDivider()
                Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()).padding(top = 6.dp)) {
                    when {
                        tab in 1..6 && appTiles == null -> Box(Modifier.fillMaxWidth().padding(30.dp), Alignment.Center) { CircularProgressIndicator(color = Accent) }
                        tiles.isEmpty() -> Box(Modifier.fillMaxWidth().padding(30.dp), Alignment.Center) { Text("Nothing found", color = Fg.copy(.55f)) }
                        else -> tiles.chunked(3).forEach { row ->
                            Row(Modifier.fillMaxWidth()) {
                                row.forEach { t ->
                                    TileView(
                                        t, t.key != null && t.key == currentDefault, Modifier.weight(1f),
                                        onClick = { onDismiss(); t.action() },
                                        onLong = {
                                            val k = t.key
                                            if (ext.isEmpty()) Toast.makeText(ctx, "This file has no extension", Toast.LENGTH_SHORT).show()
                                            else if (k == null) Toast.makeText(ctx, "Cannot be set as default", Toast.LENGTH_SHORT).show()
                                            else {
                                                SettingsStore.setDefaultOpen(ext, k)
                                                Toast.makeText(ctx, "Default for .$ext: ${t.label}", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    )
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {}, dismissButton = { TextButton(onDismiss) { Text("CLOSE") } }
    )

    if (help) AlertDialog(
        { help = false }, title = { Text("Help") },
        text = { Text("Long press the icon to set it as the default opening method of the file type, next time you can directly click the file to open it.") },
        confirmButton = { TextButton({ help = false; manage = true }) { Text("MANAGE") } },
        dismissButton = { TextButton({ help = false }) { Text("CLOSE") } }
    )
    if (manage) AlertDialog(
        { manage = false }, title = { Text("Default opening methods") },
        text = {
            val all = SettingsStore.openDefaults.entries.sortedBy { it.key }
            if (all.isEmpty()) Text("No defaults set.", color = Fg.copy(.6f))
            else Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                all.forEach { (e, v) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(".$e", style = MaterialTheme.typography.titleSmall)
                            Text(if (v.startsWith("b:")) "Built-in · " + v.substring(2) else v.substring(2).substringBefore('/'), style = MaterialTheme.typography.labelSmall, color = Fg.copy(.55f), maxLines = 1)
                        }
                        IconButton({ SettingsStore.setDefaultOpen(e, null) }) { Icon(Icons.Default.Close, "Remove default") }
                    }
                }
            }
        },
        confirmButton = { TextButton({ manage = false }) { Text("CLOSE") } }
    )
}
