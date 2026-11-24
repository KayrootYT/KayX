package com.kayx.manager.data.filesystem

import android.system.Os
import com.kayx.manager.domain.model.FileItem
import java.io.File

enum class Conflict { REPLACE, KEEP_BOTH, SKIP }

/** MT-Manager-style file helpers: permissions, symlinks, conflicts, batch rename, content search. */
object FileOps {
    enum class TransferState { RUNNING, PAUSED, CANCELLED, FAILED, COMPLETE }

    data class TransferControl(
        @Volatile var state: TransferState = TransferState.RUNNING,
        @Volatile var retryCount: Int = 0
    ) {
        fun pause() { if (state == TransferState.RUNNING) state = TransferState.PAUSED }
        fun resume() { if (state == TransferState.PAUSED) state = TransferState.RUNNING }
        fun cancel() { state = TransferState.CANCELLED }
        fun isStopped(): Boolean = state == TransferState.CANCELLED
    }

    data class TransferProgress(
        val completedItems: Int,
        val totalItems: Int,
        val completedBytes: Long,
        val totalBytes: Long,
        val currentName: String,
        val state: TransferState
    )

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

    private val compoundExts = listOf(".tar.gz", ".tar.bz2", ".tar.xz", ".tar.zst", ".tar.lz")

    fun uniqueName(dir: File, name: String): File {
        var t = File(dir, name)
        if (!t.exists()) return t
        val compound = compoundExts.firstOrNull { name.lowercase().endsWith(it) && name.length > it.length }
        val dot = if (compound != null) name.length - compound.length else name.lastIndexOf('.')
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

    private fun copyAny(src: File, dest: File) = copyAny(src, dest, TransferControl())

    private fun awaitTransfer(control: TransferControl) {
        while (control.state == TransferState.PAUSED) Thread.sleep(50)
        check(control.state != TransferState.CANCELLED) { "Transfer cancelled" }
    }

    /** Stream files in bounded chunks so pause/cancel also works during a single large file. */
    private fun copyFileControlled(src: File, dest: File, control: TransferControl) {
        dest.parentFile?.let { check(it.exists() || it.mkdirs()) { "Cannot create destination folder: ${it.path}" } }
        java.io.FileInputStream(src).use { input ->
            java.io.FileOutputStream(dest, false).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    awaitTransfer(control)
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
        }
    }

    private fun copyAny(src: File, dest: File, control: TransferControl) {
        awaitTransfer(control)
        val lexicalPath = src.absolutePath
        check(src.canonicalPath == lexicalPath) { "Refusing to follow symbolic link: ${src.name}" }
        if (src.isDirectory) {
            check(dest.mkdir()) { "Cannot create folder: ${dest.name}" }
            val children = src.listFiles() ?: error("Cannot read folder: ${src.name}")
            for (child in children) {
                awaitTransfer(control)
                copyAny(child, File(dest, child.name), control)
            }
        } else copyFileControlled(src, dest, control)
    }

    /** Like [copyAny], but removes a half-written destination when the copy fails. */
    private fun copyClean(src: File, dest: File) {
        try { copyAny(src, dest) } catch (e: Exception) { dest.deleteRecursively(); throw e }
    }

    private fun copyClean(src: File, dest: File, control: TransferControl) {
        try { copyAny(src, dest, control) } catch (e: Exception) { dest.deleteRecursively(); throw e }
    }

    /** Copy or move [files] into [dst] using [policy] for name conflicts. Returns number of items processed. */
    fun transfer(
        files: List<File>, dst: File, move: Boolean, policy: Conflict,
        onProgress: (completed: Int, total: Int, currentName: String) -> Unit = { _, _, _ -> },
        control: TransferControl = TransferControl()
    ): Result<Int> = runCatching {
        var n = 0
        val total = files.size
        for ((index, f) in files.withIndex()) {
            while (control.state == TransferState.PAUSED) Thread.sleep(50)
            check(control.state != TransferState.CANCELLED) { "Transfer cancelled" }
            onProgress(index, total, f.name)
            check(f.exists()) { "Source not found: ${f.name}" }
            var dest = File(dst, f.name)
            var replaceBackup: File? = null
            if (f.isDirectory) {
                // must run before any conflict handling: "replace" deletes the destination first
                val src = f.canonicalPath.trimEnd(File.separatorChar) + File.separator
                check(!(dst.canonicalPath.trimEnd(File.separatorChar) + File.separator).startsWith(src)) { "Cannot copy a folder into itself" }
            }
            val same = dest.exists() && dest.canonicalPath == f.canonicalPath
            if (same && move) continue
            if (dest.exists()) {
                when {
                    same -> dest = uniqueName(dst, f.name)
                    policy == Conflict.SKIP -> { onProgress(index + 1, total, f.name); continue }
                    policy == Conflict.KEEP_BOTH -> dest = uniqueName(dst, f.name)
                    else -> {
                        val backup = File(dst, ".kayx-replace-${System.nanoTime()}-${dest.name}")
                        check(!backup.exists()) { "Could not create replacement backup" }
                        check(dest.renameTo(backup)) { "Could not stage existing ${f.name}" }
                        replaceBackup = backup
                    }
                }
            }
            var preserveCompletedCopy = false
            try {
                if (move) {
                    if (!f.renameTo(dest)) {
                        copyClean(f, dest, control)
                        // Once the full destination copy exists, never delete it merely because
                        // source cleanup failed: recursive deletion can fail after partial progress.
                        preserveCompletedCopy = true
                        if (!f.deleteRecursively()) {
                            replaceBackup?.let { backup ->
                                if (backup.exists()) {
                                    val preservedOld = uniqueName(dst, f.name)
                                    check(backup.renameTo(preservedOld)) { "Copied destination, but could not preserve the replaced item" }
                                }
                            }
                            error("Copied ${f.name}, but source cleanup failed; destination copy was retained")
                        }
                    }
                } else copyClean(f, dest, control)
                replaceBackup?.deleteRecursively()
            } catch (e: Exception) {
                if (!preserveCompletedCopy) dest.deleteRecursively()
                replaceBackup?.let { backup ->
                    if (backup.exists()) {
                        val restoreTarget = if (preserveCompletedCopy) uniqueName(dst, f.name) else dest
                        check(backup.renameTo(restoreTarget)) { "Transfer failed and existing file could not be restored" }
                    }
                }
                throw e
            }
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
            fun tpl(s: String, quote: Boolean = false): String {
                fun q(v: String) = if (quote) java.util.regex.Matcher.quoteReplacement(v) else v
                return s.replace("{n}", (start + i).toString()).replace("{name}", q(base)).replace("{ext}", q(ext))
            }
            val newName = when {
                find.isBlank() -> tpl(replace)
                rx != null -> rx.replace(f.name, tpl(replace, true))
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
        val done = ArrayList<Triple<File, File, File>>() // temp, final, original
        val finished = HashSet<File>()
        try {
            for ((idx, pair) in plan.withIndex()) {
                val (f, nn) = pair
                val t = File(f.parentFile, ".kayx-rn-${System.nanoTime()}-$idx")
                check(f.renameTo(t)) { "Rename failed: ${f.name}" }
                done.add(Triple(t, File(f.parentFile, nn), f))
            }
            for (x in done) { check(x.first.renameTo(x.second)) { "Rename failed: ${x.second.name}" }; finished.add(x.second) }
        } catch (e: Exception) {
            // undo: nothing may stay behind under a temporary name
            for (x in done) if (x.second in finished) x.second.renameTo(x.first)
            for (x in done) x.first.renameTo(x.third)
            throw e
        }
    }


}
