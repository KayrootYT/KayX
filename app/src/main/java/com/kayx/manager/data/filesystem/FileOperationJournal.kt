package com.kayx.manager.data.filesystem

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Base64

/**
 * Small durable undo journal for completed local file operations.
 * Undo never overwrites an existing source and never deletes a copy whose content changed.
 */
class FileOperationJournal(private val directory: File, private val maxEntries: Int = 50) {
    enum class Kind { MOVE, COPY, RENAME }
    data class Entry(val kind: Kind, val source: String, val destination: String, val fingerprint: String)

    private val file = File(directory, "file-operations.undo")
    private val temp = File(directory, "file-operations.undo.tmp")

    @Synchronized fun entries(): List<Entry> = readEntries()

    @Synchronized fun recordMove(source: File, destination: File) =
        append(Entry(Kind.MOVE, source.absolutePath, destination.absolutePath, ""))

    @Synchronized fun recordRename(source: File, destination: File) =
        append(Entry(Kind.RENAME, source.absolutePath, destination.absolutePath, ""))

    @Synchronized fun recordCopy(destination: File): Boolean {
        val value = fingerprint(destination) ?: return false
        append(Entry(Kind.COPY, "", destination.absolutePath, value))
        return true
    }

    @Synchronized fun undo(): Result<Entry> = runCatching {
        val current = readEntries()
        val entry = current.lastOrNull() ?: error("Nothing to undo")
        val source = File(entry.source)
        val destination = File(entry.destination)
        require(destination.isAbsolute && destination.exists()) { "The affected item no longer exists" }
        when (entry.kind) {
            Kind.MOVE, Kind.RENAME -> {
                require(source.isAbsolute && !source.exists()) { "Original location is no longer available" }
                require(destination.canonicalFile != source.canonicalFile) { "Invalid undo path" }
                source.parentFile?.let { require(it.isDirectory || it.mkdirs()) { "Cannot recreate original folder" } }
                require(destination.renameTo(source)) { "Could not restore " + source.name }
            }
            Kind.COPY -> {
                require(fingerprint(destination) == entry.fingerprint) {
                    "Copied item changed since the operation; refusing to delete it"
                }
                require(destination.deleteRecursively()) { "Could not remove copied item" }
            }
        }
        writeEntries(current.dropLast(1))
        entry
    }

    private fun append(entry: Entry) {
        require(maxEntries > 0)
        require(entry.destination.isNotBlank() && File(entry.destination).isAbsolute)
        if (entry.kind != Kind.COPY) require(entry.source.isNotBlank() && File(entry.source).isAbsolute)
        writeEntries((readEntries() + entry).takeLast(maxEntries))
    }

    private fun readEntries(): List<Entry> {
        if (!file.isFile) return emptyList()
        return runCatching {
            file.readLines(Charsets.UTF_8).mapNotNull { line ->
                val fields = line.split('\t')
                if (fields.size != 4) return@mapNotNull null
                val kind = runCatching { Kind.valueOf(fields[0]) }.getOrNull() ?: return@mapNotNull null
                fun decode(value: String) = String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
                val source = decode(fields[1])
                val destination = decode(fields[2])
                val fingerprint = decode(fields[3])
                if (!File(destination).isAbsolute || (kind != Kind.COPY && !File(source).isAbsolute)) null
                else Entry(kind, source, destination, fingerprint)
            }.takeLast(maxEntries)
        }.getOrDefault(emptyList())
    }

    private fun writeEntries(entries: List<Entry>) {
        directory.mkdirs()
        FileOutputStream(temp).use { output ->
            val text = entries.joinToString("\n") { entry ->
                listOf(entry.kind.name, entry.source, entry.destination, entry.fingerprint).joinToString("\t") {
                    Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8))
                }
            }
            output.write(text.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
        if (file.exists()) require(file.delete()) { "Could not replace undo journal" }
        require(temp.renameTo(file)) { "Could not commit undo journal" }
    }

    private fun fingerprint(target: File): String? = runCatching {
        require(target.exists())
        val digest = MessageDigest.getInstance("SHA-256")
        if (target.isFile) {
            FileInputStream(target).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
        } else {
            target.walkTopDown().filter { it != target }
                .sortedBy { it.relativeTo(target).invariantSeparatorsPath }
                .forEach { item ->
                    digest.update(item.relativeTo(target).invariantSeparatorsPath.toByteArray(Charsets.UTF_8))
                    digest.update(if (item.isDirectory) 1 else 0)
                    if (item.isFile) FileInputStream(item).use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                        }
                    }
                }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()
}
