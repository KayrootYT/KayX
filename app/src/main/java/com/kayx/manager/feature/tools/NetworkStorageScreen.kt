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
import com.kayx.manager.data.filesystem.FileOps
import com.kayx.manager.data.network.FtpClient
import com.kayx.manager.data.network.NetworkCredentialStore
import com.kayx.manager.data.network.SftpClient
import com.kayx.manager.data.network.SmbClient
import com.kayx.manager.data.network.WebDavClient
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
import java.io.FileInputStream
import java.security.DigestInputStream
import java.util.Properties
import java.nio.file.Files
import java.security.MessageDigest

private enum class StorageProtocol(val title: String, val defaultPort: String) {
    FTP("FTP", "21"), FTPS("Explicit FTPS", "21"), SFTP("SFTP", "22"), SMB("SMB3", "445"), WEBDAV("WebDAV over HTTPS", "443")
}

/** Unified network browser for FTP/FTPS, strict-host-key SFTP, encrypted SMB3 and HTTPS WebDAV. */
@Composable
fun NetworkStorageScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val credentials = remember { NetworkCredentialStore(context) }
    val ftp = remember { FtpClient() }
    val sftp = remember { SftpClient() }
    val smb = remember { SmbClient() }
    val webDav = remember { WebDavClient() }
    DisposableEffect(Unit) {
        onDispose { Thread { ftp.disconnect(); sftp.disconnect(); smb.disconnect(); webDav.disconnect() }.start() }
    }

    var protocol by remember { mutableStateOf(StorageProtocol.FTP) }
    var protocolMenu by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("21") }
    var username by remember { mutableStateOf("anonymous") }
    var password by remember { mutableStateOf("") }
    var shareName by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }
    var rememberPassword by remember { mutableStateOf(false) }
    var knownHostsReady by remember { mutableStateOf(false) }
    val knownHosts = remember { File(context.filesDir, "network/known_hosts") }
    var connected by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var path by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<FtpClient.Entry>>(emptyList()) }
    var progress by remember { mutableStateOf("") }
    var nameDialog by remember { mutableStateOf<Triple<String, String, Boolean>?>(null) }
    var deleteTarget by remember { mutableStateOf<FtpClient.Entry?>(null) }
    val profileKey = remember(protocol, host, port, username, shareName, domain) {
        "${protocol.name}|${host.trim()}|${port.trim()}|${username.trim()}|${shareName.trim()}|${domain.trim()}"
    }

    LaunchedEffect(profileKey) {
        val result = withContext(Dispatchers.IO) { runCatching { credentials.read(profileKey) } }
        val saved = result.getOrNull()
        if (result.isFailure) snack.showSnackbar(result.exceptionOrNull()?.message ?: "Saved password could not be read")
        rememberPassword = saved != null
        if (saved != null) password = saved
    }

    fun remote(name: String): String = when (protocol) {
        StorageProtocol.FTP, StorageProtocol.FTPS -> name
        StorageProtocol.SFTP -> (path.trimEnd('/') + "/" + name).ifBlank { "/$name" }
        StorageProtocol.SMB -> listOf(path.trim('\\'), name).filter { it.isNotBlank() }.joinToString("\\")
        StorageProtocol.WEBDAV -> listOf(path.trim('/'), name).filter { it.isNotBlank() }.joinToString("/")
    }

    fun listCurrent() {
        entries = when (protocol) {
            StorageProtocol.FTP, StorageProtocol.FTPS -> ftp.list()
            StorageProtocol.SFTP -> sftp.list(path.ifBlank { "/" }).map { FtpClient.Entry(it.name, it.directory, it.size, it.modifiedEpochSeconds.toString()) }
            StorageProtocol.SMB -> smb.list(path).map { FtpClient.Entry(it.name, it.directory, it.size, "") }
            StorageProtocol.WEBDAV -> webDav.list(path).map { FtpClient.Entry(it.name, it.directory, it.size, it.modified) }
        }
        if (protocol == StorageProtocol.FTP || protocol == StorageProtocol.FTPS) path = ftp.pwd()
    }

    fun action(block: () -> Unit, after: () -> Unit = {}) {
        if (busy) return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(block) }
            busy = false
            progress = ""
            result.onFailure { snack.showSnackbar(it.message ?: "Network operation failed") }
                .onSuccess { after() }
        }
    }

    fun refresh() = action({ listCurrent() })

    fun disconnect() {
        Thread { ftp.disconnect(); sftp.disconnect(); smb.disconnect(); webDav.disconnect() }.start()
        connected = false
        entries = emptyList()
        path = ""
    }

    fun openDirectory(entry: FtpClient.Entry) {
        if (busy) return
        action({
            when (protocol) {
                StorageProtocol.FTP, StorageProtocol.FTPS -> ftp.cwd(entry.name)
                else -> path = remote(entry.name)
            }
            listCurrent()
        })
    }

    fun download(entry: FtpClient.Entry) {
        action({
            val directory = File(Environment.getExternalStorageDirectory(), "KayX/network")
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create KayX/network download folder" }
            val target = FileOps.uniqueName(directory, entry.name)
            // Keep stable partials only for protocols that verify the remote object before resuming.
            val resumableSftp = protocol == StorageProtocol.SFTP
            val resumableFtp = protocol == StorageProtocol.FTP || protocol == StorageProtocol.FTPS
            val resumableSmb = protocol == StorageProtocol.SMB
            val resumableWebDav = protocol == StorageProtocol.WEBDAV
            val resumable = resumableSftp || resumableFtp || resumableSmb || resumableWebDav
            val resumeIdentity = "$profileKey|$path|${entry.name}"
            val resumeKey = MessageDigest.getInstance("SHA-256")
                .digest(resumeIdentity.toByteArray(Charsets.UTF_8))
                .take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val partial = File(directory, if (resumable) ".kayx-$resumeKey.part" else ".kayx-${System.nanoTime()}.part")
            val smbMetadata = File(directory, ".kayx-$resumeKey.smbmeta")
            try {
                val offset = if (resumable && partial.isFile) partial.length() else 0L
                FileOutputStream(partial, resumable && offset > 0L).use { output ->
                    when (protocol) {
                        StorageProtocol.FTP, StorageProtocol.FTPS -> ftp.downloadResumable(entry.name, output, offset) { progress = "${entry.name}: ${formatBytes(it)}" }
                        StorageProtocol.SFTP -> sftp.downloadResumable(remote(entry.name), output, offset) { progress = "${entry.name}: ${formatBytes(it)}" }
                        StorageProtocol.SMB -> {
                            val expectedFingerprint = if (offset > 0L && smbMetadata.isFile) smbMetadata.readText(Charsets.UTF_8) else null
                            smb.downloadResumable(remote(entry.name), output, offset, expectedFingerprint, { fingerprint ->
                                val temporaryMetadata = File(directory, "${smbMetadata.name}.tmp")
                                FileOutputStream(temporaryMetadata).use { metadataOutput ->
                                    metadataOutput.write(fingerprint.toByteArray(Charsets.UTF_8))
                                    metadataOutput.fd.sync()
                                }
                                Files.move(temporaryMetadata.toPath(), smbMetadata.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                            }) { progress = "${entry.name}: ${formatBytes(it)}" }
                        }
                        StorageProtocol.WEBDAV -> webDav.downloadResumable(remote(entry.name), output, offset) { progress = "${entry.name}: ${formatBytes(it)}" }
                    }
                    output.fd.sync()
                }
                Files.move(partial.toPath(), target.toPath())
                runCatching { smbMetadata.delete() }
            } catch (failure: Throwable) {
                if (failure is SmbClient.SmbResumeMismatchException) {
                    runCatching { partial.delete() }
                    runCatching { smbMetadata.delete() }
                } else if (!resumable) {
                    runCatching { partial.delete() }
                }
                throw failure
            }
        }, { scope.launch { snack.showSnackbar("Saved to KayX/network/${entry.name}") } })
    }

    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) action({
            val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }?.takeIf { it.isNotBlank() } ?: "upload.bin"
            val uploadDirectory = File(context.filesDir, "network/uploads")
            check(uploadDirectory.isDirectory || uploadDirectory.mkdirs()) { "Cannot create private upload staging folder" }
            val identity = "$profileKey|$path|$name"
            val identityKey = MessageDigest.getInstance("SHA-256")
                .digest(identity.toByteArray(Charsets.UTF_8)).take(12)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val staged = File(uploadDirectory, "$identityKey.source")
            val stagedCandidate = File(uploadDirectory, "$identityKey.source.new")
            val metadata = File(uploadDirectory, "$identityKey.properties")
            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)?.use { source ->
                FileOutputStream(stagedCandidate).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            } ?: error("Cannot read selected file")
            val sourceHash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val sourceSize = stagedCandidate.length()
            val previous = Properties().apply {
                if (metadata.isFile) metadata.inputStream().use { load(it) }
            }
            val sameSource = staged.isFile && staged.length() == sourceSize &&
                previous.getProperty("sourceSha256") == sourceHash &&
                previous.getProperty("profile") == profileKey &&
                previous.getProperty("destination") == remote(name)
            if (sameSource) stagedCandidate.delete()
            else {
                Files.move(stagedCandidate.toPath(), staged.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                previous.clear()
            }
            val destination = remote(name)
            val suffix = sourceHash.take(20)
            val temporary = when (protocol) {
                StorageProtocol.FTP, StorageProtocol.FTPS -> ".kayx-upload-$identityKey-$suffix.part"
                StorageProtocol.SFTP -> destination.substringBeforeLast('/', "").let { parent ->
                    if (parent.isEmpty()) ".kayx-upload-$identityKey-$suffix.part" else "$parent/.kayx-upload-$identityKey-$suffix.part"
                }
                StorageProtocol.SMB -> destination.substringBeforeLast('\\', "").let { parent ->
                    if (parent.isEmpty()) ".kayx-upload-$identityKey-$suffix.part" else "$parent\\.kayx-upload-$identityKey-$suffix.part"
                }
                StorageProtocol.WEBDAV -> ""
            }
            var offset = if (sameSource) previous.getProperty("offset")?.toLongOrNull() ?: 0L else 0L
            if (offset !in 0L..sourceSize) offset = 0L
            fun persistUploadState(acknowledged: Long) {
                previous.setProperty("sourceSha256", sourceHash)
                previous.setProperty("size", sourceSize.toString())
                previous.setProperty("profile", profileKey)
                previous.setProperty("destination", destination)
                previous.setProperty("temporary", temporary)
                previous.setProperty("offset", acknowledged.coerceIn(0L, sourceSize).toString())
                val pending = File(uploadDirectory, "$identityKey.properties.new")
                FileOutputStream(pending).use { output ->
                    previous.store(output, "KayX resumable network upload; contains no credentials")
                    output.fd.sync()
                }
                Files.move(pending.toPath(), metadata.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            }
            if (!sameSource || previous.getProperty("temporary") != temporary) {
                offset = 0L
                previous.clear()
                persistUploadState(0L)
            } else {
                // Ensure the latest source identity and destination are durable before network I/O.
                persistUploadState(offset)
            }
            FileInputStream(staged).use { input ->
                skipUploadBytes(input, offset)
                when (protocol) {
                    StorageProtocol.FTP, StorageProtocol.FTPS ->
                        ftp.uploadResumable(destination, temporary, input, offset, sourceSize) {
                            persistUploadState(it)
                            progress = "$name: ${formatBytes(it)} / ${formatBytes(sourceSize)}"
                        }
                    StorageProtocol.SFTP ->
                        sftp.uploadResumable(destination, temporary, input, offset, sourceSize) {
                            persistUploadState(it)
                            progress = "$name: ${formatBytes(it)} / ${formatBytes(sourceSize)}"
                        }
                    StorageProtocol.SMB ->
                        smb.uploadResumable(destination, temporary, input, offset, sourceSize) {
                            persistUploadState(it)
                            progress = "$name: ${formatBytes(it)} / ${formatBytes(sourceSize)}"
                        }
                    StorageProtocol.WEBDAV ->
                        webDav.upload(destination, input) {
                            progress = "$name: ${formatBytes(it)} / ${formatBytes(sourceSize)} (WebDAV atomic upload; resume not supported)"
                        }
                }
            }
            staged.delete()
            metadata.delete()
        }, { listCurrent() })
    }

    val knownHostsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) action({
            val directory = knownHosts.parentFile!!
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create private network settings folder" }
            context.contentResolver.openInputStream(uri)?.use { input ->
                knownHosts.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Cannot read known_hosts file")
            check(knownHosts.isFile && knownHosts.length() in 1..(1024L * 1024L)) { "Invalid known_hosts file" }
            knownHostsReady = true
        }, { scope.launch { snack.showSnackbar("SSH known_hosts imported into app-private storage") } })
    }

    ToolScaffold(
        if (connected) "${protocol.title} · ${host}" else "Network storage",
        { if (connected) disconnect() else onBack() },
        snack,
        actions = {
            if (connected) {
                IconButton({ action({
                    if (protocol == StorageProtocol.FTP || protocol == StorageProtocol.FTPS) ftp.up()
                    else path = path.substringBeforeLast(if (protocol == StorageProtocol.SMB) "\\" else "/", "")
                    listCurrent()
                }) }, enabled = !busy) { Icon(Icons.Default.ArrowUpward, "Up") }
                IconButton({ nameDialog = Triple("mkdir", "", false) }, enabled = !busy) { Icon(Icons.Default.CreateNewFolder, "New folder") }
                IconButton({ uploadPicker.launch("*/*") }, enabled = !busy) { Icon(Icons.Default.Upload, "Upload") }
                IconButton({ refresh() }, enabled = !busy) { Icon(Icons.Default.Refresh, "Refresh") }
                if (busy) IconButton({
                    when (protocol) {
                        StorageProtocol.FTP, StorageProtocol.FTPS -> ftp.cancelTransfer()
                        StorageProtocol.SFTP -> sftp.cancelTransfer()
                        StorageProtocol.SMB -> smb.cancelTransfer()
                        StorageProtocol.WEBDAV -> webDav.cancelRequest()
                    }
                    connected = false
                    busy = false
                    progress = ""
                }) { Icon(Icons.Default.Close, "Cancel transfer") }
            }
        }
    ) { padding ->
        if (!connected) {
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box {
                    OutlinedButton({ protocolMenu = true }, Modifier.fillMaxWidth()) {
                        Text(protocol.title, Modifier.weight(1f))
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                    DropdownMenu(protocolMenu, { protocolMenu = false }) {
                        StorageProtocol.entries.forEach { item ->
                            DropdownMenuItem(text = { Text(item.title) }, onClick = {
                                protocol = item
                                port = item.defaultPort
                                username = if (item == StorageProtocol.FTP || item == StorageProtocol.FTPS) "anonymous" else ""
                                protocolMenu = false
                            })
                        }
                    }
                }
                OutlinedTextField(
                    host, { host = it.trim() }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(if (protocol == StorageProtocol.WEBDAV) "HTTPS WebDAV URL" else "Server host") }
                )
                if (protocol != StorageProtocol.WEBDAV) {
                    OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Port") })
                }
                if (protocol == StorageProtocol.SMB) {
                    OutlinedTextField(shareName, { shareName = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Share name") })
                    OutlinedTextField(domain, { domain = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Domain (optional)") })
                }
                if (protocol == StorageProtocol.SFTP) {
                    OutlinedButton({ knownHostsPicker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.VerifiedUser, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (knownHostsReady || knownHosts.isFile) "SSH known_hosts imported" else "Import verified SSH known_hosts")
                    }
                    Text("SFTP refuses unknown host keys. Import a known_hosts file whose fingerprint you verified independently.", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                }
                OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Username") })
                OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(rememberPassword, { checked ->
                        rememberPassword = checked
                        if (!checked) scope.launch(Dispatchers.IO) { runCatching { credentials.remove(profileKey) } }
                        else scope.launch(Dispatchers.IO) { runCatching { credentials.read(profileKey) }.getOrNull()?.let { saved -> withContext(Dispatchers.Main) { password = saved } } }
                    })
                    Text("Remember password securely")
                }
                Text(
                    when (protocol) {
                        StorageProtocol.FTP -> "FTP is unencrypted. Use only on a trusted network."
                        StorageProtocol.FTPS -> "Explicit FTPS verifies the server certificate and hostname; no TLS downgrade is allowed."
                        StorageProtocol.SFTP -> "SSH host keys are strictly checked against the imported known_hosts file."
                        StorageProtocol.SMB -> "SMB3 encryption and signing are required. Servers without SMB3 encryption are rejected."
                        StorageProtocol.WEBDAV -> "HTTPS only. Certificate checks stay enabled and redirects are rejected."
                    },
                    style = MaterialTheme.typography.labelSmall, color = TextMuted
                )
                Button({
                    action({
                        when (protocol) {
                            StorageProtocol.FTP -> ftp.connect(host, port.toIntOrNull() ?: 21, username, password, secure = false)
                            StorageProtocol.FTPS -> ftp.connect(host, port.toIntOrNull() ?: 21, username, password, secure = true)
                            StorageProtocol.SFTP -> sftp.connect(host, port.toIntOrNull() ?: 22, username, password, knownHosts)
                            StorageProtocol.SMB -> smb.connect(host, port.toIntOrNull() ?: 445, shareName, username, password, domain)
                            StorageProtocol.WEBDAV -> webDav.connect(host, username, password)
                        }
                        try {
                            path = when (protocol) {
                                StorageProtocol.FTP, StorageProtocol.FTPS -> ftp.pwd()
                                StorageProtocol.SFTP -> "/"
                                else -> ""
                            }
                            listCurrent()
                            if (rememberPassword && password.isNotEmpty()) credentials.save(profileKey, password)
                            connected = true
                        } catch (failure: Throwable) {
                            when (protocol) {
                                StorageProtocol.FTP, StorageProtocol.FTPS -> ftp.disconnect()
                                StorageProtocol.SFTP -> sftp.disconnect()
                                StorageProtocol.SMB -> smb.disconnect()
                                StorageProtocol.WEBDAV -> webDav.disconnect()
                            }
                            throw failure
                        }
                    })
                }, Modifier.fillMaxWidth(), enabled = !busy && host.isNotBlank() && (protocol != StorageProtocol.SFTP || knownHosts.isFile) && (protocol != StorageProtocol.SMB || shareName.isNotBlank())) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White) else Text("Connect")
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(path.ifBlank { "/" }, style = MaterialTheme.typography.labelMedium, color = Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (progress.isNotEmpty()) Text(progress, style = MaterialTheme.typography.labelSmall, color = TextMuted)
                val sorted = remember(entries) { entries.sortedWith(compareBy({ !it.dir }, { it.name.lowercase() })) }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                    items(sorted) { entry ->
                        var menu by remember { mutableStateOf(false) }
                        GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp), onClick = {
                            if (!busy) {
                                if (entry.dir) openDirectory(entry) else download(entry)
                            }
                        }) {
                            Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (entry.dir) Icons.Default.Folder else Icons.Default.InsertDriveFile, null, tint = if (entry.dir) Accent else Fg.copy(.7f))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text((if (entry.dir) "Folder" else formatBytes(entry.size)) + if (entry.modified.isNotEmpty()) " · ${entry.modified}" else "",
                                        style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                }
                                Box {
                                    IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "Actions") }
                                    DropdownMenu(menu, { menu = false }) {
                                        if (!entry.dir) DropdownMenuItem(text = { Text("Download") }, onClick = { menu = false; download(entry) })
                                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; nameDialog = Triple("rename", entry.name, entry.dir) })
                                        DropdownMenuItem(text = { Text("Delete", color = Color(0xFFFF6B6B)) }, onClick = { menu = false; deleteTarget = entry })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    nameDialog?.let { (mode, initial, isDirectory) ->
        var value by remember(mode, initial) { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = { nameDialog = null },
            title = { Text(if (mode == "mkdir") "New folder" else "Rename") },
            text = { OutlinedTextField(value, { value = it }, singleLine = true) },
            confirmButton = {
                TextButton({
                    val newName = value.trim()
                    nameDialog = null
                    if (newName.isNotEmpty()) action({
                        when (protocol) {
                            StorageProtocol.FTP, StorageProtocol.FTPS -> if (mode == "mkdir") ftp.mkdir(newName) else ftp.rename(initial, newName)
                            StorageProtocol.SFTP -> if (mode == "mkdir") sftp.createDirectory(remote(newName)) else sftp.rename(remote(initial), remote(newName))
                            StorageProtocol.SMB -> if (mode == "mkdir") smb.createDirectory(remote(newName)) else smb.rename(remote(initial), remote(newName), isDirectory)
                            StorageProtocol.WEBDAV -> if (mode == "mkdir") webDav.createDirectory(remote(newName)) else webDav.move(remote(initial), remote(newName))
                        }
                        listCurrent()
                    })
                }) { Text("OK") }
            },
            dismissButton = { TextButton({ nameDialog = null }) { Text("Cancel") } }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete ${target.name}?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton({
                    deleteTarget = null
                    action({
                        when (protocol) {
                            StorageProtocol.FTP, StorageProtocol.FTPS -> if (target.dir) ftp.rmdir(target.name) else ftp.delete(target.name)
                            StorageProtocol.SFTP -> if (target.dir) sftp.deleteDirectory(remote(target.name)) else sftp.deleteFile(remote(target.name))
                            StorageProtocol.SMB -> if (target.dir) smb.deleteDirectory(remote(target.name)) else smb.deleteFile(remote(target.name))
                            StorageProtocol.WEBDAV -> webDav.delete(remote(target.name))
                        }
                        listCurrent()
                    })
                }) { Text("Delete", color = Color(0xFFFF6B6B)) }
            },
            dismissButton = { TextButton({ deleteTarget = null }) { Text("Cancel") } }
        )
    }
}

private fun skipUploadBytes(input: FileInputStream, requested: Long) {
    var remaining = requested
    while (remaining > 0L) {
        val skipped = input.skip(remaining)
        if (skipped > 0L) {
            remaining -= skipped
        } else {
            if (input.read() < 0) throw IllegalStateException("Staged upload source ended before the saved resume offset")
            remaining--
        }
    }
}
