package com.kayx.manager.data.filesystem

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.InputStream
import java.io.OutputStream

/**
 * SAF access for removable volumes and provider-protected directories.
 *
 * A tree URI is the durable permission boundary. Descendant document URIs are
 * resolved from that tree instead of treating a single-document URI as a tree.
 */
object SafRepository {
    private const val PREFS = "kayx_saf"
    private const val TREE_URIS = "tree_uris"

    data class Entry(
        val uri: Uri,
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long,
        val mimeType: String?
    )

    fun persistTreePermission(context: Context, uri: Uri, grantedFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION): Boolean {
        val resolver = context.contentResolver
        val flags = grantedFlags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) return false
        return try {
            resolver.takePersistableUriPermission(uri, flags)
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val saved = prefs.getStringSet(TREE_URIS, emptySet()).orEmpty().toMutableSet()
            saved += uri.toString()
            prefs.edit().putStringSet(TREE_URIS, saved).apply()
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    fun persistedTrees(context: Context): List<Uri> {
        val granted = context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri.toString() }
            .toSet()
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getStringSet(TREE_URIS, emptySet()).orEmpty()
        val valid = saved.filter { it in granted }
        // Prune stale records after users revoke access in Android settings.
        if (valid.size != saved.size) prefs.edit().putStringSet(TREE_URIS, valid.toSet()).apply()
        return valid.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
    }

    fun hasWriteAccess(context: Context, treeUri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isReadPermission && it.isWritePermission
        }

