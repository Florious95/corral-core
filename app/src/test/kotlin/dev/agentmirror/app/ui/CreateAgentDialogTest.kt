package dev.agentmirror.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
import dev.agentmirror.app.ui.theme.Appearance
import dev.agentmirror.app.ui.theme.ModernistDarkTokens
import dev.agentmirror.app.ui.theme.ModernistLightTokens
import dev.agentmirror.app.ui.theme.ThemeId
import androidx.compose.ui.graphics.Color
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
    fun l2TopBarRightHasOnlyCreateAgentAndNetworkPillBesideWorkspaceTitle() {
        compose.setContent {
            AppTheme {
                SessionListScreen(
                    workspaceName = "通用问题对话-chat",
                    workspacePath = "/repo/chat",
                    sessions = emptyList(),
                    connectionPath = dev.agentmirror.app.tsnet.ConnectionPath.TAILNET,
                    onBack = {},
                    onSessionClick = {},
                    onToggleStar = {},
                    agentLaunchers = listOf(
                        AgentLauncherUi("pi", "Pi Coding Agent", supportsBypass = true, naming = "cli"),
                    ),
                )
            }
        }
        compose.onNodeWithTag("create-agent-button").assertIsDisplayed()
        compose.onNodeWithText("tailnet").assertIsDisplayed()
        compose.onNodeWithText("通用问题对话-chat").assertIsDisplayed()
        val btnBounds = compose.onNodeWithTag("create-agent-button").getUnclippedBoundsInRoot()
        val pillBounds = compose.onNodeWithText("tailnet").getUnclippedBoundsInRoot()
        val titleBounds = compose.onNodeWithText("通用问题对话-chat").getUnclippedBoundsInRoot()
        assertTrue("LanPill top must be below top bar button", pillBounds.top >= btnBounds.bottom)
        assertTrue("LanPill must be to the right of title", pillBounds.left >= titleBounds.right)
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
                    AgentIconCard(
                        launcher = AgentLauncherUi("cursor", "Cursor", supportsBypass = true, naming = "cli"),
                        isSelected = false,
                        onClick = {},
                    )
                    AgentIconCard(
                        launcher = AgentLauncherUi("grok", "Grok", supportsBypass = true, naming = "cli"),
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

        compose.onNodeWithTag("agent-card-cursor").assertExists().assertIsNotSelected()
        compose.onNodeWithTag("agent-card-check-cursor", useUnmergedTree = true).assertDoesNotExist()

        // 验证 Grok 官方品牌图标卡片
        compose.onNodeWithTag("agent-card-grok").assertExists().assertIsNotSelected()
        compose.onNodeWithTag("agent-card-check-grok", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Grok").assertExists()
    }

    @Test
    fun formSwitchesToGrokCardWithCheckmarkAndSelection() {
        var selectedProvider by mutableStateOf("pi")
        var bypass by mutableStateOf(false)

        compose.setContent {
            AppTheme {
                CreateAgentFormContent(
                    name = "Agent",
                    onNameChange = {},
                    launchers = listOf(
                        AgentLauncherUi("pi", "Pi Coding Agent", supportsBypass = false, naming = "cli"),
                        AgentLauncherUi("codex", "Codex", supportsBypass = true, naming = "cli"),
                        AgentLauncherUi("cursor", "Cursor", supportsBypass = true, naming = "cli"),
                        AgentLauncherUi("grok", "Grok", supportsBypass = true, naming = "cli"),
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

        // 初始选中 Pi
        compose.onNodeWithTag("agent-card-pi").assertIsSelected()
        compose.onNodeWithTag("agent-card-grok").assertIsNotSelected()

        // 点击选择 Grok
        compose.onNodeWithTag("agent-card-grok").performClick()
        assertEquals("grok", selectedProvider)
        compose.onNodeWithTag("agent-card-grok").assertIsSelected()
        compose.onNodeWithTag("agent-card-check-grok", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("agent-card-pi").assertIsNotSelected()
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
        compose.onNodeWithText("+ Agent").assertExists().assertIsNotEnabled()
    }

    @Test
    fun modernistTokensMatchIOSModernistSpec() {
        // 浅色现代主义规范：冷白底色、RGB 234->242 漫反射渐变、深炭黑 ink、纯红 accent
        assertEquals(Color(0xFFF3F2F2), ModernistLightTokens.canvas)
        assertEquals(Color(0xFFEAE9E9), ModernistLightTokens.surface)
        assertEquals(Color(0xFFF2F2F2), ModernistLightTokens.diffuseEnd)
        assertEquals(Color(0xFF201E1D), ModernistLightTokens.ink)
        assertEquals(Color(0xFFEC3013), ModernistLightTokens.accent)
        assertEquals(Color(0xFFF3F2F2), ModernistLightTokens.onAccent)
        assertEquals(Color(0x66201E1D), ModernistLightTokens.divider)

        // 深色现代主义规范：深底色、浅反相 ink、纯红 accent
        assertEquals(Color(0xFF201E1D), ModernistDarkTokens.canvas)
        assertEquals(Color(0xFF2D2B2B), ModernistDarkTokens.surface)
        assertEquals(Color(0xFF353332), ModernistDarkTokens.diffuseEnd)
        assertEquals(Color(0xFFF3F2F2), ModernistDarkTokens.ink)
        assertEquals(Color(0xFFEC3013), ModernistDarkTokens.accent)
    }

    @Test
    fun modernistLightFormRendersConnectedRowWithRedMarkerAndSwitchesSelection() {
        var selectedProvider by mutableStateOf("pi")
        var bypass by mutableStateOf(false)

        compose.setContent {
            AppTheme(appearance = Appearance.Light, themeId = ThemeId.Modernist) {
                CreateAgentFormContent(
                    name = "ModernistAgent",
                    onNameChange = {},
                    launchers = listOf(
                        AgentLauncherUi("pi", "Pi", supportsBypass = true, naming = "cli"),
                        AgentLauncherUi("codex", "Codex", supportsBypass = false, naming = "cli"),
                        AgentLauncherUi("cursor", "Cursor", supportsBypass = true, naming = "cli"),
                        AgentLauncherUi("grok", "Grok", supportsBypass = true, naming = "cli"),
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

        // 验证初始状态：Pi 选中，且带有 10dp 纯红 selection-marker
        compose.onNodeWithTag("agent-card-pi").assertIsSelected()
        compose.onNodeWithTag("agent-card-selection-marker-pi", useUnmergedTree = true).assertExists()

        // 验证其余未选中节点：无 marker，未选中
        compose.onNodeWithTag("agent-card-codex").assertIsNotSelected()
        compose.onNodeWithTag("agent-card-selection-marker-codex", useUnmergedTree = true).assertDoesNotExist()

        compose.onNodeWithTag("agent-card-cursor").assertIsNotSelected()
        compose.onNodeWithTag("agent-card-selection-marker-cursor", useUnmergedTree = true).assertDoesNotExist()

        compose.onNodeWithTag("agent-card-grok").assertIsNotSelected()
        compose.onNodeWithTag("agent-card-selection-marker-grok", useUnmergedTree = true).assertDoesNotExist()

        // 点击切换到 Grok
        compose.onNodeWithTag("agent-card-grok").performClick()
        assertEquals("grok", selectedProvider)
        compose.onNodeWithTag("agent-card-grok").assertIsSelected()
        compose.onNodeWithTag("agent-card-selection-marker-grok", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("agent-card-pi").assertIsNotSelected()
        compose.onNodeWithTag("agent-card-selection-marker-pi", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun modernistDarkFormRendersSelectionMarkerAndSwitches() {
        var selectedProvider by mutableStateOf("cursor")
        var bypass by mutableStateOf(false)

        compose.setContent {
            AppTheme(appearance = Appearance.Dark, themeId = ThemeId.Modernist) {
                CreateAgentFormContent(
                    name = "ModernistDarkAgent",
                    onNameChange = {},
                    launchers = listOf(
                        AgentLauncherUi("pi", "Pi", supportsBypass = true, naming = "cli"),
                        AgentLauncherUi("cursor", "Cursor", supportsBypass = true, naming = "cli"),
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

        compose.onNodeWithTag("agent-card-cursor").assertIsSelected()
        compose.onNodeWithTag("agent-card-selection-marker-cursor", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("agent-card-pi").assertIsNotSelected()
        compose.onNodeWithTag("agent-card-selection-marker-pi", useUnmergedTree = true).assertDoesNotExist()
    }
}
