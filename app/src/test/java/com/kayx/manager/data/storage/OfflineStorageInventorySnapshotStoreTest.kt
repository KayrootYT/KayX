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

class OfflineStorageInventorySnapshotStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun savesAndRestoresBoundedMetadataSnapshot() {
        val root = temporaryFolder.newFolder("storage")
        val file = File(root, "photo.jpg").apply { writeText("image-data") }
        val entry = OfflineStorageInventory.Entry(
            key = file.absolutePath,
            path = file.canonicalPath,
            name = file.name,
            size = file.length(),
            modified = file.lastModified(),
            category = Category.IMAGES,
            saf = false
        )
        val result = OfflineStorageInventory.Result(
            entries = listOf(entry),
            largeFiles = emptyList(),
            oldFiles = emptyList(),
            duplicateCandidates = emptyList(),
            duplicateGroups = emptyList(),
            totalBytes = file.length(),
            scannedRoots = 1,
            inaccessibleRoots = 0,
            truncated = false
        )
        val store = OfflineStorageInventorySnapshotStore(temporaryFolder.newFolder("private"))

        assertTrue(store.save(result))
        val restored = store.load()

        assertNotNull(restored)
        assertEquals(listOf(entry), restored!!.entries)
        assertEquals(file.length(), restored.totalBytes)
        assertEquals(1, restored.scannedRoots)
    }

    @Test fun dropsLocalEntriesWhoseMetadataChangedAfterSnapshot() {
        val root = temporaryFolder.newFolder("storage")
        val file = File(root, "report.txt").apply { writeText("before") }
        val entry = OfflineStorageInventory.Entry(
            file.absolutePath, file.canonicalPath, file.name, file.length(), file.lastModified(),
            Category.DOCUMENTS, saf = false
        )
        val store = OfflineStorageInventorySnapshotStore(temporaryFolder.newFolder("private"))
        assertTrue(store.save(OfflineStorageInventory.Result(
            listOf(entry), emptyList(), emptyList(), emptyList(), emptyList(),
            file.length(), 1, 0, false
        )))

        file.writeText("changed content and size")

        assertTrue(store.load()!!.entries.isEmpty())
    }

    @Test fun recoversValidBackupWhenPrimarySnapshotIsCorrupt() {
        val privateDir = temporaryFolder.newFolder("private")
        val store = OfflineStorageInventorySnapshotStore(privateDir)
        val file = File(temporaryFolder.newFolder("storage"), "a.txt").apply { writeText("a") }
        val entry = OfflineStorageInventory.Entry(
            file.absolutePath, file.canonicalPath, file.name, file.length(), file.lastModified(),
            Category.DOCUMENTS, saf = false
        )
        val result = OfflineStorageInventory.Result(
            listOf(entry), emptyList(), emptyList(), emptyList(), emptyList(), file.length(), 1, 0, false
        )
        assertTrue(store.save(result))
        val primary = File(privateDir, "offline-assistant-inventory.snapshot")
        val backup = File(privateDir, "offline-assistant-inventory.snapshot.bak")
        assertTrue(primary.renameTo(backup))
        primary.writeText("corrupt")

        assertNotNull(store.load())
        assertTrue(primary.isFile)
        assertFalse(backup.exists())
    }

    @Test fun rejectsMalformedSnapshot() {
        val privateDir = temporaryFolder.newFolder("private")
        File(privateDir, "offline-assistant-inventory.snapshot").writeText("bad")
        assertNull(OfflineStorageInventorySnapshotStore(privateDir).load())
    }
}
