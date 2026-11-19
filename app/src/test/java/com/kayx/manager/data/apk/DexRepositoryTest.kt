package com.kayx.manager.data.apk

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Adler32
import org.junit.Assert.*
import org.junit.Test

class DexRepositoryTest {
    private fun validDex(): ByteArray {
        val bytes = ByteArray(112)
        "dex\n039\u0000".toByteArray(Charsets.US_ASCII).copyInto(bytes, 0)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(32, bytes.size)
        b.putInt(36, 112)
        b.putInt(40, 0x12345678)
        b.putInt(8, Adler32().apply { update(bytes, 12, bytes.size - 12) }.value.toInt())
        return bytes
    }

    @Test fun acceptsValidDexAndChecksHeaderChecksum() {
        val file = File.createTempFile("kayx-valid-", ".dex")
        try {
            file.writeBytes(validDex())
            val info = DexRepository().inspect(file)
            assertEquals("dex\n039", info.magic)
            assertTrue(info.checksumValid)
            assertEquals(112L, info.fileSize)
        } finally { file.delete() }
    }

    @Test fun reportsStaleAdlerChecksumWithoutAcceptingItAsValid() {
        val file = File.createTempFile("kayx-bad-checksum-", ".dex")
        try {
            val bytes = validDex()
            bytes[50] = 1
            file.writeBytes(bytes)
            assertFalse(DexRepository().inspect(file).checksumValid)
        } finally { file.delete() }
    }

    @Test fun rejectsInvalidDexEntryNames() {
        assertTrue(DexRepository.isDexEntryName("classes.dex"))
        assertTrue(DexRepository.isDexEntryName("classes12.dex"))
        assertFalse(DexRepository.isDexEntryName("../classes.dex"))
        assertFalse(DexRepository.isDexEntryName("classesx.dex"))
    }

    @Test fun decodesModifiedUtf8Null() {
        val decoded = decodeMutf8(byteArrayOf(0xC0.toByte(), 0x80.toByte()), 0, 2)
        assertEquals("\u0000", decoded)
    }
}


    @Test fun stringDataCannotReadPastDeclaredDexFileSize() {
        val bytes = validDex().copyOf(120)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(56, 1) // one string_id
        b.putInt(60, 112) // points exactly past declared file_size
        val file = File.createTempFile("kayx-bad-string-offset-", ".dex")
        try {
            file.writeBytes(bytes)
            val info = DexRepository().inspect(file)
            assertEquals("<invalid string offset>", info.strings.single())
        } finally { file.delete() }
    }
