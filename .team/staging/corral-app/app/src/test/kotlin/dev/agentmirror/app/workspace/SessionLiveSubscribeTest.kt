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

import dev.agentmirror.app.conn.Level2Frame
import dev.agentmirror.app.conn.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * t.live：收藏进会话（不经过二级菜单）必须订当前工作区 level2。
 * 灯和「查看」读同一份 [viewMenuSource]；没这条订阅时缓存停在 idle、行数为 0。
 */
class SessionLiveSubscribeTest {

    @Test
    fun enterSessionLiveFromFavoriteSubscribesAndFollowsWorkingFrame() {
        val subscribed = mutableListOf<String>()
        val unsubscribed = mutableListOf<String>()
        val wvm = WorkspaceViewModel(
            requestList = {},
            subscribeLevel2 = { subscribed.add(it) },
            unsubscribeLevel2 = { unsubscribed.add(it) },
            favoriteStore = MemoryFavoriteStore(),
        )
        val idle = entry("idle")
        wvm.toggleFavorite(idle)

        val before = wvm.viewMenuSource(REF)
        assertEquals("改前必红：没进二级就没有缓存", 0, before.sessions.size)
        assertTrue("还没订过", subscribed.isEmpty())

        wvm.enterSessionLive(REF)
        assertEquals(listOf(CWD), subscribed)
        assertEquals(CWD, wvm.viewMenuSource(REF).currentWorkspace)

        wvm.onFrame(frame(1, "idle"))
        assertEquals(L2Status.IDLE, wvm.viewMenuSource(REF).sessions.single().status)

        wvm.onFrame(frame(2, "working"))
        val after = wvm.viewMenuSource(REF)
        assertEquals(1, after.sessions.size)
        assertEquals(L2Status.WORKING, after.sessions.single().status)
        assertEquals("已订同一 cwd 不得周期重订", 1, subscribed.size)
        assertTrue(unsubscribed.isEmpty())
    }

    @Test
    fun enterSessionLiveIsNoopWhenAlreadySubscribedToSameWorkspace() {
        val subscribed = mutableListOf<String>()
        val wvm = WorkspaceViewModel(
            requestList = {},
            subscribeLevel2 = { subscribed.add(it) },
            unsubscribeLevel2 = {},
            favoriteStore = MemoryFavoriteStore(),
        )
        wvm.enterLevel2(CWD)
        wvm.onFrame(frame(1, "idle"))
        assertEquals(1, subscribed.size)
        assertEquals(CWD, wvm.viewMenuSource(REF).currentWorkspace)
        wvm.enterSessionLive(REF)
        assertEquals("会话页再进同一工作区不得再发订阅", 1, subscribed.size)
    }

    @Test
    fun enterSessionLiveDoesNotGuessWorkspaceWhenUnresolved() {
        val subscribed = mutableListOf<String>()
        val wvm = WorkspaceViewModel(
            requestList = {},
            subscribeLevel2 = { subscribed.add(it) },
            unsubscribeLevel2 = {},
            favoriteStore = MemoryFavoriteStore(),
        )
        wvm.enterSessionLive("missing-ref")
        assertTrue(subscribed.isEmpty())
        assertEquals(0, wvm.viewMenuSource("missing-ref").sessions.size)
    }

    private fun entry(status: String) = Session(
        ref = REF,
        name = "远程控制 app 开发",
        cwd = CWD,
        rows = 24,
        cols = 80,
        title = "◐ 远程控制 app 开发",
        status = status,
        sessionName = "team",
        windowIndex = "1",
        windowName = "claude_code",
    ).toL2Entry()

    private fun frame(seq: Long, status: String) = Level2Frame(
        workspace = CWD,
        seq = seq,
        sessions = listOf(
            Session(
                ref = REF,
                name = "远程控制 app 开发",
                cwd = CWD,
                rows = 24,
                cols = 80,
                title = "◐ 远程控制 app 开发",
                status = status,
                sessionName = "team",
                windowIndex = "1",
                windowName = "claude_code",
            ),
        ),
    )

    companion object {
        private const val CWD = "/Volumes/nvme/Projects/远程Agent安卓"
        private const val SOCK = "/tmp/tmux-1000/android"
        private const val REF = "$SOCK\u001f%2"
    }
}
