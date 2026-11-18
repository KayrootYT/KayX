package com.kayx.manager.feature.tools
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import com.kayx.manager.data.settings.SettingsStore
import com.kayx.manager.ui.theme.Fg
import androidx.compose.ui.platform.LocalContext

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.net.Uri
import androidx.compose.ui.text.input.VisualTransformation
import com.kayx.manager.ui.editor.SyntaxLang
import com.kayx.manager.ui.editor.SyntaxTransformation
import com.kayx.manager.ui.editor.SyntaxColors
import com.kayx.manager.ui.theme.LocalPalette
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.viewinterop.AndroidView
import com.kayx.manager.data.archive.ArchiveRepository
import com.kayx.manager.data.archive.ZipItem
import com.kayx.manager.data.checksum.ChecksumRepository
import com.kayx.manager.data.checksum.Hashes
import com.kayx.manager.ui.glass.GlassCard
import com.kayx.manager.ui.glass.glassBackground
import com.kayx.manager.ui.theme.Accent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.kayx.manager.data.apk.ApkSignRepo
import com.kayx.manager.data.archive.ArchiveEdit
import com.kayx.manager.data.archive.ZIP_SEP
import com.kayx.manager.data.filesystem.ExternalOps

private fun fmt(b: Long): String {
    if (b < 1024) return "$b B"
    val u = arrayOf("KB", "MB", "GB", "TB"); var v = b / 1024.0; var i = 0
    while (v >= 1024 && i < u.lastIndex) { v /= 1024; i++ }
    return "%.1f %s".format(v, u[i])
}

@Composable
fun ToolScaffold(
    title: String, onBack: () -> Unit, snack: SnackbarHostState? = null,
    actions: @Composable RowScope.() -> Unit = {}, bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    BackHandler(onBack = onBack)
    Scaffold(
        modifier = Modifier.glassBackground(), containerColor = Color.Transparent,
        snackbarHost = { if (snack != null) SnackbarHost(snack) },
        bottomBar = bottomBar,
        topBar = {
            GlassCard(Modifier.statusBarsPadding().padding(12.dp).fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Text(title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    actions()
                }
            }
        }, content = content
    )
}

@Composable
private fun Center(pad: PaddingValues, text: String) =
    Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) { Text(text, color = Fg.copy(.6f)) }

// ---------------- Text editor ----------------
@Composable
fun TextEditorScreen(path: String, onBack: () -> Unit) {
    val file = remember(path) { File(path) }
    var original by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmExit by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(path) {
        val r = withContext(Dispatchers.IO) {
            runCatching {
                require(file.length() <= 2_000_000) { "File too large for the text editor (max 2 MB)" }
                val s = file.readText()
                require('\u0000' !in s.take(4096)) { "Looks like a binary file. Use the Hex viewer." }
                s
            }
        }
        r.onSuccess { original = it; text = it }.onFailure { error = it.message }
    }
    val palette = LocalPalette.current
    val lang = remember(file.name) { SyntaxLang.fromFileName(file.name) }
    val transform = remember(lang, palette.dark) {
        if (lang == SyntaxLang.PLAIN) VisualTransformation.None else SyntaxTransformation(lang, SyntaxColors.of(palette.dark))
    }
    val useHighlight = SettingsStore.syntaxHighlight && text.length <= 200_000
    val dirty = original != null && text != original
    val back = { if (dirty) confirmExit = true else onBack() }
    ToolScaffold(file.name + if (dirty) " •" else "", back, snack, actions = {
        if (original != null) IconButton(enabled = dirty, onClick = {
            scope.launch {
                val r = withContext(Dispatchers.IO) {
                    runCatching { if (SettingsStore.editorBackup) file.copyTo(File(file.parentFile, file.name + ".bak"), overwrite = true); file.writeText(text) }
                }
                if (r.isSuccess) original = text
                snack.showSnackbar(r.exceptionOrNull()?.message ?: (if (SettingsStore.editorBackup) "Saved (backup: ${file.name}.bak)" else "Saved"))
            }
        }) { Icon(Icons.Default.Save, null, tint = if (dirty) Accent else Fg.copy(.3f)) }
    }) { pad ->
        when {
            error != null -> Center(pad, error!!)
            original == null -> Center(pad, "Loading…")
            else -> TextField(
                text, { text = it }, Modifier.fillMaxSize().padding(pad).padding(horizontal = 8.dp),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = SettingsStore.editorFontSize.sp, color = Fg),
                visualTransformation = if (useHighlight) transform else VisualTransformation.None,
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent)
            )
        }
    }
    if (confirmExit) AlertDialog({ confirmExit = false }, title = { Text("Discard changes?") },
        confirmButton = { TextButton({ confirmExit = false; onBack() }) { Text("Discard", color = Color(0xFFFF6B6B)) } },
        dismissButton = { TextButton({ confirmExit = false }) { Text("Keep editing") } })
}

