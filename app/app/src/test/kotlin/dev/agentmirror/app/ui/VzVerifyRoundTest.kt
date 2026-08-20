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

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.agentmirror.app.conn.Session
import dev.agentmirror.app.session.DiffSync
import dev.agentmirror.app.session.InputStatus
import dev.agentmirror.app.session.OverlayTestHarness
import dev.agentmirror.app.session.SessionScreen
import dev.agentmirror.app.termview.BoxBlockGeometry
import dev.agentmirror.app.tsnet.ConnectionPath
import dev.agentmirror.app.ui.screens.SessionShellScreen
import dev.agentmirror.app.ui.theme.AppTheme
import dev.agentmirror.app.ui.theme.Appearance
import dev.agentmirror.app.ui.theme.Dims
import dev.agentmirror.app.ui.theme.TermPalette
import dev.agentmirror.app.ui.theme.TerminalMetrics
import dev.agentmirror.app.ui.theme.appDarkScheme
import dev.agentmirror.app.ui.theme.appLightScheme
import dev.agentmirror.app.workspace.FavoriteKey
import dev.agentmirror.app.workspace.L2Status
import dev.agentmirror.app.workspace.favoriteKey
import dev.agentmirror.app.workspace.sessionDisplayName
import dev.agentmirror.app.workspace.toL2Entry
import dev.agentmirror.terminal.TerminalColor
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
 * t.ver 独立验收单测（A-vr-test）。只断言本轮契约操作数，不改产品码。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VzVerifyRoundTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun VzVerifyGapTotalPaddingIsTenDpAndColsCapped() {
        val outer = Dims.terminalCardMargin.value
        val inner = TerminalMetrics.paddingLeft.value
        assertEquals("卡片外", 4f, outer)
        assertEquals("终端内", 6f, inner)
        assertEquals("屏幕边到首字符", 10f, outer + inner)
        assertEquals(112, TerminalMetrics.maxCols)
        val uncapped = ((20000f - 18f - 18f) / 10f).toInt()
        assertTrue("未封顶会远超 112: $uncapped", uncapped > 112)
        assertEquals(112, TerminalMetrics.colsFor(20000f, 10f, 18f, 18f))
    }

    @Test
    fun VzVerifyCursorKeepsSelectionAfterExternalUpdate() {
        var draft by mutableStateOf(TextFieldValue("abcdefgh", TextRange(4)))
        var extra by mutableStateOf(0)
        compose.setContent {
            AppTheme(appearance = Appearance.Light) {
                SessionShellScreen(
                    sessionDisplayName = "远控 leader$extra",
                    status = dev.agentmirror.app.ui.model.SessionStatus.Idle,
                    draft = draft,
                    onDraftChange = { draft = it },
                    onSend = {},
                    onBack = {},
                    onOpenSwitcher = {},
                    onKeyPress = {},
                    onAttach = {},
                ) {}
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { extra = 1 }
        compose.waitForIdle()
        assertEquals("外部更新不得把光标打回末尾", 4, draft.selection.start)
        assertEquals(4, draft.selection.end)
    }

    @Test
    fun VzVerifySentHidesSuccessKeepsFailure() {
        val h = OverlayTestHarness()
        compose.setContent {
            AppTheme(appearance = Appearance.Light) {
                SessionScreen(viewModel = h.vm, name = "远控 leader", onBack = {})
            }
        }
        compose.runOnIdle { h.vm.inputStatus = InputStatus.Sent }
        compose.waitForIdle()
        compose.onNodeWithText("已发送").assertDoesNotExist()
        compose.runOnIdle { h.vm.inputStatus = InputStatus.Failed("发送失败：超时") }
        compose.waitForIdle()
        compose.onNodeWithText("发送失败：超时").assertIsDisplayed()
        compose.onNodeWithText("已发送").assertDoesNotExist()
    }

    @Test
    fun VzVerifyNetPillUsesRealConnectionPath() {
        compose.setContent {
            AppTheme(appearance = Appearance.Light) {
                SessionShellScreen(
                    sessionDisplayName = "远控 leader",
                    status = dev.agentmirror.app.ui.model.SessionStatus.Idle,
                    connectionPath = ConnectionPath.TAILNET,
                    draft = TextFieldValue(""),
                    onDraftChange = {},
                    onSend = {},
                    onBack = {},
                    onOpenSwitcher = {},
                    onKeyPress = {},
                    onAttach = {},
                ) {}
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("LAN").assertDoesNotExist()
        compose.onNodeWithText("tailnet").assertIsDisplayed()
    }

    @Test
    fun VzVerifyLampFollowsLiveOverlayWithoutNavigation() {
        val h = OverlayTestHarness()
        val idle = session(h.vm.ref, L2Status.IDLE)
        val busy = session(h.vm.ref, L2Status.WORKING)
        var overlay by mutableStateOf(listOf(idle))
        compose.setContent {
            AppTheme(appearance = Appearance.Light) {
                SessionScreen(
                    viewModel = h.vm,
                    name = "远控 leader",
                    onBack = {},
                    overlaySessions = overlay,
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Idle").assertIsDisplayed()
        compose.runOnIdle { overlay = listOf(busy) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Busy").assertIsDisplayed()
    }

    @Test
    fun VzVerifyPaperNotAnsi0AndUserBlockDarker() {
        val pal = TermPalette.Light
        val paper = TermPalette.colorFor(TerminalColor.Indexed(0), background = true, dark = false)
        val ansi0 = pal.ansi16[0]!!
        val block = TermPalette.colorFor(TerminalColor.Indexed(254), background = true, dark = false)
        assertEquals(pal.defaultBg, paper)
        assertNotEquals(ansi0, paper)
        assertTrue(TermPalette.luma(paper) > TermPalette.luma(ansi0))
        assertEquals(pal.userBlockBg, block)
        assertTrue(TermPalette.luma(block) < TermPalette.luma(paper))
    }

    @Test
    fun VzVerifyGlyphSeamZeroAtFractionalDensity() {
        val cellW = 17
        val origin = 14
        val rects = (0 until 4).map { col ->
            BoxBlockGeometry.fills(0x2588, origin + col * cellW, 0, cellW, 22).single().rect
        }
        for (i in 0 until rects.size - 1) {
            assertEquals(
                "█ i=$i right=${rects[i].right} nextLeft=${rects[i + 1].left}",
                rects[i].right,
                rects[i + 1].left,
            )
        }
    }

    @Test
    fun VzVerifyDiffSyncAppendZeroBackspaceMidEditEqual() {
        val p = DiffSync.plan("l", "ls")
        assertEquals(0, p.backspaces)
        assertEquals("s", p.typed)
        val local = StringBuilder("hello")
        val cli = StringBuilder("hello")
        local.deleteAt(1)
        DiffSync.applyTo(cli, DiffSync.plan(cli.toString(), local.toString()))
        local.insert(1, 'a')
        DiffSync.applyTo(cli, DiffSync.plan(cli.toString(), local.toString()))
        assertEquals("hallo", local.toString())
        assertEquals(local.toString(), cli.toString())
    }

    @Test
    fun VzVerifyIdentityKeyContainsSocket() {
        val a = Session(
            ref = "/tmp/tmux-501/ident-a\u001f%1",
            name = "claude_code",
            cwd = "/ws/甲",
            rows = 24,
            cols = 80,
            title = "◐ x",
            status = "idle",
            sessionName = "team",
            windowIndex = "0",
            windowName = "claude_code",
        ).toL2Entry()
        val b = Session(
            ref = "/tmp/tmux-501/ident-b\u001f%1",
            name = "claude_code",
            cwd = "/ws/乙",
            rows = 24,
            cols = 80,
            title = "◐ x",
            status = "idle",
            sessionName = "team",
            windowIndex = "0",
            windowName = "claude_code",
        ).toL2Entry()
        assertEquals(FavoriteKey(a.ref), a.favoriteKey())
        assertNotEquals(a.favoriteKey(), b.favoriteKey())
        assertTrue("身份键必须含 socket 路径", a.favoriteKey().ref.contains("/tmp/tmux-501/ident-a"))
        val title = sessionDisplayName(
            windowName = a.windowName,
            sessionName = a.sessionName,
            name = a.name,
            title = a.title,
        )
        assertNotEquals("claude_code", title)
    }

    @Test
    fun VzVerifySchemeSurfaceContainerNotFrameworkPurple() {
        val lightDefault = androidx.compose.material3.lightColorScheme()
        val darkDefault = androidx.compose.material3.darkColorScheme()
        assertNotEquals(lightDefault.surfaceContainer, appLightScheme.surfaceContainer)
        assertNotEquals(darkDefault.surfaceContainer, appDarkScheme.surfaceContainer)
        assertNotEquals(lightDefault.primary, appLightScheme.primary)
    }

    private fun session(ref: String, status: L2Status) = dev.agentmirror.app.workspace.L2Entry(
        ref = ref,
        name = "n",
        title = "t",
        rows = 24,
        cols = 80,
        status = status,
        cwd = "/tmp/ws",
        sessionName = "n",
        windowIndex = "1",
        windowName = "n",
    )
}
