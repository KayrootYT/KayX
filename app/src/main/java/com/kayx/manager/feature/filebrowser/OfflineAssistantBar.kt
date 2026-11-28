package com.kayx.manager.feature.filebrowser

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.util.Locale

/** A local command input. Voice is accepted only through Android's on-device recognizer. */
@Composable
fun OfflineAssistantBar(
    onCommand: (OfflineAssistantCommand) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Offline assistant · text and on-device voice") }
    var listening by remember { mutableStateOf(false) }
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    val historyPrefs = remember(context) { context.getSharedPreferences("kayx_offline_assistant", Context.MODE_PRIVATE) }
    var historyEnabled by remember { mutableStateOf(historyPrefs.getBoolean("history_enabled", true)) }
    var history by remember { mutableStateOf(historyPrefs.getString("history", "").orEmpty().lineSequence().filter { it.isNotBlank() }.take(20).toList()) }
    var showHistory by remember { mutableStateOf(false) }

    fun submitCommand(raw: String) {
        val command = OfflineAssistantParser.parse(raw)
        if (command == null) {
            status = "Command not understood. Type help for examples"
            return
        }
        onCommand(command)
        if (historyEnabled) {
            history = (listOf(raw.trim()) + history.filterNot { it.equals(raw.trim(), ignoreCase = true) }).take(20)
            historyPrefs.edit().putString("history", history.joinToString("\\n")).apply()
        }
        status = "Executed offline"
        text = ""
    }

    fun startOfflineVoice() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            status = "Offline voice requires Android 12 or newer; text commands still work"
            return
        }
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            status = "On-device speech pack is unavailable; install an offline language pack or use text"
            return
        }
        runCatching {
            recognizer?.destroy()
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also { speech ->
                recognizer = speech
                speech.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { status = "Listening offline…" }
                    override fun onBeginningOfSpeech() { status = "Listening…" }
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() { listening = false; status = "Processing on device…" }
                    override fun onError(error: Int) {
                        listening = false
                        status = "Voice unavailable (code $error). Try text command."
                    }
                    override fun onResults(results: Bundle?) {
                        listening = false
                        val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                        text = spoken
                        submitCommand(spoken)
                    }
                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                    override fun onEndOfSegmentedSession() { listening = false }
                })
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                }
                speech.startListening(intent)
                listening = true
                status = "Starting on-device voice…"
            }
        }.onFailure {
            listening = false
            status = "Could not start offline voice. Text commands are available."
        }
    }

    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startOfflineVoice() else status = "Microphone permission denied; text commands still work"
    }

    DisposableEffect(Unit) {
        onDispose {
            recognizer?.let { speech -> runCatching { speech.cancel(); speech.destroy() } }
            recognizer = null
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Ask KayX: find large videos…") },
                textStyle = MaterialTheme.typography.bodyMedium
            )
            TextButton(onClick = { showHistory = true }) { Text("History") }
            IconButton(onClick = {
                if (listening) {
                    recognizer?.cancel()
                    listening = false
                    status = "Voice input cancelled"
                } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    status = "Offline voice requires Android 12 or newer; text commands still work"
                } else if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                    status = "On-device speech pack is unavailable; install an offline language pack or use text"
                } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    startOfflineVoice()
                } else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            }) {
                Icon(if (listening) Icons.Default.Stop else Icons.Default.Mic, contentDescription = if (listening) "Stop voice input" else "Speak offline command")
            }
            IconButton(onClick = { submitCommand(text) }, enabled = text.isNotBlank()) {
                Icon(Icons.Default.Send, contentDescription = "Run offline command")
            }
        }
        Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (showHistory) AlertDialog(
        onDismissRequest = { showHistory = false },
        title = { Text("Offline command history") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = historyEnabled, onCheckedChange = {
                        historyEnabled = it
                        historyPrefs.edit().putBoolean("history_enabled", it).apply()
                        if (!it) {
                            history = emptyList()
                            historyPrefs.edit().remove("history").apply()
                        }
                    })
                    Text("Save command history on this device")
                }
                if (historyEnabled && history.isNotEmpty()) {
                    history.forEach { entry ->
                        TextButton(onClick = { text = entry; showHistory = false }) {
                            Text(entry, maxLines = 1)
                        }
                    }
                    TextButton(onClick = {
                        history = emptyList()
                        historyPrefs.edit().remove("history").apply()
                    }) { Text("Clear history") }
                } else Text("No saved commands")
            }
        },
        confirmButton = { TextButton(onClick = { showHistory = false }) { Text("Close") } }
    )
}
