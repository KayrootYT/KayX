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
    fun singlePaneAlwaysUsesPrimarySelection() {
        val state = BrowserState(dualPane = false, activePane = 1,
            primarySelected = setOf("/primary.txt"), secondarySelected = setOf("/secondary.txt"))
        assertEquals(setOf("/primary.txt"), state.selected)
    }
}
