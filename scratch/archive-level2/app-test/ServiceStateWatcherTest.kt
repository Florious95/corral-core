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

package dev.agentmirror.app.service

import dev.agentmirror.app.conn.AgentState
import dev.agentmirror.app.conn.ListDeltaFrame
import dev.agentmirror.app.conn.ListingFrame
import dev.agentmirror.app.conn.Session
import dev.agentmirror.app.conn.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StateWatcher 纯 JVM 单测（验收 --tests "*Service*"，类名含 Service）。
 *
 * 红测先行（fg-service 知识基底 §4）：→blocked 沿触发一次且仅一次、同状态不重复、
 * unknown 永不通知、会话消失清除其通知，各一条；另补离开 blocked 清除、
 * 初始基线不通知、断连期间变化在重连时补齐触发，共 8 条。
 *
 * 2026-08-15（用户裁定「去除完成这个状态」，058）：done 沿相关测试随 StateWatcher 的
 * done 支持一并移除——done 不再触发通知（服务端不产 done）。
 */
class ServiceStateWatcherTest {

    private val notifies = mutableListOf<Triple<String, String, AgentState>>()
    private val clears = mutableListOf<String>()

    private fun freshWatcher(): StateWatcher =
        StateWatcher(
            onNotify = { ref, name, state -> notifies.add(Triple(ref, name, state)) },
            onClear = { ref -> clears.add(ref) },
        )

    // ---- 帧构造辅助（协议对象直构，不走编解码） ----

    private fun session(ref: String, name: String, cwd: String, state: AgentState): Session =
        Session(ref = ref, name = name, cwd = cwd, state = state, rows = 24, cols = 80)

    private fun ws(cwd: String, vararg sessions: Session): Workspace =
        Workspace(
            cwd = cwd,
            sessionCount = sessions.size,
            aggregateState = AgentState.UNKNOWN,
            sessions = sessions.toList(),
        )

    private fun listing(vararg workspaces: Workspace): ListingFrame =
        ListingFrame(reqId = 1, seq = 1, workspaces = workspaces.toList())

    private fun added(vararg sessions: Session): ListDeltaFrame =
        ListDeltaFrame(seq = 1, addedSessions = sessions.toList())

    private fun changed(vararg sessions: Session): ListDeltaFrame =
        ListDeltaFrame(seq = 1, changedSessions = sessions.toList())

    private fun removed(vararg refs: String): ListDeltaFrame =
        ListDeltaFrame(seq = 1, removedRefs = refs.toList())

    // ---- 沿触发：→blocked ----

    /** →blocked 沿触发一次且仅一次；同状态重复 delta 不重复推送。 */
    @Test
    fun transitionToBlocked_firesOnceAndOnlyOnce() {
        val w = freshWatcher()
        w.onFrame(listing(ws("/a", session("s1", "claude", "/a", AgentState.WORKING))))
        w.onFrame(changed(session("s1", "claude", "/a", AgentState.BLOCKED)))
        w.onFrame(changed(session("s1", "claude", "/a", AgentState.BLOCKED)))

        assertEquals(listOf(Triple("s1", "claude", AgentState.BLOCKED)), notifies)
        assertTrue(clears.isEmpty())
    }

    /** unknown 永不通知：不因首次见/变化而通知。 */
    @Test
    fun unknown_neverNotifies() {
        val w = freshWatcher()
        w.onFrame(added(session("s1", "claude", "/a", AgentState.UNKNOWN)))
        w.onFrame(changed(session("s1", "claude", "/a", AgentState.UNKNOWN)))
        w.onFrame(changed(session("s1", "claude", "/a", AgentState.WORKING)))

        assertTrue(notifies.isEmpty())
    }

    // ---- 通知生命周期：离开/消失清除 ----

