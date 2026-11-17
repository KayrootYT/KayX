package com.kayx.manager.data.archive

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveSafetyTest {
    @Test
    fun zipExtractionRejectsTraversalEntry() {
        val root = createTempDir(prefix = "kayx-archive-traversal-")
        try {
            val zip = File(root, "bad.zip")
            ZipOutputStream(zip.outputStream()).use { out ->
                out.putNextEntry(ZipEntry("../escape.txt"))
                out.write("bad".toByteArray())
                out.closeEntry()
            }
            val result = ArchiveRepository().extract(zip, null)
            assertTrue(result.isFailure)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun zipIntegrityReportsFileCount() {
        val root = createTempDir(prefix = "kayx-archive-integrity-")
        try {
            val zip = File(root, "ok.zip")
            ZipOutputStream(zip.outputStream()).use { out ->
                out.putNextEntry(ZipEntry("one.txt"))
                out.write("one".toByteArray())
                out.closeEntry()
                out.putNextEntry(ZipEntry("folder/"))
                out.closeEntry()
            }
            val (count, error) = ArchiveRepository().testIntegrity(zip)
            assertEquals(1, count)
            assertEquals(null, error)
        } finally {
            root.deleteRecursively()
        }
    }
}
