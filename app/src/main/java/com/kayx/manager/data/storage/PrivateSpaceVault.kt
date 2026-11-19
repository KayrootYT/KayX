package com.kayx.manager.data.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/**
 * AES-256-GCM private-space vault.
 *
 * The random vault key is wrapped by a PIN-derived key, then wrapped again by an Android
 * Keystore AES key. Files are streamed to authenticated encrypted blobs; plaintext is only
 * materialized in the app-private staging directory while the vault session is unlocked.
 */
internal object PrivateSpaceVault {
    private const val PREFS = "kayx_private"
    private const val ALIAS = "kayx_private_vault_wrap_v1"
    private const val ITERATIONS = 250_000
    private const val KEY_BYTES = 32
    private const val IV_BYTES = 12
    private const val MAGIC = "KXVAULT1"
    private const val EXT = ".kxv"
    private const val MAX_FILES = 100_000

    @Volatile private var sessionKey: ByteArray? = null

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun vaultDir(ctx: Context) = File(ctx.filesDir, "private_space_vault")
    fun isUnlocked(): Boolean = sessionKey?.size == KEY_BYTES

    fun fileCount(ctx: Context): Int {
        val root = vaultDir(ctx)
        if (!root.isDirectory) return 0
        return root.walkTopDown().count { it.isFile && it.name.endsWith(EXT) }
    }

    @Synchronized fun initialize(ctx: Context, pin: String, stage: File): Result<Unit> = runCatching {
        require(pin.length in 4..8 && pin.all(Char::isDigit)) { "PIN must contain 4–8 digits" }
        val key = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val pinKey = deriveKey(pin, salt)
        val wrappedPin = try { encryptBytes(pinKey, key) } finally { pinKey.fill(0) }
        val wrappedKeystore = encryptWithKeystore(wrappedPin)
        check(prefs(ctx).edit()
            .putString("vault_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("vault_wrapped_key", Base64.encodeToString(wrappedKeystore, Base64.NO_WRAP))
            .putInt("vault_kdf_iterations", ITERATIONS)
            .putString("vault_cipher", "AES-256-GCM")
            .commit()) { "Could not persist encrypted vault key metadata" }
        sessionKey?.fill(0)
        sessionKey = key
        persistStage(ctx, stage, key)
    }.onFailure { sessionKey = null }

    @Synchronized fun unlock(ctx: Context, pin: String, stage: File): Result<Unit> = runCatching {
        val p = prefs(ctx)
        val salt = Base64.decode(p.getString("vault_salt", null) ?: error("Vault key metadata is missing"), Base64.DEFAULT)
        val wrapped = Base64.decode(p.getString("vault_wrapped_key", null) ?: error("Vault key is missing"), Base64.DEFAULT)
        val iterations = p.getInt("vault_kdf_iterations", ITERATIONS).coerceIn(200_000, 500_000)
        require(p.getString("vault_cipher", null) == "AES-256-GCM") { "Unsupported private vault cipher" }
        val pinKey = deriveKey(pin, salt, iterations)
        val key = try {
            val pinWrapped = decryptWithKeystore(wrapped)
            decryptBytes(pinKey, pinWrapped)
        } finally {
            pinKey.fill(0)
        }
        require(key.size == KEY_BYTES) { "Invalid vault key" }

        val vault = vaultDir(ctx)
        if (stageHasFiles(stage)) {
            // Recover edits left in staging by process death before the last lock transaction.
            persistStage(ctx, stage, key)
        } else {
            require(vault.isDirectory) { "Encrypted private vault is missing; refusing to create an empty replacement" }
            stage.mkdirs()
            decryptTree(vault, stage, key)
        }
        sessionKey?.fill(0)
        sessionKey = key
    }.onFailure { sessionKey?.fill(0); sessionKey = null }

    /** Commit all staged edits, verify every encrypted file, then wipe the plaintext staging tree. */
    @Synchronized fun lock(ctx: Context, stage: File): Result<Unit> = runCatching {
        val key = sessionKey ?: return@runCatching Unit
        persistStage(ctx, stage, key)
        check(stage.deleteRecursively()) { "Could not clear plaintext private-space staging files" }
        check(stage.mkdirs()) { "Could not recreate private-space staging directory" }
        sessionKey = null
        key.fill(0)
    }

    private fun persistStage(ctx: Context, stage: File, key: ByteArray) {
        val vault = vaultDir(ctx)
        val temp = File(ctx.filesDir, "private_space_vault.new")
        val backup = File(ctx.filesDir, "private_space_vault.bak")
        temp.deleteRecursively()
        backup.deleteRecursively()
        temp.mkdirs()
        encryptTree(stage, temp, key)
        val verify = File(ctx.cacheDir, "kayx-vault-verify")
        verify.deleteRecursively()
        verify.mkdirs()
        try {
            decryptTree(temp, verify, key)
            check(treesEqual(stage, verify)) { "Encrypted vault verification failed; original files were preserved" }
        } finally {
            verify.deleteRecursively()
        }

        val hadVault = vault.exists()
        if (hadVault) check(vault.renameTo(backup)) { "Could not preserve previous private vault" }
        try {
            check(temp.renameTo(vault)) { "Could not install encrypted private vault" }
        } catch (t: Throwable) {
            if (hadVault) check(backup.renameTo(vault)) { "Could not restore previous private vault" }
            throw t
        }
        backup.deleteRecursively()
    }

    private fun encryptTree(source: File, target: File, key: ByteArray) {
        require(source.isDirectory) { "Private-space staging directory is missing" }
        var count = 0
        fun walk(src: File, dst: File) {
            src.listFiles().orEmpty().forEach { item ->
                require(!java.nio.file.Files.isSymbolicLink(item.toPath())) { "Private space cannot contain symbolic links: ${item.name}" }
                val out = File(dst, item.name + if (item.isFile) EXT else "")
                if (item.isDirectory) {
                    check(out.mkdirs() || out.isDirectory) { "Cannot create vault directory" }
                    walk(item, out)
                } else if (item.isFile) {
                    count++
                    require(count <= MAX_FILES) { "Private space exceeds $MAX_FILES files" }
                    encryptFile(item, out, key)
                }
            }
        }
        walk(source, target)
    }

    private fun decryptTree(source: File, target: File, key: ByteArray) {
        if (!source.exists()) return
        require(source.isDirectory) { "Private vault is not a directory" }
        var count = 0
        fun walk(src: File, dst: File) {
            src.listFiles().orEmpty().forEach { item ->
                require(!java.nio.file.Files.isSymbolicLink(item.toPath())) { "Invalid symbolic link in private vault" }
                if (item.isDirectory) {
                    check(dst.resolve(item.name).mkdirs() || dst.resolve(item.name).isDirectory)
                    walk(item, File(dst, item.name))
                } else if (item.isFile && item.name.endsWith(EXT)) {
                    count++
                    require(count <= MAX_FILES) { "Private vault exceeds $MAX_FILES files" }
                    val output = File(dst, item.name.removeSuffix(EXT))
                    decryptFile(item, output, key)
                } else {
                    error("Unexpected file in private vault: ${item.name}")
                }
            }
        }
        walk(source, target)
    }

    internal fun encryptFile(input: File, output: File, key: ByteArray) {
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val temp = File(output.parentFile, output.name + ".tmp")
        FileInputStream(input).use { src ->
            FileOutputStream(temp).use { dst ->
                dst.write(MAGIC.toByteArray(Charsets.US_ASCII))
                dst.write(iv)
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = src.read(buffer)
                    if (n < 0) break
                    cipher.update(buffer, 0, n)?.let { encrypted -> dst.write(encrypted) }
                }
                dst.write(cipher.doFinal())
                dst.fd.sync()
            }
        }
        check(temp.renameTo(output)) { "Could not finalize encrypted file: ${input.name}" }
    }

