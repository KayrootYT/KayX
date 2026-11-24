package com.kayx.manager.feature.storage

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.kayx.manager.data.filesystem.SafRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SafBrowserScreen(treeUri: Uri, onBack: () -> Unit) {
    val context = LocalContext.current
    var relativePath by remember(treeUri) { mutableStateOf("") }
    var entries by remember(treeUri, relativePath) { mutableStateOf<List<SafRepository.Entry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var createDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<SafRepository.Entry?>(null) }
    var deleteTarget by remember { mutableStateOf<SafRepository.Entry?>(null) }
    var name by remember { mutableStateOf("") }

    suspend fun refresh() {
        loading = true
        error = null
        val result = withContext(Dispatchers.IO) {
            runCatching { SafRepository.entries(context, treeUri, relativePath) }
        }
        entries = result.getOrDefault(emptyList())
        if (result.isFailure) error = result.exceptionOrNull()?.message ?: "Unable to read this folder"
        loading = false
    }
    LaunchedEffect(treeUri, relativePath) { refresh() }
    val goUp: () -> Unit = {
        relativePath = relativePath.substringBeforeLast('/', "").trim('/')
    }
    BackHandler(relativePath.isNotBlank(), goUp)

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
            Column(Modifier.weight(1f)) {
                Text("SD card / SAF", style = MaterialTheme.typography.titleLarge)
                Text(if (relativePath.isBlank()) "Selected folder" else relativePath, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            IconButton({ createDialog = true; name = "" }) { Icon(Icons.Default.CreateNewFolder, "New folder") }
            IconButton({ kotlinx.coroutines.MainScope().launch { refresh() } }) { Icon(Icons.Default.Refresh, "Refresh") }
        }
        HorizontalDivider()
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        if (!loading && entries.isEmpty() && error == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("This folder is empty", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else LazyColumn(Modifier.fillMaxSize()) {
            if (relativePath.isNotBlank()) item {
                Row(Modifier.fillMaxWidth().clickable(onClick = goUp).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Folder, null)
                    Spacer(Modifier.width(14.dp))
                    Text("..")
                }
            }
            items(entries, key = { it.uri.toString() }) { entry ->
                var menu by remember(entry.uri) { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth().clickable {
                        if (entry.isDirectory) {
                            relativePath = listOf(relativePath, entry.name).filter { it.isNotBlank() }.joinToString("/")
                        } else {
                            runCatching {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(entry.uri, entry.mimeType ?: "*/*")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(intent)
                            }.onFailure { error = it.message ?: "No app can open this file" }
                        }
                    }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(if (entry.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile, null)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(entry.name, maxLines = 1)
                        Text(if (entry.isDirectory) "Folder" else formatSafSize(entry.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box {
                        TextButton(onClick = { menu = true }) { Text("⋮") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = {
                                menu = false; renameTarget = entry; name = entry.name
                            })
                            DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = {
                                menu = false; deleteTarget = entry
                            })
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }

    if (createDialog) AlertDialog(
        onDismissRequest = { createDialog = false },
        title = { Text("Create folder") },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Folder name") }) },
        confirmButton = { TextButton(onClick = {
            val created = SafRepository.createFolder(context, treeUri, relativePath, name.trim())
            if (created == null) error = "Could not create folder"
            createDialog = false
            kotlinx.coroutines.MainScope().launch { refresh() }
        }) { Text("Create") } },
        dismissButton = { TextButton({ createDialog = false }) { Text("Cancel") } }
    )
    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = {
                if (SafRepository.rename(context, target.uri, name.trim()) == null) error = "Rename failed or provider does not support it"
                renameTarget = null
                kotlinx.coroutines.MainScope().launch { refresh() }
            }) { Text("Rename") } },
            dismissButton = { TextButton({ renameTarget = null }) { Text("Cancel") } }
        )
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete ${target.name}?") },
            text = { Text("This removes the item from the selected storage folder.") },
            confirmButton = { TextButton(onClick = {
                if (!SafRepository.delete(context, target.uri)) error = "Delete failed"
                deleteTarget = null
                kotlinx.coroutines.MainScope().launch { refresh() }
            }) { Text("Delete") } },
            dismissButton = { TextButton({ deleteTarget = null }) { Text("Cancel") } }
        )
    }
}

private fun formatSafSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
}
