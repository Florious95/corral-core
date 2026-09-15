package dev.agentmirror.app.ui.screens

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.graphics.Color
import dev.agentmirror.app.ui.model.WorkspaceItem
import dev.agentmirror.app.ui.theme.AppTheme
import dev.agentmirror.app.ui.theme.Appearance
import org.junit.Assert.assertEquals
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
class WorkspaceListScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun workingBadgeIsGreenAndShowsCount() {
        compose.setContent {
            AppTheme(Appearance.Light) { MahjongStatusBadge(workingCount = 2) }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("mahjong-status-badge").assertExists()
        compose.onNodeWithText("2").assertExists()
        val bounds = compose.onNodeWithTag("mahjong-status-badge").getUnclippedBoundsInRoot()
        assertEquals(26f, bounds.right.value - bounds.left.value, 0.5f)
        assertEquals(34f, bounds.bottom.value - bounds.top.value, 0.5f)
        assertEquals(Color(0xFF047857), MahjongWorkingColor)
    }

    @Test
    fun idleBadgeIsGrayAndShowsZeroWithoutArrow() {
        compose.setContent {
            AppTheme(Appearance.Light) {
                WorkspaceListScreen(
                    workspaces = listOf(WorkspaceItem("/repo", "repo", "/repo", 1)),
                    onWorkspaceClick = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("mahjong-status-badge", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("0", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("❯", useUnmergedTree = true).assertDoesNotExist()
        assertTrue(MahjongIdleColor != Color.Transparent)
    }
}
