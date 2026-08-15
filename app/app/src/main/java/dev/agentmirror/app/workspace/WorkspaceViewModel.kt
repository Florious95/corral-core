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

import dev.agentmirror.app.conn.BinaryFrame
import dev.agentmirror.app.conn.ConnectionManager
import dev.agentmirror.app.conn.ConnectionState
import dev.agentmirror.app.conn.FrameError
import dev.agentmirror.app.conn.FramePayload
import dev.agentmirror.app.conn.ListDeltaFrame
import dev.agentmirror.app.conn.ListingFrame
import dev.agentmirror.app.service.ServiceWire
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 连接状态的 UI 映射（展示层只用这四态渲染顶栏/引导）。
 *
 * CONNECTING 合并 conn 层的 CONNECTING 与 AUTHENTICATING（对用户同为"连接中"）。
 * 断连态由 conn 层管理：RECONNECTING 自动退避重连；STOPPED 是永久关闭（auth 被拒 /
 * 显式 stop），不再自动重连。UI 只反映状态，不决策（004 无状态免疫）。
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

/**
 * 一级工作区条目：cwd 聚合。session_count 以服务端权威值为准，客户端只渲染不重算。
 *
 * 060 uproot（2026-08-15）：二级会话列表模型（会话条目 / sessions）与聚合状态随
 * 状态判定整体拔除；一级菜单只保留 cwd 与会话数。
 */
data class WorkspaceUi(
    val cwd: String,
    val sessionCount: Int,
)

/** 工作区首页整体 UI 状态（唯一渲染源）。 */
data class WorkspaceUiState(
    val connection: ConnectionUi = ConnectionUi.CONNECTING,
    val workspaces: List<WorkspaceUi> = emptyList(),
) {
    /** 连接未就绪且无缓存列表 = 加载态；此时不能提前显示“暂无工作区”。 */
    val isLoading: Boolean get() = connection == ConnectionUi.CONNECTING && workspaces.isEmpty()

    /** 就绪且无工作区 = 空态，给引导文案（无工作区 ≠ 错误）。 */
    val isEmpty: Boolean get() = connection == ConnectionUi.READY && workspaces.isEmpty()

    /** 断连（重连中/已关闭）：顶栏提示，列表保留最后一次已知状态。 */
    val isDisconnected: Boolean
        get() = connection == ConnectionUi.RECONNECTING || connection == ConnectionUi.STOPPED
}

/**
 * 工作区一级菜单的纯 JVM 视图模型（知识基底 §1 分层：ViewModel 消费 conn 层
 * listing/list_delta 帧流 → UI 状态，Compose 屏只做薄渲染）。
 *
 * 输入侧是回调（由接线层把 [ConnectionManager.Listener.onFrame] /
 * [ConnectionManager.Listener.onStateChanged] 接进来）；seq 跳变与 delta 先于 listing
 * 等一致性恢复已由 conn 层 ConnectionManager 自动重新 list（conn 知识基底 §1），本层
 * 只按顺序渲染收到的 listing / list_delta，不自行推导聚合。
 *
 * 刷新模型（2026-08-15 用户裁定）：[refresh] 是进入一级与下拉共用的主动拉新入口，
 * 置刷新在途标记并发出一次全量列表请求；新 [ListingFrame] 到达后复位。**零周期性自动刷新**
 * （禁令）——本类无周期拉取结构（无无限循环/周期定时器/固定延迟协程）；主动刷新只在
 * 用户进入/下拉时发生。
 *
 * 060 uproot（2026-08-15）：二级会话列表模型（会话条目 / 落位逻辑等）
 * 与聚合状态随状态判定整体拔除。本 VM 只维护一级工作区（cwd → session_count）。
 *
 * @contract
 * @pre none（任意时刻可构造；任意帧可到达，无关帧被忽略）
 * @post 收到 ListingFrame 整体替换一级工作区模型；收到 ListDeltaFrame 按 changed_workspaces
 *       增量更新 session_count；[onConnectionStateChanged] 把 [ConnectionState] 映射为 UI 四态；
 *       [refresh] 置刷新在途标记并发出一次全量列表请求
 * @err none（无异常面；无关帧走 else 分支忽略，不破坏状态）
 * @inv 工作区保服务端下发顺序（LinkedHashMap 插入序）；session_count 以服务端权威值为准；
 *       零周期性自动刷新（无周期拉取结构）
 */
