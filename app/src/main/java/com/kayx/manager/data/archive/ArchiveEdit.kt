package com.kayx.manager.data.archive

import android.content.Context
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Separator used to address "<archive>::kx::<entry>" as one string. */
const val ZIP_SEP = "::kx::"

/** In-place ZIP/APK editing. Rewrites keep STORED entries STORED and zipalign them (4 bytes, .so = 4096). */
object ArchiveEdit {
    private class Counting(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
    }

    private fun hit(selected: String, name: String): Boolean {
        val n = selected.trimEnd('/')
        return name == n || name == "$n/" || name.startsWith("$n/")
    }

    private fun safeEntryName(raw: String): String {
        require(raw.isNotBlank() && raw.none { it.code == 0 }) { "Invalid archive entry name" }
        val name = raw.replace('\\', '/')
        require(!name.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(name)) {
            "Archive entry must be a relative path"
        }
        require(name.split('/').none { it == ".." || it == "." }) { "Unsafe archive entry path" }
        return name
    }

    private const val MAX_PREVIEW_BYTES = 64L * 1024 * 1024

    fun readEntry(zip: File, name: String, maxBytes: Long = MAX_PREVIEW_BYTES): ByteArray {
        require(maxBytes in 1..MAX_PREVIEW_BYTES) { "Preview size limit is invalid" }
        return ZipFile(zip).use { archive ->
            val entry = archive.getEntry(safeEntryName(name)) ?: error("Entry not found: $name")
            require(!entry.isDirectory && entry.size in 0..maxBytes) { "Archive entry is too large to preview" }
            archive.getInputStream(entry).use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(count.toLong() <= maxBytes - total) { "Archive entry exceeds preview size limit" }
                    output.write(buffer, 0, count)
                    total += count
                }
                output.toByteArray()
            }
        }
    }

    fun extractToCache(ctx: Context, zip: File, name: String): File {
        val base = File(ctx.cacheDir, "zipview/${zip.absolutePath.hashCode()}").canonicalFile
        val out = File(base, name).canonicalFile
        val prefix = base.path.trimEnd(File.separatorChar) + File.separator
        check(out.path.startsWith(prefix)) { "Blocked unsafe path: $name" }
        out.parentFile?.mkdirs()
        out.writeBytes(readEntry(zip, name))
        return out
    }

    private fun pad(offset: Long, name: String, page: Boolean): ByteArray? {
        val align = if (page) 4096 else 4
        val nameLen = name.toByteArray(Charsets.UTF_8).size
        var p = ((align - ((offset + 30 + nameLen) % align)) % align).toInt()
        if (p == 0) return null
        if (p < 4) p += align
        val b = ByteArray(p)
        b[0] = 0x35; b[1] = 0xD9.toByte()
        b[2] = ((p - 4) and 0xFF).toByte(); b[3] = (((p - 4) shr 8) and 0xFF).toByte()
        return b
    }

    private fun putStored(zos: ZipOutputStream, cnt: Counting, name: String, size: Long, crc: Long, time: Long, data: () -> java.io.InputStream) {
        val ze = ZipEntry(name)
        ze.method = ZipEntry.STORED; ze.size = size; ze.compressedSize = size; ze.crc = crc
        if (time > 0) ze.time = time
        zos.closeEntry()
        ze.extra = pad(cnt.count, name, name.endsWith(".so"))
        zos.putNextEntry(ze)
        data().use { it.copyTo(zos) }
        zos.closeEntry()
    }

    private fun putBytes(zos: ZipOutputStream, cnt: Counting, name: String, stored: Boolean, bytes: ByteArray, time: Long) {
        if (stored) {
            val crc = CRC32().apply { update(bytes) }.value
            putStored(zos, cnt, name, bytes.size.toLong(), crc, time) { bytes.inputStream() }
        } else {
            val ze = ZipEntry(name); if (time > 0) ze.time = time
            zos.putNextEntry(ze); zos.write(bytes); zos.closeEntry()
        }
    }

    /**
     * Rewrites [zip] atomically. [delete] removes entries/folders, [replace] swaps content of existing entries,
     * [rename] renames entries/folders, [add] adds new entries (or replaces if present). Returns number of removed entries.
     * With all maps empty this simply re-aligns the archive (zipalign).
     */
    fun rewrite(
        zip: File, delete: Set<String> = emptySet(), replace: Map<String, ByteArray> = emptyMap(),
        rename: Map<String, String> = emptyMap(), add: Map<String, ByteArray> = emptyMap()
    ): Result<Int> = runCatching {
        require(zip.isFile) { "Archive file is missing" }
        delete.forEach { safeEntryName(it) }
        replace.keys.forEach { safeEntryName(it) }
        add.keys.forEach { safeEntryName(it) }
        rename.forEach { (old, new) -> safeEntryName(old); safeEntryName(new) }
        val tmp = File(zip.parentFile, "." + zip.name + ".kayx-tmp-" + UUID.randomUUID())
        var removed = 0
        try {
            ZipFile(zip).use { src ->
                val fileOutput = FileOutputStream(tmp, false)
                val cnt = Counting(BufferedOutputStream(fileOutput, 1 shl 16))
                ZipOutputStream(cnt).use { zos ->
                    val written = HashSet<String>()
                    val en = src.entries()
                    while (en.hasMoreElements()) {
                        val e = en.nextElement()
                        if (delete.any { hit(it, e.name) }) { removed++; continue }
                        var name = e.name
                        for ((old, new) in rename) {
                            val o = old.trimEnd('/'); val nn = new.trimEnd('/')
                            if (name == o) name = nn else if (name.startsWith("$o/")) name = nn + name.substring(o.length)
                        }
                        safeEntryName(name)
                        check(written.add(name)) { "Archive edit creates a duplicate entry: $name" }
                        val rep = replace[e.name] ?: add[e.name]
                        when {
                            rep != null -> putBytes(zos, cnt, name, e.method == ZipEntry.STORED, rep, e.time)
                            e.isDirectory -> { zos.closeEntry(); zos.putNextEntry(ZipEntry(name).apply { if (e.time > 0) time = e.time }); zos.closeEntry() }
                            e.method == ZipEntry.STORED -> putStored(zos, cnt, name, e.size, e.crc, e.time) { src.getInputStream(e) }
                            else -> {
                                zos.putNextEntry(ZipEntry(name).apply { if (e.time > 0) time = e.time })
                                src.getInputStream(e).use { it.copyTo(zos) }; zos.closeEntry()
                            }
                        }
                    }
                    for ((n, b) in add) {
                        val safeName = safeEntryName(n)
                        if (written.add(safeName)) putBytes(zos, cnt, safeName, safeName.endsWith(".arsc"), b, System.currentTimeMillis())
                    }
                    zos.finish()
                    zos.flush()
                    fileOutput.fd.sync()
                }
            }
            try {
                Files.move(tmp.toPath(), zip.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), zip.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (t: Throwable) { tmp.delete(); throw t }
        removed
    }
}
