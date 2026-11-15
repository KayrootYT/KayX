package com.kayx.manager.data.apk

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decodes Android binary XML (AndroidManifest.xml, res/layout xml files) into readable text XML. */
object AxmlDecoder {
    fun isAxml(b: ByteArray) = b.size > 8 && b[0] == 3.toByte() && b[1] == 0.toByte() && b[2] == 8.toByte() && b[3] == 0.toByte()

    private val RADIX = floatArrayOf(0.00390625f, 3.0517578E-5f, 1.1920929E-7f, 4.656613E-10f)
    private val UNITS = arrayOf("px", "dip", "sp", "pt", "in", "mm")

    fun decode(data: ByteArray): String {
        require(isAxml(data)) { "Not a binary XML file" }
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        var strings = emptyList<String>()
        fun str(i: Int) = if (i < 0 || i >= strings.size) "" else strings[i]
        val ns = LinkedHashMap<String, String>()
        val pending = ArrayList<Pair<String, String>>()
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        var depth = 0
        var pos = 8
        fun indent() { repeat(depth) { sb.append("    ") } }
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        fun qname(uri: Int, name: Int): String {
            val n = str(name).ifEmpty { "attr_0x%x".format(name) }
            val u = str(uri)
            val p = if (u.isEmpty()) null else ns[u]
            return if (p.isNullOrEmpty()) n else "$p:$n"
        }
        fun value(raw: Int, type: Int, d: Int): String {
            if (raw != -1) return str(raw)
            return when (type) {
                0x00 -> ""
                0x01 -> if ((d ushr 24) == 1) "@android:0x%08x".format(d) else "@0x%08x".format(d)
                0x02 -> "?0x%08x".format(d)
                0x03 -> str(d)
                0x04 -> java.lang.Float.intBitsToFloat(d).toString()
                0x05 -> { val m = (d shr 8) * RADIX[(d shr 4) and 3]; fmtNum(m) + (UNITS.getOrNull(d and 0xF) ?: "") }
                0x06 -> { val m = (d shr 8) * RADIX[(d shr 4) and 3] * 100; fmtNum(m) + if ((d and 0xF) == 1) "%p" else "%" }
                0x10 -> d.toString()
                0x11 -> "0x%x".format(d)
                0x12 -> if (d != 0) "true" else "false"
                in 0x1c..0x1f -> "#%08x".format(d)
                else -> "0x%x".format(d)
            }
        }
        while (pos + 8 <= data.size) {
            val type = bb.getShort(pos).toInt() and 0xFFFF
            val size = bb.getInt(pos + 4)
            if (size <= 0 || pos + size > data.size) break
            when (type) {
                0x0001 -> strings = readPool(bb, pos)
                0x0100 -> { val p = str(bb.getInt(pos + 16)); val u = str(bb.getInt(pos + 20)); ns[u] = p; pending += p to u }
                0x0101 -> ns.remove(str(bb.getInt(pos + 20)))
                0x0102 -> {
                    indent()
                    sb.append('<').append(qname(bb.getInt(pos + 16), bb.getInt(pos + 20)))
                    for ((p, u) in pending) sb.append(" xmlns:").append(p).append("=\"").append(esc(u)).append('"')
                    pending.clear()
                    val attrStart = bb.getShort(pos + 24).toInt() and 0xFFFF
                    val attrSize = (bb.getShort(pos + 26).toInt() and 0xFFFF).let { if (it == 0) 20 else it }
                    val count = bb.getShort(pos + 28).toInt() and 0xFFFF
                    for (i in 0 until count) {
                        val a = pos + 16 + attrStart + i * attrSize
                        val t = bb.get(a + 15).toInt() and 0xFF
                        sb.append("\n"); indent(); sb.append("    ")
                        sb.append(qname(bb.getInt(a), bb.getInt(a + 4))).append("=\"")
                            .append(esc(value(bb.getInt(a + 8), t, bb.getInt(a + 16)))).append('"')
                    }
                    sb.append(">\n"); depth++
                }
                0x0103 -> { depth = (depth - 1).coerceAtLeast(0); indent(); sb.append("</").append(qname(bb.getInt(pos + 16), bb.getInt(pos + 20))).append(">\n") }
                0x0104 -> { indent(); sb.append(esc(str(bb.getInt(pos + 16)))).append('\n') }
            }
            pos += size
        }
        return sb.toString()
    }

    private fun fmtNum(f: Float): String = if (f == f.toInt().toFloat()) f.toInt().toString() else "%.2f".format(f).trimEnd('0').trimEnd('.')

    private fun readPool(bb: ByteBuffer, pos: Int): List<String> {
        val count = bb.getInt(pos + 8)
        val flags = bb.getInt(pos + 16)
        val strStart = bb.getInt(pos + 20)
        val utf8 = (flags and 0x100) != 0
        val offBase = pos + (bb.getShort(pos + 2).toInt() and 0xFFFF)
        fun u8(p: Int) = bb.get(p).toInt() and 0xFF
        fun u16(p: Int) = bb.getShort(p).toInt() and 0xFFFF
        val out = ArrayList<String>(count)
        for (i in 0 until count) {
            var p = pos + strStart + bb.getInt(offBase + i * 4)
            out += runCatching {
                if (utf8) {
                    if ((u8(p) and 0x80) != 0) p += 2 else p += 1
                    var bl = u8(p); p++
                    if ((bl and 0x80) != 0) { bl = ((bl and 0x7F) shl 8) or u8(p); p++ }
                    val bytes = ByteArray(bl); bb.position(p); bb.get(bytes); String(bytes, Charsets.UTF_8)
                } else {
                    var len = u16(p); p += 2
                    if ((len and 0x8000) != 0) { len = ((len and 0x7FFF) shl 16) or u16(p); p += 2 }
                    val sbd = StringBuilder(len)
                    for (k in 0 until len) sbd.append(u16(p + k * 2).toChar())
                    sbd.toString()
                }
            }.getOrDefault("")
        }
        return out
    }
}
