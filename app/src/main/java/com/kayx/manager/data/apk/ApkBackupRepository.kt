package com.kayx.manager.data.apk

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.UUID

/**
 * Creates non-overwriting, crash-resistant backups of installed APK payloads.
 * A staged backup is only published after every APK (including splits) is synced.
 */
object ApkBackupRepository {
    private const val MAX_SINGLE_APK_BYTES = 2L shl 30
    private const val BUFFER_SIZE = 64 * 1024

    fun backup(
        appLabel: String,
        versionName: String,
        apkPaths: List<String>,
        destinationRoot: File
    ): Result<File> = runCatching {
        require(apkPaths.isNotEmpty()) { "No APK files were provided for backup" }
        val sources = apkPaths.map { File(it).canonicalFile }
        sources.forEach { source ->
            require(source.isFile && source.canRead()) { "APK source is missing or unreadable: ${source.name}" }
            require(source.length() in 1..MAX_SINGLE_APK_BYTES) { "APK source has an invalid or unsupported size: ${source.name}" }
        }

        val root = destinationRoot.canonicalFile
        if (!root.exists()) require(root.mkdirs()) { "Cannot create APK backup directory" }
        require(root.isDirectory && root.canWrite()) { "APK backup destination is not writable" }

        val baseName = sanitizeSegment("${appLabel}_${versionName}")
        if (sources.size == 1) {
            // A single APK is published as a file, not a directory with an .apk suffix.
            val target = uniqueTarget(root, baseName, ".apk")
            val stagedFile = File(root, ".kayx-apk-tmp-${UUID.randomUUID()}.apk")
            try {
                copyAndSync(sources.single(), stagedFile)
                check(!target.exists()) { "Backup destination already exists" }
                Files.move(stagedFile.toPath(), target.toPath())
                target
            } finally {
                stagedFile.delete()
            }
        } else {
            val target = uniqueTarget(root, baseName, "")
            val staging = File(root, ".kayx-apk-tmp-${UUID.randomUUID()}")
            require(staging.mkdir()) { "Cannot create temporary APK backup directory" }
            try {
                val usedNames = mutableSetOf<String>()
                sources.forEachIndexed { index, source ->
                    val original = sanitizeSegment(source.name.ifBlank { "split_${index + 1}.apk" })
                    val name = uniqueFileName(original, usedNames)
                    copyAndSync(source, File(staging, name))
                }
                check(!target.exists()) { "Backup destination already exists" }
                Files.move(staging.toPath(), target.toPath())
                target
            } catch (t: Throwable) {
                staging.deleteRecursively()
                throw t
            }
        }
    }

    private fun copyAndSync(source: File, target: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(target, false).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var copied = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    require(count.toLong() <= MAX_SINGLE_APK_BYTES - copied) {
                        "APK grew beyond the supported backup size"
                    }
                    output.write(buffer, 0, count)
                    copied += count
                }
                output.flush()
                output.fd.sync()
                require(copied == source.length()) { "APK changed while it was being backed up" }
            }
        }
    }

    private fun uniqueTarget(root: File, baseName: String, extension: String): File {
        var candidate = File(root, "$baseName$extension")
        var suffix = 2
        while (candidate.exists()) {
            candidate = File(root, "$baseName ($suffix)$extension")
            suffix++
        }
        return candidate
    }

    private fun uniqueFileName(original: String, used: MutableSet<String>): String {
        val name = if (original.endsWith(".apk", true)) original else "$original.apk"
        if (used.add(name.lowercase())) return name
        val stem = name.substringBeforeLast('.')
        val extension = name.substringAfterLast('.', "apk")
        var suffix = 2
        while (true) {
            val candidate = "$stem ($suffix).$extension"
            if (used.add(candidate.lowercase())) return candidate
            suffix++
        }
    }

    private fun sanitizeSegment(value: String): String {
        val cleaned = value.trim()
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .replace(Regex("\\s+"), "_")
            .trim('.', ' ')
            .take(100)
        return cleaned.ifBlank { "app_backup" }
    }
}
