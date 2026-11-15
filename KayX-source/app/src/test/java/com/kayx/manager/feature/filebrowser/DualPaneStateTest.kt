package com.kayx.manager.feature.filebrowser

import com.kayx.manager.data.filesystem.Conflict
import com.kayx.manager.data.filesystem.FileOps
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DualPaneStateTest {
    @Test
    fun otherDirIsTheFolderOfTheInactivePane() {
        val s = BrowserState(dualPane = true, dir = "/left", secondaryDir = "/right", activePane = 0)
        assertEquals("/right", s.otherDir)
        assertEquals("/left", s.copy(activePane = 1).otherDir)
    }

    @Test
    fun backAndForwardFollowTheActivePanesOwnHistory() {
        val s = BrowserState(
            dualPane = true,
            backStacks = listOf(listOf("/x"), emptyList()),
            fwdStacks = listOf(emptyList(), listOf("/y"))
        )
        assertTrue(s.canBack); assertFalse(s.canForward)
        val right = s.copy(activePane = 1)
        assertFalse(right.canBack); assertTrue(right.canForward)
    }

    @Test
    fun singlePaneAlwaysUsesFirstPaneHistory() {
        val s = BrowserState(dualPane = false, activePane = 1, backStacks = listOf(listOf("/x"), emptyList()))
        assertEquals(0, s.activeIdx)
        assertTrue(s.canBack)
    }

    @Test
    fun copyToOtherPaneKeepsSourcesAndMoveRemovesThem() {
        val left = createTempDir(prefix = "kayx-left-")
        val right = createTempDir(prefix = "kayx-right-")
        try {
            val a = File(left, "a.txt").apply { writeText("a") }
            val b = File(left, "b.txt").apply { writeText("b") }

            assertTrue(FileOps.transfer(listOf(a, b), right, move = false, policy = Conflict.KEEP_BOTH).isSuccess)
            assertTrue(a.exists() && b.exists())
            assertEquals(setOf("a.txt", "b.txt"), right.list()!!.toSet())

            val c = File(left, "c.txt").apply { writeText("c") }
            assertTrue(FileOps.transfer(listOf(c), right, move = true, policy = Conflict.KEEP_BOTH).isSuccess)
            assertFalse(c.exists())
            assertTrue(File(right, "c.txt").exists())
        } finally {
            left.deleteRecursively(); right.deleteRecursively()
        }
    }

    @Test
    fun conflictsAreReportedBeforeAnythingIsOverwritten() {
        val left = createTempDir(prefix = "kayx-left-")
        val right = createTempDir(prefix = "kayx-right-")
        try {
            val a = File(left, "a.txt").apply { writeText("new") }
            File(right, "a.txt").writeText("old")
            assertEquals(listOf("a.txt"), FileOps.conflicts(listOf(a), right))

            assertTrue(FileOps.transfer(listOf(a), right, move = false, policy = Conflict.SKIP).isSuccess)
            assertEquals("old", File(right, "a.txt").readText())
        } finally {
            left.deleteRecursively(); right.deleteRecursively()
        }
    }
}
