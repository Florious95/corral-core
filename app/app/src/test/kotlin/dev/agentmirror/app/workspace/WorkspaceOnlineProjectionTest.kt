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

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.agentmirror.app.conn.Level2Frame
import dev.agentmirror.app.conn.Session
import dev.agentmirror.app.ui.theme.AgentMirrorTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 对话首页会话列表：失联行仍在，标「不在线」，短按不打开。
 * idle / waiting / unknown 只要仍在 live 快照里就必须当在线。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkspaceOnlineProjectionTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun sessionListKeepsDroppedRowOfflineAndKeepsOnlineIdleWaitingUnknown() {
        val vm = WorkspaceViewModel(
            requestList = {},
            subscribeLevel2 = {},
            unsubscribeLevel2 = {},
            favoriteStore = MemoryFavoriteStore(),
        )
        var opened: Pair<String, String>? = null
        val idle = sample("ref-idle", "idle", "idle", "idle", "1")
        val waiting = sample("ref-waiting", "waiting", "waiting", "waiting", "2")
        val unknown = sample("ref-unknown", "unknown", "unknown", "unknown", "3", health = "unknown")
        vm.enterLevel2(CWD)
        vm.onFrame(frame(1, idle, waiting, unknown))

        compose.setContent {
            AgentMirrorTheme {
                WorkspaceScreen(
                    viewModel = vm,
                    selectedWorkspaceCwd = CWD,
                    onSelectWorkspace = {},
                    onBackToList = {},
                    onOpenSettings = {},
                    onOpenSession = { ref, name -> opened = ref to name },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("l2-row-ref-idle").assertExists()
        compose.onNodeWithTag("l2-row-ref-waiting").assertExists()
        compose.onNodeWithTag("l2-row-ref-unknown").assertExists()
        compose.onNodeWithText("不在线", useUnmergedTree = true).assertDoesNotExist()

        vm.onFrame(frame(2, idle, waiting))
        compose.waitForIdle()
        compose.onNodeWithTag("l2-row-ref-idle").assertExists()
        compose.onNodeWithTag("l2-row-ref-waiting").assertExists()
        compose.onNodeWithTag("l2-row-ref-unknown").assertExists()
        compose.onNodeWithTag("l2-offline-ref-unknown", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("l2-offline-ref-idle", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("l2-offline-ref-waiting", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("l2-row-ref-unknown").performClick()
        compose.runOnIdle { assertNull(opened) }
        assertEquals(listOf("ref-idle", "ref-waiting"), vm.level2.value.sessions.map { it.ref })

        vm.onFrame(Level2Frame(workspace = CWD, seq = 3, sessions = emptyList()))
        compose.waitForIdle()
        compose.onNodeWithTag("l2-row-ref-idle").assertExists()
        compose.onNodeWithTag("l2-row-ref-waiting").assertExists()
        compose.onNodeWithTag("l2-row-ref-unknown").assertExists()
        compose.onNodeWithTag("l2-offline-ref-idle", useUnmergedTree = true).assertExists()
        compose.onAllNodesWithText("不在线", useUnmergedTree = true).assertCountEquals(3)
        compose.onNodeWithTag("l2-row-ref-idle").performClick()
        compose.runOnIdle { assertNull(opened) }
        assertTrue(vm.level2.value.sessions.isEmpty())

        vm.onFrame(frame(4, idle, waiting, unknown))
        compose.waitForIdle()
        compose.onNodeWithTag("l2-offline-ref-idle", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("l2-offline-ref-waiting", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("l2-offline-ref-unknown", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("l2-row-ref-idle").performClick()
        compose.runOnIdle { assertEquals("ref-idle" to "idle", opened) }
    }

    private fun frame(seq: Long, vararg sessions: Session) = Level2Frame(
        workspace = CWD,
        seq = seq,
        sessions = sessions.toList(),
    )

    private fun sample(
        ref: String,
        name: String,
        activity: String,
        status: String,
        windowIndex: String,
        health: String = "normal",
    ) = Session(
        ref = ref,
        name = name,
        cwd = CWD,
        rows = 24,
        cols = 80,
        title = name,
        activity = activity,
        status = status,
        health = health,
        sessionName = name,
        windowIndex = windowIndex,
        windowName = name,
    )
}

private const val CWD = "/proj/a"
