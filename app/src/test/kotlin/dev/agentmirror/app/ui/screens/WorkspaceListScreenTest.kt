package dev.agentmirror.app.ui.screens

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
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

    @Test
    fun connectingBannerRendersAlongsideWorkspaceHeader() {
        compose.setContent {
            AppTheme(Appearance.Light) {
                WorkspaceListScreen(
                    workspaces = emptyList(),
                    onWorkspaceClick = {},
                    connectionBanner = "连接中…",
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("工作区").assertExists()
        compose.onNodeWithTag("connection-banner").assertExists()
        compose.onNodeWithText("连接中…").assertExists()
    }

    @Test
    fun hoistedListStateSurvivesListReentry() {
        val workspaces = (0 until 12).map { index ->
            WorkspaceItem("/repo-$index", "repo-$index", "/repo-$index", 0)
        }
        var showList by mutableStateOf(true)
        var expectedIndex = -1
        var expectedOffset = -1
        lateinit var listState: LazyListState

        compose.setContent {
            AppTheme(Appearance.Light) {
                val state = rememberLazyListState()
                listState = state
                if (showList) {
                    WorkspaceListScreen(
                        workspaces = workspaces,
                        onWorkspaceClick = {},
                        state = state,
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("workspace-list-scroll").performScrollToIndex(3)
        compose.runOnIdle {
            expectedIndex = listState.firstVisibleItemIndex
            expectedOffset = listState.firstVisibleItemScrollOffset
            assertTrue(expectedIndex > 0)
        }

        compose.runOnIdle { showList = false }
        compose.waitForIdle()
        compose.runOnIdle { showList = true }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(expectedIndex, listState.firstVisibleItemIndex)
            assertEquals(expectedOffset, listState.firstVisibleItemScrollOffset)
        }
    }

    @Test
    fun pinnedWorkspacesMoveFirstWhilePreservingServerOrder() {
        val workspaces = listOf(
            WorkspaceItem("/a", "a", "/a", 0),
            WorkspaceItem("/b", "b", "/b", 0),
            WorkspaceItem("/c", "c", "/c", 0),
        )
        val ordered = sortWorkspacesPinnedFirst(workspaces, setOf("/c", "/a"))
        assertEquals(listOf("/a", "/c", "/b"), ordered.map { it.path })
    }

    @Test
    fun longPressReportsWorkspaceItem() {
        var selected: WorkspaceItem? = null
        compose.setContent {
            AppTheme(Appearance.Light) {
                WorkspaceListScreen(
                    workspaces = listOf(WorkspaceItem("/repo", "repo", "/repo", 1)),
                    onWorkspaceClick = {},
                    onWorkspaceLongClick = { selected = it },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("workspace-row-/repo").performTouchInput { longClick() }
        compose.runOnIdle { assertEquals("/repo", selected?.path) }
    }

    @Test
    fun multiDigitWorkingBadgeRendersWithoutError() {
        compose.setContent {
            AppTheme(Appearance.Light) {
                androidx.compose.foundation.layout.Column {
                    MahjongStatusBadge(workingCount = 12)
                    MahjongStatusBadge(workingCount = 105)
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("12").assertExists()
        compose.onNodeWithText("105").assertExists()
    }
}
