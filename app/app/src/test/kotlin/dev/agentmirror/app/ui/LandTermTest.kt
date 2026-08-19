/*
 * Copyright 2026 AgentMirror Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.agentmirror.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.agentmirror.app.session.OverlayTestHarness
import dev.agentmirror.app.session.SessionScreen
import dev.agentmirror.app.termview.TermLeftEdge
import dev.agentmirror.app.ui.components.runningDotColor
import dev.agentmirror.app.ui.model.SessionStatus
import dev.agentmirror.app.ui.theme.AppTheme
import dev.agentmirror.app.ui.theme.Appearance
import dev.agentmirror.app.ui.theme.LightPalette
import dev.agentmirror.app.ui.theme.TermPalette
import dev.agentmirror.app.ui.theme.TerminalMetrics
import dev.agentmirror.app.ui.theme.TerminalPaletteDark
import dev.agentmirror.app.ui.theme.TerminalPaletteLight
import dev.agentmirror.app.workspace.L2Entry
import dev.agentmirror.app.workspace.L2Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * t.term 落位：会话页外壳 + 「查看」sheet + TerminalSpec 色板/度量。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LandTermTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun landTermTokensMatchDesignHandoff() {
        assertEquals(14, TerminalMetrics.paddingLeft.value.toInt())
        assertEquals(14, TerminalMetrics.paddingRight.value.toInt())
        assertEquals(TerminalMetrics.paddingLeft.value, TermLeftEdge.LEFT_MARGIN_DP)
        assertEquals(Color(0xFFF7F8FB), TerminalPaletteLight.background)
        assertEquals(Color(0xFFE6F5F2), TerminalPaletteLight.userBlockBackground)
        assertEquals(Color(0xFF0A1120), TerminalPaletteDark.background)
        assertEquals(Color(0xFF10241F), TerminalPaletteDark.userBlockBackground)
        assertEquals(TerminalPaletteLight.background.toArgb(), TermPalette.Light.defaultBg)
        assertEquals(TerminalPaletteLight.userBlockBackground.toArgb(), TermPalette.Light.userBlockBg)
        assertEquals(TerminalPaletteDark.background.toArgb(), TermPalette.Dark.defaultBg)
        assertEquals(TerminalPaletteDark.userBlockBackground.toArgb(), TermPalette.Dark.userBlockBg)
        assertNotEquals(TermPalette.Light.defaultBg, TermPalette.Light.userBlockBg)
        assertTrue(
            "浅色：userBlock 必须比 background 更深（白底开一块）",
            TermPalette.luma(TermPalette.Light.userBlockBg) < TermPalette.luma(TermPalette.Light.defaultBg),
        )
        assertTrue(
            "深色：userBlock 必须比 background 更浅",
            TermPalette.luma(TermPalette.Dark.userBlockBg) > TermPalette.luma(TermPalette.Dark.defaultBg),
        )
        assertEquals(LightPalette.busyDot, runningDotColor(LightPalette, SessionStatus.Busy))
        assertEquals(LightPalette.idleChipText, runningDotColor(LightPalette, SessionStatus.Idle))
        assertEquals(LightPalette.unknownDot, runningDotColor(LightPalette, SessionStatus.Unknown))
        assertNotEquals(
            runningDotColor(LightPalette, SessionStatus.Unknown),
            runningDotColor(LightPalette, SessionStatus.Idle),
        )
    }

    @Test
    fun landTermSessionTitleIsDisplayNameNotClaudeCode() {
        val h = OverlayTestHarness()
        compose.setContent {
            AppTheme(appearance = Appearance.Light) {
                SessionScreen(
                    viewModel = h.vm,
                    name = "远控 leader",
                    onBack = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("session-title").assertIsDisplayed()
        compose.onNodeWithText("远控 leader").assertIsDisplayed()
        compose.onNodeWithText("claude_code").assertDoesNotExist()
        compose.onNodeWithText("查看").assertIsDisplayed()
    }

    @Test
    fun landTermViewSheetUsesCurrentWorkspaceSessions() {
        val h = OverlayTestHarness()
        compose.setContent {
            AppTheme(appearance = Appearance.Light) {
                SessionScreen(
                    viewModel = h.vm,
                    name = "sess-a",
                    onBack = {},
                    overlaySessions = listOf(
                        entry(REF_A, "sess-a", CWD_A, L2Status.WORKING),
                    ),
                )
            }
        }
        compose.onNodeWithTag("session-overlay").assertDoesNotExist()
        compose.onNodeWithTag("session-overlay-open").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("session-overlay").assertIsDisplayed()
        compose.onNodeWithText("切换会话").assertIsDisplayed()
        compose.onNodeWithText("多agent协作", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("l2-row-$REF_A").assertExists()
        compose.onAllNodesWithText("sess-a").assertCountEquals(2)
        compose.onNodeWithText("sess-b").assertDoesNotExist()
        compose.onNodeWithText("远程Agent安卓").assertDoesNotExist()
        compose.onNodeWithText("claude_code").assertDoesNotExist()
    }

    private fun entry(ref: String, sessionName: String, cwd: String, status: L2Status) = L2Entry(
        ref = ref,
        name = sessionName,
        title = "t",
        rows = 24,
        cols = 80,
        status = status,
        cwd = cwd,
        sessionName = sessionName,
        windowIndex = "1",
        windowName = sessionName,
    )
}

private const val CWD_A = "/Volumes/nvme/Projects/多agent协作"
private const val SOCK_A = "/tmp/tmux-1000/collab"
private const val REF_A = "$SOCK_A\u001f%1"
