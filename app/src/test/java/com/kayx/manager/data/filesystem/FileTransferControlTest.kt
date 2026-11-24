package com.kayx.manager.data.filesystem

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileTransferControlTest {
    @Test
    fun cancelledTransferDoesNotLeaveDestinationPayload() {
        val base = createTempDir(prefix = "kayx-transfer-cancel-")
        try {
            val source = File(base, "source.bin").apply { writeBytes(ByteArray(4096) { 7 }) }
            val destination = File(base, "destination").apply { mkdirs() }
            val control = FileOps.TransferControl()

            val result = FileOps.transfer(
                listOf(source),
                destination,
                move = false,
                policy = Conflict.KEEP_BOTH,
                onProgress = { _, _, _ -> control.cancel() },
                control = control
            )

            assertTrue(result.isFailure)
            assertTrue(control.isStopped())
            assertFalse(File(destination, source.name).exists())
            assertTrue(source.exists())
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun reportsActualBytesWrittenDuringCopy() {
        val base = createTempDir(prefix = "kayx-transfer-progress-")
        try {
            val source = File(base, "source.bin").apply { writeBytes(ByteArray(150_000) { 3 }) }
            val destination = File(base, "destination").apply { mkdirs() }
            var copiedBytes = 0L

            val result = FileOps.transfer(
                listOf(source), destination, move = false, policy = Conflict.KEEP_BOTH,
                onBytesCopied = { copiedBytes += it }
            )

            assertTrue(result.isSuccess)
            assertEquals(source.length(), copiedBytes)
            assertEquals(source.length(), File(destination, source.name).length())
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun copyTransferPreservesNestedDirectoryContents() {
        val base = createTempDir(prefix = "kayx-transfer-copy-")
        try {
            val source = File(base, "source").apply { mkdirs() }
            File(source, "nested").mkdirs()
            File(source, "nested/data.txt").writeText("KayX transfer test")
            val destination = File(base, "destination").apply { mkdirs() }

            val result = FileOps.transfer(
                listOf(source),
                destination,
                move = false,
                policy = Conflict.KEEP_BOTH
            )

            assertTrue(result.isSuccess)
            assertTrue(File(destination, "source/nested/data.txt").readText() == "KayX transfer test")
            assertTrue(source.exists())
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun copyRefusesToFollowSymbolicLinkSources() {
        val base = createTempDir(prefix = "kayx-transfer-symlink-")
        try {
            val outside = File(base, "outside.txt").apply { writeText("keep") }
            val link = File(base, "linked.txt")
            try {
                java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
            } catch (_: UnsupportedOperationException) {
                return
            } catch (_: SecurityException) {
                return
            }
            val destination = File(base, "destination").apply { mkdirs() }
            val result = FileOps.transfer(listOf(link), destination, move = false, policy = Conflict.KEEP_BOTH)
            assertTrue(result.isFailure)
            assertFalse(File(destination, link.name).exists())
            assertTrue(outside.readText() == "keep")
            assertTrue(link.exists())
        } finally {
            base.deleteRecursively()
        }
    }
}
