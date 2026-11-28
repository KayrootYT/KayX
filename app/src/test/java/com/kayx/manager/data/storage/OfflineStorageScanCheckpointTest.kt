package com.kayx.manager.data.storage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineStorageScanCheckpointTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun roundTripsPendingDirectoriesAndLocalMetadata() {
        val root = temporaryFolder.newFolder("storage-root")
        val checkpoint = File(temporaryFolder.root, "scan.checkpoint")
        val source = File(root, "Pictures/photo.jpg").absolutePath
        val entry = OfflineStorageInventory.Entry(
            key = source,
            path = source,
            name = "photo.jpg",
            size = 123L,
            modified = 456L,
            category = Category.IMAGES,
            saf = false
        )
        val state = OfflineStorageScanCheckpoint.State(
            root = root.canonicalPath,
            maxEntries = 50_000,
            maxDepth = 24,
            localComplete = false,
            pending = listOf(OfflineStorageScanCheckpoint.PendingDirectory(File(root, "Pictures").path, 1)),
            entries = listOf(entry)
        )

        OfflineStorageScanCheckpoint.saveFile(checkpoint, state)
        val restored = OfflineStorageScanCheckpoint.loadFile(checkpoint, root, 50_000, 24)

        assertNotNull(restored)
        assertEquals(state, restored)
    }

    @Test fun rejectsCheckpointForDifferentRootOrScanLimits() {
        val root = temporaryFolder.newFolder("root-a")
        val other = temporaryFolder.newFolder("root-b")
        val checkpoint = File(temporaryFolder.root, "scan.checkpoint")
        OfflineStorageScanCheckpoint.saveFile(
            checkpoint,
            OfflineStorageScanCheckpoint.State(root.canonicalPath, 50_000, 24, true, emptyList(), emptyList())
        )

        assertNull(OfflineStorageScanCheckpoint.loadFile(checkpoint, other, 50_000, 24))
        assertNull(OfflineStorageScanCheckpoint.loadFile(checkpoint, root, 10_000, 24))
        assertNull(OfflineStorageScanCheckpoint.loadFile(checkpoint, root, 50_000, 12))
    }

    @Test fun refusesOversizedOrMalformedCheckpoint() {
        val root = temporaryFolder.newFolder("root")
        val checkpoint = File(temporaryFolder.root, "scan.checkpoint")
        checkpoint.writeText("not-a-kayx-checkpoint")
        assertNull(OfflineStorageScanCheckpoint.loadFile(checkpoint, root, 50_000, 24))
        checkpoint.writeBytes(ByteArray(64 * 1024 * 1024 + 1))
        assertNull(OfflineStorageScanCheckpoint.loadFile(checkpoint, root, 50_000, 24))
    }
}
