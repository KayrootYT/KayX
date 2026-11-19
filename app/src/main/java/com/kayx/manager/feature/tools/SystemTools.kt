package com.kayx.manager.feature.tools
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send

import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kayx.manager.data.apk.ApkSignRepo
import com.kayx.manager.data.filesystem.ExternalOps
import com.kayx.manager.data.filesystem.RecycleBin
import com.kayx.manager.data.settings.SettingsStore
import com.kayx.manager.ui.glass.GlassCard
import com.kayx.manager.ui.theme.Accent
import com.kayx.manager.ui.theme.Fg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

private val RED = Color(0xFFFF6B6B)

// ───────────────────────── Recycle bin ─────────────────────────
@Composable
fun RecycleBinScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var items by remember { mutableStateOf<List<RecycleBin.Entry>?>(null) }
    var sel by remember { mutableStateOf<RecycleBin.Entry?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }
    fun refresh() = scope.launch { items = withContext(Dispatchers.IO) { RecycleBin.list() } }
    LaunchedEffect(Unit) { refresh() }
    fun act(msg: String, block: () -> Result<Any?>) = scope.launch {
        val r = withContext(Dispatchers.IO) { block() }
        snack.showSnackbar(r.exceptionOrNull()?.message ?: msg); refresh()
    }

    ToolScaffold("Recycle Bin", onBack, snack, actions = {
        var m by remember { mutableStateOf(false) }
        IconButton({ confirmEmpty = true }, enabled = !items.isNullOrEmpty()) { Icon(Icons.Default.DeleteSweep, "Empty") }
        Box {
            IconButton({ m = true }) { Icon(Icons.Default.MoreVert, null) }
            DropdownMenu(m, { m = false }) {
                DropdownMenuItem({ Text(if (SettingsStore.useRecycleBin) "Recycle bin: ON (tap to disable)" else "Recycle bin: OFF (tap to enable)") },
                    { SettingsStore.updateUseRecycleBin(!SettingsStore.useRecycleBin); m = false })
            }
        }
    }) { pad ->
        val list = items
        if (list == null) Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) { CircularProgressIndicator(color = Accent) }
        else if (list.isEmpty()) Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) { Text("Recycle bin is empty", color = Fg.copy(.6f)) }
        else LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(list, key = { it.id }) { e ->
                GlassCard(Modifier.fillMaxWidth(), onClick = { sel = e }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (e.isDir) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile, null, tint = Accent)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(e.original, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = Fg.copy(.55f))
                            Text(DateFormat.getDateTimeInstance().format(Date(e.deletedAt)), style = MaterialTheme.typography.labelSmall, color = Fg.copy(.55f))
                            val dayMs = 24L * 60 * 60 * 1000
                            val daysLeft = ((e.deletedAt + 30L * dayMs - System.currentTimeMillis()).coerceAtLeast(0L) + dayMs - 1) / dayMs
                            Text("Auto-delete in $daysLeft day(s)", style = MaterialTheme.typography.labelSmall, color = if (daysLeft <= 3) RED else Fg.copy(.55f))
                        }
                    }
                }
            }
        }
    }
    sel?.let { e ->
        AlertDialog({ sel = null }, title = { Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, text = {
            Column {
                TextButton({ sel = null; act("Restored") { RecycleBin.restore(e) } }, Modifier.fillMaxWidth()) { Text("Restore to original location") }
                TextButton({ sel = null; act("Deleted permanently") { RecycleBin.purge(e) } }, Modifier.fillMaxWidth()) { Text("Delete permanently", color = RED) }
            }
        }, confirmButton = {}, dismissButton = { TextButton({ sel = null }) { Text("Cancel") } })
    }
    if (confirmEmpty) AlertDialog({ confirmEmpty = false }, title = { Text("Empty recycle bin?") }, text = { Text("All items will be deleted permanently.") },
        confirmButton = { TextButton({ confirmEmpty = false; act("Recycle bin emptied") { RecycleBin.empty() } }) { Text("Empty", color = RED) } },
        dismissButton = { TextButton({ confirmEmpty = false }) { Text("Cancel") } })
}

