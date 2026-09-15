package dev.agentmirror.app.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.createComposeRule
import dev.agentmirror.app.ui.model.SessionItem
import dev.agentmirror.app.ui.model.SessionStatus
import dev.agentmirror.app.ui.screens.SessionListScreen
import dev.agentmirror.app.ui.theme.AppTheme
import dev.agentmirror.app.workspace.AgentLauncherUi
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CreateAgentDialogTest {
    @get:Rule
    val compose = createComposeRule()

    private val session = SessionItem(
        id = "/tmp/tmux.sock\u001f%0",
        displayName = "Anchor",
        path = "/repo",
        status = SessionStatus.Idle,
        starred = false,
    )

    @Test
    fun advertisedCapabilitiesEnableCreateButton() {
        compose.setContent {
            AppTheme {
                SessionListScreen(
                    workspaceName = "repo",
                    workspacePath = "/repo",
                    sessions = listOf(session),
                    onBack = {},
                    onSessionClick = {},
                    onToggleStar = {},
                    agentLaunchers = listOf(
                        AgentLauncherUi("pi", "Pi Coding Agent", supportsBypass = true, naming = "cli"),
                    ),
                )
            }
        }
        compose.onNodeWithTag("create-agent-button").assertIsEnabled()
    }

    @Test
    fun emptyCapabilitiesDisableCreateButton() {
        compose.setContent {
            AppTheme {
                SessionListScreen(
                    workspaceName = "repo",
                    workspacePath = "/repo",
                    sessions = listOf(session),
                    onBack = {},
                    onSessionClick = {},
                    onToggleStar = {},
                )
            }
        }
        compose.onNodeWithText("+ 新建 Agent").assertExists().assertIsNotEnabled()
    }
}