class WorkspaceViewModel(
    initialConnection: ConnectionUi = ConnectionUi.CONNECTING,
    private val requestList: () -> Unit = { ServiceWire.managerOrNull()?.list() },
) : ConnectionManager.Listener {

    private val _uiState = MutableStateFlow(WorkspaceUiState(connection = initialConnection))

    /** 唯一渲染源（Compose 屏 collectAsState 消费）。 */
    val uiState: StateFlow<WorkspaceUiState> = _uiState.asStateFlow()

    /** 刷新请求在途标记（进入即刷 / 下拉刷共用；新 listing 到达复位）。 */
    private val _refreshing = MutableStateFlow(false)

    /** 刷新在途标记（Compose 下拉指示器消费）。 */
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /**
     * 全量列表刷新入口（2026-08-15 用户裁定刷新模型）。
     *
     * 进入一级的 LaunchedEffect 与下拉手势共调本入口：置刷新在途标记并发出一次
     * [ConnectionManager.list] 请求；新 [ListingFrame] 到达后由 [applyListing] 复位标记。
     * 刷新不重复换列表——conn 层 READY 后自动 list 的既有语义保留，本入口是**主动**拉新。
     *
     * @contract
     * @pre none（连接未就绪时 list() 内部自判返回 false，不抛）
     * @post 刷新标记置位；经 [requestList] 发出一次全量列表请求（连接就绪时）
     * @err none（[requestList] 默认实现经 managerOrNull 空安全，不抛）
     * @inv 本方法只发请求，不改列表；列表只在 [applyListing] 时整体替换
     */
    fun refresh() {
        _refreshing.value = true
        requestList()
    }

    /** 内部模型：cwd → session_count（保服务端下发顺序）。 */
    private val workspaceCounts = LinkedHashMap<String, Int>()

    // ---- ConnectionManager.Listener（接线层经 ServiceWire.uiConnector 原样路由进来）----
    // 与 SessionViewModel 同款接线语义：VM 实现 Listener 供接线层把 uiConnector 扇出的回调
    // 原样路由进来；不自行 setListener（共享连接由 [ServiceWire.manager] 进程级单例持有，
    // 其内部包装监听把事件喂给调用方 connListener 并扇出到 [ServiceWire.uiConnector]）。

    /** 连接态回调（Listener 入口）：委托 [onConnectionStateChanged] 保持公开 API 不变。 */
    override fun onStateChanged(state: ConnectionState) = onConnectionStateChanged(state)

    /** 帧回调（Listener 入口）：只消费 listing / list_delta，其余帧忽略（本屏不关心）。 */
    override fun onFrame(frame: FramePayload) {
        when (frame) {
            is ListingFrame -> applyListing(frame)
            is ListDeltaFrame -> applyDelta(frame)
            else -> Unit // 无关帧（auth_ack/input_ack/error/…）不影响列表渲染。
        }
    }

    // 镜像/解码错误/输入回执/重连通知归会话页与服务层，工作区列表不消费（空实现防泄漏）。

    override fun onBinary(frame: BinaryFrame) = Unit

    override fun onLocalDecodeError(code: FrameError, message: String) = Unit

    override fun onInputResult(reqId: Long, ok: Boolean, reason: String?) = Unit

    override fun onReconnect(attempt: Int, delayMs: Long) = Unit

    // ---- 输入侧（接线层回调入口，公开 API）----

    /** 连接状态回调入口：把 conn 层 ConnectionState 映射为 UI 四态。 */
    fun onConnectionStateChanged(state: ConnectionState) {
        _uiState.update { it.copy(connection = state.toUi()) }
    }

    // ---- listing：权威全量，整体替换 ----

    private fun applyListing(frame: ListingFrame) {
        // 新 listing 到达 = 刷新完成：复位刷新在途标记（进入/下拉刷共用语义）。
        _refreshing.value = false
        workspaceCounts.clear()
        for (w in frame.workspaces) {
            workspaceCounts[w.cwd] = w.sessionCount
        }
        publish()
    }

    // ---- delta：按协议 §5.3 增量更新一级工作区 ----

    private fun applyDelta(frame: ListDeltaFrame) {
        // 二级会话增删（added/changed/removed sessions）是二级实时流的数据源，
        // 不在本一级 VM 消费；一级只关心 changed_workspaces 里的 session_count 元数据。
        for (w in frame.changedWorkspaces) {
            workspaceCounts[w.cwd] = w.sessionCount
        }
        // 一级菜单的 session_count 是服务端权威值；removed 会话对一级的意义由
        // changed_workspaces 携带（无 removed_workspaces 通道，服务端保证覆盖）。
        publish()
    }

    /** 把内部模型快照发布为 UI 状态（保序）。 */
    private fun publish() {
        _uiState.update {
            it.copy(
                workspaces = workspaceCounts.map { (cwd, count) ->
                    WorkspaceUi(cwd = cwd, sessionCount = count)
                },
            )
        }
    }

    private fun ConnectionState.toUi(): ConnectionUi = when (this) {
        ConnectionState.CONNECTING, ConnectionState.AUTHENTICATING -> ConnectionUi.CONNECTING
        ConnectionState.READY -> ConnectionUi.READY
        ConnectionState.RECONNECTING -> ConnectionUi.RECONNECTING
        ConnectionState.STOPPED -> ConnectionUi.STOPPED
    }
}
