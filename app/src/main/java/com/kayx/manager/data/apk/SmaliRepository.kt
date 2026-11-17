package com.kayx.manager.data.apk

import org.jf.baksmali.Baksmali
import org.jf.baksmali.BaksmaliOptions
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.DexFile
import org.jf.dexlib2.DexFileFactory
import java.io.File

/** DEX -> Smali workspace backed by the open-source smali/baksmali toolchain. */
class SmaliRepository {
    fun disassemble(dexFile: File, outputDir: File): File {
        require(dexFile.isFile && dexFile.length() >= 112) { "DEX file is missing or invalid" }
        require(dexFile.length() <= 128L * 1024 * 1024) { "DEX file exceeds the 128 MB safety limit" }
        if (outputDir.exists()) outputDir.deleteRecursively()
        check(outputDir.mkdirs()) { "Cannot create Smali output directory" }
        val dex: DexFile = DexFileFactory.loadDexFile(dexFile, Opcodes.getDefault())
        val options = BaksmaliOptions().apply { apiLevel = 35 }
        val ok = Baksmali.disassembleDexFile(dex, outputDir, Runtime.getRuntime().availableProcessors().coerceIn(1, 4), options)
        check(ok) { "Baksmali could not disassemble this DEX file" }
        return outputDir
    }

    fun listSmali(root: File): List<File> {
        val canonicalRoot = root.canonicalFile
        return root.walkTopDown().filter { it.isFile && it.extension == "smali" }
            .filter { it.canonicalFile.toPath().startsWith(canonicalRoot.toPath()) }
            .sortedBy { it.relativeTo(root).path.lowercase() }.toList()
    }

    fun read(file: File, root: File): String {
        requireInside(file, root)
        require(file.isFile && file.length() <= 4L * 1024 * 1024) { "Smali file is missing or larger than 4 MB" }
        return file.readText(Charsets.UTF_8)
    }

    fun save(file: File, root: File, content: String) {
        requireInside(file, root)
        require(file.extension == "smali") { "Only .smali files can be edited here" }
        require(content.toByteArray(Charsets.UTF_8).size <= 4 * 1024 * 1024) { "Smali file exceeds 4 MB" }
        check(file.isFile) { "Smali file no longer exists" }
        val backup = File(file.parentFile, file.name + ".bak")
        file.copyTo(backup, overwrite = true)
        file.writeText(content, Charsets.UTF_8)
    }

    private fun requireInside(file: File, root: File) {
        val base = root.canonicalFile.toPath()
        val target = file.canonicalFile.toPath()
        require(target.startsWith(base)) { "Path is outside the Smali workspace" }
    }
}
