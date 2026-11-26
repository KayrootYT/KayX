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

    @Test fun epsvAcceptsRfcDelimiterAndRejectsInvalidPorts() {
        assertEquals(2121, parseFtpEpsvPort("229 Entering Extended Passive Mode (!!!2121!)"))
        assertEquals(null, parseFtpEpsvPort("229 Entering Extended Passive Mode (|||70000|)"))
    }

    @Test fun ftpResumeOffsetMustFitVerifiedRemoteSize() {
        assertEquals(0L, validateFtpResumeOffset(0L, 100L))
        assertEquals(100L, validateFtpResumeOffset(100L, 100L))
        assertThrows(IllegalArgumentException::class.java) { validateFtpResumeOffset(-1L, 100L) }
        assertThrows(IllegalArgumentException::class.java) { validateFtpResumeOffset(101L, 100L) }
        assertThrows(IllegalArgumentException::class.java) { validateFtpResumeOffset(0L, -1L) }
    }

    @Test fun ftpUploadResumeRequiresExactTemporarySizeAndValidRange() {
        validateFtpUploadResumeState(0L, 100L, 0L)
        validateFtpUploadResumeState(40L, 100L, 40L)
        validateFtpUploadResumeState(100L, 100L, 100L)
        assertThrows(IllegalArgumentException::class.java) { validateFtpUploadResumeState(-1L, 100L, 0L) }
        assertThrows(IllegalArgumentException::class.java) { validateFtpUploadResumeState(101L, 100L, 101L) }
        assertThrows(IllegalArgumentException::class.java) { validateFtpUploadResumeState(40L, 100L, 39L) }
    }

    @Test fun pasvValidatesOctetsAndPort() {
        assertEquals(50000, parseFtpPasvPort("227 Entering Passive Mode (10,0,0,5,195,80)"))
        assertEquals(null, parseFtpPasvPort("227 Entering Passive Mode (999,0,0,5,195,80)"))
        assertEquals(null, parseFtpPasvPort("227 Entering Passive Mode (10,0,0,5,0,0)"))
    }

}
