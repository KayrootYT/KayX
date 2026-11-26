package com.kayx.manager.data.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AiChatMessage(val role: Role, val content: String) {
    enum class Role(val wireValue: String) { SYSTEM("system"), USER("user"), ASSISTANT("assistant") }
}

sealed interface AiChatResult {
    data class Success(val text: String) : AiChatResult
    data class Failure(val reason: String) : AiChatResult
}

/** Minimal provider-neutral contract; file tools and agents are deliberately not exposed yet. */
fun interface AiChatClient {
    suspend fun complete(messages: List<AiChatMessage>): AiChatResult
}

/**
 * OpenAI-compatible Chat Completions transport.
 * Uses platform HttpURLConnection to avoid adding a networking dependency for one endpoint.
 */
class OpenAiCompatibleChatClient(
    context: Context,
    private val config: AiProviderConfig,
    private val connectTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_TIMEOUT_MS
) : AiChatClient {
    private val credentialVault = AiCredentialVault(context.applicationContext)

    override suspend fun complete(messages: List<AiChatMessage>): AiChatResult = withContext(Dispatchers.IO) {
        val validation = config.validate()
        if (validation is AiProviderConfig.Validation.Invalid) {
            return@withContext AiChatResult.Failure(validation.reason)
        }
        if (!config.enabled) return@withContext AiChatResult.Failure("AI is disabled in KayX settings")
        if (messages.isEmpty()) return@withContext AiChatResult.Failure("At least one message is required")
        if (messages.size > MAX_MESSAGES || messages.any { it.content.length > MAX_MESSAGE_CHARS }) {
            return@withContext AiChatResult.Failure("AI request exceeds the allowed message limits")
        }
        val apiKey = credentialVault.readApiKey()?.takeIf { it.isNotBlank() }
            ?: return@withContext AiChatResult.Failure("Configure an AI API key in KayX settings")

        var connection: HttpURLConnection? = null
        try {
            val endpoint = config.baseUrl.trimEnd('/') + "/chat/completions"
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doOutput = true
                instanceFollowRedirects = false
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            val payload = JSONObject()
                .put("model", config.model)
                .put("messages", JSONArray().apply {
                    messages.forEach { message ->
                        put(JSONObject().put("role", message.role.wireValue).put("content", message.content))
                    }
                })
                .put("stream", false)
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            if (status in 300..399) {
                return@withContext AiChatResult.Failure("AI provider redirects are not allowed")
            }
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val output = StringBuilder()
                val buffer = CharArray(4096)
                while (output.length < MAX_RESPONSE_CHARS) {
                    val count = reader.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_CHARS - output.length))
                    if (count < 0) break
                    output.append(buffer, 0, count)
                }
                output.toString()
            }.orEmpty()
            if (status !in 200..299) {
                // Do not echo response headers or request details, which could contain credentials.
                return@withContext AiChatResult.Failure("AI provider returned HTTP $status")
            }
            val text = JSONObject(body).getJSONArray("choices")
                .optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
            if (text.isBlank()) AiChatResult.Failure("AI provider returned an empty response")
            else AiChatResult.Success(text)
        } catch (_: java.net.SocketTimeoutException) {
            AiChatResult.Failure("AI request timed out")
        } catch (_: Exception) {
            AiChatResult.Failure("Could not complete the AI request")
        } finally {
            connection?.disconnect()
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000
        const val MAX_MESSAGES = 32
        const val MAX_MESSAGE_CHARS = 16_000
        const val MAX_RESPONSE_CHARS = 256_000
    }
}
