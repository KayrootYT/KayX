package com.kayx.manager.data.archive

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveEditSafetyTest {
    @Test
    fun readEntryReturnsSmallPayload() {
        val root = createTempDir(prefix = "kayx-archive-preview-")
        try {
            val zip = File(root, "preview.zip")
            ZipOutputStream(zip.outputStream()).use { out ->
                out.putNextEntry(ZipEntry("folder/item.txt"))
                out.write("hello".toByteArray())
                out.closeEntry()
            }
            assertEquals("hello", ArchiveEdit.readEntry(zip, "folder/item.txt").toString(Charsets.UTF_8))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun readEntryRejectsPayloadAboveCallerLimit() {
        val root = createTempDir(prefix = "kayx-archive-preview-limit-")
        try {
            val zip = File(root, "large.zip")
            ZipOutputStream(zip.outputStream()).use { out ->
                out.putNextEntry(ZipEntry("payload.bin"))
                out.write(ByteArray(256) { 7 })
                out.closeEntry()
            }
            assertTrue(runCatching { ArchiveEdit.readEntry(zip, "payload.bin", 64) }.isFailure)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun readEntryRejectsTraversalName() {
        val root = createTempDir(prefix = "kayx-archive-preview-path-")
        try {
            val zip = File(root, "safe.zip")
            ZipOutputStream(zip.outputStream()).use { out ->
                out.putNextEntry(ZipEntry("safe.txt"))
                out.write("safe".toByteArray())
                out.closeEntry()
            }
            assertTrue(runCatching { ArchiveEdit.readEntry(zip, "../safe.txt") }.isFailure)
        } finally { root.deleteRecursively() }
    }
}
