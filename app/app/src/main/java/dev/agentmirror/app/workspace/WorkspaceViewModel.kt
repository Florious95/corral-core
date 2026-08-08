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

import dev.agentmirror.app.conn.AgentState
import dev.agentmirror.app.conn.ConnectionState
import dev.agentmirror.app.conn.FramePayload
import dev.agentmirror.app.conn.ListDeltaFrame
import dev.agentmirror.app.conn.ListingFrame
import dev.agentmirror.app.conn.Session
import dev.agentmirror.app.conn.Workspace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 连接状态的 UI 映射（展示层只用这四态渲染顶栏/引导）。
 *
 * CONNECTING 合并 conn 层的 CONNECTING 与 AUTHENTICATING（对用户同为"连接中"）。
 * 断连（RECONNECTING/STOPPED）由 conn 层自动重连，UI 只反映状态（004 无状态免疫）。
 */
enum class ConnectionUi {
    /** 拨号中/认证中。 */
    CONNECTING,

    /** 认证通过，可交换业务帧。 */
    READY,

    /** 掉线，退避重连中。 */
    RECONNECTING,

    /** 永久关闭（auth 被拒 / 显式 stop）。 */
    STOPPED,
}

/** 二级会话条目（渲染层薄模型）；ref 是寻址键，name 是展示标签（可重名，002）。 */
data class SessionUi(
    val ref: String,
    val name: String,
    val state: AgentState,
)

/** 一级工作区条目：cwd 聚合。聚合字段全部来自服务端权威值，客户端只渲染不重算（012）。 */
data class WorkspaceUi(
    val cwd: String,
    val sessionCount: Int,
    val aggregateState: AgentState,
    val sessions: List<SessionUi>,
)

/** 工作区首页整体 UI 状态（唯一渲染源）。 */
data class WorkspaceUiState(
    val connection: ConnectionUi = ConnectionUi.CONNECTING,
    val workspaces: List<WorkspaceUi> = emptyList(),
) {
    /** 就绪且无工作区 = 空态，给引导文案（无工作区 ≠ 错误）。 */
    val isEmpty: Boolean get() = connection == ConnectionUi.READY && workspaces.isEmpty()

    /** 断连（重连中/已关闭）：顶栏提示，列表保留最后一次已知状态。 */
    val isDisconnected: Boolean
        get() = connection == ConnectionUi.RECONNECTING || connection == ConnectionUi.STOPPED
}

/**
 * 工作区两级导航的纯 JVM 视图模型（知识基底 §1 分层：ViewModel 消费 conn 层
 * listing/list_delta 帧流 → UI 状态，Compose 屏只做薄渲染）。
 *
 * 输入侧是回调（由接线层把 [ConnectionManager.Listener.onFrame] /
 * [ConnectionManager.Listener.onStateChanged] 接进来）；seq 跳变与 delta 先于 listing
 * 等一致性恢复已由 conn 层 ConnectionManager 自动重新 list（conn 知识基底 §1），本层
 * 只按顺序渲染收到的 listing / list_delta，不自行推导聚合。
 *
 * 聚合语义（docs/protocol.md §5 + requirement 012）：
 * - session_count / aggregate_state 是服务端权威值，客户端只渲染、不重算；
 * - delta 无 removed_workspaces 通道：会话全走的空工作区由本层从一级列表移除（渲染必需）；
 * - changed_workspaces 中 sessions 可省略，只携带 cwd/count/aggregate 语义。
 */
