package com.kayx.manager.feature.tools
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kayx.manager.data.apk.AxmlDecoder
import com.kayx.manager.data.archive.ArchiveEdit
import com.kayx.manager.data.archive.ZIP_SEP
import com.kayx.manager.data.settings.SettingsStore
import com.kayx.manager.ui.editor.SyntaxColors
import com.kayx.manager.ui.editor.SyntaxLang
import com.kayx.manager.ui.editor.SyntaxTransformation
import com.kayx.manager.ui.theme.Accent
import com.kayx.manager.ui.theme.Fg
import com.kayx.manager.ui.theme.LocalPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.Charset

private val ENCODINGS = listOf("UTF-8", "UTF-16", "ISO-8859-1", "windows-1252", "GBK", "Shift_JIS")

/** MT-style text editor: line numbers, symbol bar, undo/redo, find & replace (regex), go to line, encodings, edit toggle, preview. */
@Composable
fun EditorCore(
    title: String, fileName: String, readOnly: Boolean = false, onPreview: (() -> Unit)? = null,
    load: suspend (Charset) -> String, save: suspend (String, Charset) -> Unit, onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var charset by remember { mutableStateOf<Charset>(Charsets.UTF_8) }
    var original by remember { mutableStateOf<String?>(null) }
    var tv by remember { mutableStateOf(TextFieldValue("")) }
    var error by remember { mutableStateOf<String?>(null) }
    val undo = remember { ArrayDeque<TextFieldValue>() }
    val redo = remember { ArrayDeque<TextFieldValue>() }
    val lastEdit = remember { longArrayOf(0L) }
    var editing by remember { mutableStateOf(!readOnly) }
    var showFind by remember { mutableStateOf(false) }
    var q by remember { mutableStateOf("") }
    var rep by remember { mutableStateOf("") }
    var matchCase by remember { mutableStateOf(false) }
    var useRegex by remember { mutableStateOf(false) }
    var gotoDlg by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val ro = readOnly || !editing

    LaunchedEffect(charset) {
        error = null
        val r = withContext(Dispatchers.IO) { runCatching { load(charset) } }
        r.onSuccess { original = it; tv = TextFieldValue(it); undo.clear(); redo.clear() }
            .onFailure { error = it.message ?: "Cannot open file" }
    }

    val dirty = original != null && tv.text != original
    val palette = LocalPalette.current
    val lang = remember(fileName) { SyntaxLang.fromFileName(fileName) }
    val vt = remember(lang, palette.dark) {
        if (lang == SyntaxLang.PLAIN) VisualTransformation.None else SyntaxTransformation(lang, SyntaxColors.of(palette.dark))
    }
    val useHighlight = SettingsStore.syntaxHighlight && tv.text.length <= 200_000

    fun change(n: TextFieldValue) {
        if (ro) { tv = n; return }
        if (n.text != tv.text) {
            val now = System.currentTimeMillis()
            if (now - lastEdit[0] > 700 || undo.isEmpty()) { undo.addLast(tv); if (undo.size > 200) undo.removeFirst() }
            lastEdit[0] = now; redo.clear()
        }
        tv = n
    }
    fun doUndo() { if (undo.isNotEmpty()) { redo.addLast(tv); tv = undo.removeLast() } }
    fun doRedo() { if (redo.isNotEmpty()) { undo.addLast(tv); tv = redo.removeLast() } }
    fun insert(sym: String) {
        if (ro) return
        val sel = tv.selection
        change(TextFieldValue(tv.text.replaceRange(sel.min, sel.max, sym), TextRange(sel.min + sym.length)))
    }
    fun regexOrNull(): Regex? = runCatching {
        if (useRegex) (if (matchCase) Regex(q) else Regex(q, RegexOption.IGNORE_CASE)) else Regex(Regex.escape(q), if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE))
    }.getOrNull()

    fun findNext(forward: Boolean) {
        if (q.isEmpty()) return
        val re = regexOrNull() ?: run { scope.launch { snack.showSnackbar("Invalid regex") }; return }
        val t = tv.text
        val m = if (forward) (re.find(t, tv.selection.max.coerceAtMost(t.length)) ?: re.find(t, 0))
        else re.findAll(t).lastOrNull { it.range.first < tv.selection.min } ?: re.findAll(t).lastOrNull()
        if (m == null) { scope.launch { snack.showSnackbar("Not found") }; return }
        tv = tv.copy(selection = TextRange(m.range.first, m.range.last + 1))
        runCatching { focus.requestFocus() }
    }
    fun replaceCurrent() {
        if (ro || q.isEmpty()) return
        val re = regexOrNull() ?: return
        val sel = tv.text.substring(tv.selection.min, tv.selection.max)
        if (sel.isNotEmpty() && re.matchEntire(sel) != null) {
            val r = if (useRegex) re.replace(sel, rep) else rep
            change(TextFieldValue(tv.text.replaceRange(tv.selection.min, tv.selection.max, r), TextRange(tv.selection.min + r.length)))
        }
        findNext(true)
    }
    fun replaceAll() {
        if (ro || q.isEmpty()) return
        val re = regexOrNull() ?: return
        val n = re.findAll(tv.text).count()
        if (n == 0) { scope.launch { snack.showSnackbar("Not found") }; return }
        change(TextFieldValue(if (useRegex) re.replace(tv.text, rep) else re.replace(tv.text) { rep }))
        scope.launch { snack.showSnackbar("Replaced $n match(es)") }
    }
    fun doSave(after: () -> Unit = {}) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { save(tv.text, charset) } }
            if (r.isSuccess) { original = tv.text; after() }
            snack.showSnackbar(r.exceptionOrNull()?.message ?: if (readOnly) "Exported" else "Saved")
        }
    }

    val pos = remember(tv.selection.start, tv.text) {
        val s = tv.selection.start.coerceIn(0, tv.text.length)
        var line = 1; var last = -1
        for (i in 0 until s) if (tv.text[i] == '\n') { line++; last = i }
        line to (s - last)
    }
    val lineCount = remember(tv.text) { tv.text.count { it == '\n' } + 1 }
    val gutter = remember(lineCount) { (1..lineCount).joinToString("\n") }
    val back = { if (dirty && !readOnly) confirmExit = true else onBack() }
    val fs = SettingsStore.editorFontSize
    val textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fs.sp, lineHeight = (fs * 1.45f).sp, color = Fg)
    val gutterStyle = textStyle.copy(color = Fg.copy(.38f), textAlign = TextAlign.End)
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val screenW = LocalConfiguration.current.screenWidthDp

    ToolScaffold(title, back, snack, actions = {
        if (!readOnly) {
            IconButton(::doUndo, enabled = undo.isNotEmpty() && !ro) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
            IconButton(::doRedo, enabled = redo.isNotEmpty() && !ro) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
            IconButton({ editing = !editing }) { Icon(Icons.Default.Edit, "Edit mode", tint = if (editing) Accent else Fg) }
        }
        if (original != null) IconButton(enabled = readOnly || dirty, onClick = { doSave() }) {
            Icon(if (readOnly) Icons.Default.FileDownload else Icons.Default.Save, null, tint = if (readOnly || dirty) Accent else Fg.copy(.3f))
        }
        Box {
            IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, null) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Find & replace") }, { showFind = !showFind; menu = false })
                DropdownMenuItem({ Text("Go to line…") }, { gotoDlg = true; menu = false })
                if (onPreview != null) DropdownMenuItem({ Text("Preview in browser") }, { menu = false; if (dirty && !readOnly) doSave(onPreview) else onPreview() })
                ENCODINGS.forEach { e ->
                    DropdownMenuItem({ Text("Encoding: $e" + if (charset.name().equals(e, true)) "  ✓" else "") }, {
                        menu = false
                        if (dirty) scope.launch { snack.showSnackbar("Save or discard changes first") } else charset = Charset.forName(e)
                    })
                }
            }
        }
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(fileName + if (dirty && !readOnly) " •" else "", Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.bodySmall, color = Fg.copy(.75f))
                Text("${pos.first}:${pos.second}   ${charset.name()}" + if (ro) "   RO" else "", style = MaterialTheme.typography.bodySmall, color = Fg.copy(.75f))
            }
            if (showFind) Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(q, { q = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Find") }, textStyle = TextStyle(fontSize = 14.sp))
                    IconButton({ findNext(false) }) { Icon(Icons.Default.ArrowUpward, "Previous") }
                    IconButton({ findNext(true) }) { Icon(Icons.Default.ArrowDownward, "Next") }
                }
                if (!ro) Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(rep, { rep = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Replace with") }, textStyle = TextStyle(fontSize = 14.sp))
                    TextButton(::replaceCurrent) { Text("Replace") }
                    TextButton(::replaceAll) { Text("All") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(matchCase, { matchCase = !matchCase }, { Text("Aa") })
                    FilterChip(useRegex, { useRegex = !useRegex }, { Text(".*") })
                }
            }
            when {
                error != null -> Center(PaddingValues(0.dp), error!!)
                original == null -> Center(PaddingValues(0.dp), "Loading…")
                else -> Row(Modifier.weight(1f).fillMaxWidth().verticalScroll(vScroll)) {
                    Text(gutter, Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp), style = gutterStyle)
                    Box(Modifier.weight(1f).horizontalScroll(hScroll)) {
                        BasicTextField(
                            tv, { change(it) }, Modifier.focusRequester(focus).widthIn(min = (screenW - 56).dp).padding(top = 4.dp, end = 24.dp, bottom = 24.dp),
                            readOnly = ro, textStyle = textStyle, cursorBrush = SolidColor(Accent),
                            visualTransformation = if (useHighlight) vt else VisualTransformation.None
                        )
                    }
                }
            }
            if (!ro && original != null) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
                SYMBOLS.forEach { sym ->
                    TextButton({ insert(sym) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(if (sym == "\t") "→" else sym, fontSize = 18.sp, color = Fg) }
                }
            }
        }
    }
    if (gotoDlg) {
        var n by remember { mutableStateOf("") }
        AlertDialog({ gotoDlg = false }, title = { Text("Go to line") },
            text = { OutlinedTextField(n, { n = it.filter(Char::isDigit) }, singleLine = true) },
            confirmButton = { TextButton({
                val target = (n.toIntOrNull() ?: 1).coerceAtLeast(1)
                var off = 0; var line = 1
                while (line < target) { val i = tv.text.indexOf('\n', off); if (i < 0) break; off = i + 1; line++ }
                tv = tv.copy(selection = TextRange(off)); gotoDlg = false
                runCatching { focus.requestFocus() }
            }) { Text("Go") } },
            dismissButton = { TextButton({ gotoDlg = false }) { Text("Cancel") } })
    }
    if (confirmExit) AlertDialog({ confirmExit = false }, title = { Text("Discard changes?") },
        confirmButton = { TextButton({ confirmExit = false; onBack() }) { Text("Discard", color = Color(0xFFFF6B6B)) } },
        dismissButton = { TextButton({ confirmExit = false }) { Text("Keep editing") } })
}

