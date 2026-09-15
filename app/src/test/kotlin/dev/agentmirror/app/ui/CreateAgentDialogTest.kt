package dev.agentmirror.app.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Column
import dev.agentmirror.app.ui.screens.AgentIconCard
import dev.agentmirror.app.ui.screens.CreateAgentFormContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
    fun agentIconCardsRenderOfficialIconsAndSelection() {
        compose.setContent {
            AppTheme {
                Column {
                    AgentIconCard(
                        launcher = AgentLauncherUi("pi", "Pi Coding Agent", supportsBypass = true, naming = "cli"),
                        isSelected = true,
                        onClick = {},
                    )
                    AgentIconCard(
                        launcher = AgentLauncherUi("codex", "Codex", supportsBypass = false, naming = "cli"),
                        isSelected = false,
                        onClick = {},
                    )
                }
            }
        }

        compose.onNodeWithTag("agent-card-pi").assertExists().assertIsSelected()
        compose.onNodeWithTag("agent-card-check-pi", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("✓", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("agent-card-codex").assertExists().assertIsNotSelected()
        compose.onNodeWithTag("agent-card-check-codex", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun formHasNoTargetSessionRowAndSwitchesSelectedAgent() {
        var name by mutableStateOf("")
        var selectedProvider by mutableStateOf("pi")
        var bypass by mutableStateOf(false)

        compose.setContent {
            AppTheme {
                CreateAgentFormContent(
                    name = name,
                    onNameChange = { name = it },
                    launchers = listOf(
                        AgentLauncherUi("pi", "Pi Coding Agent", supportsBypass = true, naming = "cli"),
                        AgentLauncherUi("codex", "Codex", supportsBypass = true, naming = "cli"),
                    ),
                    selectedProvider = selectedProvider,
                    onSelectProvider = { selectedProvider = it },
                    bypass = bypass,
                    onBypassChange = { bypass = it },
                    supportsBypass = true,
                    inFlight = false,
                    error = null,
                )
            }
        }

        // 验证彻底删除了目标会话
        compose.onNodeWithText("目标会话").assertDoesNotExist()

        // 验证初始状态 Pi 选中，Codex 未选中
        compose.onNodeWithTag("agent-card-pi").assertIsSelected()
        compose.onNodeWithTag("agent-card-codex").assertIsNotSelected()

        // 切换点击 Codex
        compose.onNodeWithTag("agent-card-codex").performClick()
        compose.onNodeWithTag("agent-card-codex").assertIsSelected()
        compose.onNodeWithTag("agent-card-pi").assertIsNotSelected()
        assertEquals("codex", selectedProvider)

        // 输入名称
        compose.onNodeWithTag("create-agent-name").performTextInput("新助手")
        assertEquals("新助手", name)
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
