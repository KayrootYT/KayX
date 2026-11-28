package com.kayx.manager.data.storage

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Bounded app-private snapshot of the last completed inventory.
 *
 * The snapshot contains paths and metadata only (never file contents). Local entries are
 * revalidated when loaded; SAF entries retain stable keys and must be re-resolved against
 * the currently persisted tree grant before any operation uses them.
 */
internal class OfflineStorageInventorySnapshotStore(private val directory: File) {
    private val primary = File(directory, FILE_NAME)
    private val backup = File(directory, "$FILE_NAME.bak")
    private val temp = File(directory, "$FILE_NAME.tmp")

    @Synchronized
    fun save(result: OfflineStorageInventory.Result): Boolean = runCatching {
        require(result.entries.size <= MAX_ENTRIES)
        val bytes = java.io.ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeInt(result.scannedRoots)
                output.writeInt(result.inaccessibleRoots)
                output.writeBoolean(result.truncated)
                output.writeInt(result.entries.size)
                result.entries.forEach { entry ->
                    output.writeUTF(entry.key)
                    output.writeUTF(entry.path)
                    output.writeUTF(entry.name)
                    output.writeLong(entry.size)
                    output.writeLong(entry.modified)
                    output.writeInt(entry.category?.ordinal ?: -1)
                    output.writeBoolean(entry.saf)
                }
            }
            buffer.toByteArray()
        }
        require(bytes.size <= MAX_BYTES) { "Inventory snapshot exceeds its safety limit" }
        directory.mkdirs()
        FileOutputStream(temp).use { stream ->
            stream.write(bytes)
            stream.flush()
            stream.fd.sync()
        }
        backup.delete()
        val hadPrimary = primary.exists()
        if (hadPrimary) check(primary.renameTo(backup)) { "Could not preserve previous inventory snapshot" }
        try {
            check(temp.renameTo(primary)) { "Could not commit inventory snapshot" }
            backup.delete()
        } catch (failure: Throwable) {
            if (hadPrimary && backup.exists()) check(backup.renameTo(primary)) { "Could not restore previous inventory snapshot" }
            throw failure
        }
        true
    }.getOrDefault(false)

    @Synchronized
    fun load(nowMillis: Long = System.currentTimeMillis()): OfflineStorageInventory.Result? {
        fun read(file: File): OfflineStorageInventory.Result? {
            if (!file.isFile || file.length() !in 1..MAX_BYTES.toLong()) return null
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
            return runCatching {
                DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                    require(input.readInt() == MAGIC && input.readInt() == VERSION)
                    val roots = input.readInt().also { require(it >= 0) }
                    val inaccessible = input.readInt().also { require(it >= 0) }
                    val truncated = input.readBoolean()
                    val count = input.readInt().also { require(it in 0..MAX_ENTRIES) }
                    val entries = ArrayList<OfflineStorageInventory.Entry>(count)
                    repeat(count) {
                        val key = input.readUTF()
                        val path = input.readUTF()
                        val name = input.readUTF()
                        val size = input.readLong().also { require(it >= 0L) }
                        val modified = input.readLong().also { require(it >= 0L) }
                        val categoryOrdinal = input.readInt()
                        val category = if (categoryOrdinal == -1) null else
                            Category.values().getOrNull(categoryOrdinal) ?: error("Invalid inventory category")
                        val saf = input.readBoolean()
                        require(key.isNotBlank() && path.isNotBlank() && name.isNotBlank())
                        val valid = if (saf) {
                            StorageLocationValidation.isSafeSafKey(key)
                        } else {
                            val source = File(key)
                            runCatching {
                                source.isAbsolute && !java.nio.file.Files.isSymbolicLink(source.toPath()) &&
                                    source.canonicalPath == path && source.isFile && source.canRead() &&
                                    source.length() == size && source.lastModified() == modified
                            }.getOrDefault(false)
                        }
                        if (valid) entries += OfflineStorageInventory.Entry(key, path, name, size, modified, category, saf)
                    }
                    require(input.available() == 0) { "Trailing inventory snapshot data" }
                    val oldCutoff = nowMillis - DEFAULT_OLD_AGE_DAYS * 24L * 60L * 60L * 1000L
                    val large = entries.filter { it.size >= DEFAULT_LARGE_BYTES }.sortedByDescending { it.size }.take(100)
                    val old = entries.filter { it.modified in 1 until oldCutoff }.sortedBy { it.modified }.take(100)
                    OfflineStorageInventory.Result(
                        entries = entries,
                        largeFiles = large,
                        oldFiles = old,
                        duplicateCandidates = emptyList(),
                        duplicateGroups = emptyList(),
                        totalBytes = entries.sumOf { it.size },
                        scannedRoots = roots,
                        inaccessibleRoots = inaccessible,
                        truncated = truncated
                    )
                }
            }.getOrNull()
        }

        val loaded = read(primary)
        if (loaded != null) {
            backup.delete()
            return loaded
        }
        val recovered = read(backup) ?: return null
        primary.delete()
        if (backup.renameTo(primary)) return recovered
        return recovered
    }

    @Synchronized
    fun clear() {
        primary.delete()
        backup.delete()
        temp.delete()
    }

    private object StorageLocationValidation {
        fun isSafeSafKey(key: String): Boolean {
            val location = com.kayx.manager.data.filesystem.StorageLocation.fromStableKey(key)
                as? com.kayx.manager.data.filesystem.StorageLocation.Saf ?: return false
            return location.relativePath.split('/').none { it == "." || it == ".." || it.contains('\\') }
        }
    }

    private companion object {
        const val FILE_NAME = "offline-assistant-inventory.snapshot"
        const val MAGIC = 0x4B584953 // KXIS
        const val VERSION = 1
        const val MAX_ENTRIES = 50_000
        const val MAX_BYTES = 16 * 1024 * 1024
        const val DEFAULT_LARGE_BYTES = 100L * 1024 * 1024
        const val DEFAULT_OLD_AGE_DAYS = 180L
    }
}
