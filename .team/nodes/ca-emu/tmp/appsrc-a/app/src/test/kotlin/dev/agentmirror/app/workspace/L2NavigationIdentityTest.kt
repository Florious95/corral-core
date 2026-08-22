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

package dev.agentmirror.app.workspace

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.agentmirror.app.conn.Level2Frame
import dev.agentmirror.app.conn.Session
import dev.agentmirror.app.ui.theme.AgentMirrorTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 061：跳转身份只能用结构字段（session_name / window_index / window_name），
 * 不得从标题字符串里抠。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class L2NavigationIdentityTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun clickUsesStructuralFieldsNeverTitleSubstring() {
        val refA = "/sockA\u001f%0"
        val refB = "/sockB\u001f%1"
        val sharedTitle = "◐  decoy-session :99 decoy-window — do not parse"

        val vm = WorkspaceViewModel(
            requestList = {},
            subscribeLevel2 = {},
            unsubscribeLevel2 = {},
        )
        vm.enterLevel2("/proj/a")
        vm.onFrame(
            Level2Frame(
                workspace = "/proj/a",
                seq = 1,
                sessions = listOf(
                    Session(
                        ref = refA,
                        name = "should-not-win-over-window-name",
                        cwd = "/proj/a",
                        rows = 24,
                        cols = 80,
                        title = sharedTitle,
                        status = "working",
                        sessionName = "real-session-a",
                        windowIndex = "2",
                        windowName = "real-window-a",
                    ),
                    Session(
                        ref = refB,
                        name = "should-not-win-over-window-name",
                        cwd = "/proj/a",
                        rows = 24,
                        cols = 80,
                        title = sharedTitle,
                        status = "idle",
                        sessionName = "real-session-b",
                        windowIndex = "7",
                        windowName = "real-window-b",
                    ),
                ),
            ),
        )

        val rows = vm.level2.value.sessions
        assertEquals("real-window-a", rows[0].identityLabel)
        assertEquals("real-window-b", rows[1].identityLabel)
        assertEquals("2", rows[0].windowIndex)
        assertEquals("7", rows[1].windowIndex)
        assertEquals("real-session-a", rows[0].sessionName)
        assertEquals(sharedTitle, rows[0].title)
        assertNotEquals(rows[0].navigationName, "decoy-session")

        var openedRef: String? = null
        var openedName: String? = null
        compose.setContent {
            AgentMirrorTheme {
                L2SessionList(
                    sessions = vm.level2.value.sessions,
                    onOpenSession = { ref, name ->
                        openedRef = ref
                        openedName = name
                    },
                )
            }
        }

        compose.onNodeWithTag("l2-row-$refB").performClick()
        assertEquals("点行必须用结构 ref 导航，不得从 title 抠", refB, openedRef)
        assertEquals("展示名必须来自 window_name，不得来自 title", "real-window-b", openedName)
    }
}
