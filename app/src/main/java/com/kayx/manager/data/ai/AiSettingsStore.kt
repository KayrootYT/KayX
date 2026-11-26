package com.kayx.manager.data.ai

import android.content.Context

/** Persists non-secret provider settings. API keys belong exclusively in [AiCredentialVault]. */
object AiSettingsStore {
    private const val PREFERENCES = "kayx_ai_settings"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_MODEL = "model"
    private const val KEY_ENABLED = "enabled"

    fun load(context: Context): AiProviderConfig {
        val prefs = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        return AiProviderConfig(
            baseUrl = prefs.getString(KEY_BASE_URL, "").orEmpty(),
            model = prefs.getString(KEY_MODEL, "").orEmpty(),
            enabled = prefs.getBoolean(KEY_ENABLED, false)
        )
    }

    fun save(context: Context, config: AiProviderConfig) {
        require(config.validate() is AiProviderConfig.Validation.Valid)
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BASE_URL, config.baseUrl.trim().trimEnd('/'))
            .putString(KEY_MODEL, config.model.trim())
            .putBoolean(KEY_ENABLED, config.enabled)
            .commit()
            .also { check(it) { "Could not save AI settings" } }
    }
}
