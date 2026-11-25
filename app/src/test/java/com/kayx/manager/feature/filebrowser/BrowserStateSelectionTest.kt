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
    fun selectionUpdatesOnlyTheTargetTab() {
        val tabs = listOf(
            BrowserTab("/first", setOf("/first/a")),
            BrowserTab("/second", setOf("/second/b"))
        )

        val updated = updateBrowserTabSelection(tabs, 1, setOf("/second/c"))

        assertEquals(setOf("/first/a"), updated[0].selected)
        assertEquals(setOf("/second/c"), updated[1].selected)
    }

    @Test
    fun browserSessionPathCodecRoundTripsUnicodeAndReservedCharacters() {
        val paths = listOf(
            "/storage/emulated/0/Download",
            "saf://location/content%3A%2F%2Fprovider%2Ftree%2Froot?path=Music%2Fहिंदी songs",
            "/storage/emulated/0/folder/with spaces and ? symbols"
        )

        assertEquals(paths, decodeBrowserPaths(encodeBrowserPaths(paths)))
    }

    @Test
    fun browserSessionPathCodecIgnoresCorruptEntries() {
        val encoded = encodeBrowserPaths(listOf("/valid/path"))
        assertEquals(listOf("/valid/path"), decodeBrowserPaths(encoded + "\n%%%invalid%%%"))
    }

    @Test
    fun singlePaneAlwaysUsesPrimarySelection() {
        val state = BrowserState(dualPane = false, activePane = 1,
            primarySelected = setOf("/primary.txt"), secondarySelected = setOf("/secondary.txt"))
        assertEquals(setOf("/primary.txt"), state.selected)
    }
}
