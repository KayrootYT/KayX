package com.kayx.manager.data.filesystem

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineTransferRecoveryStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun record() = OfflineTransferRecoveryStore.Record(
        sources = listOf("local:/storage/emulated/0/Download/a.txt", "local:/storage/emulated/0/Download/b.txt"),
        destination = "local:/storage/emulated/0/Documents",
        moving = false,
        conflictPolicy = Conflict.KEEP_BOTH,
        state = OfflineTransferRecoveryStore.State.RUNNING,
        startedAtMillis = 123456789L
    )

    @Test fun persistsProgressAndMarksRunningOperationInterruptedAfterRestart() {
        val store = OfflineTransferRecoveryStore(temporaryFolder.newFolder())
        assertTrue(store.begin(record()))
        assertTrue(store.updateProgress(1))

        val restored = OfflineTransferRecoveryStore(storeDirectory(store)).markInterrupted()

        assertNotNull(restored)
        assertEquals(OfflineTransferRecoveryStore.State.INTERRUPTED, restored!!.state)
        assertEquals(1, restored.completedItems)
        assertEquals(2, restored.totalItems)
        assertEquals(record().sources, restored.sources)
    }

    @Test fun retainsNeedsReviewUntilExplicitlyCleared() {
        val directory = temporaryFolder.newFolder()
        val store = OfflineTransferRecoveryStore(directory)
        assertTrue(store.begin(record()))
        assertTrue(store.markNeedsReview())
        assertEquals(OfflineTransferRecoveryStore.State.NEEDS_REVIEW, store.load()!!.state)
        store.clear()
        assertNull(store.load())
    }

    @Test fun recoversValidBackupWhenPrimaryIsCorrupt() {
        val directory = temporaryFolder.newFolder()
        val store = OfflineTransferRecoveryStore(directory)
        assertTrue(store.begin(record()))
        val primary = File(directory, "offline-transfer-recovery.bin")
        val backup = File(directory, "offline-transfer-recovery.bin.bak")
        assertTrue(primary.renameTo(backup))
        primary.writeText("corrupt")

        val restored = store.load()

        assertNotNull(restored)
        assertEquals(record().sources, restored!!.sources)
        assertTrue(primary.isFile)
        assertTrue(!backup.exists())
    }

    private fun storeDirectory(store: OfflineTransferRecoveryStore): File {
        val field = OfflineTransferRecoveryStore::class.java.getDeclaredField("directory")
        field.isAccessible = true
        return field.get(store) as File
    }
}