// ---------------- Hex viewer ----------------
@Composable
fun HexViewerScreen(path: String, onBack: () -> Unit) {
    val file = remember(path) { File(path) }
    val raf = remember(path) { runCatching { RandomAccessFile(file, "r") }.getOrNull() }
    DisposableEffect(path) { onDispose { runCatching { raf?.close() } } }
    ToolScaffold(file.name, onBack) { pad ->
        if (raf == null || file.length() == 0L) { Center(pad, if (raf == null) "Cannot open file" else "Empty file"); return@ToolScaffold }
        val per = SettingsStore.hexBytesPerRow
        val rows = ((file.length() + per - 1) / per).toInt()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, pad.calculateTopPadding() + 4.dp, 16.dp, 24.dp)) {
            items(rows) { i ->
                val buf = ByteArray(per)
                val n = runCatching { raf.seek(i * per.toLong()); raf.read(buf) }.getOrDefault(0).coerceAtLeast(0)
                val hex = (0 until per).joinToString(" ") { if (it < n) "%02X".format(buf[it]) else "  " }
                val ascii = (0 until n).map { val c = buf[it].toInt() and 0xFF; if (c in 32..126) c.toChar() else '.' }.joinToString("")
                Text("%08X  %s  %s".format(i * per.toLong(), hex, ascii), fontFamily = FontFamily.Monospace, fontSize = if (per > 8) 9.sp else 12.sp, color = Fg.copy(.9f))
            }
        }
    }
}

// ---------------- Image viewer ----------------
@Composable
fun ImageViewerScreen(path: String, onBack: () -> Unit) {
    var bmp by remember { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(path) {
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, o)
                var s = 1
                while (o.outWidth / s > 2048 || o.outHeight / s > 2048) s *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })!!.asImageBitmap()
            }
        }
        r.onSuccess { bmp = it }.onFailure { failed = true }
    }
    ToolScaffold(File(path).name, onBack) { pad ->
        val b = bmp
        when {
            failed -> Center(pad, "Cannot decode image")
            b == null -> Center(pad, "Loading…")
            else -> Image(b, null, Modifier.fillMaxSize().padding(pad)
                .pointerInput(Unit) { detectTransformGestures { _, pan, zoom, _ -> scale = (scale * zoom).coerceIn(1f, SettingsStore.maxZoom); offset = if (scale == 1f) Offset.Zero else offset + pan } }
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                contentScale = ContentScale.Fit)
        }
    }
}

