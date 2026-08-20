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

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.agentmirror.app.MainNavState
import dev.agentmirror.app.conn.Level2Frame
import dev.agentmirror.app.conn.Session
import dev.agentmirror.app.ui.theme.AgentMirrorTheme
import dev.agentmirror.app.workspace.WorkspaceViewModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 契约 088 E8 / A-088-back：sheet 展开时系统返回关列表，不退出会话。
 *
 * 夹具复现生产栈：根 [BackHandler] → [MainNavState.onSystemBack] 清 activeSession，
 * sheet 后注册所以应先吃到返回。修前 sheet 无处理器 → 会话被弹出。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayBackClosesSheetNotSessionTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun overlayOpen_systemBack_closesSheetKeepsSession() {
        val nav = MainNavState(initialShowPairing = false)
        nav.selectedWorkspaceCwd = "/proj/a"
        nav.activeSession = "ref-A" to "Agent A"
        val h = OverlayTestHarness()
        val overlaySessions = seededWorkspace().level2.value.sessions
        var dispatcher: OnBackPressedDispatcher? = null

        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            AgentMirrorTheme {
                BackHandler(
                    enabled = nav.activeSession != null || nav.selectedWorkspaceCwd != null,
                ) {
                    nav.onSystemBack()
                }
                if (nav.activeSession != null) {
                    SessionScreen(
                        viewModel = h.vm,
                        name = "Agent A",
                        onBack = { nav.activeSession = null },
                        overlaySessions = overlaySessions,
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("session-overlay-open").performClick()
        compose.waitForIdle()
        assertTrue(h.vm.overlayOpen)
        compose.onNodeWithTag("session-overlay").assertExists()
        compose.onNodeWithTag("session-topbar").assertExists()

        requireNotNull(dispatcher).onBackPressed()
        compose.waitForIdle()

        assertFalse("一次系统返回必须关 sheet", h.vm.overlayOpen)
        compose.onNodeWithTag("session-overlay").assertDoesNotExist()
        assertNotNull("会话必须仍在栈顶，不得 finish / 清 activeSession", nav.activeSession)
        compose.onNodeWithTag("session-topbar").assertExists()
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
                        ref = "ref-A",
                        name = "Agent A",
                        title = "t",
                        rows = 24,
                        cols = 80,
                        status = "working",
                        cwd = "/proj/a",
                        sessionName = "agent-a",
                        windowIndex = "1",
                        windowName = "agent-a",
                    ),
                ),
            ),
        )
        return wvm
    }
}
