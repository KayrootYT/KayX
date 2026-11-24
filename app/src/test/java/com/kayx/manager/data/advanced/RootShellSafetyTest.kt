package com.kayx.manager.data.advanced

import org.junit.Assert.*
import org.junit.Test

class RootShellSafetyTest {
    @Test fun quotesSingleQuotesWithoutShellExpansion() {
        assertEquals("'a'\\''b'", RootShell.q("a'b"))
        assertEquals("'/sdcard/My Files; echo bad'", RootShell.q("/sdcard/My Files; echo bad"))
    }

    @Test fun refusesFilesystemAndSystemMountRoots() {
        listOf("/", "/system", "/vendor", "/product", "/data", "/proc", "/sys", "/dev", "/apex").forEach {
            assertFalse("$it must be protected", isSafeRecursiveDeleteTarget(it))
        }
    }

    @Test fun refusesBlankDeletePaths() {
        assertFalse(isSafeRecursiveDeleteTarget(""))
        assertFalse(isSafeRecursiveDeleteTarget("   "))
    }

    @Test fun allowsSpecificUserFilesAndDirectories() {
        assertTrue(isSafeRecursiveDeleteTarget("/sdcard/Download/old.zip"))
        assertTrue(isSafeRecursiveDeleteTarget("/data/local/tmp/test"))
    }

    @Test fun refusesSharedStorageAndRemovableVolumeRoots() {
        listOf("/storage", "/storage/emulated/0", "/storage/ABCD-1234", "/mnt/media_rw/ABCD-1234", "/mnt/user/0/primary").forEach {
            assertFalse("$it must be protected", isSafeRecursiveDeleteTarget(it))
        }
        assertTrue(isSafeRecursiveDeleteTarget("/storage/emulated/0/Download/old.zip"))
    }

    @Test fun resolvesSymlinksBeforeCheckingProtectedRoots() {
        val base = java.io.File(System.getProperty("java.io.tmpdir"), "kayx-root-guard-${System.nanoTime()}").apply { mkdirs() }
        try {
            val link = java.io.File(base, "system-link")
            try {
                java.nio.file.Files.createSymbolicLink(link.toPath(), java.nio.file.Paths.get("/system"))
                assertFalse(isSafeRecursiveDeleteTarget(java.io.File(link, "private").path))
            } catch (_: UnsupportedOperationException) {
                // Host filesystem does not support symbolic links.
            } catch (_: SecurityException) {
                // CI sandbox disallows symbolic-link creation.
            }
        } finally {
            base.deleteRecursively()
        }
    }
}
