package com.kayx.manager.data.archive

import com.github.junrar.Archive
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/** Extract ZIP/APK/JAR, TAR variants, 7z and single-file gzip/bzip2/xz streams. */
object MultiExtract {
    data class ExtractionLimits(
        val maxEntries: Int = 100_000,
        val maxExtractedBytes: Long = 8L * 1024 * 1024 * 1024
    ) {
        init {
            require(maxEntries > 0) { "Entry limit must be positive" }
            require(maxExtractedBytes > 0) { "Extraction byte limit must be positive" }
        }
    }

    private val zipLike = listOf(".zip", ".apk", ".jar", ".aar", ".xapk", ".apks", ".apkm")
    private val tarLike = listOf(".tar", ".tar.gz", ".tgz", ".tar.bz2", ".tbz2", ".tbz", ".tar.xz", ".txz")
    private val single = listOf(".gz", ".bz2", ".xz")
    private const val RAR_SUFFIX = ".rar"

    fun canExtract(name: String): Boolean {
        val lower = name.lowercase()
        return (zipLike + tarLike + single + listOf(".7z", RAR_SUFFIX)).any { lower.endsWith(it) }
    }

    private fun unique(directory: File): File {
        var target = directory
        var index = 1
        while (target.exists()) { target = File(directory.parentFile, "${directory.name} ($index)"); index++ }
        return target
    }

    private fun safe(root: File, name: String): File {
        require(name.isNotBlank() && name.none { it.code == 0 }) { "Blocked invalid archive entry" }
        val normalized = name.replace('\\', '/')
        require(!normalized.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(normalized)) {
            "Blocked absolute archive path: $name"
        }
        require(normalized.split('/').none { it == ".." || it == "." }) { "Blocked unsafe path: $name" }
        val output = File(root, normalized)
        val rootPath = root.canonicalPath
        val outputPath = output.canonicalPath
        check(outputPath == rootPath || outputPath.startsWith(rootPath + File.separator)) {
            "Blocked unsafe path: $name"
        }
        return output
    }

    private fun decompress(name: String, raw: InputStream): InputStream = when {
        name.endsWith(".gz") || name.endsWith(".tgz") -> GzipCompressorInputStream(raw)
        name.endsWith(".bz2") || name.endsWith(".tbz2") || name.endsWith(".tbz") -> BZip2CompressorInputStream(raw)
        name.endsWith(".xz") || name.endsWith(".txz") -> XZCompressorInputStream(raw)
        else -> raw
    }

    private fun copyLimited(input: InputStream, output: OutputStream, remaining: Long): Long {
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            check(read.toLong() <= remaining - copied) { "Archive exceeds extraction byte limit" }
            output.write(buffer, 0, read)
            copied += read
        }
        return copied
    }

    fun extract(
        src: File,
        limits: ExtractionLimits = ExtractionLimits()
    ): Result<Pair<File, Int>> = runCatching {
        require(src.isFile) { "Archive file is missing" }
        val name = src.name.lowercase()
        if (zipLike.any { name.endsWith(it) }) {
            return@runCatching ArchiveRepository().extract(
                src, null, ArchiveRepository.ArchiveLimits(limits.maxEntries, limits.maxExtractedBytes)
            ).getOrThrow()
        }

        val suffix = listOf(".tar.gz", ".tar.bz2", ".tar.xz", ".tgz", ".tbz2", ".tbz", ".txz", ".tar", ".7z", ".gz", ".bz2", ".xz")
            .firstOrNull { name.endsWith(it) }
        val baseName = suffix?.let { src.name.dropLast(it.length) } ?: src.nameWithoutExtension
        val isTar = tarLike.any { name.endsWith(it) }
        val isSevenZip = name.endsWith(".7z")
        val isRar = name.endsWith(RAR_SUFFIX)
        val destination = unique(File(src.parentFile, baseName.ifEmpty { "extracted" }))
        var count = 0
        var totalBytes = 0L

        fun acceptEntry() {
            count++
            check(count <= limits.maxEntries) { "Archive exceeds entry limit (${limits.maxEntries})" }
        }
        fun copyEntry(input: InputStream, output: OutputStream) {
            totalBytes += copyLimited(input, output, limits.maxExtractedBytes - totalBytes)
        }

        try {
            check(destination.mkdirs()) { "Could not create output folder" }
            when {
                !isTar && !isSevenZip && !isRar -> {
                    acceptEntry()
                    val output = safe(destination, baseName.ifEmpty { "out" })
                    BufferedInputStream(src.inputStream()).use { raw ->
                        decompress(name, raw).use { input ->
                            output.outputStream().buffered().use { stream -> copyEntry(input, stream) }
                        }
                    }
                }
                isRar -> {
                    Archive(src).use { archive ->
                        var entry = archive.nextFileHeader()
                        while (entry != null) {
                            acceptEntry()
                            val output = safe(destination, entry.fileName)
                            if (entry.isDirectory) {
                                check(output.mkdirs() || output.isDirectory) { "Could not create directory: " + entry.fileName }
                            } else {
                                check(!output.exists() || output.isFile) { "Archive file/folder name conflict: " + entry.fileName }
                                output.parentFile?.let { check(it.mkdirs() || it.isDirectory) { "Could not create parent directory" } }
                                archive.getInputStream(entry).use { input ->
                                    output.outputStream().buffered().use { stream -> copyEntry(input, stream) }
                                }
                            }
                            entry = archive.nextFileHeader()
                        }
                    }
                }
                isSevenZip -> {
                    SevenZFile(RandomAccessFile(src, "r").channel).use { archive ->
                        val buffer = ByteArray(64 * 1024)
                        var entry = archive.nextEntry
                        while (entry != null) {
                            acceptEntry()
                            val output = safe(destination, entry.name)
                            if (entry.isDirectory) {
                                check(output.mkdirs() || output.isDirectory) { "Could not create directory: ${entry.name}" }
                            } else {
                                check(!output.exists() || output.isFile) { "Archive file/folder name conflict: ${entry.name}" }
                                output.parentFile?.let { check(it.mkdirs() || it.isDirectory) { "Could not create parent directory" } }
                                output.outputStream().buffered().use { stream ->
                                    while (true) {
                                        val read = archive.read(buffer)
                                        if (read < 0) break
                                        check(read.toLong() <= limits.maxExtractedBytes - totalBytes) {
                                            "Archive exceeds extraction byte limit (${limits.maxExtractedBytes})"
                                        }
                                        stream.write(buffer, 0, read)
                                        totalBytes += read
                                    }
                                }
                            }
                            entry = archive.nextEntry
                        }
                    }
                }
                else -> {
                    TarArchiveInputStream(decompress(name, BufferedInputStream(src.inputStream()))).use { archive ->
                        var entry = archive.nextTarEntry
                        while (entry != null) {
                            acceptEntry()
                            val output = safe(destination, entry.name)
                            when {
                                entry.isDirectory -> check(output.mkdirs() || output.isDirectory) {
                                    "Could not create directory: ${entry.name}"
                                }
                                entry.isFile -> {
                                    check(!output.exists() || output.isFile) { "Archive file/folder name conflict: ${entry.name}" }
                                    output.parentFile?.let { check(it.mkdirs() || it.isDirectory) { "Could not create parent directory" } }
                                    output.outputStream().buffered().use { stream -> copyEntry(archive, stream) }
                                }
                                else -> error("Unsupported TAR entry type: ${entry.name}")
                            }
                            entry = archive.nextTarEntry
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            destination.deleteRecursively()
            throw error
        }
        destination to count
    }
}
