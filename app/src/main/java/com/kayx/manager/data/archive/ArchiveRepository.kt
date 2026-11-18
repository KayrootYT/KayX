package com.kayx.manager.data.archive

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** ZIP archive operations. Uses the JDK ZIP implementation; encrypted ZIPs need a dedicated library. */
data class ZipItem(val name: String, val size: Long, val compressed: Long, val isDir: Boolean)

class ArchiveRepository {
    data class ArchiveLimits(
        val maxEntries: Int = 100_000,
        val maxExtractedBytes: Long = 8L * 1024 * 1024 * 1024
    )

    private fun validateEntryName(name: String) {
        require(name.isNotBlank()) { "Archive contains an empty entry name" }
        require(!name.contains('\u0000')) { "Archive entry contains a NUL byte" }
    }

    private fun validateEntryLimit(entries: Int, bytes: Long, limits: ArchiveLimits) {
        check(entries <= limits.maxEntries) { "Archive exceeds entry limit (${limits.maxEntries})" }
        check(bytes <= limits.maxExtractedBytes) { "Archive exceeds extraction limit (${limits.maxExtractedBytes} bytes)" }
    }

    /** Returns only the immediate children of [prefix], synthesizing folders when needed. */
    fun list(zip: File, prefix: String = ""): List<ZipItem> = ZipFile(zip).use { z ->
        val cleanPrefix = prefix.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        val children = linkedMapOf<String, ZipItem>()
        val entries = z.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (!entry.name.startsWith(cleanPrefix) || entry.name == cleanPrefix) continue
            val rest = entry.name.removePrefix(cleanPrefix)
            val first = rest.substringBefore('/')
            if (first.isEmpty()) continue
            val isFolder = rest.contains('/') || entry.isDirectory
            val childName = cleanPrefix + first + if (isFolder) "/" else ""
            val previous = children[childName]
            if (isFolder) {
                children[childName] = ZipItem(childName, previous?.size ?: 0L, previous?.compressed ?: 0L, true)
            } else {
                children[childName] = ZipItem(childName, entry.size, entry.compressedSize, false)
            }
        }
        children.values.sortedWith(compareByDescending<ZipItem> { it.isDir }.thenBy { it.name.substringAfterLast('/') .lowercase() })
    }

    /** Streams every entry through ZipInputStream, which verifies each entry's CRC. Returns (files checked, error or null). */
    fun testIntegrity(zip: File): Pair<Int, String?> {
        var count = 0
        return try {
            ZipInputStream(zip.inputStream().buffered()).use { zis ->
                val buf = ByteArray(64 * 1024)
                var e = zis.nextEntry
                while (e != null) {
                    if (!e.isDirectory) { count++; while (zis.read(buf) != -1) { } }
                    e = zis.nextEntry
                }
            }
            count to null
        } catch (ex: Exception) { count to (ex.message ?: "Corrupt archive") }
    }

    private fun uniqueDir(dir: File): File {
        var t = dir; var i = 1
        while (t.exists()) { t = File(dir.parentFile, "${dir.name} ($i)"); i++ }
        return t
    }

    /** Extract selected entries; selecting a folder includes all descendants. */
    fun extract(
        zip: File,
        names: Set<String>?,
        limits: ArchiveLimits = ArchiveLimits()
    ): Result<Pair<File, Int>> = runCatching {
        val dest = uniqueDir(File(zip.parentFile, zip.nameWithoutExtension))
        check(dest.mkdirs()) { "Could not create output folder" }
        val root = dest.canonicalPath
        var n = 0
        var totalBytes = 0L
        try {
            ZipFile(zip).use { z ->
                val entries = z.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    validateEntryName(e.name)
                    if (names != null && names.none { selected ->
                            val normalized = selected.trimEnd('/')
                            e.name == normalized || e.name == "$normalized/" || e.name.startsWith("$normalized/")
                        }) continue
                    n++
                    totalBytes += e.size.coerceAtLeast(0L)
                    validateEntryLimit(n, totalBytes, limits)
                    val out = File(dest, e.name)
                    val canonical = out.canonicalPath
                    check(canonical == root || canonical.startsWith(root + File.separator)) { "Blocked unsafe path: ${e.name}" }
                    if (e.isDirectory) { out.mkdirs(); continue }
                    out.parentFile?.mkdirs()
                    z.getInputStream(e).use { input -> out.outputStream().use { output -> input.copyTo(output) } }
                }
            }
        } catch (t: Throwable) { dest.deleteRecursively(); throw t }
        dest to n
    }

    /** Creates a ZIP from files/folders. */
    fun create(files: List<File>, out: File): Result<Unit> = runCatching {
        check(files.isNotEmpty()) { "Select at least one file" }
        check(!out.exists()) { "${out.name} already exists" }
        try {
            ZipOutputStream(out.outputStream().buffered()).use { zos ->
                for (f in files) {
                    check(f.exists()) { "Missing source: ${f.name}" }
                    val base = f.parentFile ?: f
                    for (x in f.walkTopDown()) {
                        if (x.canonicalPath == out.canonicalPath) continue
                        val rel = x.relativeTo(base).path.replace(File.separatorChar, '/')
                        if (rel.isEmpty()) continue
                        if (x.isDirectory) { zos.putNextEntry(ZipEntry("$rel/")); zos.closeEntry() }
                        else { zos.putNextEntry(ZipEntry(rel)); x.inputStream().use { it.copyTo(zos) }; zos.closeEntry() }
                    }
                }
            }
        } catch (t: Throwable) { out.delete(); throw t }
    }

    /** Removes exact entries and all descendants of selected folders (keeps STORED/aligned entries intact). */
    fun removeEntries(zip: File, names: Set<String>): Result<Int> = runCatching {
        require(names.isNotEmpty()) { "Select entries to remove" }
        ArchiveEdit.rewrite(zip, delete = names).getOrThrow()
    }
}
