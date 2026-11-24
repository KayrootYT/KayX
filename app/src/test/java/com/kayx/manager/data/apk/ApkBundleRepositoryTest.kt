package com.kayx.manager.data.apk

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkBundleRepositoryTest {
    @Test
    fun inspectsBaseAndSplitApksInBundle() {
        val root = createTempDir(prefix = "kayx-apk-bundle-")
        try {
            val bundle = File(root, "sample.apks")
            ZipOutputStream(bundle.outputStream()).use { out ->
                for (name in listOf("splits/arm64-v8a.apk", "base.apk", "manifest.json")) {
                    out.putNextEntry(ZipEntry(name))
                    if (name.endsWith(".apk")) out.write(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
                    else out.write("{}".toByteArray())
                    out.closeEntry()
                }
            }
            val inspected = ApkBundleRepository.inspect(bundle).getOrThrow()
            assertEquals("APKS/XAPK", inspected.type)
            assertEquals(listOf("base.apk", "splits/arm64-v8a.apk"), inspected.apkFiles)
            assertEquals(listOf("manifest.json"), inspected.manifestFiles)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsBundleWithoutApkPayload() {
        val root = createTempDir(prefix = "kayx-apk-bundle-empty-")
        try {
            val bundle = File(root, "empty.xapk")
            ZipOutputStream(bundle.outputStream()).use { out ->
                out.putNextEntry(ZipEntry("manifest.json"))
                out.write("{}".toByteArray())
                out.closeEntry()
            }
            assertTrue(ApkBundleRepository.inspect(bundle).isFailure)
        } finally {
            root.deleteRecursively()
        }
    }
    @Test
    fun rejectsPathTraversalEntries() {
        val root = createTempDir(prefix = "kayx-apk-bundle-traversal-")
        try {
            val bundle = File(root, "unsafe.apks")
            ZipOutputStream(bundle.outputStream()).use { out ->
                for (name in listOf("base.apk", "../outside.apk")) {
                    out.putNextEntry(ZipEntry(name))
                    out.write(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
                    out.closeEntry()
                }
            }
            assertTrue(ApkBundleRepository.inspect(bundle).isFailure)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun inspectsSplitOnlyBundleButDoesNotAssumeItCanBeInstalledAlone() {
        val root = createTempDir(prefix = "kayx-apk-bundle-no-base-")
        try {
            val bundle = File(root, "splits.apks")
            ZipOutputStream(bundle.outputStream()).use { out ->
                out.putNextEntry(ZipEntry("split_config.arm64_v8a.apk"))
                out.write(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
                out.closeEntry()
            }
            val inspected = ApkBundleRepository.inspect(bundle).getOrThrow()
            assertEquals(listOf("split_config.arm64_v8a.apk"), inspected.apkFiles)
        } finally {
            root.deleteRecursively()
        }
    }

}
