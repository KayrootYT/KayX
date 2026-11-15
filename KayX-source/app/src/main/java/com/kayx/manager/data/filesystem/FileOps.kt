package com.kayx.manager.data.filesystem

import android.system.Os
import com.kayx.manager.domain.model.FileItem
import java.io.File

enum class Conflict { REPLACE, KEEP_BOTH, SKIP }

/** MT-Manager-style file helpers: permissions, symlinks, conflicts, batch rename, content search. */
object FileOps {

    fun sizeAndCount(f: File): Pair<Long, Int> {
        if (f.isFile) return f.length() to 1
        var size = 0L; var n = 0
        f.walkTopDown().forEach { if (it != f) { n++; if (it.isFile) size += it.length() } }
        return size to n
    }

    fun modeOf(f: File): Int? = runCatching { Os.stat(f.absolutePath).st_mode and 0xFFF }.getOrNull()

    fun modeString(mode: Int): String {
        val sb = StringBuilder()
        val chars = "rwxrwxrwx"
        for (i in 0 until 9) sb.append(if (mode and (1 shl (8 - i)) != 0) chars[i] else '-')
        return sb.toString()
    }

    fun chmod(f: File, mode: Int, recursive: Boolean) {
        if (recursive && f.isDirectory) f.walkTopDown().forEach { Os.chmod(it.absolutePath, mode) }
        else Os.chmod(f.absolutePath, mode)
    }

    fun symlink(target: File, link: File) {
        check(!link.exists()) { "${link.name} already exists" }
        Os.symlink(target.absolutePath, link.absolutePath)
    }

    fun uniqueName(dir: File, name: String): File {
        var t = File(dir, name)
        if (!t.exists()) return t
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (t.exists()) { t = File(dir, "$base ($i)$ext"); i++ }
        return t
    }

    fun conflicts(files: List<File>, dst: File): List<String> = files.filter {
        val d = File(dst, it.name)
        d.exists() && d.canonicalPath != it.canonicalPath
    }.map { it.name }

    private fun copyAny(src: File, dest: File) {
        if (src.isDirectory) check(src.copyRecursively(dest, overwrite = false)) { "Copy failed: ${src.name}" }
        else src.copyTo(dest, overwrite = false)
    }

    /** Copy or move [files] into [dst] using [policy] for name conflicts. Returns number of items processed. */
    fun transfer(
        files: List<File>, dst: File, move: Boolean, policy: Conflict,
        onProgress: (completed: Int, total: Int, currentName: String) -> Unit = { _, _, _ -> }
    ): Result<Int> = runCatching {
        var n = 0
        val total = files.size
        for ((index, f) in files.withIndex()) {
            onProgress(index, total, f.name)
            check(f.exists()) { "Source not found: ${f.name}" }
            var dest = File(dst, f.name)
            val same = dest.exists() && dest.canonicalPath == f.canonicalPath
            if (same && move) continue
            if (dest.exists()) {
                when {
                    same -> dest = uniqueName(dst, f.name)
                    policy == Conflict.SKIP -> continue
                    policy == Conflict.KEEP_BOTH -> dest = uniqueName(dst, f.name)
                    else -> check(dest.deleteRecursively()) { "Could not replace ${f.name}" }
                }
            }
            if (f.isDirectory) {
                val src = f.canonicalPath.trimEnd(File.separatorChar) + File.separator
                check(!dest.canonicalPath.startsWith(src)) { "Cannot copy a folder into itself" }
            }
            if (move) {
                if (!f.renameTo(dest)) { copyAny(f, dest); check(f.deleteRecursively()) { "Could not remove source: ${f.name}" } }
            } else copyAny(f, dest)
            n++
            onProgress(index + 1, total, f.name)
        }
        n
    }

    fun duplicate(files: List<File>): Result<Unit> = runCatching {
        for (f in files) copyAny(f, uniqueName(f.parentFile ?: f, f.name))
    }

    fun globToRegex(glob: String): Regex {
        val sb = StringBuilder()
        for (c in glob) when (c) {
            '*' -> sb.append(".*"); '?' -> sb.append('.')
            '.', '(', ')', '+', '|', '^', '$', '@', '%', '{', '}', '[', ']', '\\' -> sb.append('\\').append(c)
            else -> sb.append(c)
        }
        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
    }

    /**
     * Rename [files] in one go. If [find] is blank, [replace] is a template: {n} counter, {name} base name, {ext} extension with dot.
     * Otherwise [find] is replaced by [replace] (literal or regex); {n}/{name}/{ext} also work in [replace].
     */
    fun batchRename(files: List<File>, find: String, replace: String, regex: Boolean, start: Int): Result<Unit> = runCatching {
        require(replace.isNotEmpty() || find.isNotEmpty()) { "Nothing to do" }
        val rx = if (find.isNotEmpty() && regex) Regex(find) else null
        val plan = files.mapIndexed { i, f ->
            val base = f.nameWithoutExtension
            val ext = if (f.extension.isEmpty()) "" else "." + f.extension
            fun tpl(s: String) = s.replace("{n}", (start + i).toString()).replace("{name}", base).replace("{ext}", ext)
            val newName = when {
                find.isBlank() -> tpl(replace)
                rx != null -> rx.replace(f.name, tpl(replace))
                else -> f.name.replace(find, tpl(replace))
            }
            f to newName
        }
        require(plan.all { it.second.isNotBlank() && !it.second.contains('/') && it.second != "." && it.second != ".." }) { "Invalid resulting name" }
        require(plan.map { it.second }.toSet().size == plan.size) { "Resulting names are not unique" }
        val sources = files.map { it.absolutePath }.toSet()
        for ((f, nn) in plan) {
            val d = File(f.parentFile, nn)
            check(!d.exists() || d.absolutePath in sources) { "$nn already exists" }
        }
        // two-phase to survive swaps
        val temps = plan.map { (f, nn) -> val t = File(f.parentFile, ".kayx-rn-" + System.nanoTime() + "-" + f.name); check(f.renameTo(t)) { "Rename failed: ${f.name}" }; t to File(f.parentFile, nn) }
        for ((t, d) in temps) check(t.renameTo(d)) { "Rename failed: ${d.name}" }
    }

    /** Search inside text files (up to 2 MB each) below [dir]. */
    fun grep(dir: File, query: String, showHidden: Boolean, ignoreCase: Boolean = true, limit: Int = 300, isActive: () -> Boolean = { true }): List<FileItem> {
        val out = ArrayList<FileItem>()
        if (query.isBlank()) return out
        for (f in dir.walkTopDown().maxDepth(8).onEnter { showHidden || !it.isHidden || it == dir }) {
            if (out.size >= limit || !isActive()) break
            if (!f.isFile || f.length() > 2_000_000 || (!showHidden && f.isHidden)) continue
            val hit = runCatching {
                val binary = f.inputStream().use { ins ->
                    val head = ByteArray(1024); val r = ins.read(head)
                    r > 0 && head.take(r).any { it == 0.toByte() }
                }
                !binary && f.bufferedReader().useLines { lines -> lines.any { it.contains(query, ignoreCase) } }
            }.getOrDefault(false)
            if (hit) out += FileItem(f.absolutePath, f.name, false, f.length(), f.lastModified(), f.isHidden)
        }
        return out
    }
}
