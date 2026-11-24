package com.kayx.manager.data.filesystem

import android.content.Context
import android.net.Uri
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
            await(control)
            val source = location(key)
            val name = when (source) { is StorageLocation.Local -> source.directory.name; is StorageLocation.Saf -> source.relativePath.substringAfterLast('/') }
            onProgress(index, sourceKeys.size, name)
            if (transferOne(context, source, destination, move, policy, control, warnings)) completed++ else failures += name
            onProgress(index + 1, sourceKeys.size, name)
        }
        Outcome(completed, failures, warnings)
    }

    private fun transferOne(context: Context, source: StorageLocation, destination: StorageLocation, move: Boolean,
        policy: Conflict, control: FileOps.TransferControl, warnings: MutableList<String>): Boolean {
        if (source is StorageLocation.Local && destination is StorageLocation.Local)
            return FileOps.transfer(listOf(source.directory), destination.directory, move, policy, control = control).isSuccess
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
            val target = uniqueTarget(destination.directory, doc.name ?: return false, policy) ?: return false
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
        if (policy == Conflict.REPLACE) { if (!target.deleteRecursively()) return null; return target }
        return FileOps.uniqueName(parent, name)
    }

    private fun location(key: String): StorageLocation = StorageLocation.fromStableKey(key) ?: StorageLocation.Local(File(key))
    private fun await(control: FileOps.TransferControl) {
        while (control.state == FileOps.TransferState.PAUSED) Thread.sleep(50)
        check(control.state != FileOps.TransferState.CANCELLED) { "Transfer cancelled" }
    }
}