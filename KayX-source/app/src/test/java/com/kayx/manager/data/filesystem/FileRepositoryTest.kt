package com.kayx.manager.data.filesystem

import com.kayx.manager.domain.model.SortBy
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileRepositoryTest {
    @Test
    fun createListRenameAndSearchFiles() = runBlocking {
        val root = createTempDir(prefix = "kayx-repository-")
        try {
            val repository = FileRepository()
            assertTrue(repository.createFolder(root, "Documents").isSuccess)
            assertTrue(repository.createFile(root, "notes.txt").isSuccess)

            val listed = repository.list(root, sort = SortBy.NAME)
            assertEquals(listOf("Documents", "notes.txt"), listed.map { it.name })

            assertTrue(repository.rename(File(root, "notes.txt"), "memo.txt").isSuccess)
            val matches = repository.search(root, "memo")
            assertEquals(listOf("memo.txt"), matches.map { it.name })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun searchFindsFilesBeyondFourFolderLevelsAndReusesIndex() = runBlocking {
        val root = createTempDir(prefix = "kayx-deep-search-")
        try {
            val repository = FileRepository()
            val deep = File(root, "one/two/three/four/five/six").apply { mkdirs() }
            File(deep, "needle-document.txt").writeText("found")

            val first = repository.search(root, "needle")
            assertEquals(listOf("needle-document.txt"), first.map { it.name })

            // A second query should reuse the same index; a newly added file appears after invalidation.
            File(deep, "needle-second.txt").writeText("also found")
            repository.invalidateSearchCache()
            val refreshed = repository.search(root, "needle")
            assertEquals(2, refreshed.size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsUnsafeNamesAndExistingTargets() = runBlocking {
        val root = createTempDir(prefix = "kayx-validation-")
        try {
            val repository = FileRepository()
            assertTrue(repository.createFile(root, "../escape").isFailure)
            assertTrue(repository.createFile(root, "same.txt").isSuccess)
            assertTrue(repository.createFile(root, "same.txt").isFailure)
        } finally {
            root.deleteRecursively()
        }
    }
    @Test
    fun transferReportsProgressAndKeepsBothOnConflict() {
        val root = createTempDir(prefix = "kayx-transfer-")
        try {
            val sourceDir = File(root, "source").apply { mkdirs() }
            val destinationDir = File(root, "destination").apply { mkdirs() }
            val source = File(sourceDir, "report.txt").apply { writeText("new") }
            File(destinationDir, "report.txt").writeText("existing")
            val updates = mutableListOf<Triple<Int, Int, String>>()

            val result = FileOps.transfer(listOf(source), destinationDir, move = false, policy = Conflict.KEEP_BOTH) { done, total, name ->
                updates += Triple(done, total, name)
            }

            assertTrue(result.isSuccess)
            assertEquals("existing", File(destinationDir, "report.txt").readText())
            assertEquals("new", File(destinationDir, "report (1).txt").readText())
            assertTrue(updates.any { it.first == 1 && it.second == 1 && it.third == "report.txt" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun transferRejectsCopyingDirectoryIntoItself() {
        val root = createTempDir(prefix = "kayx-transfer-self-")
        try {
            val parent = File(root, "parent").apply { mkdirs() }
            val child = File(parent, "child").apply { mkdirs() }
            File(child, "sample.txt").writeText("sample")
            val result = FileOps.transfer(listOf(parent), child, move = false, policy = Conflict.KEEP_BOTH)
            assertTrue(result.isFailure)
        } finally {
            root.deleteRecursively()
        }
    }

}