// ---------------- Checksums ----------------
@Composable
fun ChecksumScreen(path: String, onBack: () -> Unit) {
    val file = remember(path) { File(path) }
    var h by remember { mutableStateOf<Hashes?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var expected by remember { mutableStateOf("") }
    val clip = LocalClipboardManager.current
    LaunchedEffect(path) {
        withContext(Dispatchers.IO) { runCatching { ChecksumRepository().compute(file) } }.onSuccess { h = it }.onFailure { error = it.message }
    }
    ToolScaffold(file.name, onBack) { pad ->
        val r = h
        when {
            error != null -> Center(pad, error!!)
            r == null -> Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) { CircularProgressIndicator(color = Accent) }
            else -> Column(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Size: ${fmt(file.length())}", color = Fg.copy(.7f))
                listOf("MD5" to r.md5, "SHA-1" to r.sha1, "SHA-256" to r.sha256).forEach { (n, v) ->
                    GlassCard(Modifier.fillMaxWidth(), onClick = { clip.setText(AnnotatedString(v)) }) {
                        Column(Modifier.padding(14.dp)) {
                            Text("$n  (tap to copy)", style = MaterialTheme.typography.labelMedium, color = Accent)
                            Text(v, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                    }
                }
                OutlinedTextField(expected, { expected = it }, Modifier.fillMaxWidth(), label = { Text("Paste expected hash to compare") }, singleLine = true)
                val e = expected.trim().lowercase()
                if (e.isNotEmpty()) {
                    val ok = e == r.md5 || e == r.sha1 || e == r.sha256
                    Text(if (ok) "✓ Match" else "✗ No match", color = if (ok) Color(0xFF6BFFA0) else Color(0xFFFF6B6B))
                }
            }
        }
    }
}

// ---------------- ZIP browser / extractor ----------------
@Composable
fun ZipScreen(path: String, onBack: () -> Unit, onOpenTool: (String, com.kayx.manager.domain.model.Tool) -> Unit = { _, _ -> }) {
    val file = remember(path) { File(path) }
    val repo = remember { ArchiveRepository() }
    var prefix by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<ZipItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var sel by remember { mutableStateOf(setOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var entryDialog by remember { mutableStateOf<ZipItem?>(null) }
    var renameFor by remember { mutableStateOf<ZipItem?>(null) }
    var zmenu by remember { mutableStateOf(false) }
    var pendingReplace by remember { mutableStateOf<String?>(null) }

    fun refresh() = scope.launch {
        busy = true
        val result = withContext(Dispatchers.IO) { runCatching { repo.list(file, prefix) } }
        result.onSuccess { items = it; error = null; sel = emptySet() }
            .onFailure { error = "Cannot read archive: ${it.message}" }
        busy = false
    }
    LaunchedEffect(path, prefix) { refresh() }

    fun extract(names: Set<String>?) = scope.launch {
        busy = true
        val r = withContext(Dispatchers.IO) { repo.extract(file, names) }
        busy = false
        snack.showSnackbar(r.fold({ "Extracted ${it.second} file(s) to ${it.first.name}" }, { it.message ?: "Extraction failed" }))
    }
    fun removeSelected() = scope.launch {
        busy = true
        val r = withContext(Dispatchers.IO) { repo.removeEntries(file, sel) }
        busy = false
        r.onSuccess { snack.showSnackbar("Removed $it archive entries"); refresh() }
            .onFailure { snack.showSnackbar(it.message ?: "Remove failed") }
    }

    fun runEdit(okMsg: String, block: () -> Result<Any?>) = scope.launch {
        busy = true
        val r = withContext(Dispatchers.IO) { block() }
        busy = false
        r.onSuccess { snack.showSnackbar(okMsg); refresh() }.onFailure { snack.showSnackbar(it.message ?: "Failed") }
    }
    val replacePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = pendingReplace; pendingReplace = null
        if (uri != null && target != null) runEdit("Replaced ${target.substringAfterLast('/')}") {
            runCatching { ArchiveEdit.rewrite(file, replace = mapOf(target to ExternalOps.readUri(ctx, uri))).getOrThrow() }
        }
    }
    val addPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) runEdit("Added ${uris.size} file(s)") {
            runCatching { ArchiveEdit.rewrite(file, add = uris.associate { (prefix + ExternalOps.uriName(ctx, it)) to ExternalOps.readUri(ctx, it) }).getOrThrow() }
        }
    }
    fun signOut() = com.kayx.manager.data.filesystem.FileOps.uniqueName(file.parentFile ?: file, file.nameWithoutExtension + "_signed.apk")

    ToolScaffold(file.name, onBack, snack, actions = {
        Box {
            IconButton(onClick = { zmenu = true }, enabled = !busy) { Icon(Icons.Default.MoreVert, "More") }
            DropdownMenu(zmenu, { zmenu = false }) {
                DropdownMenuItem({ Text("Add files here…") }, { zmenu = false; addPicker.launch(arrayOf("*/*")) })
                DropdownMenuItem({ Text("Zipalign (re-align archive)") }, { zmenu = false; runEdit("Aligned") { ArchiveEdit.rewrite(file) } })
                DropdownMenuItem({ Text("Sign APK (v1+v2+v3)") }, { zmenu = false; val o = signOut(); runEdit("Signed → ${o.name}") { ApkSignRepo.sign(ctx.applicationContext, file, o) } })
            }
        }
        IconButton(onClick = { refresh() }, enabled = !busy) { Icon(Icons.Default.Refresh, "Refresh") }
        IconButton(onClick = {
            scope.launch {
                busy = true
                val r = withContext(Dispatchers.IO) { repo.testIntegrity(file) }
                busy = false
                snack.showSnackbar(if (r.second == null) "Archive OK — ${r.first} file(s) verified" else "Problem after ${r.first} file(s): ${r.second}")
            }
        }, enabled = !busy) { Icon(Icons.Default.VerifiedUser, "Test integrity") }
        if (sel.isNotEmpty()) IconButton(onClick = { confirmRemove = true }, enabled = !busy) { Icon(Icons.Default.DeleteOutline, "Remove selected") }
    }, bottomBar = {
        if (items != null) GlassCard(Modifier.navigationBarsPadding().padding(12.dp).fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${sel.size} selected", Modifier.weight(1f), color = Fg.copy(.65f), style = MaterialTheme.typography.labelMedium)
                OutlinedButton({ extract(sel) }, enabled = sel.isNotEmpty() && !busy) { Text("Extract") }
                Button({ extract(null) }, enabled = !busy) { Text("Extract all") }
            }
        }
    }) { pad ->
        val list = items
        Column(Modifier.fillMaxSize().padding(pad)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { prefix = prefix.trimEnd('/').substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" } }, enabled = prefix.isNotEmpty()) {
                    Icon(Icons.Default.ArrowUpward, "Parent folder", tint = if (prefix.isNotEmpty()) Accent else Color.Gray)
                }
                Column(Modifier.weight(1f)) {
                    Text("/${prefix}", maxLines = 1, overflow = TextOverflow.Ellipsis, color = Accent, style = MaterialTheme.typography.labelMedium)
                    Text("${list?.size ?: 0} items · ${fmt(file.length())}", color = Fg.copy(.55f), style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { sel = if (sel.size == (list?.size ?: 0)) emptySet() else list.orEmpty().map { it.name }.toSet() }, enabled = !list.isNullOrEmpty()) { Text("Select all") }
            }
            when {
                error != null -> Center(PaddingValues(0.dp), error!!)
                list == null || busy -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = Accent) }
                list.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Empty folder", color = Fg.copy(.55f)) }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(list, key = { it.name }) { entry ->
                        val selected = entry.name in sel
                        GlassCard(Modifier.fillMaxWidth(), tint = if (selected) Accent else Fg, onClick = {
                            if (entry.isDir) prefix = entry.name else if (sel.isNotEmpty()) sel = if (selected) sel - entry.name else sel + entry.name else entryDialog = entry
                        }, onLongClick = { sel = if (selected) sel - entry.name else sel + entry.name }) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (selected) Icons.Default.CheckCircle else if (entry.isDir) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                                    null, tint = if (selected || entry.isDir) Accent else Fg.copy(.7f))
                                Spacer(Modifier.width(11.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(entry.name.removePrefix(prefix).trimEnd('/'), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                                    if (!entry.isDir) Text("${fmt(entry.size)}  ·  ${fmt(entry.compressed)} compressed", style = MaterialTheme.typography.labelSmall, color = Fg.copy(.52f))
                                    else Text("Folder", style = MaterialTheme.typography.labelSmall, color = Fg.copy(.52f))
                                }
                                if (entry.isDir) Icon(Icons.Default.ChevronRight, null, tint = Fg.copy(.45f))
                            }
                        }
                    }
                }
            }
        }
    }
    entryDialog?.let { e ->
        val n = e.name.lowercase()
        val acts = mutableListOf<Pair<String, () -> Unit>>()
        fun act(l: String, r: () -> Unit) { acts.add(l to r) }
        act("Edit as text") { onOpenTool(path + ZIP_SEP + e.name, com.kayx.manager.domain.model.Tool.ZIPTEXT) }
        if (n.endsWith(".xml")) act("Decoded XML (binary AXML)") { onOpenTool(path + ZIP_SEP + e.name, com.kayx.manager.domain.model.Tool.AXML) }
        fun viaCache(t: com.kayx.manager.domain.model.Tool) = scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { ArchiveEdit.extractToCache(ctx, file, e.name) } }
            r.onSuccess { onOpenTool(it.absolutePath, t) }.onFailure { snack.showSnackbar(it.message ?: "Cannot open") }
        }
        act("Hex view") { viaCache(com.kayx.manager.domain.model.Tool.HEX) }
        if (listOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp").any { n.endsWith(it) }) act("Image view") { viaCache(com.kayx.manager.domain.model.Tool.IMAGE) }
        act("Replace with file…") { pendingReplace = e.name; replacePicker.launch(arrayOf("*/*")) }
        act("Rename") { renameFor = e }
        act("Extract") { extract(setOf(e.name)) }
        AlertDialog({ entryDialog = null }, title = { Text(e.name.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = { Column { acts.forEach { (l, r) -> TextButton({ entryDialog = null; r() }, Modifier.fillMaxWidth()) { Text(l) } } } },
            confirmButton = {}, dismissButton = { TextButton({ entryDialog = null }) { Text("Cancel") } })
    }
    renameFor?.let { e ->
        var nn by remember(e.name) { mutableStateOf(e.name) }
        AlertDialog({ renameFor = null }, title = { Text("Rename entry") }, text = { OutlinedTextField(nn, { nn = it }, singleLine = true) },
            confirmButton = { TextButton({ renameFor = null; runEdit("Renamed") { ArchiveEdit.rewrite(file, rename = mapOf(e.name to nn.trim())) } }) { Text("OK") } },
            dismissButton = { TextButton({ renameFor = null }) { Text("Cancel") } })
    }
    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text("Remove from archive?") },
        text = { Text("${sel.size} selected item(s) will be removed from this ZIP. Original archive will be rewritten.") },
        confirmButton = { TextButton(onClick = { confirmRemove = false; removeSelected() }) { Text("Remove", color = Color(0xFFFF6B6B)) } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } }
    )
}


