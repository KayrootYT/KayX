package com.kayx.manager.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NetworkProtocolSafetyTest {
    @Test fun sftpPathsRejectControlCharactersAndTraversalSegments() {
        assertEquals("reports/final.txt", validateSftpPath("reports/final.txt"))
        listOf("", "a/../secret", "bad\rpath", "bad\npath", "bad\u0000path", "x".repeat(4097))
            .forEach { assertThrows(IllegalArgumentException::class.java) { validateSftpPath(it) } }
    }

    @Test fun sftpNamesRejectUnsafeSegments() {
        listOf("", ".", "..", "../escape", "folder/file", "folder\\file", "x".repeat(256))
            .forEach { assertEquals(false, validateSftpName(it)) }
        assertEquals(true, validateSftpName("quarterly report.pdf"))
    }

    @Test fun sftpResumeOffsetsMustFitTheCurrentRemoteFile() {
        assertEquals(0L, validateSftpResumeOffset(0L, 100L))
        assertEquals(100L, validateSftpResumeOffset(100L, 100L))
        assertThrows(IllegalArgumentException::class.java) { validateSftpResumeOffset(-1L, 100L) }
        assertThrows(IllegalArgumentException::class.java) { validateSftpResumeOffset(101L, 100L) }
        assertThrows(IllegalArgumentException::class.java) { validateSftpResumeOffset(0L, -1L) }
    }

    @Test fun smbPathsRejectTraversalAndAbsolutePaths() {
        assertEquals("folder\\report.txt", validateSmbPath("folder\\report.txt"))
        listOf("../escape", "folder\\..\\escape", "\\server\\share", "/root", "bad/name", "bad\rname")
            .forEach { assertThrows(IllegalArgumentException::class.java) { validateSmbPath(it) } }
    }

    @Test fun smbResumeOffsetMustFitCurrentRemoteFile() {
        assertEquals(0L, validateSmbResumeOffset(0L, 100L))
        assertEquals(100L, validateSmbResumeOffset(100L, 100L))
        assertThrows(IllegalArgumentException::class.java) { validateSmbResumeOffset(-1L, 100L) }
        assertThrows(IllegalArgumentException::class.java) { validateSmbResumeOffset(101L, 100L) }
        assertThrows(IllegalArgumentException::class.java) { validateSmbResumeOffset(0L, -1L) }
    }

    @Test fun smbResumeRequiresMatchingPersistedFingerprint() {
        assertEquals(true, isSmbResumeFingerprintMatch(0L, null, "size:timestamp"))
        assertEquals(true, isSmbResumeFingerprintMatch(50L, "size:timestamp", "size:timestamp"))
        assertEquals(false, isSmbResumeFingerprintMatch(50L, null, "size:timestamp"))
        assertEquals(false, isSmbResumeFingerprintMatch(50L, "old", "new"))
    }

    @Test fun smbNamesRejectSeparatorsAndTraversal() {
        listOf("", ".", "..", "../escape", "folder\\file", "folder/file", "bad\u0000name")
            .forEach { assertEquals(false, validateSmbName(it)) }
        assertEquals(true, validateSmbName("annual report.pdf"))
    }
}
