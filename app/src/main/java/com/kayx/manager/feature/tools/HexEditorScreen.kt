package com.kayx.manager.feature.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kayx.manager.data.settings.SettingsStore
import com.kayx.manager.ui.glass.GlassCard
import com.kayx.manager.ui.theme.Accent
import com.kayx.manager.ui.theme.Fg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

internal fun parseHex(s: String): ByteArray? {
    val clean = s.filter { !it.isWhitespace() }
    if (clean.isEmpty() || clean.length % 2 != 0) return null
    val out = ByteArray(clean.length / 2)
    for (i in out.indices) {
        val hi = Character.digit(clean[i * 2], 16)
        val lo = Character.digit(clean[i * 2 + 1], 16)
        if (hi < 0 || lo < 0) return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

internal fun indexOfBytes(hay: ByteArray, len: Int, pat: ByteArray, from: Int): Int {
    val last = len - pat.size
    var i = from
    while (i <= last) {
        var j = 0
        while (j < pat.size && hay[i + j] == pat[j]) j++
        if (j == pat.size) return i
        i++
    }
    return -1
}

/** Finds [pat] in [file] starting at [from], honouring unsaved [edits]. Returns the offset or -1. */
internal fun findInFile(file: File, edits: Map<Long, Byte>, pat: ByteArray, from: Long): Long {
    val total = file.length()
    if (pat.isEmpty() || from >= total) return -1
    RandomAccessFile(file, "r").use { raf ->
        val chunk = 1 shl 20
        val buf = ByteArray(chunk + pat.size - 1)
        var pos = from.coerceAtLeast(0L)
        while (pos < total) {
            raf.seek(pos)
            val n = raf.read(buf, 0, buf.size)
            if (n <= 0) break
            for ((o, b) in edits) if (o >= pos && o < pos + n) buf[(o - pos).toInt()] = b
            val idx = indexOfBytes(buf, n, pat, 0)
            if (idx >= 0) return pos + idx
            if (n < buf.size) break
            pos += chunk
        }
    }
    return -1
}

/** MT-style hex editor: tap a byte to select it, type a new hex value, find (hex/text), go to offset, undo, save in place. */
@Composable
fun HexEditorScreen(path: String, onBack: () -> Unit) {
    val file = remember(path) { File(path) }
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val raf = remember(path) { runCatching { RandomAccessFile(file, "r") }.getOrNull() }
    DisposableEffect(path) { onDispose { runCatching { raf?.close() } } }
    val per = SettingsStore.hexBytesPerRow.coerceIn(4, 32)
    val listState = rememberLazyListState()

    val edits = remember(path) { mutableStateMapOf<Long, Byte>() }
    val undo = remember(path) { mutableStateListOf<Triple<Long, Byte?, Byte?>>() }
    var version by remember(path) { mutableStateOf(0) }
    var length by remember(path) { mutableStateOf(file.length()) }
    var selected by remember(path) { mutableStateOf(-1L) }
    var input by remember(path) { mutableStateOf("") }
    var dlg by remember { mutableStateOf(0) } // 0 none, 1 find, 2 goto, 3 discard
    var findHex by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var gotoText by remember { mutableStateOf("") }

    fun readOrig(off: Long): Byte? = runCatching {
        raf!!.seek(off)
        val v = raf.read()
        if (v < 0) null else v.toByte()
    }.getOrNull()

    fun readRow(i: Int): ByteArray {
        val buf = ByteArray(per)
        val n = runCatching { raf!!.seek(i.toLong() * per); raf.read(buf) }.getOrDefault(0).coerceAtLeast(0)
        return buf.copyOf(n)
    }

    fun current(off: Long): Byte? = edits[off] ?: readOrig(off)

    fun scrollTo(off: Long) { scope.launch { listState.animateScrollToItem((off / per).toInt()) } }

    fun select(off: Long) {
        selected = off
        input = current(off)?.let { "%02X".format(it) } ?: ""
    }

    fun setByte(off: Long, v: Byte) {
        val orig = readOrig(off) ?: return
        val prev = edits[off]
        if ((prev ?: orig) == v) return
        val now: Byte? = if (v == orig) null else v
        undo.add(Triple(off, prev, now))
        if (now == null) edits.remove(off) else edits[off] = now
    }

    fun undoLast() {
        if (undo.isEmpty()) return
        val (off, prev, _) = undo.removeAt(undo.lastIndex)
        if (prev == null) edits.remove(off) else edits[off] = prev
        select(off)
    }

    fun save() {
        scope.launch {
            val snapshot = edits.toMap()
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    if (SettingsStore.editorBackup && file.length() <= 64L * 1024 * 1024)
                        file.copyTo(File(file.parentFile, file.name + ".bak"), overwrite = true)
                    RandomAccessFile(file, "rw").use { w ->
                        for ((o, b) in snapshot.toSortedMap()) { w.seek(o); w.write(b.toInt() and 0xFF) }
                    }
                }
            }
            r.onSuccess {
                edits.clear(); undo.clear(); version++; length = file.length()
                snack.showSnackbar(if (SettingsStore.editorBackup) "Saved (backup: ${file.name}.bak)" else "Saved")
            }.onFailure { snack.showSnackbar(it.message ?: "Save failed") }
        }
    }

    fun find() {
        val pat = if (findHex) parseHex(findQuery) else findQuery.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
        if (pat == null) { scope.launch { snack.showSnackbar(if (findHex) "Invalid hex (use pairs like DE AD BE EF)" else "Type something to find") }; return }
        val snap = edits.toMap()
        val start = selected + 1
        scope.launch {
            val hit = withContext(Dispatchers.IO) {
                runCatching { findInFile(file, snap, pat, start).let { if (it < 0 && start > 0) findInFile(file, snap, pat, 0) else it } }.getOrDefault(-1L)
            }
            if (hit < 0) snack.showSnackbar("Not found") else { select(hit); scrollTo(hit); snack.showSnackbar("Found at 0x%X".format(hit)) }
        }
    }

    ToolScaffold(
        file.name + if (edits.isNotEmpty()) " •" else "", { if (edits.isNotEmpty()) dlg = 3 else onBack() }, snack,
        actions = {
            IconButton({ undoLast() }, enabled = undo.isNotEmpty()) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
            IconButton({ dlg = 2 }) { Icon(Icons.Default.FormatListNumbered, "Go to offset") }
            IconButton({ dlg = 1 }) { Icon(Icons.Default.Search, "Find") }
            IconButton({ save() }, enabled = edits.isNotEmpty()) { Icon(Icons.Default.Save, "Save") }
        },
        bottomBar = {
            if (selected >= 0) GlassCard(Modifier.navigationBarsPadding().padding(12.dp).fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ if (selected > 0) { select(selected - 1); scrollTo(selected) } }) { Icon(Icons.Default.ChevronLeft, "Previous byte") }
                    Column(Modifier.weight(1f)) {
                        Text("Offset 0x%X  (%d)".format(selected, selected), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Fg.copy(.7f))
                        TextField(
                            input, { v -> input = v.filter { Character.digit(it, 16) >= 0 }.take(2).uppercase() },
                            singleLine = true, label = { Text("Hex value") },
                            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent)
                        )
                    }
                    TextButton({
                        val b = parseHex(input.padStart(2, '0'))?.firstOrNull()
                        if (b != null) setByte(selected, b)
                    }) { Text("Set", color = Accent) }
                    IconButton({ if (selected + 1 < length) { select(selected + 1); scrollTo(selected) } }) { Icon(Icons.Default.ChevronRight, "Next byte") }
                }
            }
        }
    ) { pad ->
        when {
            raf == null -> Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) { Text("Cannot open file", color = Fg.copy(.6f)) }
            length == 0L -> Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) { Text("Empty file", color = Fg.copy(.6f)) }
            else -> {
                val rows = ((length + per - 1) / per).toInt()
                LazyColumn(
                    Modifier.fillMaxSize(), state = listState,
                    contentPadding = PaddingValues(12.dp, pad.calculateTopPadding() + 4.dp, 12.dp, pad.calculateBottomPadding() + 16.dp)
                ) {
                    items(rows) { i ->
                        val base = remember(i, version, per) { readRow(i) }
                        HexRow(i, per, base, edits, selected, ::select)
                    }
                }
            }
        }
    }

    when (dlg) {
        1 -> AlertDialog({ dlg = 0 }, title = { Text("Find") }, text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(!findHex, { findHex = false }, { Text("Text") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(findHex, { findHex = true }, { Text("Hex") })
                }
                OutlinedTextField(findQuery, { findQuery = it }, Modifier.padding(top = 8.dp), singleLine = true,
                    label = { Text(if (findHex) "e.g. 50 4B 03 04" else "Text (UTF-8)") })
            }
        }, confirmButton = { TextButton({ dlg = 0; find() }) { Text("Find next") } },
            dismissButton = { TextButton({ dlg = 0 }) { Text("Cancel") } })
        2 -> AlertDialog({ dlg = 0 }, title = { Text("Go to offset") }, text = {
            OutlinedTextField(gotoText, { gotoText = it.filter { c -> Character.digit(c, 16) >= 0 || c == 'x' || c == 'X' } }, singleLine = true,
                label = { Text("Hex offset, e.g. 1A40") })
        }, confirmButton = {
            TextButton({
                val off = gotoText.removePrefix("0x").removePrefix("0X").toLongOrNull(16)
                dlg = 0
                if (off != null) { val o = off.coerceIn(0L, (length - 1).coerceAtLeast(0L)); select(o); scrollTo(o) }
            }) { Text("Go") }
        }, dismissButton = { TextButton({ dlg = 0 }) { Text("Cancel") } })
        3 -> AlertDialog({ dlg = 0 }, title = { Text("Discard changes?") }, text = { Text("${edits.size} edited byte(s) are not saved.") },
            confirmButton = { TextButton({ dlg = 0; onBack() }) { Text("Discard", color = Color(0xFFFF6B6B)) } },
            dismissButton = { TextButton({ dlg = 0 }) { Text("Keep editing") } })
    }
}

