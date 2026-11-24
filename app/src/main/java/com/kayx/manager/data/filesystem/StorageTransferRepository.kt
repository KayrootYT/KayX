package com.kayx.manager.data.filesystem

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import java.io.File

/** Cross-provider transfer dispatcher. SAF keys are resolved via DocumentFile only. */
class StorageTransferRepository {
    data class Outcome(val completed: Int, val failed: List<String>, val cleanupWarnings: List<String> = emptyList())

    fun transfer(context: Context, sourceKeys: List<String>, destinationKey: String, move: Boolean,
        policy: Conflict, control: FileOps.TransferControl,
        onProgress: (Int, Int, String, Long, Long) -> Unit = { _, _, _, _, _ -> }): Result<Outcome> = runCatching {
        val destination = location(destinationKey)
        when (destination) {
            is StorageLocation.Local -> require(destination.directory.isDirectory && destination.directory.canWrite()) {
                "Local destination folder is unavailable or not writable"
            }
            is StorageLocation.Saf -> {
                val tree = Uri.parse(destination.treeUri)
                require(SafRepository.hasWriteAccess(context, tree)) {
                    "Selected SAF destination is read-only or its write permission was revoked"
                }
                require(SafRepository.findByRelativePath(context, tree, destination.relativePath)?.isDirectory == true) {
                    "Selected SAF destination folder is unavailable"
                }
            }
        }
        require(sourceKeys.isNotEmpty()) { "No source items selected" }
        val failures = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        var completed = 0
        val itemSizes = sourceKeys.map { key -> runCatching { itemSize(context, location(key)) }.getOrDefault(0L) }
        val totalBytes = itemSizes.sum()
        var completedBytes = 0L
        for ((index, key) in sourceKeys.withIndex()) {
            val source = location(key)
            val itemBytes = itemSizes[index]
            val name = when (source) { is StorageLocation.Local -> source.directory.name; is StorageLocation.Saf -> source.relativePath.substringAfterLast('/') }
            if (control.isStopped()) {
                failures.addAll(sourceKeys.drop(index))
                break
            }
            var currentItemBytes = 0L
            val byteCallback: (Long) -> Unit = { delta ->
                currentItemBytes += delta.coerceAtLeast(0L)
                onProgress(index, sourceKeys.size, name, completedBytes + currentItemBytes, totalBytes)
            }
            onProgress(index, sourceKeys.size, name, completedBytes, totalBytes)
            val ok = try { transferOne(context, source, destination, move, policy, control, warnings, byteCallback) }
                catch (_: Exception) { false }
            if (ok) {
                completed++
                completedBytes = (completedBytes + maxOf(itemBytes, currentItemBytes)).coerceAtMost(totalBytes)
            } else failures += key
            onProgress(index + 1, sourceKeys.size, name, completedBytes, totalBytes)
        }
        Outcome(completed, failures, warnings)
    }

    private fun transferOne(context: Context, source: StorageLocation, destination: StorageLocation, move: Boolean,
        policy: Conflict, control: FileOps.TransferControl, warnings: MutableList<String>,
        onBytesCopied: (Long) -> Unit): Boolean {
        if (!(source is StorageLocation.Local && destination is StorageLocation.Local)) {
            val sourceName = when (source) {
                is StorageLocation.Local -> source.directory.name
                is StorageLocation.Saf -> source.relativePath.substringAfterLast('/')
            }
            val destinationExists = when (destination) {
                is StorageLocation.Local -> File(destination.directory, sourceName).exists()
                is StorageLocation.Saf -> SafRepository.findByRelativePath(
                    context, Uri.parse(destination.treeUri), destination.relativePath
                )?.findFile(sourceName) != null
            }
            if (destinationExists && policy == Conflict.SKIP) return true
            // Cross-provider replacement must stage/restore provider documents atomically.
            // Until that is available, fail safely rather than silently turning REPLACE into KEEP_BOTH.
            if (destinationExists && policy == Conflict.REPLACE) return false
        }
        if (source is StorageLocation.Local && destination is StorageLocation.Local) {
            var itemCompleted = false
            val result = FileOps.transfer(
                listOf(source.directory), destination.directory, move, policy,
                onProgress = { completed, _, _ -> if (completed > 0) itemCompleted = true },
                control = control,
                onBytesCopied = onBytesCopied
            )
            if (result.isSuccess) return true
            // FileOps reports source-cleanup failure after retaining the complete destination.
            if (move && itemCompleted) {
                warnings += source.directory.name
                return true
            }
            return false
        }
        if (source is StorageLocation.Local && destination is StorageLocation.Saf) {
            val tree = Uri.parse(destination.treeUri)
            if (!move) return SafRepository.copyLocalFileToTree(context, source.directory, tree, destination.relativePath, control, onBytesCopied) != null
            var cleanupFailed = false
            val copied = SafRepository.moveLocalFileToTree(context, source.directory, tree, destination.relativePath, control,
                onSourceCleanupFailure = { cleanupFailed = true }, onBytesCopied = onBytesCopied)
            if (cleanupFailed) warnings += source.directory.name
            return copied != null
        }
        if (source is StorageLocation.Saf && destination is StorageLocation.Saf) {
            val sourceParent = source.relativePath.substringBeforeLast('/', "")
            if (move && source.treeUri == destination.treeUri && sourceParent == destination.relativePath) return true
            val doc = resolveDocument(context, source) ?: return false
            val copied = if (move) SafRepository.moveDocumentToTree(context, doc.uri, Uri.parse(destination.treeUri), destination.relativePath, doc.name, control, onBytesCopied)
                else SafRepository.copyDocumentToTree(context, doc.uri, Uri.parse(destination.treeUri), destination.relativePath, doc.name, control, onBytesCopied)
            return copied != null
        }
        if (source is StorageLocation.Saf && destination is StorageLocation.Local) {
            val doc = resolveDocument(context, source) ?: return false
            val sourceName = doc.name ?: return false
            if (!isSafeName(sourceName)) return false
            if (doc.isDirectory) {
                val sourcePath = externalStoragePath(context, source)
                val destinationPath = runCatching { destination.directory.canonicalFile }.getOrNull()
                if (sourcePath != null && destinationPath != null &&
                    (destinationPath == sourcePath || destinationPath.toPath().startsWith(sourcePath.toPath()))) return false
            }
            val target = uniqueTarget(destination.directory, sourceName, policy) ?: return false
            if (!copySafNode(context, doc, target, control, onBytesCopied)) return false
            if (move) {
                try { await(control) } catch (_: Exception) {
                    target.deleteRecursively()
                    return false
                }
                if (!SafRepository.delete(context, doc.uri)) warnings += (doc.name ?: target.name)
            }
            return true
        }
        return false
    }

