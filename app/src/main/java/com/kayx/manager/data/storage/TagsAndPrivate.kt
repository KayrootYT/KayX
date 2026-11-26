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

/** PIN-protected, encrypted private vault stored inside the app sandbox. */
object PrivateSpace {
    private const val PIN_SCHEME = "pbkdf2-hmac-sha256"
    private const val LEGACY_PBKDF2_SCHEME = "pbkdf2-hmac-sha1"
    private const val PIN_ITERATIONS = 120_000

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("kayx_private", Context.MODE_PRIVATE)

    /** Plaintext staging is populated only during an unlocked session and wiped when the app backgrounds. */
    private fun stagingDir(ctx: Context): File = File(ctx.applicationContext.filesDir, "private_space").apply { mkdirs() }
    fun dir(ctx: Context): File {
        check(isUnlocked()) { "Unlock Private space before accessing its files" }
        return stagingDir(ctx)
    }
    fun hasPin(ctx: Context) = !prefs(ctx).getString("hash", null).isNullOrEmpty()
    fun isUnlocked(): Boolean = PrivateSpaceVault.isUnlocked()
    fun fileCount(ctx: Context): Int {
        val root = if (isUnlocked()) stagingDir(ctx) else File(ctx.applicationContext.filesDir, "private_space_vault")
        if (!root.isDirectory) return 0
        return walkFilesBounded(root, maxDepth = 128, parallelism = 2).count {
            if (isUnlocked()) true else it.name.endsWith(".kxv")
        }
    }

    fun setPin(ctx: Context, pin: String): Result<Unit> = runCatching {
        require(pin.length in 4..8 && pin.all(Char::isDigit)) { "PIN must contain 4–8 digits" }
        val p = prefs(ctx)
        val salt = PrivateSpacePin.newSalt()
        val encoded = PrivateSpacePin.derive(pin, salt, PIN_ITERATIONS)
        check(p.edit()
            .putString("scheme", PIN_SCHEME)
            .putString("salt", salt)
            .putString("hash", encoded)
            .putInt("iterations", PIN_ITERATIONS)
            .commit()) { "Could not save private-space PIN" }
        try {
            PrivateSpaceVault.initialize(ctx.applicationContext, pin, stagingDir(ctx)).getOrThrow()
        } catch (t: Throwable) {
            p.edit().remove("scheme").remove("salt").remove("hash").remove("iterations")
                .remove("vault_salt").remove("vault_wrapped_key").remove("vault_kdf_iterations").remove("vault_cipher").commit()
            throw t
        }
    }

    /** Verifies the PIN and opens/migrates the encrypted vault into a temporary app-private working directory. */
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
                PrivateSpacePin.constantTimeEquals(stored, PrivateSpacePin.legacyHash(pin, salt))
            }
        }.getOrDefault(false)
        if (!valid) return false

        return runCatching {
            if (p.getString("scheme", null) != PIN_SCHEME) {
                val newSalt = PrivateSpacePin.newSalt()
                check(p.edit()
                    .putString("scheme", PIN_SCHEME)
                    .putString("salt", newSalt)
                    .putString("hash", PrivateSpacePin.derive(pin, newSalt, PIN_ITERATIONS))
                    .putInt("iterations", PIN_ITERATIONS)
                    .commit()) { "Could not upgrade private-space PIN" }
            }
            if (p.getString("vault_wrapped_key", null).isNullOrEmpty()) {
                // Existing pre-vault installs are encrypted on first successful unlock.
                PrivateSpaceVault.initialize(ctx.applicationContext, pin, stagingDir(ctx)).getOrThrow()
            } else {
                PrivateSpaceVault.unlock(ctx.applicationContext, pin, stagingDir(ctx)).getOrThrow()
            }
            true
        }.getOrDefault(false)
    }

    /** Persist edits, authenticate the encrypted copy, then remove plaintext staging data. */
    fun lock(ctx: Context): Result<Unit> = PrivateSpaceVault.lock(ctx.applicationContext, stagingDir(ctx))
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

    /** Legacy installs only: verify the old PIN hash, then migrate to PBKDF2-HMAC-SHA256. */
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
