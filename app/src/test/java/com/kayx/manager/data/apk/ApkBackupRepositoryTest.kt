package com.kayx.manager.data.apk

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkBackupRepositoryTest {
    @Test
    fun backsUpSingleApkWithoutOverwritingExistingBackup() {
        val root = createTempDir(prefix = "kayx-apk-backup-single-")
        try {
            val source = File(root, "source.apk").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val destination = File(root, "out")
            val first = ApkBackupRepository.backup("Demo App", "1.0", listOf(source.path), destination).getOrThrow()
            val second = ApkBackupRepository.backup("Demo App", "1.0", listOf(source.path), destination).getOrThrow()

            assertEquals("Demo_App_1.0.apk", first.name)
            assertEquals("Demo_App_1.0 (2).apk", second.name)
            assertTrue(first.readBytes().contentEquals(source.readBytes()))
            assertTrue(second.readBytes().contentEquals(source.readBytes()))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun backsUpBaseAndSplitApksAsOneDirectory() {
        val root = createTempDir(prefix = "kayx-apk-backup-splits-")
        try {
            val base = File(root, "base.apk").apply { writeBytes(byteArrayOf(1, 2)) }
            val split = File(root, "config.arm64.apk").apply { writeBytes(byteArrayOf(3, 4, 5)) }
            val result = ApkBackupRepository.backup(
                "Demo/App", "2.0", listOf(base.path, split.path), File(root, "out")
            ).getOrThrow()

            assertTrue(result.isDirectory)
            assertTrue(File(result, "base.apk").exists())
            assertTrue(File(result, "config.arm64.apk").exists())
            assertTrue(File(result, "base.apk").readBytes().contentEquals(base.readBytes()))
            assertTrue(File(result, "config.arm64.apk").readBytes().contentEquals(split.readBytes()))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsMissingSourceWithoutLeavingTemporaryBackup() {
        val root = createTempDir(prefix = "kayx-apk-backup-missing-")
        try {
            val source = File(root, "present.apk").apply { writeBytes(byteArrayOf(1)) }
            val result = ApkBackupRepository.backup(
                "Demo", "1", listOf(source.path, File(root, "missing.apk").path), File(root, "out")
            )
            assertTrue(result.isFailure)
            assertFalse(File(root, "out").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sanitizesBackupNamesAndDeduplicatesSplitNames() {
        val root = createTempDir(prefix = "kayx-apk-backup-names-")
        try {
            val first = File(root, "same.apk").apply { writeBytes(byteArrayOf(1)) }
            val secondDir = File(root, "second").apply { mkdirs() }
            val second = File(secondDir, "same.apk").apply { writeBytes(byteArrayOf(2)) }
            val result = ApkBackupRepository.backup(
                "../Demo:App", "1/2", listOf(first.path, second.path), File(root, "out")
            ).getOrThrow()

            assertEquals(2, result.listFiles()?.size)
            assertTrue(result.name.none { it == '/' || it == '\\' })
            assertTrue(File(result, "same.apk").exists())
            assertTrue(File(result, "same (2).apk").exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
