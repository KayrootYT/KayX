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
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }): Result<Outcome> = runCatching {
        val destination = location(destinationKey)
        require(sourceKeys.isNotEmpty()) { "No source items selected" }
        val failures = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        var completed = 0
        for ((index, key) in sourceKeys.withIndex()) {
            val source = location(key)
            val name = when (source) { is StorageLocation.Local -> source.directory.name; is StorageLocation.Saf -> source.relativePath.substringAfterLast('/') }
            if (control.isStopped()) {
                sourceKeys.drop(index).forEach { remaining ->
                    val item = location(remaining)
                    failures += when (item) { is StorageLocation.Local -> item.directory.name; is StorageLocation.Saf -> item.relativePath.substringAfterLast('/') }
                }
                break
            }
            onProgress(index, sourceKeys.size, name)
            val ok = try { transferOne(context, source, destination, move, policy, control, warnings) }
                catch (_: Exception) { false }
            if (ok) completed++ else failures += name
            onProgress(index + 1, sourceKeys.size, name)
        }
        Outcome(completed, failures, warnings)
    }

    private fun transferOne(context: Context, source: StorageLocation, destination: StorageLocation, move: Boolean,
        policy: Conflict, control: FileOps.TransferControl, warnings: MutableList<String>): Boolean {
        if (source is StorageLocation.Local && destination is StorageLocation.Local) {
            var itemCompleted = false
            val result = FileOps.transfer(
                listOf(source.directory), destination.directory, move, policy,
                onProgress = { completed, _, _ -> if (completed > 0) itemCompleted = true },
                control = control
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
            if (!move) return SafRepository.copyLocalFileToTree(context, source.directory, tree, destination.relativePath, control) != null
            var cleanupFailed = false
            val copied = SafRepository.moveLocalFileToTree(context, source.directory, tree, destination.relativePath, control) { cleanupFailed = true }
            if (cleanupFailed) warnings += source.directory.name
            return copied != null
        }
        if (source is StorageLocation.Saf && destination is StorageLocation.Saf) {
            val doc = resolveDocument(context, source) ?: return false
            val copied = if (move) SafRepository.moveDocumentToTree(context, doc.uri, Uri.parse(destination.treeUri), destination.relativePath, doc.name, control)
                else SafRepository.copyDocumentToTree(context, doc.uri, Uri.parse(destination.treeUri), destination.relativePath, doc.name, control)
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
            if (!copySafNode(context, doc, target, control)) return false
            if (move && !SafRepository.delete(context, doc.uri)) warnings += (doc.name ?: target.name)
            return true
        }
        return false
    }

    private fun copySafNode(context: Context, source: DocumentFile, target: File, control: FileOps.TransferControl): Boolean {
        return try {
        await(control)
        if (source.isDirectory) {
            if (!target.mkdir()) return false
            for (child in source.listFiles()) {
                await(control)
                val name = child.name ?: error("Provider returned an unnamed item")
                val childTarget = uniqueTarget(target, name, Conflict.KEEP_BOTH) ?: error("Cannot create child")
                check(copySafNode(context, child, childTarget, control)) { "Could not copy " + name }
            }
        } else {
            val input = context.contentResolver.openInputStream(source.uri) ?: error("Cannot read source")
            input.use { src -> target.outputStream().use { dst ->
                val buffer = ByteArray(64 * 1024)
                while (true) { await(control); val count = src.read(buffer); if (count < 0) break; dst.write(buffer, 0, count) }
                dst.flush()
            } }
        }
        true
        } catch (_: Exception) { target.deleteRecursively(); false }
    }

    private fun resolveDocument(context: Context, location: StorageLocation.Saf): DocumentFile? =
        SafRepository.findByRelativePath(context, Uri.parse(location.treeUri), location.relativePath)

    private fun uniqueTarget(parent: File, name: String, policy: Conflict): File? {
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

    private fun externalStoragePath(context: Context, location: StorageLocation.Saf): File? = runCatching {
        val tree = Uri.parse(location.treeUri)
        if (tree.authority != "com.android.externalstorage.documents") return null
        val documentId = DocumentsContract.getTreeDocumentId(tree)
        val volume = documentId.substringBefore(':')
        val selected = documentId.substringAfter(':', "")
        val root = if (volume.equals("primary", true)) Environment.getExternalStorageDirectory()
            else File("/storage", volume)
        File(root, listOf(selected, location.relativePath).filter { it.isNotBlank() }.joinToString("/")).canonicalFile
    }.getOrNull()

    private fun location(key: String): StorageLocation = StorageLocation.fromStableKey(key) ?: StorageLocation.Local(File(key))
    private fun await(control: FileOps.TransferControl) {
        while (control.state == FileOps.TransferState.PAUSED) Thread.sleep(50)
        check(control.state != FileOps.TransferState.CANCELLED) { "Transfer cancelled" }
    }
}