package com.kayx.manager.data.ai

import java.net.URI

/**
 * Provider-neutral configuration for an optional OpenAI-compatible chat endpoint.
 *
 * AI is disabled until the user explicitly configures an HTTPS endpoint, model and API key.
 * Local file-management features must never depend on this configuration.
 */
data class AiProviderConfig(
    val baseUrl: String,
    val model: String,
    val enabled: Boolean = false
) {
    fun validate(): Validation {
        if (!enabled) return Validation.Valid
        val endpoint = baseUrl.trim()
        if (endpoint.isBlank()) return Validation.Invalid("AI endpoint is required")
        val uri = runCatching { URI(endpoint) }.getOrNull()
            ?: return Validation.Invalid("AI endpoint is not a valid URL")
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            return Validation.Invalid("AI endpoint must use HTTPS")
        }
        if (uri.host.isNullOrBlank() || uri.userInfo != null || uri.fragment != null) {
            return Validation.Invalid("AI endpoint URL is not allowed")
        }
        if (model.isBlank()) return Validation.Invalid("AI model name is required")
        if (model.length > 128 || model.any { it.isISOControl() }) {
            return Validation.Invalid("AI model name is invalid")
        }
        return Validation.Valid
    }

    sealed interface Validation {
        data object Valid : Validation
        data class Invalid(val reason: String) : Validation
    }
}
