package com.kayx.manager.data.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/** User tags (label -> file paths), persisted in SharedPreferences. Compose-observable. */
object TagStore {
    private var prefs: SharedPreferences? = null
    var tags by mutableStateOf(mapOf<String, List<String>>())
        private set

    fun init(ctx: Context) {
        val p = ctx.applicationContext.getSharedPreferences("kayx_tags", Context.MODE_PRIVATE)
        prefs = p
        val m = LinkedHashMap<String, MutableList<String>>()
        (p.getString("tags", "") ?: "").lineSequence().forEach { l ->
            val i = l.indexOf('\t')
            if (i > 0) m.getOrPut(l.substring(0, i)) { ArrayList() } += l.substring(i + 1)
        }
        tags = m
    }

    private fun persist() {
        prefs?.edit()?.putString("tags", tags.entries.joinToString("\n") { (t, ps) -> ps.joinToString("\n") { "$t\t$it" } })?.apply()
    }

    fun add(tag: String, paths: Collection<String>) {
        val t = tag.trim().replace('\t', ' ').replace('\n', ' ')
        if (t.isEmpty()) return
        val m = tags.toMutableMap()
        m[t] = (m[t].orEmpty() + paths).distinct()
        tags = m; persist()
    }

    fun remove(tag: String, path: String) {
        val m = tags.toMutableMap()
        val l = m[tag].orEmpty() - path
        if (l.isEmpty()) m.remove(tag) else m[tag] = l
        tags = m; persist()
    }

    fun deleteTag(tag: String) { tags = tags - tag; persist() }

    /** Tagged files that still exist. */
    fun files(tag: String): List<ScanFile> = tags[tag].orEmpty().map(::File).filter { it.isFile }
        .map { ScanFile(it.absolutePath, it.length(), it.lastModified(), Category.of(it.name)) }

    fun totalFiles(): Int = tags.values.flatten().distinct().count { File(it).exists() }
}

/** PIN-protected private folder stored in the app's own storage (invisible to other apps). */
object PrivateSpace {
    private const val PIN_SCHEME = "pbkdf2-hmac-sha256"
    private const val LEGACY_PBKDF2_SCHEME = "pbkdf2-hmac-sha1"
    private const val PIN_ITERATIONS = 120_000
    private const val PIN_KEY_BITS = 256

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("kayx_private", Context.MODE_PRIVATE)

    fun dir(ctx: Context): File = File(ctx.applicationContext.filesDir, "private_space").apply { mkdirs() }
    fun hasPin(ctx: Context) = !prefs(ctx).getString("hash", null).isNullOrEmpty()
    fun fileCount(ctx: Context): Int = dir(ctx).walkTopDown().count { it.isFile }

    fun setPin(ctx: Context, pin: String) {
        val salt = PrivateSpacePin.newSalt()
        val encoded = PrivateSpacePin.derive(pin, salt, PIN_ITERATIONS)
        prefs(ctx).edit()
            .putString("scheme", PIN_SCHEME)
            .putString("salt", salt)
            .putString("hash", encoded)
            .putInt("iterations", PIN_ITERATIONS)
            .apply()
    }

    /** Verifies the PIN and transparently upgrades hashes created by older KayX builds. */
    fun verify(ctx: Context, pin: String): Boolean {
        val p = prefs(ctx)
        val salt = p.getString("salt", "") ?: ""
        val stored = p.getString("hash", null) ?: return false
        val valid = runCatching {
            val scheme = p.getString("scheme", null)
            if (scheme == PIN_SCHEME) {
                val iterations = p.getInt("iterations", PIN_ITERATIONS).coerceIn(50_000, 500_000)
                PrivateSpacePin.constantTimeEquals(stored, PrivateSpacePin.derive(pin, salt, iterations))
            } else if (scheme == LEGACY_PBKDF2_SCHEME) {
                val iterations = p.getInt("iterations", PIN_ITERATIONS).coerceIn(50_000, 500_000)
                PrivateSpacePin.constantTimeEquals(stored, PrivateSpacePin.deriveLegacySha1(pin, salt, iterations))
            } else {
                // Previous versions stored SHA-256(salt + PIN). Keep existing private spaces usable.
                val legacy = PrivateSpacePin.legacyHash(pin, salt)
                PrivateSpacePin.constantTimeEquals(stored, legacy)
            }
        }.getOrDefault(false)
        if (valid && p.getString("scheme", null) != PIN_SCHEME) setPin(ctx, pin)
        return valid
    }
}

/** Small JVM-only helper so PIN derivation and constant-time comparison can be tested. */
internal object PrivateSpacePin {
    fun newSalt(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()

    fun derive(pin: String, saltHex: String, iterations: Int): String {
        val salt = saltHex.hexToBytes()
        val spec = javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
        return try {
            javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded.toHex()
        } finally {
            spec.clearPassword()
        }
    }

    fun deriveLegacySha1(pin: String, saltHex: String, iterations: Int): String {
        val salt = saltHex.hexToBytes()
        val spec = javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
        return try {
            javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                .generateSecret(spec).encoded.toHex()
        } finally {
            spec.clearPassword()
        }
    }

    fun legacyHash(pin: String, salt: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()).toHex()

    fun constantTimeEquals(first: String, second: String): Boolean =
        MessageDigest.isEqual(first.toByteArray(Charsets.UTF_8), second.toByteArray(Charsets.UTF_8))

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0 && all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' })
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
