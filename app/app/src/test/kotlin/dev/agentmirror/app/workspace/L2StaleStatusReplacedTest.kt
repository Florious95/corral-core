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

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
 * 真机：曾 working 现 idle，徽章仍冻在「进行中」。
 * 缓存优先必须保留；新快照到达后 **同一会话身份的 status 必须一起换成 idle**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class L2StaleStatusReplacedTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun cachedWorkingBecomesIdleWhenNewSnapshotArrives() {
        val vm = WorkspaceViewModel(
            requestList = {},
            subscribeLevel2 = {},
            unsubscribeLevel2 = {},
        )
        val ref = "/sock\u001f%0"
        val identity = Triple("advisor", "0", "advisor")

        vm.enterLevel2("/proj")
        vm.onFrame(snap("/proj", 1, ref, identity, "working"))
        assertEquals(L2Status.WORKING, statusOf(vm, ref))

        vm.leaveLevel2()
        // 离开后仍可能收到一帧 idle（退订缝）。必须写进缓存，不能因没订着就丢掉。
        vm.onFrame(
            snap(
                "/proj",
                2,
                ref,
                identity,
                "idle",
                title = "对照席定点变异验红绿判据 - grok",
            ),
        )
        vm.enterLevel2("/proj")
        assertEquals("缝里到达的 idle 必须写进缓存，再进不得仍是 working", L2Status.IDLE, statusOf(vm, ref))

        compose.setContent {
            AgentMirrorTheme {
                val level2 by vm.level2.collectAsState()
                L2SessionList(sessions = level2.sessions, onOpenSession = { _, _ -> })
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("空闲").assertExists()
        compose.onNodeWithText("进行中").assertDoesNotExist()

        // 订着时同一身份再从 idle 推 working 又推 idle，徽章必须跟着换。
        vm.onFrame(snap("/proj", 3, ref, identity, "working"))
        compose.waitForIdle()
        assertEquals(L2Status.WORKING, statusOf(vm, ref))
        compose.onNodeWithText("进行中").assertExists()
        vm.onFrame(
            snap("/proj", 4, ref, identity, "idle", title = "对照席定点变异验红绿判据 - grok"),
        )
        compose.waitForIdle()
        assertEquals(L2Status.IDLE, statusOf(vm, ref))
        compose.onNodeWithText("空闲").assertExists()
        compose.onNodeWithText("进行中").assertDoesNotExist()
    }

    private fun statusOf(vm: WorkspaceViewModel, ref: String): L2Status =
        vm.level2.value.sessions.single { it.ref == ref }.status

    private fun snap(
        ws: String,
        seq: Long,
        ref: String,
        identity: Triple<String, String, String>,
        status: String,
        title: String = "⠼ - Waiting for response… - x",
    ) = Level2Frame(
        workspace = ws,
        seq = seq,
        sessions = listOf(
            Session(
                ref = ref,
                name = identity.third,
                cwd = ws,
                rows = 24,
                cols = 80,
                title = title,
                status = status,
                sessionName = identity.first,
                windowIndex = identity.second,
                windowName = identity.third,
                provider = if (status == "working") "claude_code" else "codex",
                activity = status,
                health = if (status == "working" || status == "idle") "normal" else "unknown",
            ),
        ),
    )
}
