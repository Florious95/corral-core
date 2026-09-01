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
import dev.agentmirror.app.conn.Level2Frame
import dev.agentmirror.app.conn.OverlaySubscribeFrame
import dev.agentmirror.app.conn.Session
import dev.agentmirror.app.ui.theme.AgentMirrorTheme
import dev.agentmirror.app.workspace.L2Status
import dev.agentmirror.app.workspace.WorkspaceViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 072：右上角「查看」是可点的二级菜单列表，复用 [WorkspaceViewModel.level2]。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayMenuTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun overlayMenuOpensLevel2ListWithoutSubscribe() {
        val wvm = seededWorkspace()
        val h = OverlayTestHarness()
        compose.setContent {
            AgentMirrorTheme {
                SessionScreen(
                    viewModel = h.vm,
                    name = "current",
                    onBack = {},
                    overlaySessions = wvm.level2.value.sessions,
                )
            }
        }
        compose.onNodeWithTag("session-overlay").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回菜单").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("session-overlay-open").assertIsDisplayed()
        compose.onNodeWithTag("session-overlay-open").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("session-overlay").assertIsDisplayed()
        assertTrue(h.vm.overlayOpen)
        assertTrue(
            "主路径不得再发 overlay_subscribe",
            h.sent().none { it is OverlaySubscribeFrame },
        )
        compose.onNodeWithText("advisor").assertIsDisplayed()
        compose.onNodeWithText("developer").assertIsDisplayed()
        compose.onNodeWithText("进行中").assertIsDisplayed()
        compose.onNodeWithText("空闲").assertIsDisplayed()
        assertEquals(
            listOf(L2Status.WORKING, L2Status.IDLE),
            wvm.level2.value.sessions.map { it.status },
        )
    }

    @Test
    fun overlayMenuClickRowJumpsToThatSession() {
        val wvm = seededWorkspace()
        val h = OverlayTestHarness()
        var jumped: Pair<String, String>? = null
        compose.setContent {
            AgentMirrorTheme {
                SessionScreen(
                    viewModel = h.vm,
                    name = "current",
                    onBack = {},
                    overlaySessions = wvm.level2.value.sessions,
                    onOpenOverlaySession = { ref, name -> jumped = ref to name },
                )
            }
        }
        compose.onNodeWithContentDescription("返回菜单").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("session-overlay-open").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("l2-row-ref-dev").performClick()
        compose.waitForIdle()

        assertEquals("ref-dev" to "developer", jumped)
        assertFalse(h.vm.overlayOpen)
        compose.onNodeWithTag("session-overlay").assertDoesNotExist()
    }

    @Test
    fun overlayMenuTapOutsideDismissesWithoutJump() {
        val wvm = seededWorkspace()
        val h = OverlayTestHarness()
        var jumped: Pair<String, String>? = null
        compose.setContent {
            AgentMirrorTheme {
                SessionScreen(
                    viewModel = h.vm,
                    name = "current",
                    onBack = {},
                    overlaySessions = wvm.level2.value.sessions,
                    onOpenOverlaySession = { ref, name -> jumped = ref to name },
                )
            }
        }
        compose.onNodeWithContentDescription("返回菜单").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("session-overlay-open").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("session-overlay-scrim").performTouchInput {
            // 设计 sheet 贴底，点遮罩上方才是窗外。
            click(percentOffset(0.5f, 0.08f))
        }
        compose.waitForIdle()
        assertFalse(h.vm.overlayOpen)
        assertNull(jumped)
        compose.onNodeWithTag("session-overlay").assertDoesNotExist()
    }

    private fun seededWorkspace(): WorkspaceViewModel {
        val wvm = WorkspaceViewModel(
            requestList = {},
            subscribeLevel2 = {},
            unsubscribeLevel2 = {},
        )
        wvm.enterLevel2("/proj/a")
        wvm.onFrame(
            Level2Frame(
                workspace = "/proj/a",
                seq = 1,
                sessions = listOf(
                    Session(
                        ref = "ref-adv",
                        name = "advisor",
                        title = "t",
                        rows = 24,
                        cols = 80,
                        status = "working",
                        cwd = "/proj/a",
                        sessionName = "advisor",
                        windowIndex = "1",
                        windowName = "advisor",
                    ),
                    Session(
                        ref = "ref-dev",
                        name = "developer",
                        title = "t",
                        rows = 24,
                        cols = 80,
                        status = "idle",
                        cwd = "/proj/a",
                        sessionName = "developer",
                        windowIndex = "2",
                        windowName = "developer",
                    ),
                ),
            ),
        )
        return wvm
    }
}
