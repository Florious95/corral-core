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

import dev.agentmirror.app.conn.CloseSessionResultFrame
import dev.agentmirror.app.conn.ConnectionState
import dev.agentmirror.app.conn.Level2Frame
import dev.agentmirror.app.conn.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceCloseSessionTest {

    private fun makeSession(ref: String, name: String = "agent"): Session = Session(
        ref = ref,
        name = name,
        sessionName = "main",
        windowIndex = "0",
        windowName = "win",
        cwd = "/workspace",
        rows = 24,
        cols = 80,
        title = "pane title",
        status = "idle",
        health = "normal",
    )

    @Test
    fun closeSessionSendsRequestAndSetsClosingRef() {
        var requestedRef: String? = null
        val vm = WorkspaceViewModel(
            initialConnection = ConnectionUi.READY,
            closeSessionRequest = { ref ->
                requestedRef = ref
                101L
            },
        )

        val sent = vm.closeSession("sock\u001f%1")
        assertTrue("closeSession 发送成功", sent)
        assertEquals("sock\u001f%1", requestedRef)
        assertEquals("sock\u001f%1", vm.closingSessionRef.value)
    }

    @Test
    fun closeSessionOkRemovesSessionAndRefreshes() {
        var refreshed = false
        val vm = WorkspaceViewModel(
            initialConnection = ConnectionUi.READY,
            closeSessionRequest = { 202L },
            subscribeLevel2 = { refreshed = true },
        )

        // 模拟二级菜单进入并下发两个会话
        vm.onStateChanged(ConnectionState.READY)
        vm.enterLevel2("/workspace")
        vm.onFrame(
            Level2Frame(
                workspace = "/workspace",
                seq = 1L,
                sessions = listOf(
                    makeSession("sock\u001f%1", "agent-1"),
                    makeSession("sock\u001f%2", "agent-2"),
                ),
            ),
        )
        assertEquals(2, vm.level2.value.sessions.size)

        // 发起关闭第一个 pane
        vm.closeSession("sock\u001f%1")
        assertEquals("sock\u001f%1", vm.closingSessionRef.value)

        // 模拟服务端响应 close_session_result ok=true
        refreshed = false
        vm.onFrame(CloseSessionResultFrame(reqId = 202L, ok = true, reason = ""))

        // 断言已成功从列表中移除目标会话，并复位 closing 状态与触发刷新
        assertNull(vm.closingSessionRef.value)
        val remaining = vm.level2.value.sessions
        assertEquals(1, remaining.size)
        assertEquals("sock\u001f%2", remaining.first().ref)
        assertTrue("关闭成功后触发 refreshLevel2", refreshed)
    }

    @Test
    fun closeSessionUnfavoritesIfStarred() {
        val store = MemoryFavoriteStore()
        val vm = WorkspaceViewModel(
            initialConnection = ConnectionUi.READY,
            closeSessionRequest = { 303L },
            favoriteStore = store,
        )

        vm.onStateChanged(ConnectionState.READY)
        vm.enterLevel2("/workspace")
        val s1 = makeSession("sock\u001f%1", "agent-1")
        vm.onFrame(
            Level2Frame(
                workspace = "/workspace",
                seq = 1L,
                sessions = listOf(s1),
            ),
        )

        // 收藏该会话
        vm.toggleFavorite(s1.toL2Entry())
        assertEquals(1, store.load().size)

        // 关闭该会话
        vm.closeSession("sock\u001f%1")

        // 验证收藏记录已被自动同步清理
        assertEquals(0, store.load().size)
    }

    @Test
    fun closeSessionFailSetsErrorBanner() {
        val vm = WorkspaceViewModel(
            initialConnection = ConnectionUi.READY,
            closeSessionRequest = { 404L },
        )

        vm.closeSession("sock\u001f%1")
        assertEquals("sock\u001f%1", vm.closingSessionRef.value)

        // 收到失败帧
        vm.onFrame(CloseSessionResultFrame(reqId = 404L, ok = false, reason = "kill-pane failed: pane not found"))

        assertNull(vm.closingSessionRef.value)
        assertTrue(vm.level2.value.banner?.contains("kill-pane failed") == true)
    }
}