// ───────────────────────── Terminal / script executor ─────────────────────────
/** [spec] is a start directory, or a script file that is executed with `sh` immediately. */
@Composable
fun TerminalScreen(spec: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val specFile = remember(spec) { if (spec.isEmpty()) null else File(spec) }
    var cwd by remember { mutableStateOf(
        when { specFile?.isDirectory == true -> specFile.absolutePath; specFile?.isFile == true -> specFile.parent ?: "/"; else -> Environment.getExternalStorageDirectory().absolutePath }
    ) }
    val lines = remember { mutableStateListOf<String>() }
    var input by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var root by remember { mutableStateOf(false) }
    var confirmRoot by remember { mutableStateOf(false) }
    var proc by remember { mutableStateOf<Process?>(null) }
    val history = remember { mutableListOf<String>() }
    var hIdx by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()

    fun add(l: String) { lines.add(l); if (lines.size > 3000) lines.removeRange(0, 500) }

    fun readBoundedLine(reader: java.io.BufferedReader, limit: Int): Pair<String, Boolean>? {
        val line = StringBuilder(minOf(limit, 4096))
        var truncated = false
        var readAny = false
        while (true) {
            val ch = reader.read()
            if (ch < 0) return if (!readAny) null else line.toString() to truncated
            readAny = true
            if (ch == '\\n'.code) return line.toString() to truncated
            if (ch != '\\r'.code) {
                if (line.length < limit) line.append(ch.toChar()) else truncated = true
            }
        }
    }

    fun exec(cmd: String) {
        if (cmd.isBlank() || running) return
        add("$ ${cwd.substringAfterLast('/').ifEmpty { "/" }} > $cmd")
        if (history.lastOrNull() != cmd) history.add(cmd)
        hIdx = history.size
        val t = cmd.trim()
        if (t == "clear") { lines.clear(); return }
        if (t == "cd" || t.startsWith("cd ")) {
            val arg = t.removePrefix("cd").trim().ifEmpty { Environment.getExternalStorageDirectory().absolutePath }
            val target = if (arg.startsWith("/")) File(arg) else File(cwd, arg)
            if (target.isDirectory) cwd = target.canonicalPath else add("cd: no such directory: $arg")
            return
        }
        running = true
        scope.launch(Dispatchers.IO) {
            try {
                val shellCommand = if (root) "cd ${com.kayx.manager.data.advanced.RootShell.q(cwd)} && $cmd" else cmd
                val args = if (root) listOf("su", "-c", shellCommand) else listOf("sh", "-c", shellCommand)
                val p = ProcessBuilder(args).directory(File(cwd)).redirectErrorStream(true).start()
                runCatching { p.outputStream.close() }
                proc = p
                var captured = 0
                var truncationShown = false
                p.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val remaining = (512 * 1024 - captured).coerceAtLeast(0)
                        val line = readBoundedLine(reader, minOf(16 * 1024, remaining)) ?: break
                        if (remaining > 0) {
                            add(line.first)
                            captured += line.first.length
                        }
                        if ((line.second || captured >= 512 * 1024) && !truncationShown) {
                            add("[output truncated at 512 KiB; process continues]")
                            truncationShown = true
                        }
                    }
                }
                add("[exit ${p.waitFor()}]")
            } catch (e: Exception) { add("error: ${e.message}") }
            proc = null; running = false
        }
    }
    LaunchedEffect(Unit) { if (specFile?.isFile == true) exec("sh '" + specFile.absolutePath.replace("'", "'\\''") + "'") }
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex) }
    DisposableEffect(Unit) { onDispose { proc?.destroy() } }

    if (confirmRoot) AlertDialog(
        onDismissRequest = { confirmRoot = false },
        title = { Text("Enable root terminal?") },
        text = { Text("Commands will run with full root privileges and can permanently damage Android or erase data. Only continue with commands you understand.") },
        confirmButton = { TextButton({ root = true; confirmRoot = false }) { Text("Enable root") } },
        dismissButton = { TextButton({ confirmRoot = false }) { Text("Cancel") } }
    )

    ToolScaffold("Terminal", onBack, actions = {
        IconButton({ if (root) root = false else confirmRoot = true }) { Icon(Icons.Default.Security, "Root (su)", tint = if (root) Accent else Fg) }
        IconButton({ proc?.destroy() }, enabled = running) { Icon(Icons.Default.Stop, "Stop") }
        IconButton({ lines.clear() }) { Icon(Icons.Default.DeleteSweep, "Clear") }
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            Text(cwd + if (root) "   [root]" else "", Modifier.padding(horizontal = 14.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp), state = listState) {
                items(lines.size) { i -> Text(lines[i], style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Fg)) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(input, { input = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("command") },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { val c = input; input = ""; exec(c) }),
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                IconButton({ if (hIdx > 0) { hIdx--; input = history[hIdx] } }) { Icon(Icons.Default.KeyboardArrowUp, "Previous command") }
                IconButton({ if (hIdx < history.lastIndex) { hIdx++; input = history[hIdx] } else { hIdx = history.size; input = "" } }) { Icon(Icons.Default.KeyboardArrowDown, "Next command") }
                IconButton({ val c = input; input = ""; exec(c) }, enabled = !running) { Icon(Icons.AutoMirrored.Filled.Send, "Run", tint = Accent) }
            }
        }
    }
}

