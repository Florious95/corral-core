package dev.agentmirror.app.ui

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.agentmirror.app.ui.components.WorkspaceActionBottomSheet
import dev.agentmirror.app.ui.theme.AppTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkspaceActionBottomSheetTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun pinActionUsesLiquidGlassSheetAndTogglesLabel() {
        var toggled = false
        compose.setContent {
            AppTheme {
                WorkspaceActionBottomSheet(
                    workspaceName = "corral",
                    workspacePath = "/Users/work/corral",
                    isPinned = false,
                    onDismiss = {},
                    onTogglePin = { toggled = true },
                )
            }
        }

        compose.onNodeWithTag("workspace-action-bottom-sheet").assertExists()
        compose.onNodeWithTag("workspace-action-title").assertTextEquals("corral")
        compose.onNodeWithTag("workspace-pin-action").assertTextEquals("置顶工作区")
        compose.onNodeWithTag("workspace-action-cancel").assertTextEquals("取消")
        compose.onNodeWithTag("workspace-pin-action").performClick()
        assertTrue(toggled)
    }
}