@Composable
private fun HexRow(index: Int, per: Int, base: ByteArray, edits: Map<Long, Byte>, selected: Long, onSelect: (Long) -> Unit) {
    val start = index.toLong() * per
    val fs = if (per > 8) 9.sp else 12.sp
    val pad = if (per > 8) 1.dp else 2.dp
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("%08X ".format(start), fontFamily = FontFamily.Monospace, fontSize = fs, color = Fg.copy(.5f))
        for (k in 0 until per) {
            if (k < base.size) {
                val off = start + k
                val e = edits[off]
                val b = e ?: base[k]
                Text(
                    "%02X".format(b),
                    Modifier.clickable { onSelect(off) }
                        .background(if (off == selected) Accent.copy(.35f) else Color.Transparent)
                        .padding(horizontal = pad),
                    fontFamily = FontFamily.Monospace, fontSize = fs,
                    color = if (e != null) Color(0xFFFF6B6B) else Fg.copy(.9f)
                )
            } else Text("  ", Modifier.padding(horizontal = pad), fontFamily = FontFamily.Monospace, fontSize = fs)
        }
        val ascii = buildString {
            for (k in base.indices) {
                val c = (edits[start + k] ?: base[k]).toInt() and 0xFF
                append(if (c in 32..126) c.toChar() else '.')
            }
        }
        Text("  $ascii", fontFamily = FontFamily.Monospace, fontSize = fs, color = Fg.copy(.9f), maxLines = 1)
    }
}
