package com.kayx.manager.feature.settings

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kayx.manager.data.ai.AiDocumentChunk
import com.kayx.manager.data.ai.AiDocumentRepository
import com.kayx.manager.data.ai.AiDocumentResult
import com.kayx.manager.data.ai.AiLocalRetriever

@Composable
fun AiDocumentDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val repository = remember(context) { AiDocumentRepository(context.contentResolver) }
    var chunks by remember { mutableStateOf(emptyList<AiDocumentChunk>()) }
    var documents by remember { mutableStateOf(emptyList<String>()) }
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Choose text documents to search locally on this device.") }
    var error by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Selected document"
            when (val result = repository.load(uri, name, context.contentResolver.getType(uri))) {
                is AiDocumentResult.Success -> {
                    chunks = (chunks + result.document.chunks).takeLast(AiDocumentRepository.MAX_CHUNKS)
                    documents = (documents + result.document.documentName).distinct().takeLast(20)
                    status = "Loaded ${result.document.characterCount} characters from ${result.document.documentName}. Text remains in memory only."
                    error = false
                }
                is AiDocumentResult.Unsupported -> { status = result.reason; error = true }
                is AiDocumentResult.Failure -> { status = result.reason; error = true }
            }
        }
    }
    val matches = remember(chunks, query) { AiLocalRetriever.search(chunks, query, 5) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Local document intelligence") },
        text = {
            Column(
                Modifier.fillMaxWidth().height(460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Select documents explicitly. KayX extracts supported text and searches it locally. Nothing is uploaded from this screen.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(onClick = {
                    picker.launch(arrayOf(
                        "text/*", "application/json", "application/xml",
                        "application/yaml", "application/x-yaml", "*/*"
                    ))
                }, modifier = Modifier.fillMaxWidth()) { Text("Choose text document") }
                if (documents.isNotEmpty()) {
                    Text("Selected documents (${documents.size})", style = MaterialTheme.typography.titleSmall)
                    documents.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    Text("${chunks.size} local chunks · memory only", style = MaterialTheme.typography.labelSmall)
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search selected documents") },
                    singleLine = true
                )
                if (query.isNotBlank()) {
                    Text("Local matches (${matches.size})", style = MaterialTheme.typography.titleSmall)
                    matches.forEach { chunk ->
                        Text(
                            "${chunk.documentName} · part ${chunk.index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(chunk.text.take(900), style = MaterialTheme.typography.bodySmall)
                    }
                    if (matches.isEmpty()) Text("No matching passages found.", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    status,
                    color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Supported: TXT, Markdown, CSV, JSON, XML, HTML, logs and common source/config text. PDF and Office files are currently unsupported. Maximum 2 MB and 120,000 characters per document.",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = {
            if (chunks.isNotEmpty()) TextButton(onClick = {
                chunks = emptyList()
                documents = emptyList()
                query = ""
                status = "Selected document text cleared from this screen."
            }) { Text("Clear") }
        }
    )
}
