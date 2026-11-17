package com.kayx.manager.feature.filebrowser
import androidx.compose.material.icons.automirrored.filled.OpenInNew

import androidx.compose.foundation.background
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
    val file = remember(f.path) { File(f.path) }
    var info by remember { mutableStateOf<Pair<Long, Int>?>(null) }
    var mode by remember { mutableStateOf(FileOps.modeOf(file)) }
    var recursive by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(f.path) { info = withContext(Dispatchers.IO) { FileOps.sizeAndCount(file) } }
    AlertDialog(onClose, title = { Text("Properties") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(f.name, style = MaterialTheme.typography.titleSmall)
            Text("Path: ${f.path}", style = MaterialTheme.typography.bodySmall)
            Text("Type: ${if (f.isDir) "Folder" else f.name.substringAfterLast('.', "File").uppercase()}")
            Text("Size: " + (info?.let { size(it.first) + if (f.isDir) "  (${it.second} items)" else "" } ?: "calculating…"))
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

private class OpenTile(val label: String, val icon: ImageVector, val color: Color, val relevant: Boolean, val action: () -> Unit)

@Composable
private fun TileView(t: OpenTile, modifier: Modifier, onDone: () -> Unit) {
    Column(modifier.clickable { onDone(); t.action() }.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(54.dp).background(t.color, RoundedCornerShape(14.dp)), Alignment.Center) {
            Icon(t.icon, null, Modifier.size(28.dp), tint = Color.White)
        }
        Text(t.label, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2)
    }
}

/** MT-style "Open with…" grid: Built-in tools, file Actions, and System apps. */
@Composable
internal fun OpenWithDialog(
    f: FileItem, vm: FileBrowserViewModel, onOpenFile: (String, Tool) -> Unit, onProperties: () -> Unit, onDismiss: () -> Unit
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
    val builtin = buildList {
        add(OpenTile("Text editor", Icons.Default.Description, blue, ext in setOf("txt", "smali", "json", "js", "css", "java", "kt", "md", "yml", "yaml", "properties", "gradle", "ini", "conf", "log", "csv", "sh") || web || xml, o(Tool.TEXT)))
        add(OpenTile("Archive viewer", Icons.Default.Archive, brown, zip, o(Tool.ZIP)))
        add(OpenTile("Image viewer", Icons.Default.Image, teal, image, o(Tool.IMAGE)))
        add(OpenTile("Hex editor", Icons.Default.Code, slate, false, o(Tool.HEX)))
        add(OpenTile("Music player", Icons.Default.MusicNote, red, audio, o(Tool.AUDIO)))
        add(OpenTile("Video player", Icons.Default.Movie, orange, video, o(Tool.VIDEO)))
        add(OpenTile("Xml functions", Icons.Default.Code, blue, xml, o(Tool.AXML)))
        add(OpenTile("Dex functions", Icons.Default.Memory, teal, ext == "dex", o(Tool.DEX)))
        add(OpenTile("Resources (arsc)", Icons.Default.ListAlt, teal, ext == "arsc" || isApk, o(Tool.ARSC)))
        add(OpenTile("Apk viewer", Icons.Default.Android, green, isApk, o(Tool.APK)))
        add(OpenTile("Script executor", Icons.Default.Terminal, blue, script, o(Tool.TERMINAL)))
        add(OpenTile("Font viewer", Icons.Default.TextFields, purple, font, o(Tool.FONT)))
        add(OpenTile("Web functions", Icons.Default.Public, water, web, o(Tool.HTML)))
        add(OpenTile("Jar functions", Icons.Default.FolderZip, brown, ext == "jar", o(Tool.ZIP)))
        add(OpenTile("Checksums", Icons.Default.Fingerprint, slate, false, o(Tool.CHECKSUM)))
    }.sortedByDescending { it.relevant }
    val actions = buildList {
        if (isApk) {
            add(OpenTile("Install APK", Icons.Default.InstallMobile, green, true) { ExternalOps.installApk(ctx, File(p)) })
            add(OpenTile("Sign APK", Icons.Default.VerifiedUser, water, true) { vm.signApk(ctx, p) })
            add(OpenTile("Zipalign", Icons.Default.FormatAlignLeft, slate, true) { vm.alignZip(p) })
        }
        if (MultiExtract.canExtract(n)) add(OpenTile("Extract here", Icons.Default.Unarchive, brown, true) { vm.extractHere(p) })
        add(OpenTile("Share", Icons.Default.Share, blue, false) { ExternalOps.share(ctx, listOf(File(p))) })
        add(OpenTile("Copy path", Icons.Default.ContentCopy, slate, false) { ExternalOps.copyText(ctx, p); vm.notify("Path copied") })
        add(OpenTile("Properties", Icons.Default.Info, teal, false, onProperties))
    }
    val system = listOf(
        OpenTile("Open with…", Icons.AutoMirrored.Filled.OpenInNew, blue, true) { ExternalOps.openWith(ctx, File(p)) },
        OpenTile("Share", Icons.Default.Share, green, false) { ExternalOps.share(ctx, listOf(File(p))) }
    )
    var tab by remember { mutableStateOf(0) }
    AlertDialog(onDismiss, title = { Text("Open with…") }, text = {
        Column {
            Row {
                listOf("Built-in", "Actions", "System").forEachIndexed { i, l ->
                    TextButton({ tab = i }) { Text(l, color = if (tab == i) Accent else Fg.copy(.6f)) }
                }
            }
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                (when (tab) { 0 -> builtin; 1 -> actions; else -> system }).chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { TileView(it, Modifier.weight(1f), onDismiss) }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onDismiss) { Text("Close") } })
}
