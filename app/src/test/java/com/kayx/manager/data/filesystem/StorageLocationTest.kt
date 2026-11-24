package com.kayx.manager.data.filesystem

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageLocationTest {
    @Test
    fun localLocationRoundTripsWithoutLosingAbsolutePath() {
        val original = StorageLocation.Local(File("/storage/emulated/0/Download"))
        val restored = StorageLocation.fromStableKey(original.stableKey())

        assertEquals(original, restored)
        assertTrue(original.stableKey().startsWith("local:/"))
    }

    @Test
    fun safLocationRoundTripsTreeUriAndUnicodeRelativePath() {
        val original = StorageLocation.Saf(
            "content://com.android.externalstorage.documents/tree/ABCD%3A",
            "Music/हिंदी songs"
        )

        assertEquals(original, StorageLocation.fromStableKey(original.stableKey()))
    }

    @Test
    fun safChildKeepsTreeBoundaryAndAddsOnlyRelativeSegment() {
        val root = StorageLocation.Saf("content://provider/tree/root")
        val child = root.child("Documents")

        assertEquals("Documents", (child as StorageLocation.Saf).relativePath)
        assertEquals(root.treeUri, child.treeUri)
    }

    @Test(expected = IllegalArgumentException::class)
    fun safLocationRejectsParentTraversal() {
        StorageLocation.Saf("content://provider/tree/root", "Documents/../private")
    }

    @Test
    fun unknownOrMalformedLocationKeysAreRejected() {
        assertNull(StorageLocation.fromStableKey("file:///storage/emulated/0"))
        assertNull(StorageLocation.fromStableKey("saf://location/%ZZ?path="))
    }
}
