package com.kayx.manager.data.apk

data class ArscEntry(val resId: Int, val type: String, val name: String, val config: String, val value: String)
data class ArscResult(val packageName: String, val entries: List<ArscEntry>, val truncated: Boolean)

/** Read-only parser for compiled Android resource tables (resources.arsc). Bad chunks are skipped, never fatal. */
object ArscParser {
    private const val MAX_ENTRIES = 300_000
    private val RADIX = floatArrayOf(0.00390625f, 3.0517578E-5f, 1.1920929E-7f, 4.656613E-10f)
    private val UNITS = arrayOf("px", "dip", "sp", "pt", "in", "mm")

    private class Rd(val d: ByteArray) {
        fun u8(p: Int): Int = d[p].toInt() and 0xFF
        fun u16(p: Int): Int = u8(p) or (u8(p + 1) shl 8)
        fun i32(p: Int): Int = u16(p) or (u16(p + 2) shl 16)
        fun u32(p: Int): Long = i32(p).toLong() and 0xFFFFFFFFL
    }

    fun parse(data: ByteArray): Result<ArscResult> = runCatching {
        val r = Rd(data)
        require(data.size > 12 && r.u16(0) == 0x0002) { "Not a resources.arsc table" }
        var pos = r.u16(2)
        var globalPool: List<String> = emptyList()
        val out = ArrayList<ArscEntry>()
        var truncated = false
        var pkgName = ""
        while (pos + 8 <= data.size) {
            val type = r.u16(pos)
            val size = r.i32(pos + 4)
            if (size <= 0 || pos + size > data.size) break
            when (type) {
                0x0001 -> if (globalPool.isEmpty()) globalPool = readPool(r, pos)
                0x0200 -> {
                    val (name, cut) = parsePackage(r, pos, globalPool, out)
                    if (pkgName.isEmpty()) pkgName = name
                    if (cut) truncated = true
                }
            }
            pos += size
        }
        ArscResult(pkgName, out.sortedWith(compareBy({ it.type }, { it.name }, { it.config })), truncated)
    }

    private fun readPool(r: Rd, pos: Int): List<String> {
        if (r.u16(pos) != 0x0001) return emptyList()
        val hdr = r.u16(pos + 2)
        val count = r.i32(pos + 8)
        val flags = r.i32(pos + 16)
        val strStart = r.i32(pos + 20)
        if (count < 0 || count > r.d.size / 4) return emptyList()
        val utf8 = (flags and 0x100) != 0
        val out = ArrayList<String>(count)
        for (i in 0 until count) {
            out.add(runCatching {
                var p = pos + strStart + r.i32(pos + hdr + i * 4)
                if (utf8) {
                    p += if ((r.u8(p) and 0x80) != 0) 2 else 1
                    var bl = r.u8(p); p++
                    if ((bl and 0x80) != 0) { bl = ((bl and 0x7F) shl 8) or r.u8(p); p++ }
                    String(r.d, p, bl, Charsets.UTF_8)
                } else {
                    var len = r.u16(p); p += 2
                    if ((len and 0x8000) != 0) { len = ((len and 0x7FFF) shl 16) or r.u16(p); p += 2 }
                    String(r.d, p, len * 2, Charsets.UTF_16LE)
                }
            }.getOrDefault(""))
        }
        return out
    }

    private fun parsePackage(r: Rd, pos: Int, globalPool: List<String>, out: MutableList<ArscEntry>): Pair<String, Boolean> {
        val hdr = r.u16(pos + 2)
        val size = r.i32(pos + 4)
        val pkgId = r.i32(pos + 8)
        val name = String(r.d, pos + 12, 256, Charsets.UTF_16LE).substringBefore('\u0000')
        val typePool = readPool(r, pos + r.i32(pos + 268))
        val keyPool = readPool(r, pos + r.i32(pos + 276))
        var c = pos + hdr
        val end = minOf(pos + size, r.d.size)
        var truncated = false
        while (c + 8 <= end && !truncated) {
            val ct = r.u16(c)
            val cs = r.i32(c + 4)
            if (cs <= 0 || c + cs > r.d.size) break
            if (ct == 0x0201) truncated = parseType(r, c, pkgId, typePool, keyPool, globalPool, out)
            c += cs
        }
        return name to truncated
    }

