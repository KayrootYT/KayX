package com.kayx.manager.data.filesystem

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
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
    init { require(maxEntries in 1..MAX_READ_ENTRIES) { "Undo journal entry limit is out of range" } }

    enum class Kind { MOVE, COPY, RENAME, TRASH }
    data class Entry(val kind: Kind, val source: String, val destination: String, val fingerprint: String)

    private val file = File(directory, "file-operations.undo")
    private val temp = File(directory, "file-operations.undo.tmp")

    @Synchronized fun entries(): List<Entry> = readEntries()

    @Synchronized fun recordMove(source: File, destination: File) =
        append(Entry(Kind.MOVE, source.absolutePath, destination.absolutePath, ""))

    @Synchronized fun recordRename(source: File, destination: File) =
        append(Entry(Kind.RENAME, source.absolutePath, destination.absolutePath, ""))

    @Synchronized fun recordTrash(recycleEntryId: String) {
        require(isSafeRecycleEntryId(recycleEntryId))
        append(Entry(Kind.TRASH, "", recycleEntryId, ""))
    }

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
        if (entry.kind == Kind.TRASH) {
            RecycleBin.restoreById(entry.destination).getOrThrow()
        } else {
            require(destination.isAbsolute && destination.exists()) { "The affected item no longer exists" }
        }
        when (entry.kind) {
            Kind.TRASH -> Unit
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
                require(FileOps.deleteTreeInterruptible(destination)) { "Could not remove copied item" }
            }
        }
        writeEntries(current.dropLast(1))
        entry
    }

    private fun append(entry: Entry) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Undo journal operation cancelled")
        require(maxEntries > 0)
        require(entry.destination.isNotBlank())
        if (entry.kind != Kind.TRASH) require(File(entry.destination).isAbsolute)
        if (entry.kind != Kind.COPY && entry.kind != Kind.TRASH) require(entry.source.isNotBlank() && File(entry.source).isAbsolute)
        writeEntries((readEntries() + entry).takeLast(maxEntries))
    }

    /**
     * Binary, length-prefixed records avoid delimiter/empty-field ambiguity in the old
     * line-based journal. The reader retains support for journals written by earlier builds.
     */
    private fun readEntries(): List<Entry> {
        val backup = File(directory, "file-operations.undo.bak")

        fun readCandidate(candidate: File): List<Entry>? {
            if (!candidate.isFile || candidate.length() > MAX_JOURNAL_BYTES) return null
            val bytes = runCatching { candidate.readBytes() }.getOrNull() ?: return null
            return runCatching {
                val header = DataInputStream(ByteArrayInputStream(bytes))
                val magic = if (bytes.size >= 4) header.readInt() else 0
                if (magic == MAGIC) {
                    val version = header.readInt()
                    require(version == VERSION) { "Unsupported undo journal version" }
                    val count = header.readInt()
                    require(count in 0..MAX_READ_ENTRIES) { "Invalid undo journal entry count" }
                    buildList {
                        repeat(count) {
                            val kind = Kind.valueOf(header.readUTF())
                            val source = header.readUTF()
                            val destination = header.readUTF()
                            val fingerprint = header.readUTF()
                            if (isValidEntry(kind, source, destination)) {
                                add(Entry(kind, source, destination, fingerprint))
                            }
                        }
                    }.takeLast(maxEntries)
                } else {
                    readLegacyEntries(bytes)
                }
            }.getOrNull()
        }

        val primary = readCandidate(file)
        if (primary != null) {
            backup.delete()
            return primary
        }

        // A crash or interrupted replacement must not make a valid backup disappear
        // merely because the primary file exists but is corrupt or truncated.
        val recovered = readCandidate(backup) ?: return emptyList()
        if (file.exists()) file.delete()
        if (backup.renameTo(file)) return recovered
        return recovered
    }

    private fun readLegacyEntries(bytes: ByteArray): List<Entry>? =
        runCatching<List<Entry>> {
            require(bytes.isNotEmpty()) { "Empty undo journal" }
            val lines = String(bytes, Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.toList()
            require(lines.size <= MAX_READ_ENTRIES) { "Invalid legacy undo journal entry count" }
            lines.map { line ->
                val fields = line.split('\t', ignoreCase = false, limit = 4)
                require(fields.size == 4) { "Malformed legacy undo journal entry" }
                val kind = Kind.valueOf(fields[0])
                fun decode(value: String) = if (value == "-") "" else
                    String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
                val source = decode(fields[1])
                val destination = decode(fields[2])
                val fingerprint = decode(fields[3])
                require(isValidEntry(kind, source, destination)) { "Invalid legacy undo journal entry" }
                Entry(kind, source, destination, fingerprint)
            }.takeLast(maxEntries)
        }.getOrNull()

    private fun isValidEntry(kind: Kind, source: String, destination: String): Boolean =
        destination.isNotBlank() &&
            (kind == Kind.TRASH && isSafeRecycleEntryId(destination) ||
                kind != Kind.TRASH && File(destination).isAbsolute) &&
            (kind == Kind.COPY || kind == Kind.TRASH ||
                source.isNotBlank() && File(source).isAbsolute)

    private fun writeEntries(entries: List<Entry>) {
        require(entries.size <= maxEntries)
        directory.mkdirs()
        val stream = FileOutputStream(temp)
        try {
            val output = DataOutputStream(stream)
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            output.writeInt(entries.size)
            entries.forEach { entry ->
                output.writeUTF(entry.kind.name)
                output.writeUTF(entry.source)
                output.writeUTF(entry.destination)
                output.writeUTF(entry.fingerprint)
            }
            output.flush()
            stream.fd.sync()
        } finally {
            stream.close()
        }

        require(temp.length() <= MAX_JOURNAL_BYTES) { "Undo journal exceeds the 16 MiB safety limit" }
        val backup = File(directory, "file-operations.undo.bak")
        backup.delete()
        val hadJournal = file.exists()
        if (hadJournal) require(file.renameTo(backup)) { "Could not preserve undo journal" }
        try {
            require(temp.renameTo(file)) { "Could not commit undo journal" }
            backup.delete()
        } catch (failure: Throwable) {
            if (hadJournal && backup.exists()) require(backup.renameTo(file)) { "Could not restore undo journal" }
            throw failure
        }
    }

    private fun fingerprint(target: File): String? {
        return try {
            require(target.exists())
            val digest = MessageDigest.getInstance("SHA-256")
            if (target.isFile) {
                digest.update("FILE".toByteArray(Charsets.UTF_8))
                FileInputStream(target).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException("Undo fingerprint cancelled")
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
            } else {
                digest.update("DIRECTORY".toByteArray(Charsets.UTF_8))
                val pending = ArrayDeque<File>()
                val items = ArrayList<File>()
                target.listFiles()?.forEach(pending::addLast)
                while (pending.isNotEmpty()) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("Undo fingerprint cancelled")
                    val item = pending.removeLast()
                    items += item
                    if (item.isDirectory && !java.nio.file.Files.isSymbolicLink(item.toPath())) {
                        val children = item.listFiles() ?: error("Cannot read folder ${item.name}")
                        children.forEach(pending::addLast)
                    }
                }
                // Keep the exact relative-path ordering used by existing journal fingerprints.
                items.sortedBy { it.relativeTo(target).invariantSeparatorsPath }.forEach { item ->
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("Undo fingerprint cancelled")
                    val relative = item.relativeTo(target).invariantSeparatorsPath
                    digest.update(relative.toByteArray(Charsets.UTF_8))
                    digest.update((if (item.isDirectory) 1 else 0).toByte())
                    if (item.isFile) {
                        FileInputStream(item).use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                if (Thread.currentThread().isInterrupted) throw InterruptedException("Undo fingerprint cancelled")
                                val count = input.read(buffer)
                                if (count < 0) break
                                digest.update(buffer, 0, count)
                            }
                        }
                    }
                }
            }
            val hex = "0123456789abcdef"
            buildString(64) {
                digest.digest().forEach { byte ->
                    val value = byte.toInt() and 0xff
                    append(hex[value ushr 4])
                    append(hex[value and 0x0f])
                }
            }
        } catch (cancelled: InterruptedException) {
            Thread.currentThread().interrupt()
            throw cancelled
        } catch (_: Throwable) {
            null
        }
    }

    private companion object {
        const val MAGIC = 0x4B58554A // KXUJ
        const val VERSION = 1
        const val MAX_READ_ENTRIES = 10_000
        const val MAX_JOURNAL_BYTES = 16L * 1024 * 1024
    }
}
