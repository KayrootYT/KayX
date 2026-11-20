package com.kayx.manager.data.filesystem

import com.kayx.manager.domain.model.SearchMode
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileSearchEngineTest {
    @Test
    fun supportsExactRegexAndWildcardSearch() = runBlocking {
        val root = createTempDir(prefix = "kayx-search-engine-")
        try {
            File(root, "photo-final.jpg").writeText("image")
            File(root, "photo.png").writeText("image")
            File(root, "notes.txt").writeText("text")
            val engine = FileSearchEngine()

            assertEquals(listOf("notes.txt"), engine.search(FileSearchRequest(root, "notes.txt", mode = SearchMode.EXACT)).items.map { it.name })
            assertEquals(listOf("photo.png"), engine.search(FileSearchRequest(root, "photo\\.png", mode = SearchMode.REGEX)).items.map { it.name })
            assertEquals(setOf("photo-final.jpg", "photo.png"), engine.search(FileSearchRequest(root, "photo*")).items.map { it.name }.toSet())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun appliesHiddenExtensionAndSizeFilters() = runBlocking {
        val root = createTempDir(prefix = "kayx-search-filters-")
        try {
            File(root, ".secret.txt").writeText("secret")
            File(root, "small.txt").writeText("x")
            File(root, "large.txt").writeText("large payload")
            val engine = FileSearchEngine()

            assertFalse(engine.search(FileSearchRequest(root, ".txt")).items.any { it.name == ".secret.txt" })
            assertTrue(engine.search(FileSearchRequest(root, ".txt", showHidden = true)).items.any { it.name == ".secret.txt" })
            assertEquals(listOf("large.txt"), engine.search(FileSearchRequest(root, "*", minSizeBytes = 5)).items.map { it.name })
            assertEquals(listOf("large.txt", "small.txt"), engine.search(FileSearchRequest(root, "*", allowedExtensions = setOf("txt"))).items.map { it.name }.sorted())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun appliesCategoryAndDateFilters() = runBlocking {
        val root = createTempDir(prefix = "kayx-search-category-")
        try {
            val recentImage = File(root, "recent.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
            File(root, "recent.txt").writeText("document")
            File(root, "old.jpg").apply { writeText("old"); setLastModified(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000) }
            val result = FileSearchEngine().search(
                FileSearchRequest(
                    root = root,
                    query = "*",
                    allowedExtensions = setOf("jpg", ".jpeg"),
                    minSizeBytes = 4,
                    modifiedAfter = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
                )
            )
            assertEquals(listOf("recent.jpg"), result.items.map { it.name })
            assertTrue(recentImage.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun searchesTextContentsWithoutReadingBinaryFiles() = runBlocking {
        val root = createTempDir(prefix = "kayx-search-content-")
        try {
            File(root, "readme.txt").writeText("KayX search engine is fast")
            File(root, "binary.bin").writeBytes(byteArrayOf(0, 1, 2, 3))
            val engine = FileSearchEngine()
            val result = engine.searchContents(FileSearchRequest(root, "search engine"))
            assertEquals(listOf("readme.txt"), result.items.map { it.name })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun invalidRegexReturnsNoResultsInsteadOfCrashing() = runBlocking {
        val root = createTempDir(prefix = "kayx-search-regex-")
        try {
            File(root, "sample.txt").writeText("sample")
            val result = FileSearchEngine().search(FileSearchRequest(root, "[", mode = SearchMode.REGEX))
            assertTrue(result.items.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
    @Test
    fun supportsExtensionSearchWithDotAndWildcardForms() = runBlocking {
        val root = createTempDir(prefix = "kayx-search-extension-")
        try {
            File(root, "track.mp3").writeText("audio")
            File(root, "track.mp4").writeText("video")
            File(root, "my-mp3-notes.txt").writeText("notes")
            val engine = FileSearchEngine()

            assertEquals(listOf("track.mp3"), engine.search(FileSearchRequest(root, ".mp3")).items.map { it.name })
            assertEquals(listOf("track.mp3"), engine.search(FileSearchRequest(root, "*.mp3")).items.map { it.name })
            assertEquals(listOf("track.mp3", "my-mp3-notes.txt").toSet(),
                engine.search(FileSearchRequest(root, "*mp3")).items.map { it.name }.toSet())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun mapsMtStyleQueriesToSafeMediaStorePatterns() {
        assertEquals("%music%", mediaStoreLikePattern("music", SearchMode.CONTAINS))
        assertEquals("%.mp3", mediaStoreLikePattern(".mp3", SearchMode.CONTAINS))
        assertEquals("%.mp3", mediaStoreLikePattern("*.mp3", SearchMode.CONTAINS))
        assertEquals("%mp3", mediaStoreLikePattern("*mp3", SearchMode.CONTAINS))
        assertEquals("track.mp3", mediaStoreLikePattern("track.mp3", SearchMode.EXACT))
        assertEquals("%100\\\\%_done%", mediaStoreLikePattern("100%_done", SearchMode.CONTAINS))
        assertEquals(null, mediaStoreLikePattern("[a-z]", SearchMode.REGEX))
    }

}
