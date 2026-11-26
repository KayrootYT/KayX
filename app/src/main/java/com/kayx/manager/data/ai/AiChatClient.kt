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
    data class ToolCalls(val calls: List<AiToolCall>) : AiChatResult
    data class Failure(val reason: String) : AiChatResult
}

/** Minimal provider-neutral contract; tools are exposed only through [AiToolChatClient]. */
fun interface AiChatClient {
    suspend fun complete(messages: List<AiChatMessage>): AiChatResult
}

/** Optional extension for providers that support OpenAI-compatible function/tool calling. */
interface AiToolChatClient : AiChatClient {
    suspend fun completeWithTools(messages: List<AiChatMessage>, tools: List<AiToolSpec>): AiChatResult
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
) : AiToolChatClient {
    private val credentialVault = AiCredentialVault(context.applicationContext)

    override suspend fun complete(messages: List<AiChatMessage>): AiChatResult =
        completeRequest(messages, emptyList())

    override suspend fun completeWithTools(
        messages: List<AiChatMessage>,
        tools: List<AiToolSpec>
    ): AiChatResult {
        if (tools.isEmpty()) return AiChatResult.Failure("At least one approved tool definition is required")
        if (tools.size > MAX_TOOLS || tools.map { it.name }.distinct().size != tools.size) {
            return AiChatResult.Failure("AI tool definitions are invalid")
        }
        return completeRequest(messages, tools)
    }

    private suspend fun completeRequest(
        messages: List<AiChatMessage>,
        tools: List<AiToolSpec>
    ): AiChatResult = withContext(Dispatchers.IO) {
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
            if (tools.isNotEmpty()) payload.put("tools", JSONArray().apply {
                tools.forEach { put(toolDefinitionJson(it)) }
            })
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
                return@withContext AiChatResult.Failure("AI provider returned HTTP $status")
            }
            val message = JSONObject(body).getJSONArray("choices")
                .optJSONObject(0)?.optJSONObject("message")
                ?: return@withContext AiChatResult.Failure("AI provider returned an invalid response")
            val toolCalls = message.optJSONArray("tool_calls")
            if (toolCalls != null && toolCalls.length() > 0) {
                if (toolCalls.length() > MAX_TOOL_CALLS_PER_RESPONSE) {
                    return@withContext AiChatResult.Failure("AI provider returned too many tool calls")
                }
                val parsed = mutableListOf<AiToolCall>()
                for (index in 0 until toolCalls.length()) {
                    val item = toolCalls.optJSONObject(index) ?: return@withContext AiChatResult.Failure("AI provider returned an invalid tool call")
                    val function = item.optJSONObject("function") ?: return@withContext AiChatResult.Failure("AI provider returned an invalid tool call")
                    val id = item.optString("id")
                    val name = function.optString("name")
                    val rawArguments = function.optString("arguments")
                    if (id.isBlank() || id.length > 128 || name.isBlank() || name.length > 64 ||
                        rawArguments.length > MAX_TOOL_ARGUMENT_CHARS
                    ) return@withContext AiChatResult.Failure("AI provider returned an invalid tool call")
                    val args = runCatching { parseFlatArguments(rawArguments) }.getOrNull()
                        ?: return@withContext AiChatResult.Failure("AI provider returned invalid tool arguments")
                    parsed += AiToolCall(id, name, args)
                }
                return@withContext AiChatResult.ToolCalls(parsed)
            }
            val text = message.optString("content").orEmpty()
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

    private fun toolDefinitionJson(spec: AiToolSpec): JSONObject {
        val properties = JSONObject()
        spec.parameters.forEach { (name, type) ->
            val property = JSONObject()
            when (type) {
                is AiArgumentType.Text -> {
                    property.put("type", "string")
                    property.put("maxLength", type.maxLength)
                    type.allowedValues?.let { values -> property.put("enum", JSONArray(values.toList())) }
                }
                is AiArgumentType.Integer -> {
                    property.put("type", "integer")
                    if (type.min != Long.MIN_VALUE) property.put("minimum", type.min)
                    if (type.max != Long.MAX_VALUE) property.put("maximum", type.max)
                }
                is AiArgumentType.Decimal -> {
                    property.put("type", "number")
                    property.put("minimum", type.min)
                    property.put("maximum", type.max)
                }
                AiArgumentType.BooleanValue -> property.put("type", "boolean")
            }
            properties.put(name, property)
        }
        return JSONObject()
            .put("type", "function")
            .put("function", JSONObject()
                .put("name", spec.name)
                .put("description", spec.description)
                .put("strict", true)
                .put("parameters", JSONObject()
                    .put("type", "object")
                    .put("properties", properties)
                    .put("required", JSONArray(spec.required.toList()))
                    .put("additionalProperties", false)))
    }

    private fun parseFlatArguments(json: String): Map<String, Any?> {
        val objectValue = JSONObject(json)
        val result = linkedMapOf<String, Any?>()
        val keys = objectValue.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = objectValue.get(key)
            result[key] = when (value) {
                JSONObject.NULL -> null
                is JSONObject, is JSONArray -> throw IllegalArgumentException("Nested arguments are not supported")
                is Number -> if (value is Double || value is Float) value.toDouble() else value.toLong()
                is String, is Boolean -> value
                else -> throw IllegalArgumentException("Unsupported argument type")
            }
        }
        return result
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000
        const val MAX_MESSAGES = 32
        const val MAX_MESSAGE_CHARS = 16_000
        const val MAX_RESPONSE_CHARS = 256_000
        const val MAX_TOOLS = 32
        const val MAX_TOOL_CALLS_PER_RESPONSE = 8
        const val MAX_TOOL_ARGUMENT_CHARS = 16_384
    }
}
