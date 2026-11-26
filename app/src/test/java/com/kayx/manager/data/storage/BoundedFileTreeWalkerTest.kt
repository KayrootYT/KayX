package com.kayx.manager.data.storage

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedFileTreeWalkerTest {
    @Test fun walksFilesAcrossSubtreesWithoutFollowingSymlinks() {
        val root = Files.createTempDirectory("kayx-walk").toFile()
        try {
            val first = File(root, "first").apply { mkdirs() }
            val nested = File(first, "nested").apply { mkdirs() }
            val second = File(root, "second").apply { mkdirs() }
            val a = File(nested, "a.txt").apply { writeText("a") }
            val b = File(second, "b.txt").apply { writeText("b") }
            val outside = Files.createTempDirectory("kayx-outside").toFile()
            val external = File(outside, "external.txt").apply { writeText("outside") }
            val link = File(root, "linked")
            val symlinkCreated = runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }.isSuccess

            val result = walkFilesBounded(root, maxDepth = 8, parallelism = 2).toSet()
            assertTrue(a in result)
            assertTrue(b in result)
            if (symlinkCreated) assertFalse(external in result)
            outside.deleteRecursively()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun respectsDirectoryFilterAndDepthLimit() {
        val root = Files.createTempDirectory("kayx-depth").toFile()
        try {
            val included = File(root, "included").apply { mkdirs() }
            val excluded = File(root, "excluded").apply { mkdirs() }
            val deep = File(included, "one/two").apply { mkdirs() }
            val kept = File(included, "kept.txt").apply { writeText("ok") }
            val tooDeep = File(deep, "deep.txt").apply { writeText("no") }
            File(excluded, "hidden.txt").writeText("no")

            val result = walkFilesBounded(root, maxDepth = 1, parallelism = 1) {
                it.name != "excluded"
            }.toSet()
            assertTrue(kept in result)
            assertFalse(tooDeep in result)
            assertFalse(File(excluded, "hidden.txt") in result)
        } finally {
            root.deleteRecursively()
        }
    }
}