@Composable
private fun ApkComponentSection(title: String, components: List<com.kayx.manager.data.apk.ApkComponent>) {
    var expanded by remember { mutableStateOf(false) }
    GlassCard(Modifier.fillMaxWidth(), onClick = { expanded = !expanded }) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$title (${components.size})", Modifier.weight(1f), color = Accent)
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            if (expanded) {
                if (components.isEmpty()) Text("None", color = Fg.copy(.55f))
                components.forEach { component ->
                    HorizontalDivider(color = Fg.copy(.08f))
                    Text(component.name, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text("Exported: ${component.exported}  •  Enabled: ${component.enabled}" + (component.permission?.let { "  •  Permission: $it" } ?: ""), fontSize = 10.sp, color = Fg.copy(.6f))
                }
            }
        }
    }
}

// ---------------- APK Inspector ----------------
@Composable
fun ApkInspectorScreen(path: String, onBack: () -> Unit, onOpenDex: () -> Unit = {}) {
    val context = LocalContext.current
    val file = remember(path) { File(path) }
    val repo = remember(context) { com.kayx.manager.data.apk.ApkRepository(context) }
    var apkInfo by remember { mutableStateOf<com.kayx.manager.data.apk.ApkInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val clip = LocalClipboardManager.current

    LaunchedEffect(path) {
        withContext(Dispatchers.IO) { repo.getApkInfo(file) }
            .onSuccess { apkInfo = it }
            .onFailure { error = it.message ?: "Failed to inspect APK" }
    }

    ToolScaffold(file.name, onBack) { pad ->
        val info = apkInfo
        when {
            error != null -> Center(pad, error!!)
            info == null -> Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) { CircularProgressIndicator(color = Accent) }
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp, pad.calculateTopPadding() + 4.dp, 16.dp, 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(info.appName, style = MaterialTheme.typography.titleLarge, color = Accent)
                            Text(info.packageName, style = MaterialTheme.typography.bodyMedium, color = Fg.copy(.8f))
                            Text("Version: ${info.versionName} (${info.versionCode})", style = MaterialTheme.typography.bodySmall, color = Fg.copy(.6f))
                            Text("Size: ${fmt(info.size)}", style = MaterialTheme.typography.bodySmall, color = Fg.copy(.6f))
                        }
                    }
                }

                item {
                    Text("SDK Requirements", style = MaterialTheme.typography.titleSmall, color = Fg.copy(.7f))
                }

                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text("Minimum SDK", style = MaterialTheme.typography.labelMedium, color = Fg.copy(.6f))
                                Text("API ${info.minSdk}", style = MaterialTheme.typography.bodyLarge)
                            }
                            Column {
                                Text("Target SDK", style = MaterialTheme.typography.labelMedium, color = Fg.copy(.6f))
                                Text("API ${info.targetSdk}", style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }

                item {
                    Text("Manifest overview", style = MaterialTheme.typography.titleSmall, color = Fg.copy(.7f))
                }
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Package: ${info.packageName}", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            Text("Version code: ${info.versionCode}  •  Min SDK: ${info.minSdk}  •  Target SDK: ${info.targetSdk}", fontSize = 12.sp, color = Fg.copy(.7f))
                            Text("Permissions: ${info.permissions.size}  •  Activities: ${info.activities.size}  •  Services: ${info.services.size}", fontSize = 12.sp, color = Fg.copy(.7f))
                            Text("Receivers: ${info.receivers.size}  •  Providers: ${info.providers.size}", fontSize = 12.sp, color = Fg.copy(.7f))
                        }
                    }
                }
                item { Text("Requested permissions (${info.permissions.size})", style = MaterialTheme.typography.titleSmall, color = Fg.copy(.7f)) }
                if (info.permissions.isEmpty()) item { Text("No declared permissions", color = Fg.copy(.55f)) }
                items(info.permissions) { permission ->
                    GlassCard(Modifier.fillMaxWidth(), onClick = { clip.setText(AnnotatedString(permission)) }) {
                        Text(permission, Modifier.padding(12.dp), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                item { Text("App components", style = MaterialTheme.typography.titleSmall, color = Fg.copy(.7f)) }
                item { ApkComponentSection("Activities", info.activities) }
                item { ApkComponentSection("Services", info.services) }
                item { ApkComponentSection("Broadcast receivers", info.receivers) }
                item { ApkComponentSection("Content providers", info.providers) }
                item { Text("DEX & APK contents", style = MaterialTheme.typography.titleSmall, color = Fg.copy(.7f)) }
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("DEX files: ${info.dexFiles.size}  •  Resources: ${info.resourceCount}  •  Assets: ${info.assetCount}")
                            Text("Native libraries: ${info.nativeLibraries.size}", color = Fg.copy(.7f))
                            info.dexFiles.forEach { Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Accent) }
                            info.nativeLibraries.take(30).forEach { Text(it, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Fg.copy(.65f)) }
                            if (info.nativeLibraries.size > 30) Text("…and ${info.nativeLibraries.size - 30} more libraries", fontSize = 11.sp)
                        }
                    }
                }

                item {
                    Button(onClick = onOpenDex, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Code, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Open DEX Inspector (${info.dexFiles.size} files)")
                    }
                }

                item {
                    Text("Signing Certificate", style = MaterialTheme.typography.titleSmall, color = Fg.copy(.7f))
                }

                item {
                    GlassCard(Modifier.fillMaxWidth(), onClick = { clip.setText(AnnotatedString(info.certSha256)) }) {
                        Column(Modifier.padding(14.dp)) {
                            Text("SHA-256 Fingerprint (tap to copy)", style = MaterialTheme.typography.labelMedium, color = Accent)
                            Text(info.certSha256, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Fg.copy(.9f))
                        }
                    }
                }

                item {
                    GlassCard(Modifier.fillMaxWidth(), onClick = { clip.setText(AnnotatedString(info.certSha1)) }) {
                        Column(Modifier.padding(14.dp)) {
                            Text("SHA-1 Fingerprint (tap to copy)", style = MaterialTheme.typography.labelMedium, color = Accent)
                            Text(info.certSha1, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Fg.copy(.9f))
                        }
                    }
                }

                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Subject DN", style = MaterialTheme.typography.labelMedium, color = Fg.copy(.6f))
                            Text(info.certSubject, style = MaterialTheme.typography.bodySmall, color = Fg.copy(.8f))
                        }
                    }
                }

                item {
                    Text("Certificate & public key", style = MaterialTheme.typography.titleSmall, color = Fg.copy(.7f))
                }
                info.certDetails.forEach { (k, v) ->
                    item {
                        GlassCard(Modifier.fillMaxWidth(), onClick = { clip.setText(AnnotatedString(v)) }) {
                            Column(Modifier.padding(14.dp)) {
                                Text("$k (tap to copy)", style = MaterialTheme.typography.labelMedium, color = Accent)
                                Text(v, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Fg.copy(.85f))
                            }
                        }
                    }
                }
            }
        }
    }
}


