package com.kayx.manager.data.filesystem

import com.kayx.manager.domain.model.FileItem
import com.kayx.manager.domain.model.SearchMode
import com.kayx.manager.domain.model.SortBy
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import java.util.LinkedHashMap

class FileRepository {

    private val searchEngine = FileSearchEngine()

    fun invalidateSearchCache() = searchEngine.invalidate()

    suspend fun search(
        dir: File, query: String, showHidden: Boolean = false,
        mode: SearchMode = SearchMode.CONTAINS, maxAgeMs: Long = 0L
    ): List<FileItem> = searchEngine.search(
        FileSearchRequest(
            root = dir,
            query = query,
            showHidden = showHidden,
            mode = mode,
            modifiedAfter = if (maxAgeMs > 0L) System.currentTimeMillis() - maxAgeMs else 0L
        )
    ).items

    suspend fun createFolder(parent: File, name: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val folder = File(parent, name)
            check(isSafeName(name)) { "Invalid folder name" }
            check(!folder.exists()) { "Folder already exists" }
            check(folder.mkdirs()) { "Could not create folder" }
        }
    }

    suspend fun createFile(parent: File, name: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            check(isSafeName(name)) { "Invalid file name" }
            val file = File(parent, name)
            check(!file.exists()) { "File already exists" }
            check(file.createNewFile()) { "Could not create file" }
        }
    }

    suspend fun delete(files: List<File>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            for (f in files) {
                if (f.exists()) check(f.deleteRecursively()) { "Could not delete ${f.name}" }
            }
        }
    }

    suspend fun rename(file: File, newName: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            check(isSafeName(newName)) { "Invalid file name" }
            val dest = File(file.parentFile, newName)
            check(!dest.exists()) { "Target name already exists" }
            check(file.renameTo(dest)) { "Rename failed" }
        }
    }

    suspend fun copy(files: List<File>, targetDir: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            for (f in files) {
                check(f.exists()) { "Source not found: ${f.name}" }
                val dest = File(targetDir, f.name)
                check(!dest.exists()) { "Already exists: ${f.name}" }
                if (f.isDirectory) {
                    val sourcePath = f.canonicalPath.trimEnd(File.separatorChar) + File.separator
                    val targetPath = dest.canonicalPath
                    check(!targetPath.startsWith(sourcePath)) { "Cannot copy a folder into itself" }
                    check(f.copyRecursively(dest, overwrite = false)) { "Copy failed: ${f.name}" }
                } else f.copyTo(dest, overwrite = false)
            }
        }
    }

    suspend fun move(files: List<File>, targetDir: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            for (f in files) {
                check(f.exists()) { "Source not found: ${f.name}" }
                val dest = File(targetDir, f.name)
                check(!dest.exists()) { "Already exists: ${f.name}" }
                if (f.isDirectory) {
                    val sourcePath = f.canonicalPath.trimEnd(File.separatorChar) + File.separator
                    val targetPath = dest.canonicalPath
                    check(!targetPath.startsWith(sourcePath)) { "Cannot move a folder into itself" }
                    check(f.copyRecursively(dest, overwrite = false)) { "Move copy failed: ${f.name}" }
                    check(f.deleteRecursively()) { "Could not remove source: ${f.name}" }
                } else {
                    f.copyTo(dest, overwrite = false)
                    check(f.delete()) { "Could not remove source: ${f.name}" }
                }
            }
        }
    }

    private fun isSafeName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." &&
            !name.contains('/') && !name.contains('\\') && !name.any { it.code < 32 }

    /** Reads until [buf] is full or EOF, so two streams are compared chunk-for-chunk. Returns -1 at EOF. */
    private fun readFully(input: java.io.InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = input.read(buf, total, buf.size - total)
            if (n < 0) break
            total += n
        }
        return if (total == 0) -1 else total
    }

    suspend fun compareFiles(file1: File, file2: File): ComparisonResult = withContext(Dispatchers.IO) {
        val sameSize = file1.length() == file2.length()
        val sameName = file1.name == file2.name
        var identicalContent = false

        if (sameSize) {
            identicalContent = try {
                file1.inputStream().use { input1 ->
                    file2.inputStream().use { input2 ->
                        val buf1 = ByteArray(64 * 1024)
                        val buf2 = ByteArray(64 * 1024)
                        var equal = true
                        while (equal) {
                            val r1 = readFully(input1, buf1)
                            val r2 = readFully(input2, buf2)
                            if (r1 != r2) { equal = false; break }
                            if (r1 == -1) break
                            if (!buf1.copyOf(r1).contentEquals(buf2.copyOf(r2))) {
                                equal = false
                                break
                            }
                        }
                        equal
                    }
                }
            } catch (e: Exception) {
                false
            }
        }

        ComparisonResult(
            file1Name = file1.name,
            file2Name = file2.name,
            file1Size = file1.length(),
            file2Size = file2.length(),
            sameSize = sameSize,
            sameName = sameName,
            identicalContent = identicalContent
        )
    }
}

data class ComparisonResult(
    val file1Name: String,
    val file2Name: String,
    val file1Size: Long,
    val file2Size: Long,
    val sameSize: Boolean,
    val sameName: Boolean,
    val identicalContent: Boolean
)
