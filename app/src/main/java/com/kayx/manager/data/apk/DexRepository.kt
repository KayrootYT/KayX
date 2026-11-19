package com.kayx.manager.data.apk

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Adler32
import java.util.zip.ZipFile

data class DexInfo(
    val magic: String,
    val checksum: String,
    val fileSize: Long,
    val headerSize: Long,
    val stringCount: Int,
    val typeCount: Int,
    val classCount: Int,
    val strings: List<String>,
    val types: List<String>,
    val classes: List<String>,
    val checksumValid: Boolean = true
)

class DexRepository {
    companion object {
        const val MAX_DEX_BYTES = 128L * 1024 * 1024
        private const val MAX_TABLE = 2_000_000
        private const val ENDIAN_CONSTANT = 0x12345678
        private val DEX_ENTRY = Regex("classes(\\d+)?\\.dex")
        fun isDexEntryName(name: String) = DEX_ENTRY.matches(name)
    }

    fun listDexFiles(apk: File): List<String> = ZipFile(apk).use { zip ->
        zip.entries().asSequence().map { it.name }
            .filter { DEX_ENTRY.matches(it) }
            .sortedWith(compareBy<String> { if (it == "classes.dex") 1 else it.removePrefix("classes").removeSuffix(".dex").toIntOrNull() ?: 0 })
            .toList()
    }

    fun extractDex(apk: File, entry: String, output: File): File {
        require(isDexEntryName(entry)) { "Invalid DEX entry" }
        try {
            ZipFile(apk).use { zip ->
                val item = zip.getEntry(entry) ?: error("DEX entry not found")
                require(item.size in 1..MAX_DEX_BYTES) { "DEX size is unsupported" }
                zip.getInputStream(item).use { input ->
                    output.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            check(total <= MAX_DEX_BYTES) { "DEX is larger than its declared size" }
                            out.write(buf, 0, n)
                        }
                    }
                }
            }
        } catch (t: Throwable) { output.delete(); throw t }
        return output
    }

    fun inspect(file: File): DexInfo {
        require(file.isFile && file.length() in 112..MAX_DEX_BYTES) { "Invalid, truncated or oversized DEX file" }
        val bytes = file.readBytes()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magicBytes = ByteArray(8).also { b.position(0); b.get(it) }
        val magic = magicBytes.toString(Charsets.US_ASCII).trimEnd('\u0000')
        require(magic.length == 7 && magic.startsWith("dex\n") && magic.substring(4, 7).all { it.isDigit() }) { "Unsupported DEX magic: $magic" }
        fun uint(offset: Int): Long = b.getInt(offset).toLong() and 0xffffffffL
        val fileSize = uint(32)
        val headerSize = uint(36)
        require(headerSize >= 112 && fileSize >= headerSize && fileSize <= bytes.size) { "Invalid DEX header sizes" }
        require(b.getInt(40) == ENDIAN_CONSTANT) { "Unsupported DEX byte order" }
        val stringCount = uint(56); val stringOff = uint(60)
        val typeCount = uint(64); val typeOff = uint(68)
        val classCount = uint(96); val classOff = uint(100)
        require(stringCount <= MAX_TABLE && typeCount <= MAX_TABLE && classCount <= MAX_TABLE) { "DEX table is implausibly large" }
        fun validTable(off: Long, count: Long, width: Int) = (count == 0L || off >= headerSize) && off + count * width <= fileSize
        require(validTable(stringOff, stringCount, 4) && validTable(typeOff, typeCount, 4) && validTable(classOff, classCount, 32)) { "DEX table is outside file bounds" }
        val sc = stringCount.toInt(); val tc = typeCount.toInt(); val cc = classCount.toInt()
        val strings = List(sc) { readString(bytes, b.getInt(stringOff.toInt() + it * 4), fileSize.toInt()) }
        val types = List(tc) { i ->
            val idx = b.getInt(typeOff.toInt() + i * 4)
            strings.getOrNull(idx) ?: "<invalid string index $idx>"
        }
        val classes = List(cc) { i ->
            val idx = b.getInt(classOff.toInt() + i * 32)
            types.getOrNull(idx) ?: "<invalid type index $idx>"
        }
        val adler = Adler32().apply { update(bytes, 12, fileSize.toInt() - 12) }.value
        return DexInfo(magic, "%08X".format(uint(8)), fileSize, headerSize, sc, tc, cc, strings, types, classes, checksumValid = adler == uint(8))
    }

    private fun readString(bytes: ByteArray, offset: Int, fileSize: Int): String {
        if (offset < 0 || offset >= fileSize || fileSize > bytes.size) return "<invalid string offset>"
        var p = offset
        var count = 0
        while (p < fileSize) {
            val v = bytes[p++].toInt() and 0xff
            if (v and 0x80 == 0) break
            count++
            if (count > 5) return "<invalid string length>"
        }
        val start = p
        while (p < fileSize && bytes[p].toInt() != 0) p++
        if (p >= fileSize) return "<unterminated string>"
        return decodeMutf8(bytes, start, p)
    }
}

internal fun decodeMutf8(b: ByteArray, start: Int, end: Int): String {
    val sb = StringBuilder(end - start)
    var i = start
    while (i < end) {
        val a = b[i++].toInt() and 0xFF
        when {
            a < 0x80 -> sb.append(a.toChar())
            (a and 0xE0) == 0xC0 && i < end ->
                sb.append((((a and 0x1F) shl 6) or (b[i++].toInt() and 0x3F)).toChar())
            (a and 0xF0) == 0xE0 && i + 1 < end -> {
                val b2 = b[i++].toInt() and 0x3F
                val b3 = b[i++].toInt() and 0x3F
                sb.append((((a and 0x0F) shl 12) or (b2 shl 6) or b3).toChar())
            }
            else -> sb.append('�')
        }
    }
    return sb.toString()
}
