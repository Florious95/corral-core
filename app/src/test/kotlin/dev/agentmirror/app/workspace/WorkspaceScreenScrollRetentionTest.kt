package dev.agentmirror.app.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import dev.agentmirror.app.conn.ConnectionState
import dev.agentmirror.app.conn.ListingFrame
import dev.agentmirror.app.conn.Workspace
import dev.agentmirror.app.ui.theme.AgentMirrorTheme
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
class WorkspaceScreenScrollRetentionTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun l1AnchorSurvivesActualWorkspaceScreenRoute() {
        val vm = WorkspaceViewModel(requestList = {})
        vm.onConnectionStateChanged(ConnectionState.READY)
        vm.onFrame(
            ListingFrame(
                reqId = 1,
                seq = 1,
                workspaces = (0 until 12).map { index ->
                    Workspace(cwd = "/repo-$index", sessionCount = 1)
                },
            ),
        )
        var selectedWorkspace by mutableStateOf<String?>(null)
        var showScreen by mutableStateOf(true)

        compose.setContent {
            AgentMirrorTheme {
                if (showScreen) {
                    WorkspaceScreen(
                        viewModel = vm,
                        selectedWorkspaceCwd = selectedWorkspace,
                        onSelectWorkspace = { selectedWorkspace = it },
                        onBackToList = { selectedWorkspace = null },
                        onOpenSettings = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("workspace-list-scroll").performScrollToIndex(3)
        compose.waitForIdle()
        compose.onNodeWithText("repo-0", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(vm.workspaceListScrollAnchor().firstVisibleItemIndex > 0)
        }
        val anchorBeforeRoute = vm.workspaceListScrollAnchor()

        compose.runOnIdle { selectedWorkspace = "/repo-7" }
        compose.waitForIdle()
        compose.runOnIdle { selectedWorkspace = null }
        compose.waitForIdle()
        compose.runOnIdle { showScreen = false }
        compose.waitForIdle()
        compose.runOnIdle { showScreen = true }
        compose.waitForIdle()

        compose.onNodeWithText("repo-0", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(anchorBeforeRoute, vm.workspaceListScrollAnchor())
        assertTrue(selectedWorkspace == null)
    }
}
