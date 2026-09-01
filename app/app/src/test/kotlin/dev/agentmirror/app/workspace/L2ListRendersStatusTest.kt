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
import androidx.compose.ui.test.onNodeWithText
import dev.agentmirror.app.conn.Level2Frame
import dev.agentmirror.app.conn.Session
import dev.agentmirror.app.ui.theme.AgentMirrorTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 061：二级列表渲染三态状态标。帧必须打进 [WorkspaceViewModel.onFrame]。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class L2ListRendersStatusTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun listRendersWorkingIdleUnknownBadgesFromWorkspaceViewModel() {
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
                    session("ref-w", "sess-work", "working"),
                    session("ref-i", "sess-idle", "idle"),
                    session("ref-u", "sess-unk", "unknown"),
                ),
            ),
        )

        assertEquals(
            listOf(L2Status.WORKING, L2Status.IDLE, L2Status.UNKNOWN),
            vm.level2.value.sessions.map { it.status },
        )

        compose.setContent {
            AgentMirrorTheme {
                L2SessionList(
                    sessions = vm.level2.value.sessions,
                    onOpenSession = { _, _ -> },
                )
            }
        }

        compose.onNodeWithText("sess-work").assertExists("必须显示结构字段会话标识")
        compose.onNodeWithText("sess-idle").assertExists()
        compose.onNodeWithText("sess-unk").assertExists()
        compose.onNodeWithText("进行中").assertExists("working → 进行中")
        compose.onNodeWithText("空闲").assertExists("idle → 空闲")
        compose.onNodeWithText("未知").assertExists("unknown → 未知")
    }

    private fun session(ref: String, name: String, status: String) = Session(
        ref = ref,
        name = name,
        cwd = "/proj/a",
        rows = 24,
        cols = 80,
        title = "◐  decoy-title-must-not-be-identity",
        status = status,
        sessionName = name,
        windowIndex = "1",
        windowName = name,
        provider = if (status == "working") "claude_code" else "codex",
        activity = status,
        health = if (status == "working" || status == "idle") "normal" else "unknown",
    )
}