// ---------------- File Comparison ----------------
@Composable
fun FileComparisonScreen(path1: String, path2: String, onBack: () -> Unit) {
    val file1 = remember(path1) { File(path1) }
    val file2 = remember(path2) { File(path2) }
    val repo = remember { com.kayx.manager.data.filesystem.FileRepository() }
    var result by remember { mutableStateOf<com.kayx.manager.data.filesystem.ComparisonResult?>(null) }

    LaunchedEffect(path1, path2) {
        result = repo.compareFiles(file1, file2)
    }

    ToolScaffold("File Comparison", onBack) { pad ->
        val res = result
        when {
            res == null -> Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) { CircularProgressIndicator(color = Accent) }
            else -> Column(
                Modifier.fillMaxSize().padding(pad).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (res.identicalContent) "✓ Files are Identical" else "✗ Files Differ",
                            style = MaterialTheme.typography.titleLarge,
                            color = if (res.identicalContent) Color(0xFF6BFFA0) else Color(0xFFFF6B6B))
                        Text(if (res.identicalContent) "Both files have exact same byte content." else "File contents or sizes do not match.",
                            style = MaterialTheme.typography.bodyMedium, color = Fg.copy(.7f))
                    }
                }

                Text("Comparison Details", style = MaterialTheme.typography.titleSmall, color = Fg.copy(.7f))

                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Property", color = Accent, style = MaterialTheme.typography.labelLarge)
                            Text(res.file1Name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelMedium)
                        }
                        HorizontalDivider(color = Fg.copy(.1f))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Size", color = Fg.copy(.6f))
                            Text(fmt(res.file1Size) + " vs " + fmt(res.file2Size))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Size Match", color = Fg.copy(.6f))
                            Text(if (res.sameSize) "Match" else "Different", color = if (res.sameSize) Color(0xFF6BFFA0) else Color(0xFFFF6B6B))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Content Match", color = Fg.copy(.6f))
                            Text(if (res.identicalContent) "Identical" else "Different", color = if (res.identicalContent) Color(0xFF6BFFA0) else Color(0xFFFF6B6B))
                        }
                    }
                }

                Text("Text differences", style = MaterialTheme.typography.titleSmall, color = Fg.copy(.7f))
                val diff by produceState<DiffOutcome?>(null, path1, path2) { value = withContext(Dispatchers.IO) { buildTextDiff(file1, file2) } }
                when (val d = diff) {
                    null -> Box(Modifier.fillMaxWidth().padding(8.dp), Alignment.Center) { CircularProgressIndicator(color = Accent, modifier = Modifier.size(22.dp)) }
                    is DiffOutcome.Unavailable -> Text(d.reason, style = MaterialTheme.typography.bodySmall, color = Fg.copy(.6f))
                    is DiffOutcome.Lines -> if (d.changes == 0) Text("No line differences.", style = MaterialTheme.typography.bodySmall, color = Fg.copy(.6f))
                    else GlassCard(Modifier.fillMaxWidth().weight(1f)) {
                        LazyColumn(Modifier.fillMaxSize().padding(8.dp)) { items(d.rows) { r -> DiffLineRow(r) } }
                    }
                }
            }
        }
    }
}


