package com.kayx.manager.data.storage

import java.io.File
import java.security.SecureRandom
import org.junit.Assert.*
import org.junit.Test

class PrivateSpaceVaultTest {
    @Test fun encryptedFileRoundTripsAndDoesNotContainPlaintext() {
        val dir = createTempDir(prefix = "kayx-vault-roundtrip-")
        try {
            val plain = File(dir, "secret.txt").apply { writeText("private-content-12345") }
            val encrypted = File(dir, "secret.txt.kxv")
            val restored = File(dir, "restored.txt")
            val key = ByteArray(32).also { SecureRandom().nextBytes(it) }

            PrivateSpaceVault.encryptFile(plain, encrypted, key)
            assertFalse(encrypted.readBytes().toString(Charsets.ISO_8859_1).contains("private-content-12345"))
            PrivateSpaceVault.decryptFile(encrypted, restored, key)
            assertArrayEquals(plain.readBytes(), restored.readBytes())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun tamperedCiphertextIsRejectedWithoutPublishingPlaintext() {
        val dir = createTempDir(prefix = "kayx-vault-tamper-")
        try {
            val plain = File(dir, "source.txt").apply { writeText("do-not-expose-on-auth-failure") }
            val encrypted = File(dir, "source.txt.kxv")
            val restored = File(dir, "restored.txt")
            val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
            PrivateSpaceVault.encryptFile(plain, encrypted, key)

            val bytes = encrypted.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x40).toByte()
            encrypted.writeBytes(bytes)
            assertTrue(runCatching { PrivateSpaceVault.decryptFile(encrypted, restored, key) }.isFailure)
            assertFalse(restored.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun wrongVaultKeyCannotDecryptFile() {
        val dir = createTempDir(prefix = "kayx-vault-key-")
        try {
            val plain = File(dir, "source.txt").apply { writeText("key-bound-content") }
            val encrypted = File(dir, "source.txt.kxv")
            val restored = File(dir, "restored.txt")
            val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val wrong = ByteArray(32).also { SecureRandom().nextBytes(it) }
            PrivateSpaceVault.encryptFile(plain, encrypted, key)
            assertTrue(runCatching { PrivateSpaceVault.decryptFile(encrypted, restored, wrong) }.isFailure)
            assertFalse(restored.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
