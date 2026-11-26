package com.kayx.manager.data.storage

import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Walks a directory without following symbolic links and with a fixed worker limit.
 * Each top-level subtree is an independent task, so the number of active traversals never
 * exceeds [parallelism]. Interrupting the caller cancels outstanding work and stops visitors.
 */
internal fun walkFilesBounded(
    root: File,
    maxDepth: Int = 20,
    parallelism: Int = 4,
    onFileFound: (File) -> Unit = {},
    includeDirectory: (File) -> Boolean = { true }
): List<File> {
    require(maxDepth >= 0) { "maxDepth must not be negative" }
    require(parallelism >= 1) { "parallelism must be positive" }
    if (!root.isDirectory || !root.canRead()) return emptyList()

    val files = java.util.concurrent.ConcurrentLinkedQueue<File>()
    val children = root.listFiles()?.toList().orEmpty()
    children.filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }.forEach {
        files.add(it)
        onFileFound(it)
    }
    val directories = children.filter {
        it.isDirectory && !Files.isSymbolicLink(it.toPath()) && includeDirectory(it)
    }
    if (directories.isEmpty()) return files.toList()

    val pool = Executors.newFixedThreadPool(parallelism.coerceAtMost(4))
    val futures = directories.map { directory ->
        pool.submit(Callable {
            Files.walkFileTree(directory.toPath(), emptySet(), maxDepth.coerceAtLeast(1),
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (Thread.currentThread().isInterrupted) return FileVisitResult.TERMINATE
                        return if (includeDirectory(dir.toFile())) FileVisitResult.CONTINUE else FileVisitResult.SKIP_SUBTREE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (Thread.currentThread().isInterrupted) return FileVisitResult.TERMINATE
                        if (attrs.isRegularFile) {
                            val found = file.toFile()
                            files.add(found)
                            onFileFound(found)
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult =
                        FileVisitResult.CONTINUE
                })
        })
    }

    try {
        futures.forEach { it.get() }
    } catch (failure: InterruptedException) {
        futures.forEach { it.cancel(true) }
        Thread.currentThread().interrupt()
        throw failure
    } catch (failure: java.util.concurrent.ExecutionException) {
        futures.forEach { it.cancel(true) }
        throw (failure.cause ?: failure)
    } finally {
        pool.shutdownNow()
        if (!pool.awaitTermination(2, TimeUnit.SECONDS)) {
            // The visitor also checks interruption before processing every file/directory.
        }
    }
    return files.toList()
}
