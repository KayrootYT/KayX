package com.kayx.manager.data.apk

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    val classes: List<String>
)

class DexRepository {
    fun listDexFiles(apk: File): List<String> = ZipFile(apk).use { zip ->
        zip.entries().asSequence().map { it.name }
            .filter { it.matches(Regex("classes(\\d+)?\\.dex")) }
            .sortedWith(compareBy<String> { if (it == "classes.dex") 1 else it.removePrefix("classes").removeSuffix(".dex").toIntOrNull() ?: 0 })
            .toList()
    }

    fun extractDex(apk: File, entry: String, output: File): File {
        require(entry.matches(Regex("classes(\\d+)?\\.dex"))) { "Invalid DEX entry" }
        ZipFile(apk).use { zip ->
            val item = zip.getEntry(entry) ?: error("DEX entry not found")
            require(item.size in 1..(128L * 1024 * 1024)) { "DEX size is unsupported" }
            zip.getInputStream(item).use { input -> output.outputStream().use { input.copyTo(it) } }
        }
        return output
    }

    fun inspect(file: File): DexInfo {
        val bytes = file.readBytes()
        require(bytes.size >= 112) { "Invalid or truncated DEX file" }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magicBytes = ByteArray(8).also { b.position(0); b.get(it) }
        val magic = magicBytes.toString(Charsets.US_ASCII).trimEnd('\u0000')
        require(magic.startsWith("dex\n")) { "Unsupported DEX magic: $magic" }
        fun uint(offset: Int): Long = b.getInt(offset).toLong() and 0xffffffffL
        val fileSize = uint(32)
        val headerSize = uint(36)
        require(fileSize <= bytes.size && headerSize >= 112) { "Invalid DEX header sizes" }
        val stringCount = uint(56).toInt().coerceIn(0, 2_000_000)
        val stringOff = uint(60).toInt()
        val typeCount = uint(64).toInt().coerceIn(0, 2_000_000)
        val typeOff = uint(68).toInt()
        val classCount = uint(96).toInt().coerceIn(0, 2_000_000)
        val classOff = uint(100).toInt()
        fun validTable(off: Int, count: Int, width: Int) = off >= 0 && off.toLong() + count.toLong() * width <= bytes.size
        require(validTable(stringOff, stringCount, 4) && validTable(typeOff, typeCount, 4) && validTable(classOff, classCount, 32)) { "DEX table is outside file bounds" }
        val stringOffsets = List(stringCount) { b.getInt(stringOff + it * 4) }
        val strings = stringOffsets.map { readString(bytes, it) }
        val types = List(typeCount) { i ->
            val idx = b.getInt(typeOff + i * 4)
            strings.getOrNull(idx) ?: "<invalid string index $idx>"
        }
        val classes = List(classCount) { i ->
            val idx = b.getInt(classOff + i * 32)
            types.getOrNull(idx) ?: "<invalid type index $idx>"
        }
        return DexInfo(magic, "%08X".format(uint(8)), fileSize, headerSize, stringCount, typeCount, classCount, strings, types, classes)
    }

    private fun readString(bytes: ByteArray, offset: Int): String {
        if (offset !in bytes.indices) return "<invalid string offset>"
        var p = offset
        var count = 0
        while (p < bytes.size) {
            val v = bytes[p++].toInt() and 0xff
            if (v and 0x80 == 0) break
            count++
            if (count > 5) return "<invalid string length>"
        }
        val start = p
        while (p < bytes.size && bytes[p].toInt() != 0) p++
        if (p >= bytes.size) return "<unterminated string>"
        return runCatching { String(bytes, start, p - start, Charsets.UTF_8) }.getOrDefault("<decode error>")
    }
}
