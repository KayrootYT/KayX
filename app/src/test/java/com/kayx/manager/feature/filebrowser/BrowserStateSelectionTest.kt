package com.kayx.manager.feature.filebrowser

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserStateSelectionTest {
    @Test
    fun dualPaneSelectionIsIsolatedPerPane() {
        val left = setOf("/storage/emulated/0/left.txt")
        val right = setOf("/storage/emulated/0/right.txt")
        val leftActive = BrowserState(dualPane = true, activePane = 0, primarySelected = left, secondarySelected = right)
        val rightActive = leftActive.copy(activePane = 1)

        assertEquals(left, leftActive.selected)
        assertEquals(right, rightActive.selected)
        assertEquals(left, rightActive.primarySelected)
        assertEquals(right, rightActive.secondarySelected)
    }

    @Test
    fun navigationUpdatesOnlyTheActiveSinglePaneTab() {
        val tabs = listOf(
            BrowserTab("/first", setOf("/first/selected.txt")),
            BrowserTab("/second", setOf("/second/selected.txt")),
            BrowserTab("/third", setOf("/third/selected.txt"))
        )

        val updated = updateBrowserTabPath(tabs, 1, "/second/child")

        assertEquals("/first", updated[0].path)
        assertEquals("/second/child", updated[1].path)
        assertEquals(emptySet<String>(), updated[1].selected)
        assertEquals("/third", updated[2].path)
        assertEquals(setOf("/first/selected.txt"), updated[0].selected)
    }

    @Test
    fun tabPathUpdateClampsInvalidIndexAndHandlesEmptyTabs() {
        assertEquals(
            "/last/child",
            updateBrowserTabPath(listOf(BrowserTab("/first"), BrowserTab("/last")), 99, "/last/child").last().path
        )
        assertEquals(listOf(BrowserTab("/new")), updateBrowserTabPath(emptyList(), 0, "/new"))
    }

    @Test
    fun singlePaneAlwaysUsesPrimarySelection() {
        val state = BrowserState(dualPane = false, activePane = 1,
            primarySelected = setOf("/primary.txt"), secondarySelected = setOf("/secondary.txt"))
        assertEquals(setOf("/primary.txt"), state.selected)
    }
}
