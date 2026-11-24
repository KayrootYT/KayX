package com.kayx.manager.data.advanced

import org.junit.Assert.*
import org.junit.Test

class RootShellSafetyTest {
    @Test fun quotesSingleQuotesWithoutShellExpansion() {
        assertEquals("'a'\\''b'", RootShell.q("a'b"))
        assertEquals("'/sdcard/My Files; echo bad'", RootShell.q("/sdcard/My Files; echo bad"))
    }

    @Test fun refusesFilesystemAndSystemMountRoots() {
        listOf("/", "/system", "/system_ext", "/vendor", "/vendor_dlkm", "/product", "/odm", "/data", "/proc", "/sys", "/dev", "/apex", "/mnt").forEach {
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

    @Test fun refusesDescendantsOfProtectedSystemTrees() {
        listOf("/system/bin/sh", "/vendor/etc", "/product/app", "/odm/lib", "/proc/1", "/sys/kernel", "/data/system").forEach {
            assertFalse("$it must be protected", isSafeRecursiveDeleteTarget(it))
        }
    }
    @Test fun recognizesRootUidOutputButNotShellUid() {
        assertTrue(isRootIdentity("uid=0(root) gid=0(root) groups=0(root)"))
        assertTrue(isRootIdentity("uid=0, gid=0, groups=0"))
        assertFalse(isRootIdentity("uid=2000(shell) gid=2000(shell)"))
        assertFalse(isRootIdentity("uid=10000(com.example.app)"))
    }
}
