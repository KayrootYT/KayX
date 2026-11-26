package com.kayx.manager

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun activityStartsAndComposeRootIsRendered() {
        composeRule.waitForIdle()
        composeRule.onRoot().assertExists()
        val root = composeRule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        val clickableNodes = flatten(root).filter { it.config.contains(SemanticsActions.OnClick) }

        assertTrue("Startup screen should render readable text", hasAccessibleName(root))
        assertTrue("Startup screen should expose at least one actionable control", clickableNodes.isNotEmpty())
        assertTrue(
            "Every startup-screen action must expose text or a content description",
            clickableNodes.all(::hasAccessibleName)
        )
    }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> =
        listOf(node) + node.children.flatMap(::flatten)

    private fun hasAccessibleName(node: SemanticsNode): Boolean {
        val text = node.config.getOrNull(SemanticsProperties.Text).orEmpty()
        val descriptions = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        if (text.any { it.text.isNotBlank() } || descriptions.any { it.isNotBlank() }) return true
        return node.children.any(::hasAccessibleName)
    }
}
