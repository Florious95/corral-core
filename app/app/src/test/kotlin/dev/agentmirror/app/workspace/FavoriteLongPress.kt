package dev.agentmirror.app.workspace

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput

internal fun ComposeTestRule.longPressFavorite(rowTag: String, action: String) {
    val prefix = rowTag.substringBefore("-row-")
    val actionTag = "$prefix-favorite-action"
    onNodeWithTag(rowTag).performTouchInput { longClick() }
    waitUntil(2_000) {
        onAllNodesWithTag(actionTag).fetchSemanticsNodes().isNotEmpty()
    }
    onNodeWithTag(actionTag).assertTextEquals(action)
    onNodeWithTag(actionTag).performClick()
    waitForIdle()
}
