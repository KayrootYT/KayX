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
import com.kayx.manager.data.ai.AiChatClient
import com.kayx.manager.data.ai.AiChatMessage
import com.kayx.manager.data.ai.AiChatResult
import com.kayx.manager.data.ai.AiDocumentChunk
import com.kayx.manager.data.ai.AiDocumentRepository
import com.kayx.manager.data.ai.AiDocumentResult
import com.kayx.manager.data.ai.AiDocumentShareGate
import com.kayx.manager.data.ai.AiDocumentPromptBuilder
import com.kayx.manager.data.ai.AiLocalRetriever
import com.kayx.manager.data.ai.AiSettingsStore
import com.kayx.manager.data.ai.OpenAiCompatibleChatClient
import kotlinx.coroutines.launch

@Composable
fun AiDocumentDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val repository = remember(context) { AiDocumentRepository(context.contentResolver) }
    val scope = rememberCoroutineScope()
    var chunks by remember { mutableStateOf(emptyList<AiDocumentChunk>()) }
    var documents by remember { mutableStateOf(emptyList<String>()) }
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Choose text documents to search locally on this device.") }
    var error by remember { mutableStateOf(false) }
    var showShareConsent by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var aiAnswer by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            }.getOrNull()?.take(256)
                ?: uri.lastPathSegment?.substringAfterLast('/')?.take(256) ?: "Selected document"
            when (val result = repository.load(uri, name, context.contentResolver.getType(uri))) {
                is AiDocumentResult.Success -> {
                    chunks = (chunks + result.document.chunks).takeLast(AiDocumentRepository.MAX_CHUNKS)
                    documents = (documents + result.document.documentName).distinct().takeLast(20)
                    status = "Loaded ${result.document.characterCount} characters from ${result.document.documentName}. Text remains in memory only."
                    error = false
                    aiAnswer = null
                }
                is AiDocumentResult.Unsupported -> { status = result.reason; error = true }
                is AiDocumentResult.Failure -> { status = result.reason; error = true }
            }
        }
    }
    val matches = remember(chunks, query) { AiLocalRetriever.search(chunks, query, 5) }

    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text("Local document intelligence") },
        text = {
            Column(
                Modifier.fillMaxWidth().height(460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Select documents explicitly. KayX extracts supported text and searches it locally. Nothing is uploaded unless you separately approve sending selected excerpts to your configured AI provider.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(
                    onClick = {
                        picker.launch(arrayOf(
                            "text/*", "application/json", "application/xml",
                            "application/yaml", "application/x-yaml", "*/*"
                        ))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !sending
                ) { Text("Choose text document") }
                if (documents.isNotEmpty()) {
                    Text("Selected documents (${documents.size})", style = MaterialTheme.typography.titleSmall)
                    documents.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    Text("${chunks.size} local chunks · memory only", style = MaterialTheme.typography.labelSmall)
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; aiAnswer = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search selected documents") },
                    singleLine = true,
                    enabled = !sending
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
                    if (matches.isNotEmpty()) {
                        Button(
                            onClick = { showShareConsent = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !sending
                        ) { Text(if (sending) "Sending…" else "Ask configured AI about these excerpts") }
                    }
                }
                aiAnswer?.let { answer ->
                    Text("AI response", style = MaterialTheme.typography.titleSmall)
                    Text(answer, style = MaterialTheme.typography.bodyMedium)
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
        confirmButton = { TextButton(onClick = onDismiss, enabled = !sending) { Text("Done") } },
        dismissButton = {
            if (chunks.isNotEmpty()) TextButton(onClick = {
                chunks = emptyList()
                documents = emptyList()
                query = ""
                aiAnswer = null
                status = "Selected document text cleared from this screen."
            }, enabled = !sending) { Text("Clear") }
        }
    )

    if (showShareConsent) {
        val selectedNames = matches.map { it.documentName }.distinct()
        AlertDialog(
            onDismissRequest = { showShareConsent = false },
            title = { Text("Share document excerpts with AI?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("KayX will send these locally retrieved excerpts and your question to the HTTPS AI provider configured in Settings.")
                    Text("Documents: ${selectedNames.joinToString()}", style = MaterialTheme.typography.bodySmall)
                    Text("Excerpts: ${matches.size} · maximum outgoing context: ${AiDocumentShareGate.MAX_SHARED_CONTEXT_CHARS} characters", style = MaterialTheme.typography.bodySmall)
                    Text("The provider will receive this content. It is not sent until you tap “Send excerpts”.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = {
                    showShareConsent = false
                    sending = true
                    error = false
                    status = "Sending only the excerpts you approved…"
                    val approvedMatches = matches.toList()
                    val approvedQuestion = query
                    scope.launch {
                        try {
                            // Consent is granted only by this explicit confirmation action.
                            val messages = AiDocumentPromptBuilder.build(
                                question = approvedQuestion,
                                chunks = approvedMatches,
                                consentGranted = true
                            )
                            val config = AiSettingsStore.load(context)
                            val client: AiChatClient = OpenAiCompatibleChatClient(context, config)
                            when (val result = client.complete(messages)) {
                                is AiChatResult.Success -> {
                                    aiAnswer = result.text
                                    status = "AI response received. Selected source text remains in memory only."
                                }
                                is AiChatResult.Failure -> {
                                    status = result.reason
                                    error = true
                                }
                                is AiChatResult.ToolCalls -> {
                                    status = "This document question returned tool calls; KayX did not execute any tools."
                                    error = true
                                }
                            }
                        } catch (_: Exception) {
                            status = "Could not prepare the approved document excerpts."
                            error = true
                        } finally {
                            sending = false
                        }
                    }
                }, enabled = !sending) { Text("Send excerpts") }
            },
            dismissButton = {
                TextButton(onClick = { showShareConsent = false }) { Text("Cancel") }
            }
        )
    }
}
