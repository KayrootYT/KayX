package com.kayx.manager

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertTrue

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun activityStartsAndComposeRootIsRendered() {
        composeRule.waitForIdle()
        composeRule.onRoot().assertExists()
        val semantics = composeRule.onRoot(useUnmergedTree = true).printToString()
        assertTrue("Startup screen should expose readable UI text", semantics.contains("KayX") ||
            semantics.contains("Storage access needed") || semantics.contains("Search"))
    }
}
