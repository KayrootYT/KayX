package com.kayx.manager.data.apk

import org.jf.smali.Smali
import org.jf.smali.SmaliOptions
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Smali -> DEX assembly and safe replacement of one DEX entry in an APK.
 * The patched APK is deliberately left unsigned; Android requires signing before install.
 */
class DexAssemblyRepository {
    fun assemble(smaliRoot: File, outputDex: File, apiLevel: Int = 35): File {
        require(smaliRoot.isDirectory) { "Smali workspace not found" }
        val root = smaliRoot.canonicalFile.toPath()
        val sources = smaliRoot.walkTopDown()
            .filter { it.isFile && it.extension == "smali" }
            .onEach { require(it.canonicalFile.toPath().startsWith(root)) { "Smali path escapes workspace" } }
            .map { it.absolutePath }
            .toList()
        require(sources.isNotEmpty()) { "No .smali files found" }
        require(sources.size <= 50000) { "Workspace contains too many Smali files" }
        outputDex.parentFile?.let { check(it.exists() || it.mkdirs()) { "Cannot create output folder" } }
        val temp = File(outputDex.parentFile, outputDex.name + ".tmp")
        temp.delete()
        val options = SmaliOptions().apply {
            this.apiLevel = apiLevel
            this.outputDexFile = temp.absolutePath
        }
        try {
            check(Smali.assemble(options, sources)) { "Smali assembly failed; check syntax and references" }
            require(temp.isFile && temp.length() in 112..(128L * 1024 * 1024)) { "Assembler produced an invalid or oversized DEX" }
            if (outputDex.exists()) check(outputDex.delete()) { "Cannot replace previous DEX output" }
            check(temp.renameTo(outputDex)) { "Cannot finalize DEX output" }
            return outputDex
        } catch (t: Throwable) {
            temp.delete()
            throw t
        }
    }

    fun replaceDexInApk(sourceApk: File, dexEntryName: String, replacementDex: File, outputApk: File): File {
        require(sourceApk.isFile && sourceApk.length() <= 2L * 1024 * 1024 * 1024) { "Source APK is missing or too large" }
        require(dexEntryName.matches(Regex("classes(\\d+)?\\.dex"))) { "Invalid DEX entry name" }
        require(replacementDex.isFile && replacementDex.length() in 112..(128L * 1024 * 1024)) { "Replacement DEX is invalid or too large" }
        DexRepository().inspect(replacementDex)
        ZipFile(sourceApk).use { zip ->
            require(zip.getEntry(dexEntryName) != null) { "DEX entry does not exist in source APK" }
            val temp = File(outputApk.parentFile, outputApk.name + ".tmp")
            temp.parentFile?.let { check(it.exists() || it.mkdirs()) { "Cannot create output folder" } }
            temp.delete()
            try {
                ZipOutputStream(FileOutputStream(temp)).use { out ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.name == dexEntryName || isV1SignatureFile(entry.name)) continue
                        if (entry.isDirectory) continue
                        val copy = ZipEntry(entry.name).apply {
                            time = entry.time
                            comment = entry.comment
                        }
                        out.putNextEntry(copy)
                        zip.getInputStream(entry).use { input -> input.copyTo(out) }
                        out.closeEntry()
                    }
                    out.putNextEntry(ZipEntry(dexEntryName))
                    FileInputStream(replacementDex).use { it.copyTo(out) }
                    out.closeEntry()
                }
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
    }

    private fun isV1SignatureFile(name: String): Boolean {
        if (!name.startsWith("META-INF/", ignoreCase = true)) return false
        val leaf = name.substringAfterLast('/').uppercase()
        return leaf == "MANIFEST.MF" || leaf.endsWith(".SF") || leaf.endsWith(".RSA") || leaf.endsWith(".DSA") || leaf.endsWith(".EC")
    }
}