    /** 会话消失（removed_refs）→ 清除其通知。 */
    @Test
    fun sessionDisappearance_clearsNotification() {
        val w = freshWatcher()
        w.onFrame(listing(ws("/a", session("s1", "claude", "/a", AgentState.WORKING))))
        w.onFrame(changed(session("s1", "claude", "/a", AgentState.BLOCKED)))
        w.onFrame(removed("s1"))

        assertEquals(listOf("s1"), clears)
    }

    /** 离开 blocked/done（状态恢复）→ 清除其通知，无需人再注意。 */
    @Test
    fun leavingBlocked_clearsNotification() {
        val w = freshWatcher()
        w.onFrame(listing(ws("/a", session("s1", "claude", "/a", AgentState.WORKING))))
        w.onFrame(changed(session("s1", "claude", "/a", AgentState.BLOCKED)))
        w.onFrame(changed(session("s1", "claude", "/a", AgentState.WORKING)))

        assertEquals(listOf("s1"), clears)
    }

    /** blocked 进入后状态未变（同状态重复）→ 抑制，不重复推送（原 blocked→done 刷新路径随 done 删除）。 */
    @Test
    fun blockedRepeat_suppressed() {
        val w = freshWatcher()
        w.onFrame(listing(ws("/a", session("s1", "claude", "/a", AgentState.WORKING))))
        w.onFrame(changed(session("s1", "claude", "/a", AgentState.BLOCKED)))
        w.onFrame(changed(session("s1", "claude", "/a", AgentState.BLOCKED)))

        assertEquals(listOf(Triple("s1", "claude", AgentState.BLOCKED)), notifies)
        assertTrue(clears.isEmpty())
    }

    // ---- 基线语义：初始 listing 不通知，重连补齐 ----

    /** 初始 listing 是基线：已处于需要注意态（blocked）的会话不通知（沿变化才通知，非全量罗列）。 */
    @Test
    fun initialListing_establishesBaseline_noFire() {
        val w = freshWatcher()
        w.onFrame(
            listing(
                ws(
                    "/a",
                    session("s1", "claude", "/a", AgentState.BLOCKED),
                    session("s2", "codex", "/a", AgentState.IDLE),
                ),
            ),
        )

        assertTrue(notifies.isEmpty())
        assertTrue(clears.isEmpty())
    }

    /** 基线已 blocked 的会话，随后同状态 delta 也不通知（prev 已存在 ⇒ 非变化）。 */
    @Test
    fun baselineBlocked_deltaSameBlocked_suppressed() {
        val w = freshWatcher()
        w.onFrame(listing(ws("/a", session("s1", "claude", "/a", AgentState.BLOCKED))))
        w.onFrame(changed(session("s1", "claude", "/a", AgentState.BLOCKED)))

        assertTrue(notifies.isEmpty())
    }

    /** 断连期间的状态变化：重连全量 listing 与旧状态比对，变化一次触发、未变化不触发。 */
    @Test
    fun reconnectListing_firesForChangeAccumulatedDuringDisconnect() {
        val w = freshWatcher()
        w.onFrame(listing(ws("/a", session("s1", "claude", "/a", AgentState.WORKING))))
        // 断连期间 s1 → blocked；重连全量列表揭示了变化。
        w.onFrame(listing(ws("/a", session("s1", "claude", "/a", AgentState.BLOCKED))))
        // 再次全量（如后续重连）仍是 blocked：不重复通知。
        w.onFrame(listing(ws("/a", session("s1", "claude", "/a", AgentState.BLOCKED))))

        assertEquals(listOf(Triple("s1", "claude", AgentState.BLOCKED)), notifies)
    }

    // ---- 新增会话 ----

    /** added_sessions 直接以 blocked 到达（需要人的新会话）→ 通知。 */
    @Test
    fun addedSessionInBlocked_notifies() {
        val w = freshWatcher()
        w.onFrame(listing(ws("/a", session("s1", "claude", "/a", AgentState.WORKING))))
        w.onFrame(added(session("s2", "codex", "/a", AgentState.BLOCKED)))

        assertEquals(listOf(Triple("s2", "codex", AgentState.BLOCKED)), notifies)
    }
}
