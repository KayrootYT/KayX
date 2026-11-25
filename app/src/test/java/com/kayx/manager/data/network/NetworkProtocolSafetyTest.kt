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

    @Test fun smbPathsRejectTraversalAndAbsolutePaths() {
        assertEquals("folder\\report.txt", validateSmbPath("folder\\report.txt"))
        listOf("../escape", "folder\\..\\escape", "\\server\\share", "/root", "bad/name", "bad\rname")
            .forEach { assertThrows(IllegalArgumentException::class.java) { validateSmbPath(it) } }
    }

    @Test fun smbNamesRejectSeparatorsAndTraversal() {
        listOf("", ".", "..", "../escape", "folder\\file", "folder/file", "bad\u0000name")
            .forEach { assertEquals(false, validateSmbName(it)) }
        assertEquals(true, validateSmbName("annual report.pdf"))
    }
}
