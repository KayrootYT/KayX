package com.kayx.manager.data.filesystem

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Durable crash marker for a foreground transfer.
 *
 * This deliberately does not auto-retry: after process death a provider may have committed
 * a destination while the source cleanup was interrupted. The saved plan is for explicit
 * inspection/reconciliation, never an instruction to blindly repeat a destructive operation.
 */
internal class OfflineTransferRecoveryStore(private val directory: File) {
    enum class State { RUNNING, INTERRUPTED, NEEDS_REVIEW }
    data class Record(
        val sources: List<String>,
        val destination: String,
        val moving: Boolean,
        val conflictPolicy: Conflict,
        val state: State,
        val startedAtMillis: Long,
        val completedItems: Int = 0,
        val totalItems: Int = sources.size
    )

    private val file = File(directory, FILE_NAME)
    private val backup = File(directory, "$FILE_NAME.bak")
    private val temp = File(directory, "$FILE_NAME.tmp")

    @Synchronized fun begin(record: Record): Boolean {
        require(record.sources.isNotEmpty() && record.sources.size <= MAX_SOURCES)
        require(record.destination.isNotBlank() && record.destination.length <= MAX_FIELD_CHARS)
        require(record.sources.all { it.isNotBlank() && it.length <= MAX_FIELD_CHARS })
        require(record.state == State.RUNNING)
        return write(record)
    }

    @Synchronized fun updateProgress(completedItems: Int): Boolean {
        val current = read() ?: return false
        return write(current.copy(completedItems = completedItems.coerceIn(0, current.totalItems)))
    }

    @Synchronized fun markNeedsReview(): Boolean {
        val current = read() ?: return false
        return write(current.copy(state = State.NEEDS_REVIEW))
    }

    @Synchronized fun markInterrupted(): Record? {
        val current = read() ?: return null
        if (current.state == State.RUNNING) write(current.copy(state = State.INTERRUPTED))
        return read()
    }

    @Synchronized fun load(): Record? = read()

    @Synchronized fun clear() {
        file.delete()
        backup.delete()
        temp.delete()
    }

    private fun write(record: Record): Boolean = runCatching {
        directory.mkdirs()
        val bytes = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeUTF(record.state.name)
                out.writeUTF(record.destination)
                out.writeBoolean(record.moving)
                out.writeUTF(record.conflictPolicy.name)
                out.writeLong(record.startedAtMillis)
                out.writeInt(record.completedItems)
                out.writeInt(record.totalItems)
                out.writeInt(record.sources.size)
                record.sources.forEach(out::writeUTF)
            }
            buffer.toByteArray()
        }
        require(bytes.size <= MAX_BYTES)
        FileOutputStream(temp).use { stream ->
            stream.write(bytes)
            stream.flush()
            stream.fd.sync()
        }
        backup.delete()
        val hadPrimary = file.exists()
        if (hadPrimary) check(file.renameTo(backup))
        try {
            check(temp.renameTo(file))
            backup.delete()
        } catch (failure: Throwable) {
            if (hadPrimary && backup.exists()) check(backup.renameTo(file))
            throw failure
        }
        true
    }.getOrDefault(false)

    private fun read(): Record? {
        fun parse(candidate: File): Record? {
            if (!candidate.isFile || candidate.length() !in 1..MAX_BYTES.toLong()) return null
            val bytes = runCatching { candidate.readBytes() }.getOrNull() ?: return null
            return runCatching {
                DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                    require(input.readInt() == MAGIC && input.readInt() == VERSION)
                    val state = State.valueOf(input.readUTF())
                    val destination = input.readUTF().also { require(it.isNotBlank() && it.length <= MAX_FIELD_CHARS) }
                    val moving = input.readBoolean()
                    val policy = Conflict.valueOf(input.readUTF())
                    val started = input.readLong().also { require(it > 0L) }
                    val completed = input.readInt()
                    val total = input.readInt().also { require(it in 1..MAX_SOURCES) }
                    val count = input.readInt().also { require(it == total) }
                    val sources = List(count) { input.readUTF().also { value -> require(value.isNotBlank() && value.length <= MAX_FIELD_CHARS) } }
                    require(completed in 0..total && input.available() == 0)
                    Record(sources, destination, moving, policy, state, started, completed, total)
                }
            }.getOrNull()
        }
        val primary = parse(file)
        if (primary != null) { backup.delete(); return primary }
        val recovered = parse(backup) ?: return null
        file.delete()
        if (backup.renameTo(file)) return recovered
        return recovered
    }

    private companion object {
        const val FILE_NAME = "offline-transfer-recovery.bin"
        const val MAGIC = 0x4B585452 // KXTR
        const val VERSION = 1
        const val MAX_SOURCES = 10_000
        const val MAX_FIELD_CHARS = 32_768
        const val MAX_BYTES = 4 * 1024 * 1024
    }
}
