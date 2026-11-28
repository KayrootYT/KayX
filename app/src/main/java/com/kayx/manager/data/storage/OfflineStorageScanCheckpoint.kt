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
    private fun file(context: Context) = File(context.applicationContext.filesDir, "offline-assistant-scan.checkpoint")

    fun load(context: Context, root: File, maxEntries: Int, maxDepth: Int): State? = runCatching {
        val target = file(context)
        if (!target.isFile || target.length() > 16L * 1024 * 1024) return null
        val lines = target.readLines(Charsets.UTF_8)
        val header = lines.firstOrNull()?.split('\t') ?: return null
        if (header.size != 6 || header[0] != VERSION) return null
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
                    val depth = parts[1].toIntOrNull() ?: return null
                    pending += PendingDirectory(decode(parts[2]), depth)
                }
                "F" -> if (parts.size == 6) {
                    val size = parts[1].toLongOrNull() ?: return null
                    val modified = parts[2].toLongOrNull() ?: return null
                    val category = parts[3].takeIf { it != "-" }?.let { Category.valueOf(it) }
                    val path = decode(parts[4])
                    val name = decode(parts[5])
                    entries += OfflineStorageInventory.Entry(path, path, name, size, modified, category, saf = false)
                }
                else -> return null
            }
        }
        if (entries.size > maxEntries || pending.size > maxEntries) return null
        State(savedRoot, maxEntries, maxDepth, complete, pending, entries)
    }.getOrNull()

    fun save(context: Context, state: State) {
        val target = file(context)
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
        FileOutputStreamSync.write(temp, text)
        if (target.exists()) check(target.delete()) { "Could not replace scan checkpoint" }
        check(temp.renameTo(target)) { "Could not save scan checkpoint" }
    }

    fun clear(context: Context) {
        file(context).delete()
        File(context.applicationContext.filesDir, "offline-assistant-scan.checkpoint.tmp").delete()
    }

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decode(value: String): String =
        String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)

    private object FileOutputStreamSync {
        fun write(file: File, value: String) {
            java.io.FileOutputStream(file).use { stream ->
                stream.write(value.toByteArray(Charsets.UTF_8))
                stream.flush()
                stream.fd.sync()
            }
        }
    }
}
