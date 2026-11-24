package com.kayx.manager.feature.storage

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Sort
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
import kotlinx.coroutines.launch

@Composable
fun SafBrowserScreen(treeUri: Uri, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val canWrite = remember(treeUri) { SafRepository.hasWriteAccess(context, treeUri) }
    var relativePath by remember(treeUri) {
        mutableStateOf(context.getSharedPreferences("kayx_saf_browser", android.content.Context.MODE_PRIVATE).getString(treeUri.toString(), "").orEmpty())
    }
    var entries by remember(treeUri, relativePath) { mutableStateOf<List<SafRepository.Entry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var createDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<SafRepository.Entry?>(null) }
    var deleteTarget by remember { mutableStateOf<SafRepository.Entry?>(null) }
    var name by remember { mutableStateOf("") }
    var exportSource by remember { mutableStateOf<Uri?>(null) }
    var transferEntries by remember { mutableStateOf<List<SafRepository.Entry>>(emptyList()) }
    var transferProgress by remember { mutableStateOf<String?>(null) }
    var moveTransfer by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var sortMenu by remember { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf("Name") }
    var descending by remember { mutableStateOf(false) }
    var selectedUris by remember(treeUri, relativePath) { mutableStateOf<Set<String>>(emptySet()) }
    var confirmBulkDelete by remember { mutableStateOf(false) }
    suspend fun refresh() {
        loading = true
        error = null
        val result = withContext(Dispatchers.IO) {
            runCatching { SafRepository.entries(context, treeUri, relativePath) }
        }
        entries = result.getOrDefault(emptyList())
        selectedUris = selectedUris.intersect(entries.map { it.uri.toString() }.toSet())
        if (result.isFailure) error = result.exceptionOrNull()?.message ?: "Unable to read this folder"
        loading = false
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { sourceUri ->
        if (sourceUri != null) {
            scope.launch {
                val source = DocumentFile.fromSingleUri(context, sourceUri)
                val displayName = source?.name ?: "imported-file"
                val copied = withContext(Dispatchers.IO) {
                    SafRepository.copyIntoTree(context, sourceUri, treeUri, relativePath, displayName)
                }
                if (copied == null) error = "Import failed. Check write permission and available space."
                refresh()
            }
        }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { destinationUri ->
        val sourceUri = exportSource
        exportSource = null
        if (destinationUri != null && sourceUri != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) { SafRepository.exportDocument(context, sourceUri, destinationUri) }
                if (!ok) error = "Export failed"
            }
        }
    }
    val destinationTreePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { destinationTree ->
        val sources = transferEntries
        transferEntries = emptyList()
        if (destinationTree != null && sources.isNotEmpty()) {
            if (destinationTree == treeUri) {
                error = "Choose a different storage location to avoid copying items into the same tree."
                moveTransfer = false
            } else {
                SafRepository.persistTreePermission(context, destinationTree)
                scope.launch {
                    val wasMove = moveTransfer
                    var completed = 0
                    var failed = 0
                    for ((index, source) in sources.withIndex()) {
                        transferProgress = "${if (wasMove) "Moving" else "Copying"} ${index + 1}/${sources.size}: ${source.name}"
                        val result = withContext(Dispatchers.IO) {
                            if (wasMove) SafRepository.moveDocumentToTree(context, source.uri, destinationTree, targetName = source.name)
                            else SafRepository.copyDocumentToTree(context, source.uri, destinationTree, targetName = source.name)
                        }
                        if (result == null) failed++ else completed++
                    }
                    transferProgress = null
                    moveTransfer = false
                    selectedUris = emptySet()
                    error = if (failed == 0) "${if (wasMove) "Moved" else "Copied"} $completed item(s) successfully"
                        else "$completed item(s) transferred; $failed failed. Check destination permissions and free space."
                    refresh()
                }
            }
        } else moveTransfer = false
    }

    LaunchedEffect(treeUri, relativePath) {
        context.getSharedPreferences("kayx_saf_browser", android.content.Context.MODE_PRIVATE)
            .edit().putString(treeUri.toString(), relativePath).apply()
        refresh()
    }
    val visibleEntries = remember(entries, query, sortMode, descending) {
        val filtered = entries.filter { it.name.contains(query.trim(), ignoreCase = true) }
        val sorted = when (sortMode) { "Date" -> filtered.sortedBy { it.lastModified }; "Size" -> filtered.sortedBy { it.size }; else -> filtered.sortedBy { it.name.lowercase() } }
        if (descending) sorted.asReversed() else sorted
    }
    val goUp: () -> Unit = {
        relativePath = relativePath.substringBeforeLast('/', "").trim('/')
    }
    BackHandler(relativePath.isNotBlank(), goUp)

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
            Column(Modifier.weight(1f)) {
                if (searchOpen) OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Search this folder") }, trailingIcon = { IconButton(onClick = { query = ""; searchOpen = false }) { Icon(Icons.Default.Close, "Close search") } })
                else { Text("SD card / SAF", style = MaterialTheme.typography.titleLarge); Text(if (relativePath.isBlank()) "Selected folder" else relativePath, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
            }
            if (selectedUris.isNotEmpty()) {
                Text("${selectedUris.size} selected", style = MaterialTheme.typography.labelMedium)
                IconButton(onClick = {
                    transferEntries = entries.filter { it.uri.toString() in selectedUris }
                    moveTransfer = false
                    destinationTreePicker.launch(null)
                }) { Icon(Icons.Default.FileUpload, "Copy selected to storage") }
                IconButton(onClick = {
                    transferEntries = entries.filter { it.uri.toString() in selectedUris }
                    moveTransfer = true
                    destinationTreePicker.launch(null)
                }, enabled = canWrite) { Icon(Icons.Default.FileDownload, "Move selected to storage") }
                IconButton(onClick = { confirmBulkDelete = true }, enabled = canWrite) { Icon(Icons.Default.Delete, "Delete selected") }
                IconButton(onClick = { selectedUris = emptySet() }) { Icon(Icons.Default.Close, "Clear selection") }
            } else {
                if (!searchOpen) IconButton(onClick = { searchOpen = true }) { Icon(Icons.Default.Search, "Search") }
                if (visibleEntries.isNotEmpty()) TextButton(onClick = { selectedUris = visibleEntries.map { it.uri.toString() }.toSet() }) { Text("Select") }
            }
            Box {
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.Default.Sort, "Sort") }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    listOf("Name", "Date", "Size").forEach { mode -> DropdownMenuItem(text = { Text("Sort by $mode") }, onClick = { sortMode = mode; sortMenu = false }) }
                    DropdownMenuItem(text = { Text(if (descending) "Ascending" else "Descending") }, onClick = { descending = !descending; sortMenu = false })
                }
            }
            IconButton(onClick = { importPicker.launch(arrayOf("*/*")) }, enabled = canWrite) { Icon(Icons.Default.FileUpload, "Import file") }
            IconButton(onClick = { createDialog = true; name = "" }, enabled = canWrite) { Icon(Icons.Default.CreateNewFolder, "New folder") }
            IconButton({ scope.launch { refresh() } }) { Icon(Icons.Default.Refresh, "Refresh") }
        }
        HorizontalDivider()
        if (!canWrite) Text(
            "Read-only access. Choose a folder with write permission to create, import, rename or delete items.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        transferProgress?.let { label ->
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        if (!loading && visibleEntries.isEmpty() && error == null && relativePath.isBlank()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (query.isBlank()) "This folder is empty" else "No files match your search", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else LazyColumn(Modifier.fillMaxSize()) {
            if (!loading && visibleEntries.isEmpty() && error == null) item(key = "empty-result") {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(if (query.isBlank()) "This folder is empty" else "No files match your search", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (relativePath.isNotBlank()) item {
                Row(Modifier.fillMaxWidth().clickable(onClick = goUp).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Folder, null)
                    Spacer(Modifier.width(14.dp))
                    Text("..")
                }
            }
            items(visibleEntries, key = { it.uri.toString() }) { entry ->
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
                    Checkbox(
                        checked = entry.uri.toString() in selectedUris,
                        onCheckedChange = { checked ->
                            selectedUris = if (checked) selectedUris + entry.uri.toString() else selectedUris - entry.uri.toString()
                        }
                    )
                    Icon(if (entry.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile, null)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(entry.name, maxLines = 1)
                        Text(if (entry.isDirectory) "Folder" else formatSafSize(entry.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box {
                        TextButton(onClick = { menu = true }) { Text("⋮") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (!entry.isDirectory) DropdownMenuItem(text = { Text("Export") }, leadingIcon = { Icon(Icons.Default.FileDownload, null) }, onClick = {
                                menu = false
                                exportSource = entry.uri
                                exportPicker.launch(entry.name)
                            })
                            DropdownMenuItem(text = { Text("Copy to storage…") }, leadingIcon = { Icon(Icons.Default.FileUpload, null) }, onClick = {
                                menu = false
                                transferEntries = listOf(entry)
                                moveTransfer = false
                                destinationTreePicker.launch(null)
                            })
                            DropdownMenuItem(enabled = canWrite, text = { Text("Move to storage…") }, leadingIcon = { Icon(Icons.Default.FileDownload, null) }, onClick = {
                                menu = false
                                transferEntry = entry
                                moveTransfer = true
                                destinationTreePicker.launch(null)
                            })
                            DropdownMenuItem(enabled = canWrite, text = { Text("Rename") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = {
                                menu = false; renameTarget = entry; name = entry.name
                            })
                            DropdownMenuItem(enabled = canWrite, text = { Text("Delete") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = {
                                menu = false; deleteTarget = entry
                            })
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }

    if (confirmBulkDelete) AlertDialog(
        onDismissRequest = { confirmBulkDelete = false },
        title = { Text("Delete ${selectedUris.size} selected item(s)?") },
        text = { Text("Selected files and folders will be removed from this storage location. Folder contents may also be deleted.") },
        confirmButton = { TextButton(onClick = {
            val targets = entries.filter { it.uri.toString() in selectedUris }
            scope.launch {
                val failures = withContext(Dispatchers.IO) {
                    targets.count { !SafRepository.delete(context, it.uri) }
                }
                selectedUris = emptySet()
                if (failures > 0) error = "$failures item(s) could not be deleted"
                refresh()
            }
            confirmBulkDelete = false
        }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmBulkDelete = false }) { Text("Cancel") } }
    )

    if (createDialog) AlertDialog(
        onDismissRequest = { createDialog = false },
        title = { Text("Create folder") },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Folder name") }) },
        confirmButton = { TextButton(onClick = {
            val created = SafRepository.createFolder(context, treeUri, relativePath, name.trim())
            if (created == null) error = "Could not create folder"
            createDialog = false
            scope.launch { refresh() }
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
                scope.launch { refresh() }
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
                scope.launch { refresh() }
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
