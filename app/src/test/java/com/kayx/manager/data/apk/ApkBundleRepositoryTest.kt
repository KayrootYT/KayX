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
}
