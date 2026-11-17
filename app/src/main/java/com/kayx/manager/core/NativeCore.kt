package com.kayx.manager.core

/**
 * Bridge to the Rust core (rust/kayx-core -> libkayx_core.so).
 * Every call returns null when the library is missing or the native call fails,
 * so callers keep a pure-Kotlin fallback and the app never crashes because of it.
 */
object NativeCore {
    val available: Boolean = try { System.loadLibrary("kayx_core"); true } catch (_: Throwable) { false }

    @JvmStatic private external fun hashFile(path: String): String?
    @JvmStatic private external fun decodeAxml(data: ByteArray): String?
    @JvmStatic private external fun scanTree(root: String): Array<String>?
    @JvmStatic private external fun findDuplicates(paths: Array<String>): Array<String>?
    @JvmStatic private external fun hexDump(path: String, offset: Long, length: Int, perRow: Int): String?

    /** [md5, sha1, sha256, crc32] or null. */
    fun hashes(path: String): List<String>? =
        if (!available) null else runCatching { hashFile(path)?.split('|')?.takeIf { it.size == 4 } }.getOrNull()

    fun axmlToXml(data: ByteArray): String? =
        if (!available) null else runCatching { decodeAxml(data) }.getOrNull()

    /** Lines "size\tmtimeMs\tpath" for every file under [root], or null. */
    fun scan(root: String): Array<String>? =
        if (!available) null else runCatching { scanTree(root) }.getOrNull()

    /** Groups of identical files (each group = paths joined by U+001F), or null. */
    fun duplicateGroups(paths: Array<String>): Array<String>? =
        if (!available) null else runCatching { findDuplicates(paths) }.getOrNull()

    fun hex(path: String, offset: Long, length: Int, perRow: Int): String? =
        if (!available) null else runCatching { hexDump(path, offset, length, perRow) }.getOrNull()
}
