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
        val target = parent.createFile(mime, targetName) ?: return null
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
