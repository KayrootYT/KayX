package com.kayx.manager.data.storage

import android.content.Context
import java.io.File
import java.util.Base64

/**
 * Crash-safe local-filesystem scan checkpoint. It stores paths and bounded metadata only,
 * never file contents. SAF trees are restarted from their root on resume because providers
 * do not expose a portable, durable directory cursor.
 */
internal object OfflineStorageScanCheckpoint {
    internal data class PendingDirectory(val path: String, val depth: Int)
    internal data class State(
        val root: String,
        val maxEntries: Int,
        val maxDepth: Int,
        val localComplete: Boolean,
        val pending: List<PendingDirectory>,
        val entries: List<OfflineStorageInventory.Entry>
    )

    private const val VERSION = "KAYX_SCAN_V1"
    private const val MAX_CHECKPOINT_BYTES = 64L * 1024 * 1024
    private fun file(context: Context) = File(context.applicationContext.filesDir, "offline-assistant-scan.checkpoint")

    fun load(context: Context, root: File, maxEntries: Int, maxDepth: Int): State? =
        loadFile(file(context), root, maxEntries, maxDepth)

    internal fun loadFile(target: File, root: File, maxEntries: Int, maxDepth: Int): State? = runCatching {
        val backup = File(target.parentFile, target.name + ".bak")
        fun readCandidate(candidate: File): State? {
            if (!candidate.isFile || candidate.length() > MAX_CHECKPOINT_BYTES) return null
            val lines = candidate.readLines(Charsets.UTF_8)
            val header = lines.firstOrNull()?.split('\t') ?: return null
            if (header.size != 6 || header[0] != VERSION || header[5] != "checkpoint") return null
            val savedRoot = decode(header[1])
            if (savedRoot != root.canonicalPath || header[2].toIntOrNull() != maxEntries ||
                header[3].toIntOrNull() != maxDepth) return null
            val complete = header[4] == "1"
            val pending = ArrayList<PendingDirectory>()
            val entries = ArrayList<OfflineStorageInventory.Entry>()
            for (line in lines.drop(1)) {
                val parts = line.split('\t')
                when (parts.firstOrNull()) {
                    "D" -> if (parts.size == 3) {
                        val depth = parts[1].toIntOrNull()?.takeIf { it in 0..maxDepth } ?: return null
                        pending += PendingDirectory(decode(parts[2]), depth)
                    }
                    "F" -> if (parts.size == 6) {
                        val size = parts[1].toLongOrNull()?.takeIf { it >= 0L } ?: return null
                        val modified = parts[2].toLongOrNull()?.takeIf { it >= 0L } ?: return null
                        val category = parts[3].takeIf { it != "-" }?.let { Category.valueOf(it) }
                        val path = decode(parts[4])
                        val name = decode(parts[5])
                        entries += OfflineStorageInventory.Entry(path, path, name, size, modified, category, saf = false)
                    }
                    else -> return null
                }
            }
            if (entries.size > maxEntries || pending.size > maxEntries) return null
            val rootPrefix = savedRoot.trimEnd(File.separatorChar) + File.separator
            val safePending = pending.filter { item ->
                val directory = File(item.path)
                runCatching {
                    !java.nio.file.Files.isSymbolicLink(directory.toPath()) &&
                        (directory.canonicalFile.path == savedRoot || directory.canonicalFile.path.startsWith(rootPrefix)) && directory.isDirectory
                }.getOrDefault(false)
            }
            val safeEntries = entries.filter { entry ->
                val source = File(entry.path)
                runCatching {
                    !java.nio.file.Files.isSymbolicLink(source.toPath()) &&
                        source.canonicalFile.path == entry.path && source.canonicalFile.path.startsWith(rootPrefix) && source.isFile && source.canRead() &&
                        source.length() == entry.size && source.lastModified() == entry.modified
                }.getOrDefault(false)
            }
            return State(savedRoot, maxEntries, maxDepth, complete, safePending, safeEntries)
        }

        val primary = readCandidate(target)
        if (primary != null) {
            backup.delete()
            primary
        } else {
            val recovered = readCandidate(backup)
            if (recovered != null) {
                if (target.exists()) target.delete()
                backup.renameTo(target)
            }
            recovered
        }
    }.getOrNull()

    /** Checkpoint failures must never abort a read-only inventory scan. The last valid checkpoint remains intact. */
    fun save(context: Context, state: State): Boolean = saveFileSafely(file(context), state)

    internal fun saveFileSafely(target: File, state: State): Boolean =
        runCatching { saveFile(target, state); true }.getOrDefault(false)

    internal fun saveFile(target: File, state: State) {
        val temp = File(target.parentFile, target.name + ".tmp")
        val text = buildString {
            append(VERSION).append('\t').append(encode(state.root)).append('\t')
                .append(state.maxEntries).append('\t').append(state.maxDepth).append('\t')
                .append(if (state.localComplete) "1" else "0").append("\tcheckpoint\n")
            state.pending.forEach { append("D\t").append(it.depth).append('\t').append(encode(it.path)).append('\n') }
            state.entries.forEach { entry ->
                append("F\t").append(entry.size).append('\t').append(entry.modified).append('\t')
                    .append(entry.category?.name ?: "-").append('\t').append(encode(entry.path)).append('\t')
                    .append(encode(entry.name)).append('\n')
            }
        }
        val encoded = text.toByteArray(Charsets.UTF_8)
        require(encoded.size.toLong() <= MAX_CHECKPOINT_BYTES) { "Scan checkpoint exceeds the 64 MiB safety limit" }
        FileOutputStreamSync.write(temp, encoded)
        val backup = File(target.parentFile, target.name + ".bak")
        backup.delete()
        val hadCheckpoint = target.exists()
        if (hadCheckpoint) check(target.renameTo(backup)) { "Could not preserve previous scan checkpoint" }
        try {
            check(temp.renameTo(target)) { "Could not save scan checkpoint" }
            backup.delete()
        } catch (error: Throwable) {
            if (hadCheckpoint) check(backup.renameTo(target)) { "Could not restore previous scan checkpoint" }
            throw error
        }
    }

    fun clear(context: Context) {
        file(context).delete()
        File(context.applicationContext.filesDir, "offline-assistant-scan.checkpoint.tmp").delete()
        File(context.applicationContext.filesDir, "offline-assistant-scan.checkpoint.bak").delete()
    }

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decode(value: String): String =
        String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)

    private object FileOutputStreamSync {
        fun write(file: File, value: ByteArray) {
            java.io.FileOutputStream(file).use { stream ->
                stream.write(value)
                stream.flush()
                stream.fd.sync()
            }
        }
    }
}
