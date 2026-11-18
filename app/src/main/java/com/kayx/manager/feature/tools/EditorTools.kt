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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.WrapText
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
    title: String, fileName: String, readOnly: Boolean = false, onPreview: ((String) -> Unit)? = null, previewPath: String? = null,
    load: suspend (Charset) -> String, save: suspend (String, Charset) -> Unit, onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val ctxLocal = androidx.compose.ui.platform.LocalContext.current
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
    var softWrap by remember { mutableStateOf(SettingsStore.editorSoftWrap) }
    var completion by remember { mutableStateOf(SettingsStore.editorCompletion) }
    var smooth by remember { mutableStateOf(SettingsStore.editorSmooth) }
    var langOverride by remember { mutableStateOf<SyntaxLang?>(null) }
    var syntaxDlg by remember { mutableStateOf(false) }
    var prefsDlg by remember { mutableStateOf(false) }
    var webDlg by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    var confirmReload by remember { mutableStateOf(false) }
    var menuPage by remember { mutableStateOf(0) } // 0 main, 1 File, 2 Encoding
    val posBack = remember { mutableStateListOf<Int>() }
    val posFwd = remember { mutableStateListOf<Int>() }
    var layoutRes by remember { mutableStateOf<TextLayoutResult?>(null) }
    val fs = SettingsStore.editorFontSize
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val lineHeightPx = with(LocalDensity.current) { (fs * 1.45f).sp.toPx() }

    LaunchedEffect(charset, reloadKey) {
        error = null
        val r = withContext(Dispatchers.IO) { runCatching { load(charset) } }
        r.onSuccess { original = it; tv = TextFieldValue(it); undo.clear(); redo.clear() }
            .onFailure { error = it.message ?: "Cannot open file" }
    }

    val dirty = original != null && tv.text != original
    val palette = LocalPalette.current
    val autoLang = remember(fileName) { SyntaxLang.fromFileName(fileName) }
    val lang = langOverride ?: autoLang
    val vt = remember(lang, palette.dark) {
        if (lang == SyntaxLang.PLAIN) VisualTransformation.None else SyntaxTransformation(lang, SyntaxColors.of(palette.dark))
    }
    val useHighlight = SettingsStore.syntaxHighlight && !smooth && tv.text.length <= 200_000

    fun autoComplete(old: TextFieldValue, n: TextFieldValue): TextFieldValue {
        if (!completion || !old.selection.collapsed || !n.selection.collapsed) return n
        if (n.text.length != old.text.length + 1 || n.selection.start != old.selection.start + 1) return n
        val at = old.selection.start
        val c = n.text[at]
        val next = old.text.getOrNull(at)
        val pairs = mapOf('(' to ')', '[' to ']', '{' to '}', '"' to '"', '\'' to '\'')
        if (c in ")]}\"'" && next == c) return old.copy(selection = TextRange(at + 1)) // type over an existing closer
        val close = pairs[c]
        if (close != null) {
            val quote = c == '"' || c == '\''
            val prev = old.text.getOrNull(at - 1)
            if (quote && (prev?.isLetterOrDigit() == true || next?.isLetterOrDigit() == true)) return n
            if (next != null && !next.isWhitespace() && next !in ")]}\"',;") return n
            return TextFieldValue(n.text.substring(0, at + 1) + close + n.text.substring(at + 1), TextRange(at + 1))
        }
        if (c == '\n') {
            val lineStart = old.text.lastIndexOf('\n', at - 1) + 1
            val line = old.text.substring(lineStart, at)
            val indent = line.takeWhile { it == ' ' || it == '\t' }
            val last = line.trimEnd().lastOrNull()
            val opens = last != null && last in "{(["
            if (indent.isEmpty() && !opens) return n
            val extra = if (opens) "    " else ""
            val closer = when (last) { '{' -> '}'; '(' -> ')'; '[' -> ']'; else -> null }
            val ins = "\n" + indent + extra + (if (opens && closer != null && next == closer) "\n" + indent else "")
            return TextFieldValue(old.text.substring(0, at) + ins + old.text.substring(at), TextRange(at + 1 + indent.length + extra.length))
        }
        return n
    }
    fun change(n0: TextFieldValue) {
        if (ro) { tv = n0; return }
        val n = autoComplete(tv, n0)
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

    fun moveTo(off: Int, end: Int = off, record: Boolean = true) {
        val o = off.coerceIn(0, tv.text.length)
        if (record && tv.selection.start != o) {
            posBack.add(tv.selection.start); posFwd.clear()
            if (posBack.size > 100) posBack.removeAt(0)
        }
        tv = tv.copy(selection = TextRange(o, end.coerceIn(o, tv.text.length)))
        val logical = tv.text.take(o).count { it == '\n' }
        val vLine = if (softWrap) layoutRes?.let { runCatching { it.getLineForOffset(o) }.getOrNull() } ?: logical else logical
        scope.launch { runCatching { vScroll.animateScrollTo((((vLine - 2).coerceAtLeast(0)) * lineHeightPx).toInt().coerceIn(0, vScroll.maxValue)) } }
        runCatching { focus.requestFocus() }
    }
    fun prevPos() { if (posBack.isNotEmpty()) { val t = posBack.removeAt(posBack.lastIndex); posFwd.add(tv.selection.start); moveTo(t, record = false) } }
    fun nextPos() { if (posFwd.isNotEmpty()) { val t = posFwd.removeAt(posFwd.lastIndex); posBack.add(tv.selection.start); moveTo(t, record = false) } }

    fun findNext(forward: Boolean) {
        if (q.isEmpty()) return
        val re = regexOrNull() ?: run { scope.launch { snack.showSnackbar("Invalid regex") }; return }
        val t = tv.text
        val m = if (forward) (re.find(t, tv.selection.max.coerceAtMost(t.length)) ?: re.find(t, 0))
        else re.findAll(t).lastOrNull { it.range.first < tv.selection.min } ?: re.findAll(t).lastOrNull()
        if (m == null) { scope.launch { snack.showSnackbar("Not found") }; return }
        moveTo(m.range.first, m.range.last + 1)
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
    val gutter = remember(lineCount, softWrap, layoutRes) {
        val lr = layoutRes
        if (!softWrap || lr == null) (1..lineCount).joinToString("\n")
        else buildString {
            var n = 0
            for (i in 0 until lr.lineCount) {
                val st = lr.getLineStart(i)
                if (i == 0 || tv.text.getOrNull(st - 1) == '\n') { n++; append(n) }
                if (i < lr.lineCount - 1) append('\n')
            }
        }
    }
    val back = { if (dirty && !readOnly) confirmExit = true else onBack() }
    val textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fs.sp, lineHeight = (fs * 1.45f).sp, color = Fg)
    val gutterStyle = textStyle.copy(color = Fg.copy(.38f), textAlign = TextAlign.End)
    val screenW = LocalConfiguration.current.screenWidthDp

    ToolScaffold(title, back, snack, actions = {
        if (onPreview != null && previewPath != null) IconButton({ webDlg = true }) { Icon(Icons.Default.Public, "Web functions") }
        if (!readOnly) {
            IconButton(::doUndo, enabled = undo.isNotEmpty() && !ro) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
            IconButton(::doRedo, enabled = redo.isNotEmpty() && !ro) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
        }
        if (original != null) IconButton(enabled = readOnly || dirty, onClick = { doSave() }) {
            Icon(if (readOnly) Icons.Default.FileDownload else Icons.Default.Save, null, tint = if (readOnly || dirty) Accent else Fg.copy(.3f))
        }
        if (!readOnly) IconButton({ editing = !editing }) { Icon(Icons.Default.Edit, "Edit mode", tint = if (editing) Accent else Fg) }
        Box {
            IconButton({ menuPage = 0; menu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
            DropdownMenu(menu, { menu = false }) {
                when (menuPage) {
                    1 -> {
                        EdMenuItem("Back", Icons.AutoMirrored.Filled.ArrowBack) { menuPage = 0 }
                        HorizontalDivider()
                        EdMenuItem(if (readOnly) "Export decoded text" else "Save", Icons.Default.Save, enabled = readOnly || dirty) { menu = false; doSave() }
                        EdMenuItem("Reload from disk", Icons.Default.Refresh) { menu = false; if (dirty && !readOnly) confirmReload = true else reloadKey++ }
                        EdMenuItem("Copy all text", Icons.Default.ContentCopy) {
                            menu = false
                            com.kayx.manager.data.filesystem.ExternalOps.copyText(ctxLocal, tv.text)
                            scope.launch { snack.showSnackbar("Copied") }
                        }
                        EdMenuItem("Encoding: ${charset.name()}", Icons.Default.TextFields, arrow = true) { menuPage = 2 }
                    }
                    2 -> {
                        EdMenuItem("Back", Icons.AutoMirrored.Filled.ArrowBack) { menuPage = 1 }
                        HorizontalDivider()
                        ENCODINGS.forEach { e ->
                            DropdownMenuItem({ Text(e + if (charset.name().equals(e, true)) "  ✓" else "") }, {
                                menu = false
                                if (dirty) scope.launch { snack.showSnackbar("Save or discard changes first") } else charset = Charset.forName(e)
                            })
                        }
                    }
                    else -> {
                        EdMenuItem("File", Icons.Default.Description, arrow = true) { menuPage = 1 }
                        EdMenuItem("Search", Icons.Default.Search) { menu = false; showFind = !showFind }
                        EdMenuItem("Syntax", Icons.Default.Code) { menu = false; syntaxDlg = true }
                        EdMenuItem("Previous position", Icons.AutoMirrored.Filled.ArrowBack, enabled = posBack.isNotEmpty()) { menu = false; prevPos() }
                        EdMenuItem("Next position", Icons.AutoMirrored.Filled.ArrowForward, enabled = posFwd.isNotEmpty()) { menu = false; nextPos() }
                        EdMenuItem("Jump to line", Icons.Default.FormatListNumbered) { menu = false; gotoDlg = true }
                        EdMenuItem("Soft wrap", Icons.AutoMirrored.Filled.WrapText, checked = softWrap) {
                            softWrap = !softWrap; SettingsStore.updateEditorSoftWrap(softWrap); layoutRes = null; menu = false
                        }
                        EdMenuItem("Read-only mode", Icons.Default.Visibility, enabled = !readOnly, checked = ro) { editing = !editing; menu = false }
                        EdMenuItem("Smooth mode", Icons.Default.Timeline, checked = smooth) { smooth = !smooth; SettingsStore.updateEditorSmooth(smooth); menu = false }
                        EdMenuItem("Code completion", Icons.Default.AutoAwesome, checked = completion) {
                            completion = !completion; SettingsStore.updateEditorCompletion(completion); menu = false
                        }
                        if (onPreview != null && previewPath != null) EdMenuItem("Web functions", Icons.Default.Public) { menu = false; webDlg = true }
                        EdMenuItem("Preferences", Icons.Default.Settings) { menu = false; prefsDlg = true }
                        EdMenuItem("Close file", Icons.Default.Close) { menu = false; back() }
                    }
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
                    Box(if (softWrap) Modifier.weight(1f) else Modifier.weight(1f).horizontalScroll(hScroll)) {
                        BasicTextField(
                            tv, { change(it) },
                            Modifier.focusRequester(focus)
                                .then(if (softWrap) Modifier.fillMaxWidth() else Modifier.widthIn(min = (screenW - 56).dp))
                                .padding(top = 4.dp, end = 24.dp, bottom = 24.dp),
                            readOnly = ro, textStyle = textStyle, cursorBrush = SolidColor(Accent),
                            visualTransformation = if (useHighlight) vt else VisualTransformation.None,
                            onTextLayout = { if (softWrap) layoutRes = it }
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
                gotoDlg = false
                moveTo(off)
            }) { Text("Go") } },
            dismissButton = { TextButton({ gotoDlg = false }) { Text("Cancel") } })
    }
    if (confirmReload) AlertDialog({ confirmReload = false }, title = { Text("Reload from disk?") }, text = { Text("Unsaved changes will be lost.") },
        confirmButton = { TextButton({ confirmReload = false; reloadKey++ }) { Text("Reload", color = Color(0xFFFF6B6B)) } },
        dismissButton = { TextButton({ confirmReload = false }) { Text("Cancel") } })
    if (syntaxDlg) AlertDialog({ syntaxDlg = false }, title = { Text("Syntax") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            val labels = mapOf(
                SyntaxLang.PLAIN to "Plain text", SyntaxLang.XML to "XML / HTML", SyntaxLang.JSON to "JSON", SyntaxLang.SMALI to "Smali",
                SyntaxLang.CODE to "Code (Java, Kotlin, JS, C…)", SyntaxLang.HASH to "Script / config (# comments)"
            )
            val options: List<Pair<String, SyntaxLang?>> = listOf("Auto (${labels[autoLang]})" to null) + SyntaxLang.values().map { (labels[it] ?: it.name) to it }
            options.forEach { (label, value) ->
                Row(Modifier.fillMaxWidth().clickable { langOverride = value; syntaxDlg = false }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(langOverride == value, null)
                    Spacer(Modifier.width(12.dp))
                    Text(label)
                }
            }
        }
    }, confirmButton = {}, dismissButton = { TextButton({ syntaxDlg = false }) { Text("Close") } })
    if (prefsDlg) AlertDialog({ prefsDlg = false }, title = { Text("Preferences") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Font size: ${SettingsStore.editorFontSize}", style = MaterialTheme.typography.bodyMedium)
            Slider(SettingsStore.editorFontSize.toFloat(), { SettingsStore.updateEditorFontSize(it.toInt()) }, valueRange = 10f..24f, steps = 13)
            PrefSwitch("Syntax highlighting", SettingsStore.syntaxHighlight) { SettingsStore.updateSyntaxHighlight(it) }
            PrefSwitch("Soft wrap", softWrap) { softWrap = it; SettingsStore.updateEditorSoftWrap(it); layoutRes = null }
            PrefSwitch("Code completion (auto-close, auto-indent)", completion) { completion = it; SettingsStore.updateEditorCompletion(it) }
            PrefSwitch("Smooth mode (no highlighting)", smooth) { smooth = it; SettingsStore.updateEditorSmooth(it) }
            PrefSwitch("Keep .bak backup when saving", SettingsStore.editorBackup) { SettingsStore.updateEditorBackup(it) }
        }
    }, confirmButton = { TextButton({ prefsDlg = false }) { Text("Close") } })
    if (webDlg && previewPath != null) com.kayx.manager.feature.filebrowser.WebFunctionsDialog(
        previewPath,
        onEdit = { webDlg = false },
        onPreview = { root -> webDlg = false; if (dirty && !readOnly) doSave { onPreview?.invoke(root) } else onPreview?.invoke(root) },
        onMore = null, onDismiss = { webDlg = false }
    )
    if (confirmExit) AlertDialog({ confirmExit = false }, title = { Text("Discard changes?") },
        confirmButton = { TextButton({ confirmExit = false; onBack() }) { Text("Discard", color = Color(0xFFFF6B6B)) } },
        dismissButton = { TextButton({ confirmExit = false }) { Text("Keep editing") } })
}

@Composable
private fun EdMenuItem(
    label: String, icon: ImageVector, enabled: Boolean = true, checked: Boolean? = null, arrow: Boolean = false, onClick: () -> Unit
) = DropdownMenuItem(
    { Text(label) }, onClick, leadingIcon = { Icon(icon, null) }, enabled = enabled,
    trailingIcon = when {
        checked != null -> ({ Checkbox(checked, null, enabled = enabled) })
        arrow -> ({ Icon(Icons.Default.ChevronRight, null) })
        else -> null
    }
)

@Composable
private fun PrefSwitch(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(value, onChange)
    }
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
    EditorCore(file.name, file.name,
        onPreview = if (web) ({ root: String -> onOpenTool(path + "\u001f" + root, com.kayx.manager.domain.model.Tool.HTML) }) else null,
        previewPath = if (web) path else null, load = { cs ->
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