    /** Returns true when the entry limit was hit. */
    private fun parseType(r: Rd, c: Int, pkgId: Int, typePool: List<String>, keyPool: List<String>, globalPool: List<String>, out: MutableList<ArscEntry>): Boolean {
        val hdr = r.u16(c + 2)
        val typeId = r.u8(c + 8)
        val flags = r.u8(c + 9)
        val count = r.i32(c + 12)
        val entriesStart = r.i32(c + 16)
        if (count < 0 || count > 70_000) return false
        val cfg = configLabel(r, c + 20)
        val typeName = typePool.getOrNull(typeId - 1) ?: "type$typeId"
        val sparse = (flags and 1) != 0
        val off16 = (flags and 2) != 0
        for (i in 0 until count) {
            var idx = i
            val off: Long = runCatching {
                when {
                    sparse -> { val b = c + hdr + i * 4; idx = r.u16(b); r.u16(b + 2).toLong() * 4 }
                    off16 -> { val v = r.u16(c + hdr + i * 2); if (v == 0xFFFF) -1L else v.toLong() * 4 }
                    else -> { val v = r.u32(c + hdr + i * 4); if (v == 0xFFFFFFFFL) -1L else v }
                }
            }.getOrDefault(-1L)
            if (off < 0) continue
            if (out.size >= MAX_ENTRIES) return true
            runCatching {
                val base = c + entriesStart + off.toInt()
                val esize = r.u16(base)
                val eflags = r.u16(base + 2)
                val key = r.i32(base + 4)
                val name = keyPool.getOrNull(key) ?: "key$key"
                val v = if ((eflags and 1) != 0) "<bag: ${r.i32(base + 12)} items>"
                else { val vb = base + esize; value(globalPool, r.u8(vb + 3), r.i32(vb + 4)) }
                out.add(ArscEntry((pkgId shl 24) or (typeId shl 16) or idx, typeName, name, cfg, v))
            }
        }
        return false
    }

    private fun configLabel(r: Rd, p: Int): String = runCatching {
        if (r.i32(p) < 16) return@runCatching "default"
        fun ch(o: Int) = r.u8(p + o).let { if (it in 33..126) it.toChar().toString() else "" }
        val parts = ArrayList<String>()
        val mcc = r.u16(p + 4)
        if (mcc != 0) parts.add("mcc$mcc")
        val lang = ch(8) + ch(9)
        val country = ch(10) + ch(11)
        if (lang.isNotEmpty()) parts.add(if (country.isNotEmpty()) "$lang-r$country" else lang)
        val density = r.u16(p + 14)
        if (density != 0) parts.add(when (density) { 0xFFFE -> "anydpi"; 0xFFFF -> "nodpi"; else -> "${density}dpi" })
        if (parts.isEmpty()) "default" else parts.joinToString("-")
    }.getOrDefault("default")

    private fun fmtNum(f: Float): String =
        if (f == Math.floor(f.toDouble()).toFloat() && Math.abs(f) < 2.0e9f) f.toLong().toString()
        else "%.2f".format(f).trimEnd('0').trimEnd('.')

    private fun value(pool: List<String>, type: Int, d: Int): String = when (type) {
        0x00 -> "@null"
        0x01 -> "@0x%08x".format(d)
        0x02 -> "?0x%08x".format(d)
        0x03 -> pool.getOrNull(d)?.replace("\n", "⏎")?.take(300) ?: "string#$d"
        0x04 -> java.lang.Float.intBitsToFloat(d).toString()
        0x05 -> fmtNum((d shr 8) * RADIX[(d shr 4) and 3]) + (UNITS.getOrNull(d and 0xF) ?: "")
        0x06 -> fmtNum((d shr 8) * RADIX[(d shr 4) and 3] * 100f) + (if ((d and 0xF) == 1) "%p" else "%")
        0x10 -> d.toString()
        0x11 -> "0x%x".format(d)
        0x12 -> if (d != 0) "true" else "false"
        in 0x1c..0x1f -> "#%08x".format(d)
        else -> "0x%x".format(d)
    }
}
