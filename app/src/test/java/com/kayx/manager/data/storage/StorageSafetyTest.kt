package com.kayx.manager.data.storage

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageSafetyTest {
    @Test
    fun deletionGuardAcceptsOnlyFilesInsideSharedRoot() {
        val base = createTempDir(prefix = "kayx-storage-guard-")
        try {
            val root = File(base, "shared").apply { mkdirs() }
            val inside = File(root, "Download/sample.txt").apply {
                parentFile?.mkdirs()
                writeText("safe")
            }
            val outside = File(base, "private.txt").apply { writeText("keep") }
            assertTrue(isSafeSharedStorageFile(inside, root))
            assertFalse(isSafeSharedStorageFile(outside, root))
            assertFalse(isSafeSharedStorageFile(root, root))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun deletionGuardRejectsSymlinkEscapingSharedRoot() {
        val base = createTempDir(prefix = "kayx-storage-link-")
        try {
            val root = File(base, "shared").apply { mkdirs() }
            val outside = File(base, "outside.txt").apply { writeText("keep") }
            val link = File(root, "linked.txt")
            try {
                Files.createSymbolicLink(link.toPath(), outside.toPath())
                assertFalse(isSafeSharedStorageFile(link, root))
            } catch (_: UnsupportedOperationException) {
                // Some host filesystems do not support symbolic links.
            } catch (_: SecurityException) {
                // Symlink creation can be restricted in CI sandboxes.
            }
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun pinDerivationUsesSaltAndVerifiesWithConstantTimeComparisonHelper() {
        val salt = PrivateSpacePin.newSalt()
        val otherSalt = PrivateSpacePin.newSalt()
        val derived = PrivateSpacePin.derive("1234", salt, 10_000)
        val same = PrivateSpacePin.derive("1234", salt, 10_000)
        val differentPin = PrivateSpacePin.derive("4321", salt, 10_000)
        val differentSalt = PrivateSpacePin.derive("1234", otherSalt, 10_000)

        assertTrue(PrivateSpacePin.constantTimeEquals(derived, same))
        assertFalse(PrivateSpacePin.constantTimeEquals(derived, differentPin))
        assertNotEquals(derived, differentSalt)
    }
}
