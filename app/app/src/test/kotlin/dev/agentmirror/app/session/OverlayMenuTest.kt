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

package dev.agentmirror.app.session

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.percentOffset
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import dev.agentmirror.app.conn.OverlaySubscribeFrame
import dev.agentmirror.app.ui.theme.AgentMirrorTheme
import dev.agentmirror.app.workspace.L2Entry
import dev.agentmirror.app.workspace.L2Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 「查看」必须复用真实会话列表，不得渲染 HTML 占位卡。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayMenuTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun viewOpensRealSessionListWithoutStartingAnotherSubscription() {
        val h = OverlayTestHarness()
        val selected = mutableListOf<String>()
        compose.setContent {
            AgentMirrorTheme {
                SessionScreen(
                    viewModel = h.vm,
                    name = "current",
                    onBack = {},
                    overlaySessions = sampleSessions(),
                    onOpenOverlaySession = { ref, _ -> selected += ref },
                )
            }
        }
        openView()

        compose.onNodeWithTag("session-overlay").assertIsDisplayed()
        compose.onNodeWithText("切换会话").assertIsDisplayed()
        compose.onNodeWithText("会话甲").assertIsDisplayed()
        compose.onNodeWithText("会话乙").assertIsDisplayed()
        compose.onNodeWithText("查看弹出菜单（原生实现，此处仅占位）").assertDoesNotExist()
        compose.onNodeWithText("点任意处关闭").assertDoesNotExist()
        assertTrue(h.vm.overlayOpen)
        assertTrue(h.sent().none { it is OverlaySubscribeFrame })
    }

    @Test
    fun selectingOverlaySessionUsesRealListCallback() {
        val h = OverlayTestHarness()
        val selected = mutableListOf<String>()
        compose.setContent {
            AgentMirrorTheme {
                SessionScreen(
                    viewModel = h.vm,
                    name = "current",
                    onBack = {},
                    overlaySessions = sampleSessions(),
                    onOpenOverlaySession = { ref, _ -> selected += ref },
                )
            }
        }
        openView()
        compose.onNodeWithText("会话乙").performClick()
        compose.waitForIdle()

        assertEquals(listOf("ref-b"), selected)
        assertFalse(h.vm.overlayOpen)
        compose.onNodeWithTag("session-overlay").assertDoesNotExist()
    }

    @Test
    fun tappingScrimDismissesRealList() {
        val h = OverlayTestHarness()
        compose.setContent {
            AgentMirrorTheme {
                SessionScreen(
                    viewModel = h.vm,
                    name = "current",
                    onBack = {},
                    overlaySessions = sampleSessions(),
                )
            }
        }
        openView()
        compose.onNodeWithTag("session-overlay-scrim").performTouchInput {
            click(percentOffset(0.5f, 0.08f))
        }
        compose.waitForIdle()

        assertFalse(h.vm.overlayOpen)
        compose.onNodeWithTag("session-overlay").assertDoesNotExist()
    }

    private fun openView() {
        compose.onNodeWithContentDescription("返回菜单").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("session-overlay-open").performClick()
        compose.waitForIdle()
    }

    private fun sampleSessions() = listOf(
        L2Entry(
            ref = "ref-a",
            name = "a",
            title = "a",
            rows = 24,
            cols = 80,
            status = L2Status.WORKING,
            cwd = "/ws",
            sessionName = "s",
            windowIndex = "0",
            windowName = "会话甲",
        ),
        L2Entry(
            ref = "ref-b",
            name = "b",
            title = "b",
            rows = 24,
            cols = 80,
            status = L2Status.IDLE,
            cwd = "/ws",
            sessionName = "s",
            windowIndex = "1",
            windowName = "会话乙",
        ),
    )
}
