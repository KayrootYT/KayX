package com.kayx.manager.data.apk

import com.kayx.manager.data.archive.ArchiveEdit
import java.io.File
import java.util.zip.ZipFile

/**
 * Replaces one DEX entry of an APK and drops stale v1 signature files.
 * The patched output is deliberately unsigned and must be signed before installation.
 */
object ApkPatcher {
    fun replaceDex(sourceApk: File, dexEntryName: String, replacementDex: File, outputApk: File): File {
        require(sourceApk.isFile && sourceApk.length() <= 2L * 1024 * 1024 * 1024) { "Source APK is missing or too large" }
        require(DexRepository.isDexEntryName(dexEntryName)) { "Invalid DEX entry name" }
        require(replacementDex.isFile && replacementDex.length() in 112..DexRepository.MAX_DEX_BYTES) { "Replacement DEX is invalid or too large" }
        require(sourceApk.canonicalFile != outputApk.canonicalFile) { "Output APK must be a different file than the source" }
        val replacement = replacementDex.readBytes()
        val info = DexRepository().inspect(replacementDex)
        require(info.checksumValid) { "Replacement DEX has an invalid checksum" }

        val signatureFiles = ZipFile(sourceApk).use { zip ->
            require(zip.getEntry(dexEntryName) != null) { "DEX entry does not exist in source APK" }
            zip.entries().asSequence().map { it.name }.filter(::isV1SignatureFile).toSet()
        }
        outputApk.parentFile?.let { check(it.exists() || it.mkdirs()) { "Cannot create output folder" } }
        val temp = File(outputApk.parentFile, outputApk.name + ".tmp")
        val backup = File(outputApk.parentFile, outputApk.name + ".bak")
        temp.delete()
        backup.delete()
        try {
            sourceApk.copyTo(temp, overwrite = true)
            ArchiveEdit.rewrite(temp, delete = signatureFiles, replace = mapOf(dexEntryName to replacement)).getOrThrow()

            ZipFile(temp).use { rebuilt ->
                val dex = rebuilt.getEntry(dexEntryName) ?: error("Patched APK is missing the rebuilt DEX")
                check(rebuilt.getEntry("AndroidManifest.xml") != null) { "Patched APK is missing AndroidManifest.xml" }
                check(rebuilt.getInputStream(dex).use { it.readBytes().contentEquals(replacement) }) {
                    "Patched DEX bytes do not match the replacement"
                }
                check(signatureFiles.none { rebuilt.getEntry(it) != null }) { "Stale v1 signature entry remains in patched APK" }
            }

            val hadOutput = outputApk.exists()
            if (hadOutput) check(outputApk.renameTo(backup)) { "Could not preserve previous output APK" }
            try {
                check(temp.renameTo(outputApk)) { "Could not finalize patched APK" }
            } catch (t: Throwable) {
                if (hadOutput) check(backup.renameTo(outputApk)) { "Could not restore previous output APK" }
                throw t
            }
            backup.delete()
            return outputApk
        } catch (t: Throwable) {
            temp.delete()
            if (!outputApk.exists() && backup.exists()) backup.renameTo(outputApk)
            throw t
        } finally {
            temp.delete()
            if (outputApk.exists()) backup.delete()
        }
    }

    internal fun isV1SignatureFile(name: String): Boolean {
        if (!name.startsWith("META-INF/", ignoreCase = true)) return false
        val leaf = name.substringAfterLast('/').uppercase()
        if (name.count { it == '/' } != 1) return false
        return leaf == "MANIFEST.MF" || leaf.endsWith(".SF") || leaf.endsWith(".RSA") || leaf.endsWith(".DSA") || leaf.endsWith(".EC")
    }
}