private val SYMBOLS = listOf("\t", "/", "+", "-", "*", "=", "<", ">", "(", ")", "{", "}", "[", "]", ";", "\"", "'", ":", ".", ",", "_", "#", "$", "&", "|", "!", "?", "\\", "%", "@")

@Composable
private fun Center(pad: PaddingValues, text: String) =
    Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) { Text(text, color = Fg.copy(.6f)) }

private fun looksBinary(b: ByteArray) = b.take(4096).any { it == 0.toByte() }

@Composable
fun TextEditorPro(path: String, onBack: () -> Unit, onOpenTool: (String, com.kayx.manager.domain.model.Tool) -> Unit = { _, _ -> }) {
    val file = remember(path) { File(path) }
    val web = file.extension.lowercase() in setOf("html", "htm", "xhtml", "svg")
    EditorCore(file.name, file.name, onPreview = if (web) ({ onOpenTool(path, com.kayx.manager.domain.model.Tool.HTML) }) else null, load = { cs ->
        require(file.length() <= 5_000_000) { "File too large for the text editor (max 5 MB)" }
        val b = file.readBytes()
        require(cs.name().startsWith("UTF-16") || !looksBinary(b)) { "Looks like a binary file. Use the Hex viewer." }
        String(b, cs)
    }, save = { t, cs ->
        if (SettingsStore.editorBackup) file.copyTo(File(file.parentFile, file.name + ".bak"), overwrite = true)
        file.writeBytes(t.toByteArray(cs))
    }, onBack = onBack)
}

