package com.kayx.manager.data.storage

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class StorageScannerDuplicateTest {
    @Test fun comparesBytesAfterSameLengthHashBucket() {
        val dir = createTempDir(prefix = "kayx-duplicate-check-")
        try {
            val a = File(dir, "a.bin").apply { writeBytes(ByteArray(128 * 1024) { (it % 251).toByte() }) }
            val b = File(dir, "b.bin").apply { writeBytes(ByteArray(128 * 1024) { (it % 251).toByte() }) }
            val c = File(dir, "c.bin").apply { writeBytes(ByteArray(128 * 1024) { ((it + 1) % 251).toByte() }) }
            assertTrue(filesHaveSameContent(a, b))
            assertFalse(filesHaveSameContent(a, c))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun rejectsDifferentSizesAndNonFiles() {
        val dir = createTempDir(prefix = "kayx-duplicate-types-")
        try {
            val a = File(dir, "a.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val b = File(dir, "b.bin").apply { writeBytes(byteArrayOf(1, 2)) }
            val folder = File(dir, "folder").apply { mkdirs() }
            assertFalse(filesHaveSameContent(a, b))
            assertFalse(filesHaveSameContent(a, folder))
        } finally {
            dir.deleteRecursively()
        }
    }
}
