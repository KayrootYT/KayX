package com.kayx.manager.data.filesystem

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageTransferRepositoryTest {
    @Test
    fun skipConflictIsReportedWithoutCountingCopiedBytes() {
        val base = createTempDir(prefix = "kayx-storage-skip-")
        try {
            val sourceDir = File(base, "source").apply { mkdirs() }
            val destination = File(base, "destination").apply { mkdirs() }
            val source = File(sourceDir, "same.txt").apply { writeText("new payload") }
            val existing = File(destination, source.name).apply { writeText("keep existing") }
            val progress = mutableListOf<Long>()

            val result = StorageTransferRepository().transfer(
                context = null,
                sourceKeys = listOf(StorageLocation.Local(source).stableKey()),
                destinationKey = StorageLocation.Local(destination).stableKey(),
                move = false,
                policy = Conflict.SKIP,
                control = FileOps.TransferControl(),
                onProgress = { _, _, _, copied, _ -> progress += copied }
            ).getOrThrow()

            assertEquals(0, result.completed)
            assertEquals(1, result.skipped)
            assertTrue(result.failed.isEmpty())
            assertEquals("keep existing", existing.readText())
            assertTrue(source.exists())
            assertEquals(0L, progress.last())
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun movingIntoSameFolderIsAReportedNoOp() {
        val base = createTempDir(prefix = "kayx-storage-same-folder-")
        try {
            val source = File(base, "same.txt").apply { writeText("keep") }
            val result = StorageTransferRepository().transfer(
                context = null,
                sourceKeys = listOf(StorageLocation.Local(source).stableKey()),
                destinationKey = StorageLocation.Local(base).stableKey(),
                move = true,
                policy = Conflict.KEEP_BOTH,
                control = FileOps.TransferControl()
            ).getOrThrow()

            assertEquals(0, result.completed)
            assertEquals(1, result.skipped)
            assertTrue(source.exists())
            assertEquals(listOf("same.txt"), base.listFiles()?.map { it.name })
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun localCopyReportsCompletedBytesAndPreservesSource() {
        val base = createTempDir(prefix = "kayx-storage-copy-")
        try {
            val source = File(base, "source.bin").apply { writeBytes(ByteArray(180_000) { 11 }) }
            val destination = File(base, "destination").apply { mkdirs() }
            var copiedBytes = 0L
            val result = StorageTransferRepository().transfer(
                context = null,
                sourceKeys = listOf(StorageLocation.Local(source).stableKey()),
                destinationKey = StorageLocation.Local(destination).stableKey(),
                move = false,
                policy = Conflict.KEEP_BOTH,
                control = FileOps.TransferControl(),
                onProgress = { _, _, _, copied, _ -> copiedBytes = maxOf(copiedBytes, copied) }
            ).getOrThrow()

            assertEquals(1, result.completed)
            assertEquals(0, result.skipped)
            assertTrue(result.failed.isEmpty())
            assertEquals(source.length(), copiedBytes)
            assertEquals(source.readBytes().toList(), File(destination, source.name).readBytes().toList())
            assertFalse(result.cleanupWarnings.isNotEmpty())
            assertTrue(source.exists())
        } finally {
            base.deleteRecursively()
        }
    }
}
