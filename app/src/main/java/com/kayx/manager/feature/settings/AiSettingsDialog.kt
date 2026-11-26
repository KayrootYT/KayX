package com.kayx.manager.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.kayx.manager.data.ai.AiCredentialVault
import com.kayx.manager.data.ai.AiProviderConfig
import com.kayx.manager.data.ai.AiSettingsStore

@Composable
fun AiSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val vault = remember(context) { AiCredentialVault(context) }
    val initial = remember(context) { AiSettingsStore.load(context) }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var endpoint by remember { mutableStateOf(initial.baseUrl) }
    var model by remember { mutableStateOf(initial.model) }
    var apiKey by remember { mutableStateOf("") }
    var keyConfigured by remember { mutableStateOf(vault.hasApiKey()) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("KayX AI") },
        text = {
            Column(Modifier.fillMaxWidth().widthIn(max = 520.dp)) {
                Text(
                    "AI is optional. Local file management continues to work without an account or internet.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("HTTPS API base URL") },
                    placeholder = { Text("https://provider.example/v1") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Model name") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (keyConfigured) "Replace API key (leave blank to keep)" else "API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
                if (keyConfigured) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = {
                        vault.clearApiKey()
                        keyConfigured = false
                        apiKey = ""
                        enabled = false
                    }) { Text("Remove saved API key") }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.Switch(checked = enabled, onCheckedChange = { enabled = it; error = null })
                    Spacer(Modifier.width(8.dp))
                    Text("Enable optional AI requests")
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val config = AiProviderConfig(endpoint, model, enabled)
                when (val validation = config.validate()) {
                    is AiProviderConfig.Validation.Invalid -> error = validation.reason
                    AiProviderConfig.Validation.Valid -> {
                        runCatching {
                            if (apiKey.isNotBlank()) vault.saveApiKey(apiKey)
                            check(!enabled || vault.hasApiKey()) { "Add an API key before enabling AI" }
                            AiSettingsStore.save(context, config)
                        }.onSuccess { onDismiss() }.onFailure {
                            error = it.message ?: "Could not save AI settings"
                        }
                    }
                }
            }) { Text("Save") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