// ---------------- DEX Inspector + Smali workspace ----------------
private fun dexPackageOf(desc: String): String {
    val n = if (desc.startsWith("L") && desc.endsWith(";")) desc.substring(1, desc.length - 1) else desc
    return n.replace('/', '.').substringBeforeLast('.', "(default)")
}

@Composable
fun DexInspectorScreen(apkPath: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val apk = remember(apkPath) { File(apkPath) }
    val repo = remember { com.kayx.manager.data.apk.DexRepository() }
    val smaliRepo = remember { com.kayx.manager.data.apk.SmaliRepository() }
    val assemblyRepo = remember { com.kayx.manager.data.apk.DexAssemblyRepository() }
    var dexFiles by remember { mutableStateOf<List<String>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<com.kayx.manager.data.apk.DexInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    var section by remember { mutableStateOf("Classes") }
    var workspace by remember { mutableStateOf<File?>(null) }
    var smaliFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var editingFile by remember { mutableStateOf<File?>(null) }
    var editorText by remember { mutableStateOf("") }
    var dirty by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var signAfter by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(apkPath) {
        loading = true
        withContext(Dispatchers.IO) { runCatching { repo.listDexFiles(apk) } }
            .onSuccess { dexFiles = it; if (it.isEmpty()) error = "No DEX files found in this APK" }
            .onFailure { error = it.message ?: "Cannot read APK" }
        loading = false
    }
    fun openDex(name: String) = scope.launch {
        loading = true; error = null; info = null; selected = name; workspace = null; smaliFiles = emptyList()
        val result = withContext(Dispatchers.IO) { runCatching {
            val out = File(context.cacheDir, "kayx_${name.replace('.', '_')}.dex")
            repo.extractDex(apk, name, out)
            repo.inspect(out)
        } }
        result.onSuccess { info = it }.onFailure { error = it.message ?: "DEX inspection failed" }
        loading = false
    }
    fun disassemble() = scope.launch {
        val name = selected ?: return@launch
        loading = true; error = null; status = null
        val result = withContext(Dispatchers.IO) { runCatching {
            val dex = File(context.cacheDir, "kayx_${name.replace('.', '_')}.dex")
            val output = File(context.filesDir, "KayX/smali/${apk.nameWithoutExtension}/$name")
            smaliRepo.disassemble(dex, output)
            output to smaliRepo.listSmali(output)
        } }
        result.onSuccess { (dir, files) -> workspace = dir; smaliFiles = files; status = "Disassembled ${files.size} Smali files" }
            .onFailure { error = it.message ?: "Disassembly failed" }
        loading = false
    }
    fun openSmali(file: File) = scope.launch {
        loading = true; error = null
        withContext(Dispatchers.IO) { runCatching { smaliRepo.read(file, workspace!!) } }
            .onSuccess { editingFile = file; editorText = it; dirty = false }
            .onFailure { error = it.message ?: "Cannot open Smali file" }
        loading = false
    }
    fun saveSmali() = scope.launch {
        val file = editingFile ?: return@launch
        val root = workspace ?: return@launch
        loading = true; error = null
        withContext(Dispatchers.IO) { runCatching { smaliRepo.save(file, root, editorText) } }
            .onSuccess { dirty = false; status = "Saved • backup created: ${file.name}.bak" }
            .onFailure { error = it.message ?: "Save failed" }
        loading = false
    }
    fun assembleAndPatch() = scope.launch {
        val root = workspace ?: return@launch
        val dexName = selected ?: return@launch
        if (dirty) { error = "Save your edited Smali file before assembling"; return@launch }
        loading = true; error = null; status = null
        val result = withContext(Dispatchers.IO) { runCatching {
            val outputDir = File(context.filesDir, "KayX/assembled/${apk.nameWithoutExtension}").apply { mkdirs() }
            val assembledDex = File(outputDir, "${dexName}.rebuilt")
            assemblyRepo.assemble(root, assembledDex)
            val patchedDir = File(context.filesDir, "KayX/patched").apply { mkdirs() }
            val patchedApk = File(patchedDir, "${apk.nameWithoutExtension}-unsigned.apk")
            assemblyRepo.replaceDexInApk(apk, dexName, assembledDex, patchedApk)
            val publicDir = File(android.os.Environment.getExternalStorageDirectory(), "KayX/patched").apply { mkdirs() }
            if (signAfter) {
                com.kayx.manager.data.archive.ArchiveEdit.rewrite(patchedApk).getOrThrow()
                val signed = File(patchedDir, "${apk.nameWithoutExtension}-signed.apk")
                ApkSignRepo.sign(context.applicationContext, patchedApk, signed).getOrThrow()
                signed.copyTo(File(publicDir, signed.name), overwrite = true)
                File(publicDir, signed.name)
            } else {
                patchedApk.copyTo(File(publicDir, patchedApk.name), overwrite = true)
                File(publicDir, patchedApk.name)
            }
        } }
        result.onSuccess { status = (if (signAfter) "Aligned + signed APK: " else "Patched APK (UNSIGNED): ") + it.absolutePath }
            .onFailure { error = it.message ?: "Assembly or APK patch failed" }
        loading = false
    }

    ToolScaffold("DEX Inspector + Smali", onBack) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (editingFile != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(editingFile!!.name, Modifier.weight(1f), color = Accent, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { editingFile = null; dirty = false }) { Text("Files") }
                    Button(onClick = { saveSmali() }, enabled = dirty && !loading) { Text("Save") }
                }
                Text(editingFile!!.parentFile?.relativeTo(workspace!!)?.path ?: "Smali source", fontSize = 10.sp, color = Fg.copy(.5f))
                OutlinedTextField(
                    value = editorText,
                    onValueChange = { editorText = it; dirty = true },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Fg),
                    label = { Text("Smali source") },
                    minLines = 20
                )
                Text("Save creates a .bak copy of the previous file.", fontSize = 10.sp, color = Fg.copy(.5f))
            } else if (workspace != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Smali workspace", Modifier.weight(1f), color = Accent)
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(signAfter, { signAfter = it }); Text("Align + sign", fontSize = 11.sp) }
                    TextButton(onClick = { assembleAndPatch() }, enabled = !loading && !dirty) { Text("Build APK") }
                    TextButton(onClick = { workspace = null; smaliFiles = emptyList() }) { Text("DEX details") }
                }
                Text("Build APK assembles Smali and replaces $selected in a new APK. Output is unsigned.", fontSize = 10.sp, color = Fg.copy(.55f))
                Text("${smaliFiles.size} files • ${workspace!!.absolutePath}", fontSize = 10.sp, color = Fg.copy(.55f))
                OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search class/file") })
                val shownFiles = remember(smaliFiles, filter) { smaliFiles.filter { filter.isBlank() || it.name.contains(filter, true) || it.relativeTo(workspace!!).path.contains(filter, true) }.take(3000) }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                    items(shownFiles) { file ->
                        GlassCard(Modifier.fillMaxWidth(), onClick = { openSmali(file) }) {
                            Column(Modifier.padding(10.dp)) {
                                Text(file.name, fontFamily = FontFamily.Monospace, color = Fg)
                                Text(file.relativeTo(workspace!!).path, fontSize = 10.sp, color = Fg.copy(.5f))
                            }
                        }
                    }
                }
            } else if (selected == null) {
                Text("Select a DEX file from the APK", color = Fg.copy(.7f))
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
                dexFiles.forEach { name ->
                    GlassCard(Modifier.fillMaxWidth(), onClick = { openDex(name) }) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.DataObject, null, tint = Accent)
                            Spacer(Modifier.width(10.dp)); Text(name, Modifier.weight(1f), fontFamily = FontFamily.Monospace)
                            Icon(Icons.Default.ChevronRight, null)
                        }
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(selected!!, Modifier.weight(1f), color = Accent, fontFamily = FontFamily.Monospace)
                    TextButton(onClick = { selected = null; info = null; error = null; filter = "" }) { Text("All DEX") }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
                val d = info
                if (d != null) {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("DEX ${d.magic}  •  ${fmt(d.fileSize)}", color = Accent)
                            Text("Checksum: ${d.checksum}  •  Header: ${d.headerSize} bytes", fontSize = 11.sp, color = Fg.copy(.65f))
                            Text("Strings ${d.stringCount}  •  Types ${d.typeCount}  •  Classes ${d.classCount}", fontSize = 12.sp)
                        }
                    }
                    Button(onClick = { disassemble() }, modifier = Modifier.fillMaxWidth(), enabled = !loading) {
                        Icon(Icons.Default.Code, null); Spacer(Modifier.width(8.dp)); Text("Disassemble to Smali")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Classes", "Packages", "Types", "Strings").forEach { label ->
                            FilterChip(selected = section == label, onClick = { section = label }, label = { Text(label) })
                        }
                    }
                    OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Filter $section") })
                    val packages = remember(d) { d.classes.groupingBy { c -> dexPackageOf(c) }.eachCount().toSortedMap().map { (p, n) -> "$p  ·  $n" } }
                    val source = when (section) { "Types" -> d.types; "Strings" -> d.strings; "Packages" -> packages; else -> d.classes }
                    val shown = remember(source, filter) { source.filter { filter.isBlank() || it.contains(filter, true) || it.contains(filter.replace('.', '/'), true) }.take(5000) }
                    Text("${shown.size} shown${if (source.size > shown.size) " • limited to 5,000" else ""}", fontSize = 11.sp, color = Fg.copy(.55f))
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                        itemsIndexed(shown) { _, value ->
                            GlassCard(Modifier.fillMaxWidth(), onClick = if (section == "Packages") ({ filter = value.substringBefore("  ·  ").let { p -> if (p == "(default)") "" else p }; section = "Classes" }) else null) {
                                Text(value, Modifier.padding(10.dp), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Fg.copy(.9f))
                            }
                        }
                    }
                }
            }
            status?.let { Text(it, fontSize = 11.sp, color = Color(0xFF75E69A)) }
            error?.let { Text(it, fontSize = 11.sp, color = Color(0xFFFF7777)) }
        }
    }
}

