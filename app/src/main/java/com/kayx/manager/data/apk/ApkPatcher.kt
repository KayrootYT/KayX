package com.kayx.manager.data.apk

import com.kayx.manager.data.archive.ArchiveEdit
import java.io.File
import java.util.zip.ZipFile

/**
 * Replaces one DEX entry of an APK and drops the now-invalid v1 signature files.
 *
 * The rewrite keeps STORED entries and their Android alignment requirements intact.
 * The result is deliberately unsigned; sign it afterwards.
 */
object ApkPatcher {
    fun replaceDex(sourceApk: File, dexEntryName: String, replacementDex: File, outputApk: File): File {
        require(sourceApk.isFile && sourceApk.length() <= 2L * 1024 * 1024 * 1024) { "Source APK is missing or too large" }
        require(DexRepository.isDexEntryName(dexEntryName)) { "Invalid DEX entry name" }
        require(replacementDex.isFile && replacementDex.length() in 112..DexRepository.MAX_DEX_BYTES) { "Replacement DEX is invalid or too large" }
        require(sourceApk.canonicalFile != outputApk.canonicalFile) { "Output APK must be a different file than the source" }
        val info = DexRepository().inspect(replacementDex)
        require(info.checksumValid) { "Replacement DEX has an invalid checksum" }

        val signatureFiles = ZipFile(sourceApk).use { zip ->
            require(zip.getEntry(dexEntryName) != null) { "DEX entry does not exist in source APK" }
            zip.entries().asSequence().map { it.name }.filter(::isV1SignatureFile).toSet()
        }
        outputApk.parentFile?.let { check(it.exists() || it.mkdirs()) { "Cannot create output folder" } }
        val temp = File(outputApk.parentFile, outputApk.name + ".tmp")
        temp.delete()
        try {
            sourceApk.copyTo(temp, overwrite = true)
            ArchiveEdit.rewrite(temp, delete = signatureFiles, replace = mapOf(dexEntryName to replacementDex.readBytes())).getOrThrow()
            ZipFile(temp).use { rebuilt ->
                check(rebuilt.getEntry(dexEntryName) != null) { "Patched APK is missing the rebuilt DEX" }
                check(rebuilt.getEntry("AndroidManifest.xml") != null) { "Patched APK is missing AndroidManifest.xml" }
            }
            if (outputApk.exists()) check(outputApk.delete()) { "Cannot replace previous APK output" }
            check(temp.renameTo(outputApk)) { "Cannot finalize patched APK" }
            return outputApk
        } catch (t: Throwable) {
            temp.delete()
            throw t
        }
    }

    internal fun isV1SignatureFile(name: String): Boolean {
        if (!name.startsWith("META-INF/", ignoreCase = true)) return false
        val leaf = name.substringAfterLast('/').uppercase()
        if (name.count { it == '/' } != 1) return false
        return leaf == "MANIFEST.MF" || leaf.endsWith(".SF") || leaf.endsWith(".RSA") || leaf.endsWith(".DSA") || leaf.endsWith(".EC")
    }
}
