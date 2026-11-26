package com.kayx.manager.data.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores an optional AI provider key encrypted with an Android Keystore AES-256 key.
 * The plaintext key is never written to preferences, files, logs or app configuration.
 */
class AiCredentialVault(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun saveApiKey(apiKey: String) {
        require(apiKey.isNotBlank()) { "API key cannot be blank" }
        require(apiKey.length <= MAX_KEY_LENGTH) { "API key is too long" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        val packed = ByteBuffer.allocate(4 + cipher.iv.size + encrypted.size)
            .putInt(cipher.iv.size)
            .put(cipher.iv)
            .put(encrypted)
            .array()
        check(preferences.edit().putString(KEY_CIPHERTEXT, Base64.encodeToString(packed, Base64.NO_WRAP)).commit()) {
            "Could not persist encrypted AI credential"
        }
    }

    @Synchronized
    fun readApiKey(): String? {
        val encoded = preferences.getString(KEY_CIPHERTEXT, null) ?: return null
        return runCatching {
            val packed = ByteBuffer.wrap(Base64.decode(encoded, Base64.NO_WRAP))
            val ivSize = packed.int
            require(ivSize in 12..16 && packed.remaining() > ivSize)
            val iv = ByteArray(ivSize).also(packed::get)
            val encrypted = ByteArray(packed.remaining()).also(packed::get)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrNull()
    }

    @Synchronized
    fun hasApiKey(): Boolean = preferences.contains(KEY_CIPHERTEXT)

    @Synchronized
    fun clearApiKey() {
        preferences.edit().remove(KEY_CIPHERTEXT).commit()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "kayx.ai.credentials.v1"
        const val PREFERENCES = "kayx_ai_credentials"
        const val KEY_CIPHERTEXT = "api_key_ciphertext"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val MAX_KEY_LENGTH = 4096
    }
}