// ───────────────────────── Signing key ─────────────────────────
@Composable
fun SigningKeyScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var info by remember { mutableStateOf<ApkSignRepo.KeyInfo?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var confirmRegen by remember { mutableStateOf(false) }
    fun refresh() = scope.launch {
        val r = withContext(Dispatchers.IO) { ApkSignRepo.info(ctx.applicationContext) }
        info = r.getOrNull(); err = r.exceptionOrNull()?.message
    }
    LaunchedEffect(Unit) { refresh() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.size == 2) scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val files = uris.map { u -> Triple(ExternalOps.uriName(ctx, u).lowercase(), ExternalOps.readUri(ctx, u), u) }
                    val isKey = { t: Triple<String, ByteArray, *> -> t.first.endsWith(".pk8") || t.first.endsWith(".key") || String(t.second, Charsets.ISO_8859_1).contains("PRIVATE KEY") }
                    val key = files.firstOrNull { isKey(it) } ?: error("Select a .pk8 / .key private key file and a certificate")
                    val cert = files.firstOrNull { it !== key } ?: error("Certificate file missing")
                    ApkSignRepo.importKey(ctx.applicationContext, key.second, cert.second).getOrThrow()
                }
            }
            snack.showSnackbar(r.exceptionOrNull()?.message ?: "Key imported"); refresh()
        } else if (uris.isNotEmpty()) scope.launch { snack.showSnackbar("Select exactly 2 files: private key (.pk8/.key) + certificate (.x509/.pem/.crt)") }
    }
    ToolScaffold("Signing Key", onBack, snack) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Current key (used by Sign APK)", style = MaterialTheme.typography.titleSmall)
                    val i = info
                    if (i == null) Text(err ?: "Loading…", color = Fg.copy(.6f)) else {
                        Text("Subject: ${i.subject}", style = MaterialTheme.typography.bodySmall)
                        Text("Algorithm: ${i.algo}", style = MaterialTheme.typography.bodySmall)
                        Text("Valid until: ${DateFormat.getDateInstance().format(i.validUntil)}", style = MaterialTheme.typography.bodySmall)
                        Text("SHA-256:\n${i.sha256}", style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Fg))
                        Text("SHA-1:\n${i.sha1}", style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Fg))
                    }
                }
            }
            Button({ picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth()) { Text("Import key (.pk8 + certificate)") }
            OutlinedButton({
                scope.launch {
                    val r = withContext(Dispatchers.IO) { ApkSignRepo.exportTo(ctx.applicationContext, File(Environment.getExternalStorageDirectory(), "KayX/keys")) }
                    snack.showSnackbar(r.fold({ "Exported to $it" }, { e -> e.message ?: "Export failed" }))
                }
            }, Modifier.fillMaxWidth()) { Text("Export key (kayx.pk8 + kayx.x509)") }
            OutlinedButton({ confirmRegen = true }, Modifier.fillMaxWidth()) { Text("Generate new key", color = RED) }
            Text("Updates of an installed app must be signed with the same key as the installed version. Keep a backup of your key.",
                style = MaterialTheme.typography.labelSmall, color = Fg.copy(.6f))
        }
    }
    if (confirmRegen) AlertDialog({ confirmRegen = false }, title = { Text("Generate new key?") },
        text = { Text("The current key will be replaced. APKs signed with it can no longer be updated by new signatures. Export it first if you need it.") },
        confirmButton = { TextButton({
            confirmRegen = false
            scope.launch { val r = withContext(Dispatchers.IO) { ApkSignRepo.regenerate(ctx.applicationContext) }; snack.showSnackbar(r.exceptionOrNull()?.message ?: "New key generated"); refresh() }
        }) { Text("Generate", color = RED) } }, dismissButton = { TextButton({ confirmRegen = false }) { Text("Cancel") } })
}

