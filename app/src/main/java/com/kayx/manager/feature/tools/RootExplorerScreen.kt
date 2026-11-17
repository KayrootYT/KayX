package com.kayx.manager.feature.tools

import android.os.Environment
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kayx.manager.data.advanced.RootShell
import com.kayx.manager.data.storage.formatBytes
import com.kayx.manager.ui.glass.GlassCard
import com.kayx.manager.ui.theme.Accent
import com.kayx.manager.ui.theme.Fg
import com.kayx.manager.ui.theme.TextMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Browse the filesystem as root or through Shizuku (chosen in Settings). View, copy out, change permissions, delete. */
@Composable
fun RootExplorerScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var rooted by remember { mutableStateOf<Boolean?>(null) }
    var path by remember { mutableStateOf("/") }
    var nodes by remember { mutableStateOf<List<RootShell.Node>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Pair<String, String>?>(null) }
    var chmodFor by remember { mutableStateOf<RootShell.Node?>(null) }
    var deleteFor by remember { mutableStateOf<RootShell.Node?>(null) }

    fun full(n: RootShell.Node) = if (path == "/") "/" + n.name else path.trimEnd('/') + "/" + n.name

    fun load(p: String) {
        scope.launch {
            loading = true
            val r = withContext(Dispatchers.IO) { RootShell.list(p) }
            loading = false
            r.onSuccess { path = p; nodes = it }.onFailure { snack.showSnackbar(it.message ?: "Cannot open $p") }
        }
    }

    fun up() { if (path != "/") load(path.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" }) }

    fun act(label: String, cmd: () -> RootShell.Res) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching(cmd) }
            val msg = r.fold({ if (it.code == 0) "$label: done" else it.err.trim().ifBlank { "$label failed (exit ${it.code})" } }, { it.message ?: "$label failed" })
            snack.showSnackbar(msg)
            load(path)
        }
    }

    LaunchedEffect(Unit) {
        val ok = withContext(Dispatchers.IO) { RootShell.isRoot() }
        rooted = ok
        if (ok) load("/")
    }

    ToolScaffold(
        if (com.kayx.manager.data.settings.SettingsStore.backend == com.kayx.manager.data.settings.PrivilegedBackend.SHIZUKU) "Shizuku explorer" else "Root explorer", { if (rooted == true && path != "/") up() else onBack() }, snack,
        actions = { if (rooted == true) IconButton({ load(path) }) { Icon(Icons.Default.Refresh, "Refresh") } }
    ) { pad ->
        when (rooted) {
            null -> Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) { CircularProgressIndicator(color = Accent) }
            false -> Box(Modifier.fillMaxSize().padding(pad).padding(24.dp), Alignment.Center) {
                Text(
                    if (com.kayx.manager.data.settings.SettingsStore.backend == com.kayx.manager.data.settings.PrivilegedBackend.SHIZUKU)
                        "Shizuku is not ready. Start Shizuku, then open Settings → Advanced access → Shizuku and allow KayX."
                    else "Root access was not granted. This tool needs a rooted device and a root manager that allows KayX.",
                    color = Fg.copy(.7f)
                )
            }
            true -> Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("/", "/data", "/data/data", "/system", "/vendor", "/sdcard").forEach { p ->
                        AssistChip({ load(p) }, { Text(p) })
                    }
                }
                Text(path, style = MaterialTheme.typography.labelMedium, color = Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                    items(nodes) { n ->
                        var menu by remember { mutableStateOf(false) }
                        GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp), onClick = {
                            if (n.dir || n.link) load(full(n))
                            else scope.launch {
                                val r = withContext(Dispatchers.IO) { RootShell.head(full(n)) }
                                if (r.code == 0) viewing = n.name to r.out else snack.showSnackbar(r.err.trim().ifBlank { "Cannot read file" })
                            }
                        }) {
                            Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    when { n.link -> Icons.Default.Link; n.dir -> Icons.Default.Folder; else -> Icons.Default.InsertDriveFile },
                                    null, tint = if (n.dir) Accent else Fg.copy(.7f)
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(n.name + (n.target?.let { " → $it" } ?: ""), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${n.perms}  ${n.owner}" + if (!n.dir && !n.link) "  ·  ${formatBytes(n.size)}" else "",
                                        fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Box {
                                    IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "Actions") }
                                    DropdownMenu(menu, { menu = false }) {
                                        DropdownMenuItem({ Text("Copy to storage (KayX/root)") }, {
                                            menu = false
                                            val dest = Environment.getExternalStorageDirectory().absolutePath + "/KayX/root"
                                            act("Copy") { RootShell.copyOut(full(n), dest) }
                                        })
                                        DropdownMenuItem({ Text("Permissions…") }, { menu = false; chmodFor = n })
                                        DropdownMenuItem({ Text("Delete…", color = Color(0xFFFF6B6B)) }, { menu = false; deleteFor = n })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    viewing?.let { (name, text) ->
        AlertDialog(
            onDismissRequest = { viewing = null },
            title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                SelectionContainer {
                    Text(text.ifEmpty { "(empty)" }, Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            },
            confirmButton = { TextButton({ viewing = null }) { Text("Close") } }
        )
    }
    chmodFor?.let { n ->
        var mode by remember(n) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { chmodFor = null },
            title = { Text("Permissions: ${n.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Current: ${n.perms}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    OutlinedTextField(mode, { mode = it.filter(Char::isDigit).take(4) }, singleLine = true, label = { Text("Octal mode, e.g. 644") })
                }
            },
            confirmButton = { TextButton({ val t = n; chmodFor = null; act("chmod") { RootShell.chmod(mode, full(t)) } }, enabled = mode.length >= 3) { Text("Apply") } },
            dismissButton = { TextButton({ chmodFor = null }) { Text("Cancel") } }
        )
    }
    deleteFor?.let { n ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Delete ${n.name}?") },
            text = { Text("${full(n)} will be removed with root rights. This cannot be undone and can break your system.") },
            confirmButton = { TextButton({ deleteFor = null; act("Delete") { RootShell.delete(full(n)) } }) { Text("Delete", color = Color(0xFFFF6B6B)) } },
            dismissButton = { TextButton({ deleteFor = null }) { Text("Cancel") } }
        )
    }
}
