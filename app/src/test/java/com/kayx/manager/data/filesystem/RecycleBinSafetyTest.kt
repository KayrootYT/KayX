package com.kayx.manager.data.filesystem

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecycleBinSafetyTest {
    @Test
    fun recycleEntriesExpireAtThirtyDaysButNotBefore() {
        val day = 24L * 60 * 60 * 1000
        val now = 2_000_000_000_000L
        assertFalse(isRecycleExpired(now - 30 * day + 1, now))
        assertTrue(isRecycleExpired(now - 30 * day, now))
        assertTrue(isRecycleExpired(now - 45 * day, now))
        assertFalse(isRecycleExpired(now + day, now))
        assertFalse(isRecycleExpired(0L, now))
    }

    @Test
    fun rejectsTraversalAndSeparatorsInEntryIds() {
        assertTrue(isSafeRecycleEntryId("mabc123_photo.jpg"))
        assertFalse(isSafeRecycleEntryId("../private"))
        assertFalse(isSafeRecycleEntryId("folder/item"))
        assertFalse(isSafeRecycleEntryId("folder\\item"))
        assertFalse(isSafeRecycleEntryId(".."))
        assertFalse(isSafeRecycleEntryId("index.tsv"))
    }

    @Test
    fun originalMustStayInsideSharedStorageAndOutsideRecycleBin() {
        val base = createTempDir(prefix = "kayx-recycle-guard-")
        try {
            val shared = File(base, "shared").apply { mkdirs() }
            val bin = File(shared, ".KayXRecycleBin").apply { mkdirs() }
            val safe = File(shared, "Download/photo.jpg").apply { parentFile?.mkdirs(); writeText("x") }
            val privateFile = File(base, "private.txt").apply { writeText("x") }
            val binFile = File(bin, "payload").apply { writeText("x") }

            assertTrue(isSafeRecycleOriginal(safe.path, shared, bin))
            assertFalse(isSafeRecycleOriginal(privateFile.path, shared, bin))
            assertFalse(isSafeRecycleOriginal(binFile.path, shared, bin))
            assertFalse(isSafeRecycleOriginal(shared.path, shared, bin))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun rejectsSymlinkThatEscapesSharedStorage() {
        val base = createTempDir(prefix = "kayx-recycle-link-")
        try {
            val shared = File(base, "shared").apply { mkdirs() }
            val bin = File(shared, ".KayXRecycleBin").apply { mkdirs() }
            val outside = File(base, "outside.txt").apply { writeText("x") }
            val link = File(shared, "linked.txt")
            try {
                java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
                assertFalse(isSafeRecycleOriginal(link.path, shared, bin))
            } catch (_: UnsupportedOperationException) {
                // Host filesystem does not support symbolic links.
            } catch (_: SecurityException) {
                // CI sandbox disallows symbolic-link creation.
            }
        } finally {
            base.deleteRecursively()
        }
    }
}