    private fun copySafNode(context: Context, source: DocumentFile, target: File, control: FileOps.TransferControl, onBytesCopied: (Long) -> Unit): Boolean {
        return try {
        await(control)
        if (source.isDirectory) {
            if (!target.mkdir()) return false
            for (child in source.listFiles()) {
                await(control)
                val name = child.name ?: error("Provider returned an unnamed item")
                check(isSafeName(name)) { "Provider returned an unsafe item name" }
                val childTarget = uniqueTarget(target, name, Conflict.KEEP_BOTH) ?: error("Cannot create child")
                check(copySafNode(context, child, childTarget, control, onBytesCopied)) { "Could not copy " + name }
            }
        } else {
            val input = context.contentResolver.openInputStream(source.uri) ?: error("Cannot read source")
            input.use { src -> target.outputStream().use { dst ->
                val buffer = ByteArray(64 * 1024)
                while (true) { await(control); val count = src.read(buffer); if (count < 0) break; dst.write(buffer, 0, count); onBytesCopied(count.toLong()) }
                dst.flush()
                dst.fd.sync()
            } }
        }
        true
        } catch (_: Exception) { target.deleteRecursively(); false }
    }

    private fun resolveDocument(context: Context, location: StorageLocation.Saf): DocumentFile? =
        SafRepository.findByRelativePath(context, Uri.parse(location.treeUri), location.relativePath)

    private fun itemSize(context: Context, location: StorageLocation): Long {
        return when (location) {
        is StorageLocation.Local -> {
            val root = location.directory
            if (root.isFile) root.length().coerceAtLeast(0L)
            else runCatching { root.walkTopDown().filter { it.isFile }.sumOf { it.length().coerceAtLeast(0L) } }.getOrDefault(0L)
        }
        is StorageLocation.Saf -> {
            val root = resolveDocument(context, location) ?: return 0L
            if (!root.isDirectory) return root.length().coerceAtLeast(0L)
            var total = 0L
            val queue = java.util.ArrayDeque<DocumentFile>()
            val visited = mutableSetOf<String>()
            queue.add(root)
            while (queue.isNotEmpty() && visited.size < 100_000) {
                val item = queue.removeFirst()
                if (!visited.add(item.uri.toString())) continue
                if (item.isDirectory) item.listFiles().forEach { queue.addLast(it) }
                else total += item.length().coerceAtLeast(0L)
            }
            total
        }
        }
    }

    private fun uniqueTarget(parent: File, name: String, policy: Conflict): File? {
        if (!isSafeName(name)) return null
        val target = File(parent, name)
        if (!target.exists()) return target
        if (policy == Conflict.SKIP) return null
        // Do not destroy an existing destination before the replacement copy is verified.
        if (policy == Conflict.REPLACE) return null
        return FileOps.uniqueName(parent, name)
    }

    private fun isSafeName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." && !name.contains('/') &&
            !name.contains('\\') && name.none { it.code < 32 }

    private fun externalStoragePath(context: Context, location: StorageLocation.Saf): File? {
        return runCatching {
            val tree = Uri.parse(location.treeUri)
            if (tree.authority != "com.android.externalstorage.documents") null
            else {
                val documentId = DocumentsContract.getTreeDocumentId(tree)
                val volume = documentId.substringBefore(':')
                val selected = documentId.substringAfter(':', "")
                val root = if (volume.equals("primary", true)) Environment.getExternalStorageDirectory()
                    else File("/storage", volume)
                File(root, listOf(selected, location.relativePath).filter { it.isNotBlank() }.joinToString("/")).canonicalFile
            }
        }.getOrNull()
    }

    private fun location(key: String): StorageLocation {
        return StorageLocation.fromStableKey(key) ?: run {
            require(!key.startsWith("saf:", ignoreCase = true) && !key.startsWith("local:", ignoreCase = true)) {
                "Invalid storage location key"
            }
            StorageLocation.Local(File(key))
        }
    }
    private fun await(control: FileOps.TransferControl) {
        while (control.state == FileOps.TransferState.PAUSED) Thread.sleep(50)
        check(control.state != FileOps.TransferState.CANCELLED) { "Transfer cancelled" }
    }
}