package com.kayx.manager.data.archive

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** ZIP archive operations. Encrypted ZIPs need a dedicated password-aware implementation. */
data class ZipItem(val name: String, val size: Long, val compressed: Long, val isDir: Boolean)

class ArchiveRepository {
    data class ArchiveLimits(
        val maxEntries: Int = 100_000,
        val maxExtractedBytes: Long = 8L * 1024 * 1024 * 1024
    ) {
        init {
            require(maxEntries > 0) { "Entry limit must be positive" }
            require(maxExtractedBytes > 0) { "Extraction byte limit must be positive" }
        }
    }

    private fun normalizedEntryName(name: String): String {
        require(name.isNotBlank() && name.none { it.code == 0 }) { "Archive contains an invalid entry name" }
        val normalized = name.replace('\\', '/')
        require(!normalized.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(normalized)) {
            "Blocked absolute archive path: $name"
        }
        require(normalized.split('/').none { it == ".." || it == "." }) {
            "Blocked unsafe archive path: $name"
        }
        return normalized
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

    private fun uniqueDir(dir: File): File {
        var target = dir
        var index = 1
        while (target.exists()) { target = File(dir.parentFile, "${dir.name} ($index)"); index++ }
        return target
    }

    /** Returns only the immediate children of [prefix], synthesizing folders when needed. */
    fun list(zip: File, prefix: String = ""): List<ZipItem> = ZipFile(zip).use { archive ->
        val cleanPrefix = prefix.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        val children = linkedMapOf<String, ZipItem>()
        val entries = archive.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            val safeName = normalizedEntryName(entry.name)
            if (!safeName.startsWith(cleanPrefix) || safeName == cleanPrefix) continue
            val rest = safeName.removePrefix(cleanPrefix)
            val first = rest.substringBefore('/')
            if (first.isEmpty()) continue
            val isFolder = rest.contains('/') || entry.isDirectory
            val childName = cleanPrefix + first + if (isFolder) "/" else ""
            val previous = children[childName]
            if (isFolder) children[childName] = ZipItem(childName, previous?.size ?: 0L, previous?.compressed ?: 0L, true)
            else children[childName] = ZipItem(childName, entry.size, entry.compressedSize, false)
        }
        children.values.sortedWith(compareByDescending<ZipItem> { it.isDir }.thenBy { it.name.substringAfterLast('/').lowercase() })
    }

    /** Streams entries through ZipInputStream and verifies CRCs with bounded work. */
    fun testIntegrity(zip: File, limits: ArchiveLimits = ArchiveLimits()): Pair<Int, String?> {
        var count = 0
        var total = 0L
        return try {
            ZipInputStream(zip.inputStream().buffered()).use { input ->
                val buffer = ByteArray(64 * 1024)
                var entry = input.nextEntry
                while (entry != null) {
                    normalizedEntryName(entry.name)
                    count++
                    check(count <= limits.maxEntries) { "Archive exceeds entry limit" }
                    if (!entry.isDirectory) {
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            check(read.toLong() <= limits.maxExtractedBytes - total) { "Archive exceeds integrity scan byte limit" }
                            total += read
                        }
                    }
                    input.closeEntry()
                    entry = input.nextEntry
                }
            }
            count to null
        } catch (error: Exception) { count to (error.message ?: "Corrupt archive") }
    }

    /** Extract selected entries; selecting a folder includes all descendants. Limits count actual decompressed bytes. */
    fun extract(
        zip: File,
        names: Set<String>?,
        limits: ArchiveLimits = ArchiveLimits()
    ): Result<Pair<File, Int>> = runCatching {
        val destination = uniqueDir(File(zip.parentFile, zip.nameWithoutExtension))
        check(destination.mkdirs()) { "Could not create output folder" }
        val root = destination.canonicalPath
        var count = 0
        var totalBytes = 0L
        val written = HashSet<String>()
        try {
            ZipFile(zip).use { archive ->
                val entries = archive.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val safeName = normalizedEntryName(entry.name)
                    if (names != null && names.none { selected ->
                            val selectedName = normalizedEntryName(selected).trimEnd('/')
                            safeName == selectedName || safeName == "$selectedName/" || safeName.startsWith("$selectedName/")
                        }) continue
                    count++
                    check(count <= limits.maxEntries) { "Archive exceeds entry limit (${limits.maxEntries})" }
                    check(written.add(safeName)) { "Archive contains duplicate entry: $safeName" }
                    val output = File(destination, safeName)
                    val canonical = output.canonicalPath
                    check(canonical == root || canonical.startsWith(root + File.separator)) { "Blocked unsafe path: ${entry.name}" }
                    if (entry.isDirectory) {
                        check(output.isDirectory || !output.exists()) { "Archive file/folder name conflict: $safeName" }
                        check(output.mkdirs() || output.isDirectory) { "Could not create folder: $safeName" }
                    } else {
                        check(!output.exists() || output.isFile) { "Archive file/folder name conflict: $safeName" }
                        output.parentFile?.let { check(it.mkdirs() || it.isDirectory) { "Could not create parent folder" } }
                        archive.getInputStream(entry).use { input ->
                            output.outputStream().buffered().use { stream ->
                                totalBytes += copyLimited(input, stream, limits.maxExtractedBytes - totalBytes)
                            }
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

    /** Creates a ZIP from files/folders. */
    fun create(files: List<File>, out: File): Result<Unit> = runCatching {
        require(files.isNotEmpty()) { "Select at least one file" }
        check(!out.exists()) { "${out.name} already exists" }
        val written = HashSet<String>()
        try {
            ZipOutputStream(out.outputStream().buffered()).use { archive ->
                for (file in files) {
                    check(file.exists()) { "Missing source: ${file.name}" }
                    val base = file.parentFile ?: file
                    for (child in file.walkTopDown()) {
                        if (child.canonicalPath == out.canonicalPath) continue
                        val relative = child.relativeTo(base).path.replace(File.separatorChar, '/')
                        if (relative.isEmpty()) continue
                        val entryName = normalizedEntryName(relative + if (child.isDirectory) "/" else "")
                        check(written.add(entryName)) { "Duplicate archive entry: $entryName" }
                        if (child.isDirectory) {
                            archive.putNextEntry(ZipEntry(entryName))
                            archive.closeEntry()
                        } else {
                            check(child.isFile) { "Unsupported source: ${child.name}" }
                            archive.putNextEntry(ZipEntry(entryName))
                            child.inputStream().use { it.copyTo(archive) }
                            archive.closeEntry()
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            out.delete()
            throw error
        }
    }

    /** Removes exact entries and all descendants of selected folders. */
    fun removeEntries(zip: File, names: Set<String>): Result<Int> = runCatching {
        require(names.isNotEmpty()) { "Select entries to remove" }
        names.forEach { normalizedEntryName(it) }
        ArchiveEdit.rewrite(zip, delete = names).getOrThrow()
    }
}