/** Edit a text entry directly inside a ZIP/APK ("zip::kx::entry"). */
@Composable
fun ZipEntryEditorScreen(spec: String, onBack: () -> Unit) {
    val zip = remember(spec) { File(spec.substringBefore(ZIP_SEP)) }
    val entry = remember(spec) { spec.substringAfter(ZIP_SEP) }
    EditorCore(entry.substringAfterLast('/'), entry.substringAfterLast('/'), load = { cs ->
        val b = ArchiveEdit.readEntry(zip, entry)
        require(cs.name().startsWith("UTF-16") || !looksBinary(b)) { "Binary entry. Use Hex view (or Decoded XML for AndroidManifest/layouts)." }
        require(b.size <= 5_000_000) { "Entry too large (max 5 MB)" }
        String(b, cs)
    }, save = { t, cs -> ArchiveEdit.rewrite(zip, replace = mapOf(entry to t.toByteArray(cs))).getOrThrow() }, onBack = onBack)
}

/** Binary Android XML viewer (file path or "zip::kx::entry"). Save icon exports the decoded text next to the source. */
@Composable
fun AxmlViewerScreen(spec: String, onBack: () -> Unit) {
    val isZip = ZIP_SEP in spec
    val src = remember(spec) { File(if (isZip) spec.substringBefore(ZIP_SEP) else spec) }
    val entry = remember(spec) { if (isZip) spec.substringAfter(ZIP_SEP) else "" }
    val shortName = if (isZip) entry.substringAfterLast('/') else src.name
    EditorCore("$shortName (decoded)", "decoded.xml", readOnly = true, load = {
        val b = if (isZip) ArchiveEdit.readEntry(src, entry) else src.readBytes()
        if (AxmlDecoder.isAxml(b)) AxmlDecoder.decode(b) else String(b, Charsets.UTF_8)
    }, save = { t, _ ->
        File(src.parentFile, shortName.removeSuffix(".xml") + "_decoded.xml").writeText(t)
    }, onBack = onBack)
}
