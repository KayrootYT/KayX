package com.kayx.manager.data.archive

import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

/** Extract zip/apk/jar (via ArchiveRepository) plus tar(.gz/.bz2/.xz), 7z, and single-file gz/bz2/xz. */
object MultiExtract {
    private val zipLike = listOf(".zip", ".apk", ".jar", ".aar", ".xapk", ".apks", ".apkm")
    private val tarLike = listOf(".tar", ".tar.gz", ".tgz", ".tar.bz2", ".tbz2", ".tbz", ".tar.xz", ".txz")
    private val single = listOf(".gz", ".bz2", ".xz")

    fun canExtract(name: String): Boolean {
        val n = name.lowercase()
        return (zipLike + tarLike + single + ".7z").any { n.endsWith(it) }
    }

    private fun unique(dir: File): File {
        var t = dir; var i = 1
        while (t.exists()) { t = File(dir.parentFile, "${dir.name} ($i)"); i++ }
        return t
    }

    private fun safe(root: File, name: String): File {
        val out = File(root, name)
        val rc = root.canonicalPath
        check(out.canonicalPath == rc || out.canonicalPath.startsWith(rc + File.separator)) { "Blocked unsafe path: $name" }
        return out
    }

    private fun decompress(n: String, raw: InputStream): InputStream = when {
        n.endsWith(".gz") || n.endsWith(".tgz") -> GzipCompressorInputStream(raw)
        n.endsWith(".bz2") || n.endsWith(".tbz2") || n.endsWith(".tbz") -> BZip2CompressorInputStream(raw)
        n.endsWith(".xz") || n.endsWith(".txz") -> XZCompressorInputStream(raw)
        else -> raw
    }

    fun extract(src: File): Result<Pair<File, Int>> = runCatching {
        val n = src.name.lowercase()
        if (zipLike.any { n.endsWith(it) }) return@runCatching ArchiveRepository().extract(src, null).getOrThrow()
        val base = listOf(".tar.gz", ".tar.bz2", ".tar.xz", ".tgz", ".tbz2", ".tbz", ".txz", ".tar", ".7z", ".gz", ".bz2", ".xz")
            .firstOrNull { n.endsWith(it) }?.let { src.name.dropLast(it.length) } ?: src.nameWithoutExtension
        val isTar = tarLike.any { n.endsWith(it) }
        val is7z = n.endsWith(".7z")
        val dest = unique(File(src.parentFile, base.ifEmpty { "extracted" }))
        var count = 0
        try {
            if (!isTar && !is7z) {
                check(dest.mkdirs()) { "Could not create output folder" }
                decompress(n, BufferedInputStream(src.inputStream())).use { ins -> File(dest, base.ifEmpty { "out" }).outputStream().use { ins.copyTo(it) } }
                count = 1
            } else if (is7z) {
                check(dest.mkdirs()) { "Could not create output folder" }
                val sz = SevenZFile(RandomAccessFile(src, "r").channel)
                sz.use { z ->
                    var e = z.nextEntry
                    val buf = ByteArray(64 * 1024)
                    while (e != null) {
                        val out = safe(dest, e.name)
                        if (e.isDirectory) out.mkdirs() else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { o -> while (true) { val r = z.read(buf); if (r < 0) break; o.write(buf, 0, r) } }
                            count++
                        }
                        e = z.nextEntry
                    }
                }
            } else {
                check(dest.mkdirs()) { "Could not create output folder" }
                TarArchiveInputStream(decompress(n, BufferedInputStream(src.inputStream()))).use { tis ->
                    var e = tis.nextTarEntry
                    while (e != null) {
                        val out = safe(dest, e.name)
                        if (e.isDirectory) out.mkdirs() else if (e.isFile) {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { tis.copyTo(it) }
                            count++
                        }
                        e = tis.nextTarEntry
                    }
                }
            }
        } catch (t: Throwable) { dest.deleteRecursively(); throw t }
        dest to count
    }
}
