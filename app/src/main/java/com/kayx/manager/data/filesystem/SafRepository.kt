package com.kayx.manager.data.filesystem

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

/** Android Storage Access Framework support for document trees such as SD cards and protected folders. */
object SafRepository {
    private const val PREFS = "kayx_saf"
    private const val TREE_URIS = "tree_uris"

    fun persistTreePermission(context: Context, uri: Uri) {
        val resolver = context.contentResolver
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { resolver.takePersistableUriPermission(uri, flags) }

        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getStringSet(TREE_URIS, emptySet()).orEmpty().toMutableSet()
        saved += uri.toString()
        prefs.edit().putStringSet(TREE_URIS, saved).apply()
    }

    fun persistedTrees(context: Context): List<Uri> {
        val granted = context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission && it.isWritePermission }
            .map { it.uri }
            .associateBy { it.toString() }

        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet(TREE_URIS, emptySet()).orEmpty()
            .mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
            .filter { granted[it.toString()] != null }
    }

    fun removeTree(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getStringSet(TREE_URIS, emptySet()).orEmpty().toMutableSet()
        saved -= uri.toString()
        prefs.edit().putStringSet(TREE_URIS, saved).apply()
    }

    fun document(context: Context, uri: Uri): DocumentFile? =
        if (DocumentsContract.isDocumentUri(context, uri)) DocumentFile.fromSingleUri(context, uri)
        else DocumentFile.fromTreeUri(context, uri)

    fun children(context: Context, treeUri: Uri): List<DocumentFile> =
        document(context, treeUri)?.listFiles()?.toList().orEmpty()

    fun findByRelativePath(context: Context, treeUri: Uri, relativePath: String): DocumentFile? {
        var current = document(context, treeUri) ?: return null
        if (relativePath.isBlank()) return current
        for (part in relativePath.trim('/').split('/').filter { it.isNotBlank() }) {
            current = current.findFile(part) ?: return null
        }
        return current
    }

    fun createTextFile(context: Context, parent: Uri, name: String, mime: String = "text/plain"): Uri? =
        document(context, parent)?.createFile(mime, name)?.uri

    fun delete(context: Context, uri: Uri): Boolean =
        document(context, uri)?.delete() == true
}
