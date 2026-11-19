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
}