// ───────────────────────── Smali / Dalvik instruction reference ─────────────────────────
private val SMALI_REF = """
nop|nop|No operation
move|move vA, vB|Copy vB to vA (32-bit)
move-wide|move-wide vA, vB|Copy 64-bit register pair
move-object|move-object vA, vB|Copy object reference
move-result|move-result vAA|Take single-word result of the last invoke
move-result-wide|move-result-wide vAA|Take 64-bit result of the last invoke
move-result-object|move-result-object vAA|Take object result of the last invoke
move-exception|move-exception vAA|Save a just-caught exception
return-void|return-void|Return from a void method
return|return vAA|Return a 32-bit value
return-wide|return-wide vAA|Return a 64-bit value
return-object|return-object vAA|Return an object reference
const/4|const/4 vA, #+B|Load 4-bit literal (sign-extended)
const/16|const/16 vAA, #+BBBB|Load 16-bit literal
const|const vAA, #+BBBBBBBB|Load 32-bit literal
const-wide|const-wide vAA, #+BBBBBBBBBBBBBBBB|Load 64-bit literal
const-string|const-string vAA, "text"|Load string reference
const-class|const-class vAA, Lpkg/Type;|Load class reference
monitor-enter|monitor-enter vAA|Acquire the monitor of an object
monitor-exit|monitor-exit vAA|Release the monitor of an object
check-cast|check-cast vAA, Lpkg/Type;|Throw ClassCastException if not assignable
instance-of|instance-of vA, vB, Lpkg/Type;|vA = 1 if vB is instance of type, else 0
array-length|array-length vA, vB|Length of array vB
new-instance|new-instance vAA, Lpkg/Type;|Allocate a new object
new-array|new-array vA, vB, [type|Create array of length vB
filled-new-array|filled-new-array {vC, vD..}, [type|Create array from registers (result via move-result-object)
fill-array-data|fill-array-data vAA, :label|Fill array from data table
throw|throw vAA|Throw the exception in vAA
goto|goto :label|Unconditional jump (also goto/16, goto/32)
packed-switch|packed-switch vAA, :table|Switch on consecutive integer cases
sparse-switch|sparse-switch vAA, :table|Switch on sparse integer cases
cmpl-float|cmpl-float vAA, vBB, vCC|Compare floats, NaN gives -1 (also cmpg-float: NaN gives 1)
cmpl-double|cmpl-double vAA, vBB, vCC|Compare doubles (also cmpg-double)
cmp-long|cmp-long vAA, vBB, vCC|Compare longs: -1, 0 or 1
if-eq|if-eq vA, vB, :label|Jump if vA == vB
if-ne|if-ne vA, vB, :label|Jump if vA != vB
if-lt|if-lt vA, vB, :label|Jump if vA < vB
if-ge|if-ge vA, vB, :label|Jump if vA >= vB
if-gt|if-gt vA, vB, :label|Jump if vA > vB
if-le|if-le vA, vB, :label|Jump if vA <= vB
if-eqz|if-eqz vAA, :label|Jump if vAA == 0 / null
if-nez|if-nez vAA, :label|Jump if vAA != 0 / non-null
if-ltz|if-ltz vAA, :label|Jump if vAA < 0
if-gez|if-gez vAA, :label|Jump if vAA >= 0
if-gtz|if-gtz vAA, :label|Jump if vAA > 0
if-lez|if-lez vAA, :label|Jump if vAA <= 0
aget|aget vAA, vBB, vCC|vAA = array vBB[vCC] (variants: -wide -object -boolean -byte -char -short)
aput|aput vAA, vBB, vCC|array vBB[vCC] = vAA (same variants)
iget|iget vA, vB, Lpkg/C;->name:T|Read instance field (variants: -wide -object -boolean -byte -char -short)
iput|iput vA, vB, Lpkg/C;->name:T|Write instance field (same variants)
sget|sget vAA, Lpkg/C;->name:T|Read static field (same variants)
sput|sput vAA, Lpkg/C;->name:T|Write static field (same variants)
invoke-virtual|invoke-virtual {vC, ..}, Lpkg/C;->m(args)R|Call a virtual method
invoke-super|invoke-super {vC, ..}, Lpkg/C;->m(args)R|Call the superclass method
invoke-direct|invoke-direct {vC, ..}, Lpkg/C;-><init>(args)V|Call constructor / private method
invoke-static|invoke-static {vC, ..}, Lpkg/C;->m(args)R|Call a static method
invoke-interface|invoke-interface {vC, ..}, Lpkg/I;->m(args)R|Call an interface method
invoke-virtual/range|invoke-virtual/range {vN .. vM}, meth|Range form (also -super -direct -static -interface)
invoke-polymorphic|invoke-polymorphic {..}, meth, proto|Call a signature-polymorphic method (API 26+)
invoke-custom|invoke-custom {..}, call_site|Call a call site (API 26+)
neg-int|neg-int vA, vB|vA = -vB (also not-int, neg-long, not-long, neg-float, neg-double)
int-to-long|int-to-long vA, vB|Widen int (also int-to-float, int-to-double, long-to-int, long-to-float, long-to-double, float-to-int ...)
int-to-byte|int-to-byte vA, vB|Narrow int (also int-to-char, int-to-short)
add-int|add-int vAA, vBB, vCC|vAA = vBB + vCC
sub-int|sub-int vAA, vBB, vCC|vAA = vBB - vCC
mul-int|mul-int vAA, vBB, vCC|vAA = vBB * vCC
div-int|div-int vAA, vBB, vCC|vAA = vBB / vCC (throws on /0)
rem-int|rem-int vAA, vBB, vCC|vAA = vBB % vCC
and-int|and-int vAA, vBB, vCC|Bitwise AND (also or-int, xor-int)
shl-int|shl-int vAA, vBB, vCC|Shift left (also shr-int, ushr-int)
add-int/2addr|add-int/2addr vA, vB|vA = vA + vB (all binary ops have /2addr, /lit16, /lit8 forms; long/float/double versions exist)
add-int/lit8|add-int/lit8 vAA, vBB, #+CC|vAA = vBB + literal
rsub-int|rsub-int vA, vB, #+CCCC|vA = literal - vB
.class|.class public Lpkg/Name;|Declare the class
.super|.super Ljava/lang/Object;|Declare the superclass
.implements|.implements Lpkg/Iface;|Implemented interface
.field|.field private name:I|Declare a field (add = value for constants)
.method|.method public name(args)R|Begin a method (.end method to finish)
.locals|.locals N|Number of non-parameter registers (v0..vN-1)
.registers|.registers N|Total registers incl. parameters
.param|.param p1, "name"|Name a parameter
.line|.line N|Source line number marker
.prologue|.prologue|Marks the end of the method prologue
.annotation|.annotation runtime Lpkg/A;|Begin annotation (.end annotation)
.catch|.catch Lpkg/Ex; {:start .. :end} :handler|Exception handler range
.catchall|.catchall {:start .. :end} :handler|Catch any exception
.sparse-switch|.sparse-switch|Data table for sparse-switch
.packed-switch|.packed-switch N|Data table for packed-switch
.array-data|.array-data W|Data table for fill-array-data
""".trimIndent().lines().filter { it.isNotBlank() }.map { it.split('|') }.filter { it.size == 3 }

@Composable
fun SmaliRefScreen(onBack: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val shown = remember(q) { SMALI_REF.filter { q.isBlank() || it[0].contains(q.trim(), true) || it[2].contains(q.trim(), true) } }
    ToolScaffold("Smali instructions", onBack) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp), singleLine = true, placeholder = { Text("Search opcode or description") })
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(shown) { r ->
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(r[1], style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Accent))
                            Text(r[2], style = MaterialTheme.typography.bodySmall, color = Fg.copy(.8f))
                        }
                    }
                }
            }
        }
    }
}
