package com.kayx.manager.feature.tools

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kayx.manager.data.network.FtpClient
import com.kayx.manager.data.network.NetworkCredentialStore
import com.kayx.manager.data.filesystem.FileOps
import com.kayx.manager.data.storage.formatBytes
import com.kayx.manager.ui.glass.GlassCard
import com.kayx.manager.ui.theme.Accent
import com.kayx.manager.ui.theme.Fg
import com.kayx.manager.ui.theme.TextMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

/** Plain FTP browser: connect, browse, download to KayX/ftp, upload, rename, delete, new folder. */
@Composable
fun FtpScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val client = remember { FtpClient() }
    val credentialStore = remember { NetworkCredentialStore(ctx) }
    DisposableEffect(Unit) { onDispose { Thread { client.disconnect() }.start() } }

    val prefs = remember { ctx.getSharedPreferences("ftp", Context.MODE_PRIVATE) }
    var host by remember { mutableStateOf(prefs.getString("host", "") ?: "") }
    var port by remember { mutableStateOf(prefs.getString("port", "21") ?: "21") }
    var user by remember { mutableStateOf(prefs.getString("user", "anonymous") ?: "anonymous") }
    var pass by remember { mutableStateOf("") }
    var useFtps by remember { mutableStateOf(false) }
    var rememberPassword by remember { mutableStateOf(false) }
    val profileKey = remember(host, port, user) { "$host:$port:$user" }
    LaunchedEffect(profileKey) {
        val saved = withContext(Dispatchers.IO) {
            runCatching { credentialStore.read(profileKey) }.getOrElse {
                snack.showSnackbar(it.message ?: "Saved password could not be read")
                null
            }
        }
        rememberPassword = saved != null
        if (saved != null) pass = saved
    }

    var connected by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var path by remember { mutableStateOf("/") }
    var entries by remember { mutableStateOf<List<FtpClient.Entry>>(emptyList()) }
    var progress by remember { mutableStateOf("") }
    var nameDialog by remember { mutableStateOf<Pair<String, String>?>(null) } // mode ("mkdir"/"rename:<old>") to text
    var deleteTarget by remember { mutableStateOf<FtpClient.Entry?>(null) }

    fun run(block: () -> Unit, after: () -> Unit = {}) {
        if (busy) return
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching(block) }
            busy = false; progress = ""
            r.onFailure { snack.showSnackbar(it.message ?: "FTP error") }.onSuccess { after() }
        }
    }

    fun refresh() = run({ val l = client.list(); path = client.pwd(); entries = l })

    fun download(e: FtpClient.Entry) {
        run({
            val dir = File(Environment.getExternalStorageDirectory(), "KayX/ftp")
            check(dir.isDirectory || dir.mkdirs()) { "Cannot create KayX/ftp download folder" }
            val target = FileOps.uniqueName(dir, e.name.substringAfterLast('/'))
            val partial = File(dir, ".${target.name}.kayx-${System.nanoTime()}.part")
            try {
                FileOutputStream(partial).use { out ->
                    client.download(e.name, out) { progress = "${e.name}: ${formatBytes(it)}" }
                    out.fd.sync()
                }
                // Files.move without REPLACE_EXISTING guarantees an existing download is never overwritten.
                Files.move(partial.toPath(), target.toPath())
            } catch (failure: Throwable) {
                partial.delete()
                throw failure
            }
        }, { scope.launch { snack.showSnackbar("Saved to KayX/ftp/${e.name}") } })
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) run({
            val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            } ?: "upload.bin"
            val ins = ctx.contentResolver.openInputStream(uri) ?: error("Cannot read the selected file")
            ins.use { client.upload(name, it) { n -> progress = "$name: ${formatBytes(n)}" } }
        }, { refresh() })
    }

    ToolScaffold(
        if (connected) (if (useFtps) "FTPS · $host" else "FTP · $host") else "FTP client",
        { if (connected) { Thread { client.disconnect() }.start(); connected = false } else onBack() },
        snack,
        actions = {
            if (connected) {
                IconButton({ run({ client.up(); path = client.pwd(); entries = client.list() }) }, enabled = !busy) { Icon(Icons.Default.ArrowUpward, "Up") }
                IconButton({ nameDialog = "mkdir" to "" }, enabled = !busy) { Icon(Icons.Default.CreateNewFolder, "New folder") }
                IconButton({ picker.launch("*/*") }, enabled = !busy) { Icon(Icons.Default.Upload, "Upload") }
                IconButton({ refresh() }, enabled = !busy) { Icon(Icons.Default.Refresh, "Refresh") }
                if (busy) IconButton({
                    client.cancelTransfer()
                    connected = false
                    busy = false
                    progress = ""
                    scope.launch { snack.showSnackbar("Transfer cancelled; reconnect before continuing.") }
                }) { Icon(Icons.Default.Close, "Cancel transfer") }
            }
        }
    ) { pad ->
        if (!connected) {
            Column(
                Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(host, { host = it.trim() }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Host (e.g. 192.168.1.5)") })
                OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Port") })
                OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Username") })
                OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useFtps, onCheckedChange = { useFtps = it })
                    Text("Use explicit FTPS (TLS)", style = MaterialTheme.typography.bodyMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = rememberPassword, onCheckedChange = { checked ->
                        rememberPassword = checked
                        scope.launch(Dispatchers.IO) {
                            if (checked) {
                                val saved = runCatching { credentialStore.read(profileKey) }.getOrNull()
                                if (saved != null) withContext(Dispatchers.Main) { pass = saved }
                            } else {
                                runCatching { credentialStore.remove(profileKey) }
                            }
                        }
                    })
                    Text("Remember password securely", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    if (useFtps) "TLS certificate and hostname are verified. The password is not saved."
                    else "Plain FTP sends credentials and files without encryption. Use only on trusted networks. The password is not saved.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted
                )
                Button({
                    prefs.edit().putString("host", host).putString("port", port).putString("user", user).apply()
                    run({
                        client.connect(host, port.toIntOrNull() ?: 21, user, pass, secure = useFtps)
                        try {
                            val currentPath = client.pwd()
                            val currentEntries = client.list()
                            if (rememberPassword && pass.isNotEmpty()) credentialStore.save(profileKey, pass)
                            path = currentPath
                            entries = currentEntries
                            connected = true
                        } catch (failure: Throwable) {
                            client.disconnect()
                            throw failure
                        }
                    })
                }, Modifier.fillMaxWidth(), enabled = !busy && host.isNotBlank()) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White) else Text("Connect")
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(path, style = MaterialTheme.typography.labelMedium, color = Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (progress.isNotEmpty()) Text(progress, style = MaterialTheme.typography.labelSmall, color = TextMuted)
                val sorted = remember(entries) { entries.sortedWith(compareBy({ !it.dir }, { it.name.lowercase() })) }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                    items(sorted) { e ->
                        var menu by remember { mutableStateOf(false) }
                        GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp), onClick = {
                            if (!busy) { if (e.dir) run({ client.cwd(e.name); path = client.pwd(); entries = client.list() }) else download(e) }
                        }) {
                            Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (e.dir) Icons.Default.Folder else Icons.Default.InsertDriveFile, null, tint = if (e.dir) Accent else Fg.copy(.7f))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text((if (e.dir) "Folder" else formatBytes(e.size)) + if (e.modified.isNotEmpty()) "  ·  ${e.modified}" else "",
                                        style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                }
                                Box {
                                    IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "Actions") }
                                    DropdownMenu(menu, { menu = false }) {
                                        if (!e.dir) DropdownMenuItem({ Text("Download") }, { menu = false; download(e) })
                                        DropdownMenuItem({ Text("Rename") }, { menu = false; nameDialog = "rename" to e.name })
                                        DropdownMenuItem({ Text("Delete", color = Color(0xFFFF6B6B)) }, { menu = false; deleteTarget = e })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    nameDialog?.let { (mode, initial) ->
        var text by remember(mode, initial) { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = { nameDialog = null },
            title = { Text(if (mode == "mkdir") "New folder" else "Rename") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = {
                TextButton({
                    val n = text.trim(); nameDialog = null
                    if (n.isNotEmpty()) run({ if (mode == "mkdir") client.mkdir(n) else client.rename(initial, n) }, { refresh() })
                }) { Text("OK") }
            },
            dismissButton = { TextButton({ nameDialog = null }) { Text("Cancel") } }
        )
    }
    deleteTarget?.let { t ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete ${t.name}?") },
            text = { Text(if (t.dir) "The folder must be empty on the server." else "This cannot be undone.") },
            confirmButton = {
                TextButton({
                    deleteTarget = null
                    run({ if (t.dir) client.rmdir(t.name) else client.delete(t.name) }, { refresh() })
                }) { Text("Delete", color = Color(0xFFFF6B6B)) }
            },
            dismissButton = { TextButton({ deleteTarget = null }) { Text("Cancel") } }
        )
    }
}
