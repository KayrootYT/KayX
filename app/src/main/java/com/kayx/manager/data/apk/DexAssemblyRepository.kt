package com.kayx.manager.data.apk

import org.jf.smali.Smali
import org.jf.smali.SmaliOptions
import java.io.File

/** Smali -> DEX assembly and safe replacement of one DEX entry in an APK. */
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
            require(temp.isFile && temp.length() in 112..DexRepository.MAX_DEX_BYTES) { "Assembler produced an invalid or oversized DEX" }
            DexRepository().inspect(temp).also { require(it.checksumValid) { "Assembler produced a DEX with an invalid checksum" } }
            if (outputDex.exists()) check(outputDex.delete()) { "Cannot replace previous DEX output" }
            check(temp.renameTo(outputDex)) { "Cannot finalize DEX output" }
            return outputDex
        } catch (t: Throwable) {
            temp.delete()
            throw t
        }
    }

    fun replaceDexInApk(sourceApk: File, dexEntryName: String, replacementDex: File, outputApk: File): File =
        ApkPatcher.replaceDex(sourceApk, dexEntryName, replacementDex, outputApk)
}
