package com.kayx.manager.data.filesystem

import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * A filesystem-independent address for a browsable directory.
 *
 * Local locations keep their canonical filesystem path. SAF locations keep the
 * persisted tree URI and a path relative to that tree; a document URI is never
 * treated as a filesystem path.
 */
sealed class StorageLocation {
    data class Local(val directory: File) : StorageLocation()
    data class Saf(val treeUri: String, val relativePath: String = "") : StorageLocation() {
        init {
            require(treeUri.isNotBlank()) { "SAF tree URI is required" }
            require(isSafeRelativePath(relativePath)) { "Invalid SAF relative path" }
        }
    }

    fun stableKey(): String = when (this) {
        is Local -> "local:${directory.absoluteFile.normalize().path}"
        is Saf -> "saf://location/${encode(treeUri)}?path=${encode(normalizeRelativePath(relativePath))}"
    }

    fun displayName(): String = when (this) {
        is Local -> directory.name.ifBlank { directory.path }
        is Saf -> normalizeRelativePath(relativePath).substringAfterLast('/').ifBlank { "Storage" }
    }

    fun child(name: String): StorageLocation = when (this) {
        is Local -> Local(File(directory, name))
        is Saf -> Saf(treeUri, listOf(normalizeRelativePath(relativePath), name).filter { it.isNotBlank() }.joinToString("/"))
    }

    companion object {
        fun fromStableKey(key: String): StorageLocation? = runCatching {
            when {
                key.startsWith("local:") -> Local(File(key.removePrefix("local:")))
                key.startsWith("saf://location/") -> {
                    val uri = URI(key)
                    val encodedTree = uri.rawPath.removePrefix("/").takeIf { it.isNotBlank() } ?: return null
                    val tree = decode(encodedTree)
                    val relative = uri.rawQuery.orEmpty().split('&')
                        .firstOrNull { it.startsWith("path=") }
                        ?.substringAfter("path=")?.let(::decode).orEmpty()
                    Saf(tree, relative)
                }
                else -> null
            }
        }.getOrNull()

        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
        private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")

        private fun normalizeRelativePath(path: String): String =
            path.trim('/').split('/').filter { it.isNotBlank() }.joinToString("/")

        private fun isSafeRelativePath(path: String): Boolean {
            if (path.any { it.code < 32 || it == '\\u0000' }) return false
            return path.split('/').none { it == "." || it == ".." || it.contains('\\\\') }
        }
    }
}
