package com.kayx.manager.data.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores remote-service passwords encrypted with a non-exportable Android Keystore AES key. */
class NetworkCredentialStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)

    fun save(profileId: String, password: String) {
        validateProfileId(profileId)
        require(password.isNotEmpty()) { "Password is empty" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(profileId.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        val payload = cipher.iv + encrypted
        check(preferences.edit().putString(storageKey(profileId), Base64.encodeToString(payload, Base64.NO_WRAP)).commit()) {
            "Could not save encrypted credentials"
        }
    }

    fun read(profileId: String): String? {
        validateProfileId(profileId)
        val encoded = preferences.getString(storageKey(profileId), null) ?: return null
        val payload = try { Base64.decode(encoded, Base64.NO_WRAP) } catch (failure: IllegalArgumentException) {
            throw IllegalStateException("Stored credentials are corrupted", failure)
        }
        require(payload.size > IV_LENGTH) { "Stored credentials are incomplete" }
        val iv = payload.copyOfRange(0, IV_LENGTH)
        val encrypted = payload.copyOfRange(IV_LENGTH, payload.size)
        val key = getExistingKey() ?: throw IllegalStateException("Credential encryption key is missing; refusing to recreate it")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(profileId.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    fun remove(profileId: String) {
        validateProfileId(profileId)
        preferences.edit().remove(storageKey(profileId)).commit()
    }

    private fun getExistingKey(): SecretKey? {
        val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        return store.getKey(KEY_ALIAS, null) as? SecretKey
    }

    private fun getOrCreateKey(): SecretKey {
        getExistingKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun storageKey(profileId: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(profileId.toByteArray(Charsets.UTF_8))
        return "credential_" + digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun validateProfileId(profileId: String) {
        require(profileId.isNotBlank() && profileId.length <= MAX_PROFILE_ID_LENGTH) { "Invalid connection profile ID" }
        require(profileId.none { it.code < 0x20 }) { "Invalid connection profile ID" }
    }

    private companion object {
        const val STORE_NAME = "kayx_network_credentials"
        const val KEY_ALIAS = "kayx.network.credentials.aes.v1"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
        const val MAX_PROFILE_ID_LENGTH = 512
    }
}
