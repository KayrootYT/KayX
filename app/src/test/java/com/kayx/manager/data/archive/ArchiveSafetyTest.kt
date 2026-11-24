package com.kayx.manager.data.archive

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.GZIPOutputStream
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
    @Test
    fun zipExtractionEnforcesActualExpandedByteLimit() {
        val root = createTempDir(prefix = "kayx-archive-limit-")
        try {
            val zip = File(root, "large.zip")
            ZipOutputStream(zip.outputStream()).use { out ->
                out.putNextEntry(ZipEntry("payload.txt"))
                out.write(ByteArray(128) { 65 })
                out.closeEntry()
            }
            val result = ArchiveRepository().extract(
                zip, null, ArchiveRepository.ArchiveLimits(maxEntries = 10, maxExtractedBytes = 32)
            )
            assertTrue(result.isFailure)
            assertTrue(root.listFiles().orEmpty().none { it.name == "large" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun zipExtractionRejectsWindowsStyleTraversal() {
        val root = createTempDir(prefix = "kayx-archive-windows-path-")
        try {
            val zip = File(root, "windows.zip")
            ZipOutputStream(zip.outputStream()).use { out ->
                out.putNextEntry(ZipEntry("..\\\\escape.txt"))
                out.write("bad".toByteArray())
                out.closeEntry()
            }
            assertTrue(ArchiveRepository().extract(zip, null).isFailure)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun gzipExtractionEnforcesActualExpandedByteLimit() {
        val root = createTempDir(prefix = "kayx-gzip-limit-")
        try {
            val gzip = File(root, "payload.gz")
            GZIPOutputStream(gzip.outputStream()).use { it.write(ByteArray(256) { 7 }) }
            val result = MultiExtract.extract(
                gzip, MultiExtract.ExtractionLimits(maxEntries = 10, maxExtractedBytes = 64)
            )
            assertTrue(result.isFailure)
            assertTrue(root.listFiles().orEmpty().none { it.name == "payload" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun archiveIntegrityScanHonorsByteLimit() {
        val root = createTempDir(prefix = "kayx-archive-integrity-limit-")
        try {
            val zip = File(root, "large.zip")
            ZipOutputStream(zip.outputStream()).use { out ->
                out.putNextEntry(ZipEntry("payload.txt"))
                out.write(ByteArray(128) { 1 })
                out.closeEntry()
            }
            val (count, error) = ArchiveRepository().testIntegrity(
                zip, ArchiveRepository.ArchiveLimits(maxEntries = 10, maxExtractedBytes = 16)
            )
            assertEquals(1, count)
            assertTrue(error?.contains("limit") == true)
        } finally {
            root.deleteRecursively()
        }
    }

}