// ---------------- HTML preview ----------------
/** [spec] = "<file>" or "<file>\u001f<website root directory>". The root is served over a loopback HTTP server. */
@Composable
fun HtmlPreviewScreen(spec: String, onBack: () -> Unit) {
    val file = remember(spec) { File(spec.substringBefore('\u001f')) }
    val rootDir = remember(spec) {
        File(spec.substringAfter('\u001f', file.parent ?: "/")).let { r ->
            if (r.isDirectory && file.canonicalPath.startsWith(r.canonicalPath + File.separator)) r else (file.parentFile ?: r)
        }
    }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val server = remember(spec) { com.kayx.manager.data.network.LocalWebServer(rootDir) }
    DisposableEffect(spec) { onDispose { webView?.destroy(); server.stop() } }
    ToolScaffold(file.name, onBack, actions = {
        IconButton(onClick = { webView?.reload() }) { Icon(Icons.Default.Refresh, "Reload") }
    }) { pad ->
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(pad),
            factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = SettingsStore.htmlJavaScript
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.allowFileAccess = true
                    settings.allowContentAccess = false
                    settings.allowFileAccessFromFileURLs = false
                    settings.allowUniversalAccessFromFileURLs = false
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    webViewClient = WebViewClient()
                    val rel = file.canonicalPath.removePrefix(rootDir.canonicalPath).split(File.separatorChar)
                        .filter { it.isNotEmpty() }.joinToString("/") { Uri.encode(it) }
                    val url = runCatching { "http://127.0.0.1:${server.start()}/$rel" }.getOrElse { Uri.fromFile(file).toString() }
                    loadUrl(url)
                    webView = this
                }
            }
        )
    }
}