class WorkspaceViewModel(
    initialConnection: ConnectionUi = ConnectionUi.CONNECTING,
) {

    private val _uiState = MutableStateFlow(WorkspaceUiState(connection = initialConnection))

    /** 唯一渲染源（Compose 屏 collectAsState 消费）。 */
    val uiState: StateFlow<WorkspaceUiState> = _uiState.asStateFlow()

    /** 内部模型：cwd → 可变工作区（保服务端下发顺序；sessions 按 ref 索引）。 */
    private val workspaceModels = LinkedHashMap<String, WorkspaceModel>()

    private class WorkspaceModel(
        val cwd: String,
        var sessionCount: Int,
        var aggregateState: AgentState,
        val sessions: LinkedHashMap<String, SessionUi> = LinkedHashMap(),
    )

    // ---- 输入侧（接线层回调入口）----

    /** 连接状态回调入口：把 conn 层 ConnectionState 映射为 UI 四态。 */
    fun onConnectionStateChanged(state: ConnectionState) {
        _uiState.update { it.copy(connection = state.toUi()) }
    }

    /** 帧回调入口：只消费 listing / list_delta，其余帧忽略（本屏不关心）。 */
    fun onFrame(frame: FramePayload) {
        when (frame) {
            is ListingFrame -> applyListing(frame)
            is ListDeltaFrame -> applyDelta(frame)
            else -> Unit // 无关帧（auth_ack/input_ack/error/…）不影响列表渲染。
        }
    }

    // ---- listing：权威全量，整体替换 ----

    private fun applyListing(frame: ListingFrame) {
        workspaceModels.clear()
        for (w in frame.workspaces) {
            val model = WorkspaceModel(w.cwd, w.sessionCount, w.aggregateState)
            for (s in w.sessions) model.sessions[s.ref] = s.toUi()
            workspaceModels[w.cwd] = model
        }
        publish()
    }

    // ---- delta：按协议 §5.3 四组字段增量落位 ----

    private fun applyDelta(frame: ListDeltaFrame) {
        // 1. 增/改：added_sessions 与 changed_sessions 都按 ref 落位（upsert）。
        //    added 携带"完整当前值"，可新建工作区；changed 按 replace 应用。
        for (s in frame.addedSessions) upsertSession(s, createdByAdd = true)
        for (s in frame.changedSessions) upsertSession(s, createdByAdd = false)
        // 2. 删：removed_refs 按 ref 从所在工作区移除（count 是服务端权威，不在此递减）。
        for (ref in frame.removedRefs) removeSessionByRef(ref)
        // 3. 元数据：changed_workspaces 覆盖 session_count / aggregate_state（服务端权威）。
        //    sessions 可省略；对未见过的 cwd 忽略（一致流下不会出现，防御性跳过）。
        for (w in frame.changedWorkspaces) {
            workspaceModels[w.cwd]?.let { m ->
                m.sessionCount = w.sessionCount
                m.aggregateState = w.aggregateState
                if (w.sessions.isNotEmpty()) {
                    m.sessions.clear()
                    for (s in w.sessions) m.sessions[s.ref] = s.toUi()
                }
            }
        }
        pruneEmptyWorkspaces()
        publish()
    }

    /** 按 ref 落位会话：新 cwd 建占位工作区；ref 换 cwd 时迁居到新工作区。 */
    private fun upsertSession(s: Session, createdByAdd: Boolean) {
        val oldHome = findRefHome(s.ref)
        if (oldHome != null && oldHome != s.cwd) {
            workspaceModels[oldHome]?.sessions?.remove(s.ref)
        }
        val target = workspaceModels[s.cwd] ?: WorkspaceModel(
            cwd = s.cwd,
            // 占位值：无权威元数据前，单会话工作区的聚合恒等于该会话自身状态（012 规则 3
            // 亦兼容：单 unknown 会话 → 聚合 unknown），后续 changed_workspaces 会纠正。
            sessionCount = 0,
            aggregateState = s.state,
        ).also { workspaceModels[s.cwd] = it }
        target.sessions[s.ref] = s.toUi()
        // added_sessions 新建工作区且尚无权威 count 时，兜底按已知会话数占位（012：不推导聚合）。
        if (createdByAdd && oldHome == null && target.sessionCount == 0) {
            target.sessionCount = target.sessions.size
        }
    }

    /** 按 ref 删除会话；空工作区交由 [pruneEmptyWorkspaces] 统一清理。 */
    private fun removeSessionByRef(ref: String) {
        findRefHome(ref)?.let { workspaceModels[it]?.sessions?.remove(ref) }
    }

    /** 定位 ref 当前所在工作区 cwd；不存在返回 null。 */
    private fun findRefHome(ref: String): String? {
        for ((cwd, m) in workspaceModels) {
            if (m.sessions.containsKey(ref)) return cwd
        }
        return null
    }

    /** 会话全走的空工作区从一级列表移除（协议无 removed_workspaces 通道，渲染必需）。 */
    private fun pruneEmptyWorkspaces() {
        workspaceModels.entries.removeAll { it.value.sessions.isEmpty() }
    }

    /** 把内部模型快照发布为 UI 状态（保序、按 ref 入表顺序）。 */
    private fun publish() {
        _uiState.update {
            it.copy(
                workspaces = workspaceModels.values.map { m ->
                    WorkspaceUi(
                        cwd = m.cwd,
                        sessionCount = m.sessionCount,
                        aggregateState = m.aggregateState,
                        sessions = m.sessions.values.toList(),
                    )
                },
            )
        }
    }

    private fun Session.toUi() = SessionUi(ref = ref, name = name, state = state)

    private fun ConnectionState.toUi(): ConnectionUi = when (this) {
        ConnectionState.CONNECTING, ConnectionState.AUTHENTICATING -> ConnectionUi.CONNECTING
        ConnectionState.READY -> ConnectionUi.READY
        ConnectionState.RECONNECTING -> ConnectionUi.RECONNECTING
        ConnectionState.STOPPED -> ConnectionUi.STOPPED
    }
}