    fun removeTree(context: Context, uri: Uri) {
        val permission = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
        permission?.let {
            val flags = (if (it.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                (if (it.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            runCatching { context.contentResolver.releasePersistableUriPermission(uri, flags) }
        }
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getStringSet(TREE_URIS, emptySet()).orEmpty().toMutableSet()
        saved -= uri.toString()
        prefs.edit().putStringSet(TREE_URIS, saved).apply()
    }

    /** Resolve a tree root. Single-document URIs are deliberately not accepted as roots. */
    fun tree(context: Context, treeUri: Uri): DocumentFile? {
        if (!DocumentsContract.isTreeUri(treeUri)) return null
        return DocumentFile.fromTreeUri(context, treeUri)
    }

    fun document(context: Context, uri: Uri): DocumentFile? = when {
        DocumentsContract.isTreeUri(uri) -> DocumentFile.fromTreeUri(context, uri)
        DocumentsContract.isDocumentUri(context, uri) -> DocumentFile.fromSingleUri(context, uri)
        else -> null
    }

    fun children(context: Context, treeUri: Uri): List<DocumentFile> =
        tree(context, treeUri)?.listFiles()?.toList().orEmpty()

    /** List descendants by a relative path within the persisted tree. */
    fun children(context: Context, treeUri: Uri, relativePath: String): List<DocumentFile> =
        findByRelativePath(context, treeUri, relativePath)?.takeIf { it.isDirectory }?.listFiles()?.toList().orEmpty()

    fun entries(context: Context, treeUri: Uri, relativePath: String = ""): List<Entry> =
        children(context, treeUri, relativePath).mapNotNull { file ->
            val name = file.name ?: return@mapNotNull null
            Entry(file.uri, name, file.isDirectory, file.length().coerceAtLeast(0L), file.lastModified(), file.type)
        }

    fun findByRelativePath(context: Context, treeUri: Uri, relativePath: String): DocumentFile? {
        var current = tree(context, treeUri) ?: return null
        val parts = relativePath.trim('/').split('/').filter { it.isNotBlank() }
        for (part in parts) {
            // Never allow traversal outside the selected tree.
            if (part == "." || part == ".." || part.contains('\u0000')) return null
            current = current.findFile(part) ?: return null
        }
        return current
    }

    fun createFolder(context: Context, treeUri: Uri, parentRelativePath: String, name: String): Uri? {
        if (!isSafeName(name)) return null
        return findByRelativePath(context, treeUri, parentRelativePath)
            ?.takeIf { it.isDirectory }
            ?.createDirectory(name)?.uri
    }

    fun createFile(context: Context, treeUri: Uri, parentRelativePath: String, name: String, mime: String = "application/octet-stream"): Uri? {
        if (!isSafeName(name)) return null
        return findByRelativePath(context, treeUri, parentRelativePath)
            ?.takeIf { it.isDirectory }
            ?.createFile(mime, name)?.uri
    }

    fun openInput(context: Context, documentUri: Uri): InputStream? =
        runCatching { context.contentResolver.openInputStream(documentUri) }.getOrNull()

    fun openOutput(context: Context, documentUri: Uri, truncate: Boolean = true): OutputStream? =
        runCatching { context.contentResolver.openOutputStream(documentUri, if (truncate) "wt" else "wa") }.getOrNull()

    /** Stream-copy so providers/SD cards work without assuming a real filesystem path. */
    fun copyIntoTree(
        context: Context,
        sourceUri: Uri,
        targetTreeUri: Uri,
        targetParentRelativePath: String,
        targetName: String
    ): Uri? {
        if (!isSafeName(targetName)) return null
        val source = document(context, sourceUri) ?: return null
        val parent = findByRelativePath(context, targetTreeUri, targetParentRelativePath)
            ?.takeIf { it.isDirectory } ?: return null
        val mime = source.type ?: "application/octet-stream"
        val safeTargetName = uniqueChildName(parent, targetName)
        val target = parent.createFile(mime, safeTargetName) ?: return null
        return try {
            val input = context.contentResolver.openInputStream(source.uri) ?: error("Cannot read source")
            val output = context.contentResolver.openOutputStream(target.uri, "wt") ?: error("Cannot write destination")
            input.use { src -> output.use { dst -> src.copyTo(dst) } }
            target.uri
        } catch (_: Exception) {
            runCatching { target.delete() }
            null
        }
    }

    /** Pick a collision-free child name without replacing an existing document. */
    private fun uniqueChildName(parent: DocumentFile, requestedName: String): String {
        if (parent.findFile(requestedName) == null) return requestedName
        val dot = requestedName.lastIndexOf('.')
        val hasExtension = dot > 0 && dot < requestedName.lastIndex
        val stem = if (hasExtension) requestedName.substring(0, dot) else requestedName
        val extension = if (hasExtension) requestedName.substring(dot) else ""
        var index = 1
        while (true) {
            val candidate = "$stem ($index)$extension"
            if (parent.findFile(candidate) == null) return candidate
            index++
        }
    }

    /**
     * Recursively copy a SAF document (file or directory) into a destination tree.
     * The destination is removed if any descendant fails, so callers never see a
     * knowingly partial directory presented as a successful transfer.
     */
    fun copyDocumentToTree(
        context: Context,
        sourceUri: Uri,
        targetTreeUri: Uri,
        targetParentRelativePath: String = "",
        targetName: String? = null
    ): Uri? {
        val source = document(context, sourceUri) ?: return null
        val name = targetName ?: source.name ?: return null
        if (!isSafeName(name)) return null
        val parent = findByRelativePath(context, targetTreeUri, targetParentRelativePath)
            ?.takeIf { it.isDirectory } ?: return null

        fun copyNode(src: DocumentFile, dstParent: DocumentFile, dstName: String): DocumentFile? {
            if (!isSafeName(dstName)) return null
            if (src.isDirectory) {
                val safeDestinationName = uniqueChildName(dstParent, dstName)
                val dst = dstParent.createDirectory(safeDestinationName) ?: return null
                val children = runCatching { src.listFiles().toList() }.getOrElse {
                    dst.delete()
                    return null
                }
                for (child in children) {
                    val childName = child.name ?: run { dst.delete(); return null }
                    if (copyNode(child, dst, childName) == null) {
                        dst.delete()
                        return null
                    }
                }
                return dst
            }

            val safeDestinationName = uniqueChildName(dstParent, dstName)
            val dst = dstParent.createFile(src.type ?: "application/octet-stream", safeDestinationName) ?: return null
            return try {
                val input = context.contentResolver.openInputStream(src.uri) ?: error("Cannot read source")
                val output = context.contentResolver.openOutputStream(dst.uri, "wt") ?: error("Cannot write destination")
                input.use { from -> output.use { to -> from.copyTo(to) } }
                dst
            } catch (_: Exception) {
                runCatching { dst.delete() }
                null
            }
        }

        return copyNode(source, parent, name)?.uri
    }

    /**
     * Copy a normal filesystem file or directory to a SAF destination.
     * Uses streams for file contents and never assumes the destination has a filesystem path.
     */
    fun copyLocalFileToTree(
        context: Context,
        sourceFile: java.io.File,
        targetTreeUri: Uri,
        targetParentRelativePath: String = ""
    ): Uri? {
        if (!sourceFile.exists() || !sourceFile.canRead() || !isSafeName(sourceFile.name)) return null
        val parent = findByRelativePath(context, targetTreeUri, targetParentRelativePath)
            ?.takeIf { it.isDirectory } ?: return null
        val sourceRoot = runCatching { sourceFile.canonicalFile }.getOrNull() ?: return null
        val rootPath = sourceRoot.path.trimEnd(java.io.File.separatorChar)
        val visited = mutableSetOf<String>()

        fun copyNode(source: java.io.File, destination: DocumentFile): DocumentFile? {
            val canonical = runCatching { source.canonicalFile }.getOrNull() ?: return null
            if (canonical.path != rootPath && !canonical.path.startsWith(rootPath + java.io.File.separator)) return null
            if (!visited.add(canonical.path)) return null
            if (source.isDirectory) {
                val safeDestinationName = uniqueChildName(destination, source.name)
                val folder = destination.createDirectory(safeDestinationName) ?: return null
                val children = source.listFiles() ?: run { folder.delete(); return null }
                for (child in children) {
                    if (!isSafeName(child.name) || copyNode(child, folder) == null) {
                        folder.delete()
                        return null
                    }
                }
                return folder
            }
            val target = destination.createFile(
                android.webkit.MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(source.extension.lowercase()) ?: "application/octet-stream",
                uniqueChildName(destination, source.name)
            ) ?: return null
            return try {
                source.inputStream().use { input ->
                    val output = context.contentResolver.openOutputStream(target.uri, "wt")
                        ?: error("Cannot write destination")
                    output.use { input.copyTo(it) }
                }
                target
            } catch (_: Exception) {
                runCatching { target.delete() }
                null
            }
        }

        return copyNode(sourceFile, parent)?.uri
    }

    /** Move a SAF document only after its complete copy has succeeded. */
    fun moveDocumentToTree(
        context: Context,
        sourceUri: Uri,
        targetTreeUri: Uri,
        targetParentRelativePath: String = "",
        targetName: String? = null
    ): Uri? {
        if (sourceUri == targetTreeUri) return null
        val copied = copyDocumentToTree(context, sourceUri, targetTreeUri, targetParentRelativePath, targetName)
            ?: return null
        val deleted = delete(context, sourceUri)
        if (!deleted) {
            // Do not silently report a move when the provider refused source deletion.
            runCatching { document(context, copied)?.delete() }
            return null
        }
        return copied
    }

    fun exportDocument(context: Context, sourceUri: Uri, destinationUri: Uri): Boolean = try {
        val input = context.contentResolver.openInputStream(sourceUri) ?: error("Cannot read source")
        val output = context.contentResolver.openOutputStream(destinationUri, "wt") ?: error("Cannot write destination")
        input.use { src -> output.use { dst -> src.copyTo(dst) } }
        true
    } catch (_: Exception) {
        false
    }

    fun rename(context: Context, documentUri: Uri, newName: String): Uri? {
        if (!isSafeName(newName)) return null
        return runCatching {
            DocumentsContract.renameDocument(context.contentResolver, documentUri, newName)
        }.getOrNull()
    }

    fun delete(context: Context, uri: Uri): Boolean =
        runCatching { document(context, uri)?.delete() == true }.getOrDefault(false)

    private fun isSafeName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." &&
            !name.contains('/') && !name.contains('\\') && !name.any { it.code < 32 }
}
