package com.kayx.manager.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbnailSizingTest {
    @Test fun samplesLargeLandscapeAndPortraitImagesToBoundedDimensions() {
        assertEquals(16, Thumbs.sampledSize(4000, 3000))
        assertEquals(32, Thumbs.sampledSize(8000, 1000))
        assertEquals(32, Thumbs.sampledSize(1000, 8000))
    }

    @Test fun keepsSmallImagesAtFullResolutionAndRejectsInvalidBounds() {
        assertEquals(2, Thumbs.sampledSize(320, 240))
        assertEquals(1, Thumbs.sampledSize(0, 240))
        assertEquals(1, Thumbs.sampledSize(240, 0))
    }
}
