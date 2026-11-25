package com.kayx.manager.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FtpClientSafetyTest {
    @Test fun remoteNameAcceptsSafeSingleSegment() {
        assertEquals("report final.txt", validateFtpRemoteName("report final.txt"))
    }

    @Test fun remoteNameRejectsTraversalAndCommandInjection() {
        listOf("", ".", "..", "../secret", "folder/file", "folder\\file", "bad\rname", "bad\nname", "bad\u0000name", "x".repeat(256))
            .forEach { name -> assertThrows(IllegalArgumentException::class.java) { validateFtpRemoteName(name) } }
    }

    @Test fun mlsdParserFiltersDotEntriesAndUnsafeNames() {
        val entries = FtpClient().parseMlsdForTest(listOf(
            "type=file;size=12;modify=20261125093000; report.txt",
            "type=dir; folder",
            "type=cdir; .",
            "type=pdir; ..",
            "type=file; ../../escape"
        ))
        assertEquals(listOf("report.txt", "folder"), entries.map { it.name })
        assertEquals(12L, entries.first().size)
        assertEquals(true, entries.last().dir)
    }

    @Test fun unixParserFiltersUnsafeNamesAndParsesFiles() {
        val entries = FtpClient().parseUnixForTest(listOf(
            "-rw-r--r-- 1 user group 42 Nov 25 09:30 report.txt",
            "drwxr-xr-x 2 user group 0 Nov 25 09:30 folder",
            "-rw-r--r-- 1 user group 9 Nov 25 09:30 ../escape"
        ))
        assertEquals(listOf("report.txt", "folder"), entries.map { it.name })
        assertEquals(42L, entries.first().size)
        assertEquals(true, entries.last().dir)
    }
}