    internal fun decryptFile(input: File, output: File, key: ByteArray) {
        val temp = File(output.parentFile, output.name + ".tmp")
        try {
            FileInputStream(input).use { src ->
                val magic = ByteArray(MAGIC.length)
                java.io.DataInputStream(src).readFully(magic)
                require(String(magic, Charsets.US_ASCII) == MAGIC) { "Invalid encrypted file header" }
                val iv = ByteArray(IV_BYTES)
                java.io.DataInputStream(src).readFully(iv)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                FileOutputStream(temp).use { dst ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = src.read(buffer)
                        if (n < 0) break
                        cipher.update(buffer, 0, n)?.let(dst::write)
                    }
                    // GCM authentication is checked here; temp is not published before this succeeds.
                    dst.write(cipher.doFinal())
                    dst.fd.sync()
                }
            }
            check(temp.renameTo(output)) { "Could not finalize decrypted file: ${input.name}" }
        } catch (t: Throwable) {
            temp.delete()
            throw t
        }
    }

    private fun treesEqual(left: File, right: File): Boolean {
        val leftFiles = treeFiles(left)
        val rightFiles = treeFiles(right)
        if (leftFiles.keys != rightFiles.keys) return false
        return leftFiles.all { (relative, file) -> digest(file).contentEquals(digest(rightFiles.getValue(relative))) }
    }

    private fun treeFiles(root: File): Map<String, File> {
        val result = LinkedHashMap<String, File>()
        fun walk(dir: File) {
            dir.listFiles().orEmpty().forEach { file ->
                require(!java.nio.file.Files.isSymbolicLink(file.toPath()))
                if (file.isDirectory) walk(file)
                else if (file.isFile) {
                    val relative = file.relativeTo(root).invariantSeparatorsPath
                    require(result.put(relative, file) == null) { "Duplicate private-space path" }
                }
            }
        }
        walk(root)
        return result
    }

    private fun digest(file: File): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
            }
        }
        return md.digest()
    }

    private fun stageHasFiles(stage: File): Boolean = stage.exists() && stage.walkTopDown().any { it.isFile }

    private fun deriveKey(pin: String, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BYTES * 8)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun encryptBytes(key: ByteArray, plain: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(plain)
    }

    private fun decryptBytes(key: ByteArray, envelope: ByteArray): ByteArray {
        require(envelope.size > IV_BYTES + 16) { "Wrapped vault key is truncated" }
        val iv = envelope.copyOfRange(0, IV_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(envelope, IV_BYTES, envelope.size - IV_BYTES)
    }

    private fun keystoreKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun encryptWithKeystore(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun decryptWithKeystore(envelope: ByteArray): ByteArray {
        require(envelope.size > IV_BYTES + 16) { "Keystore-wrapped vault key is truncated" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, envelope.copyOfRange(0, IV_BYTES)))
        return cipher.doFinal(envelope, IV_BYTES, envelope.size - IV_BYTES)
    }
}
