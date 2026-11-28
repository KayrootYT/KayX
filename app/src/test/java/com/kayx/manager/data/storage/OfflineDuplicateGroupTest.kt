package com.kayx.manager.data.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineDuplicateGroupTest {
    @Test
    fun reclaimableBytesKeepsOneCopyAndCountsOnlyExtraCopies() {
        val entries = listOf(
            entry("/storage/emulated/0/Download/a.bin"),
            entry("/storage/ABCD-1234/Backup/a.bin"),
            entry("/storage/emulated/0/Documents/a-copy.bin")
        )
        val group = OfflineStorageInventory.DuplicateGroup(
            sha256 = "a".repeat(64),
            size = 4L * 1024L,
            entries = entries
        )

        assertEquals(8L * 1024L, group.reclaimableBytes)
    }

    @Test
    fun singleCopyGroupHasNoReclaimableBytes() {
        val group = OfflineStorageInventory.DuplicateGroup(
            sha256 = "b".repeat(64),
            size = 12L * 1024L,
            entries = listOf(entry("/storage/emulated/0/only.bin"))
        )

        assertEquals(0L, group.reclaimableBytes)
    }

    private fun entry(path: String) = OfflineStorageInventory.Entry(
        key = path,
        path = path,
        name = path.substringAfterLast('/'),
        size = 4L * 1024L,
        modified = 1L,
        category = null,
        saf = path.startsWith("/storage/ABCD")
    )
}
