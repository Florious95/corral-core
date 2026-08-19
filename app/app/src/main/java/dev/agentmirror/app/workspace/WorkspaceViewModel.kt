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
import dev.agentmirror.app.conn.Level2Frame
import dev.agentmirror.app.conn.Level2HeartbeatFrame
import dev.agentmirror.app.conn.ListDeltaFrame
import dev.agentmirror.app.conn.ListingFrame
import dev.agentmirror.app.diag.DiagLog
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
 * 061：二级状态挂在本 VM（已接 [ServiceWire.uiConnector]），禁止再 new 独立 Listener
 * 去 [ConnectionManager.setListener]。
 *
 * @contract
 * @pre none（任意时刻可构造；任意帧可到达，无关帧被忽略）
 * @post 收到 ListingFrame 整体替换一级工作区模型；收到 ListDeltaFrame 按 changed_workspaces
 *       增量更新 session_count；收到 Level2Frame 全量替换 [level2]；收到 Level2HeartbeatFrame
 *       只刷新 seq / 最后收包时刻，不清列表
 * @err 解码失败记 type+原因；workspace 对不上记两边 cwd，不改列表
 * @inv 工作区保服务端下发顺序；二级只收推送，本类无周期 list / 无定时向服务端拉状态
 */
class WorkspaceViewModel(
    initialConnection: ConnectionUi = ConnectionUi.CONNECTING,
    private val requestList: () -> Unit = { ServiceWire.managerOrNull()?.list() },
    private val subscribeLevel2: (String) -> Unit = { cwd ->
        ServiceWire.managerOrNull()?.subscribeLevel2(cwd)
        Unit
    },
    private val unsubscribeLevel2: (String) -> Unit = { cwd ->
        ServiceWire.managerOrNull()?.unsubscribeLevel2(cwd)
        Unit
    },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    favoriteStore: FavoriteStore = MemoryFavoriteStore(),
) : ConnectionManager.Listener {

    private val favoriteBook = FavoriteBook(favoriteStore, nowMs)

    private val _favorites = MutableStateFlow(favoriteBook.records())

    /** 已收藏记录（加入时间序由 [FavoriteBook.rows] 再倒排）。 */
    val favorites: StateFlow<List<FavoriteRecord>> = _favorites.asStateFlow()

    private val _uiState = MutableStateFlow(WorkspaceUiState(connection = initialConnection))

    /** 唯一渲染源（Compose 屏 collectAsState 消费）。 */
    val uiState: StateFlow<WorkspaceUiState> = _uiState.asStateFlow()

    /** 刷新请求在途标记（进入即刷 / 下拉刷共用；新 listing 到达复位）。 */
    private val _refreshing = MutableStateFlow(false)

    /** 刷新在途标记（Compose 下拉指示器消费）。 */
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _level2 = MutableStateFlow(L2UiState())

    /** 二级菜单快照（由 [onFrame] 吃 level2_frame / level2_heartbeat）。 */
    val level2: StateFlow<L2UiState> = _level2.asStateFlow()

    /**
     * 按工作区 cwd 记住上一次非空/已到达的二级快照（062 §四）。
     * 离开不清这项；再进同一 cwd 立即画它，不先发空列表。
     */
    private val level2Cache = LinkedHashMap<String, L2UiState>()

    private var subscribedWorkspace: String? = null
    private var lastLevel2AtMs: Long = 0L

    /**
     * 进入二级：立刻画该 cwd 的缓存（没有才空），再订推送。同 cwd 再进幂等。
     * 不调用 [requestList]。不先清空再画。
     *
     * @post [level2] 为该 cwd 缓存，或首次进入时为空；已发订阅
     * @inv 有缓存时本方法不会把 [level2].sessions 写成空表
     */
    fun enterLevel2(cwd: String) {
        if (subscribedWorkspace == cwd) return
        subscribedWorkspace?.let { prev ->
            rememberLevel2(prev, _level2.value)
            unsubscribeLevel2(prev)
        }
        subscribedWorkspace = cwd
        lastLevel2AtMs = 0L
        // 有缓存就立刻画旧列表；从未进过才允许空态。禁止先写空再写缓存。
        publishLevel2(level2Cache[cwd] ?: L2UiState())
        subscribeLevel2(cwd)
    }

    /**
     * 离开二级：退订，**保留**该 cwd 缓存，不清空已发布的列表。
     * 再进同一工作区时 [enterLevel2] 直接画缓存。
     */
    fun leaveLevel2() {
        val ws = subscribedWorkspace ?: return
        rememberLevel2(ws, _level2.value)
        subscribedWorkspace = null
        lastLevel2AtMs = 0L
        unsubscribeLevel2(ws)
    }

    /**
     * 心跳/帧超时检查（UI 带 now 调用，本 VM 不自起定时器）。
     * 超时只改横幅，不清列表，不向服务端发帧。
     */
    fun checkLevel2Quiet(now: Long = nowMs(), quietTimeoutMs: Long = 20_000L) {
        val ws = subscribedWorkspace ?: return
        if (lastLevel2AtMs == 0L) return
        val quietFor = now - lastLevel2AtMs
        val banner = if (quietFor >= quietTimeoutMs) {
            "二级状态已停更 ${quietFor}ms（last_at=$lastLevel2AtMs now=$now workspace=$ws）"
        } else {
            null
        }
        val current = _level2.value
        if (current.banner != banner) {
            _level2.value = current.copy(banner = banner)
        }
    }

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

    fun toggleFavorite(entry: L2Entry) {
        val (sess, idx, win) = entry.favoriteIdentity()
        DiagLog.record(
            "favorite",
            "toggleFavorite src=star session_name=$sess window_index=$idx " +
                "window_name=$win raw_triple=${entry.sessionName}/${entry.windowIndex}/${entry.windowName} " +
                "name=${entry.name} title_len=${entry.title.length}",
        )
        favoriteBook.toggle(sess, idx, win)
        _favorites.value = favoriteBook.records()
    }

    fun toggleFavorite(row: FavoriteRow) {
        favoriteBook.toggle(row.sessionName, row.windowIndex, row.windowName)
        _favorites.value = favoriteBook.records()
    }

    /** 收藏行：live 对账（全部已见工作区缓存 + 当前二级），失联保留置灰，按 addedAt 倒序。 */
    fun favoriteRows(live: List<L2Entry> = liveForFavorites()): List<FavoriteRow> =
        favoriteBook.rows(live)

    private fun liveForFavorites(): List<L2Entry> {
        val byKey = LinkedHashMap<FavoriteKey, L2Entry>()
        for (state in level2Cache.values) {
            for (entry in state.sessions) {
                byKey[entry.favoriteKey()] = entry
            }
        }
        for (entry in _level2.value.sessions) {
            byKey[entry.favoriteKey()] = entry
        }
        val subscribed = subscribedWorkspace
        // 已订工作区若已收到快照，以当前表为准：缓存里多出来的键视为失联。
        if (subscribed != null && lastLevel2AtMs != 0L) {
            val liveNow = HashSet<FavoriteKey>()
            for (entry in _level2.value.sessions) liveNow.add(entry.favoriteKey())
            val cached = level2Cache[subscribed]?.sessions.orEmpty()
            for (entry in cached) {
                if (entry.favoriteKey() !in liveNow) byKey.remove(entry.favoriteKey())
            }
        }
        return ArrayList(byKey.values)
    }

    /** 内部模型：cwd → session_count（保服务端下发顺序）。 */
    private val workspaceCounts = LinkedHashMap<String, Int>()

    // ---- ConnectionManager.Listener（接线层经 ServiceWire.uiConnector 原样路由进来）----
    // 与 SessionViewModel 同款接线语义：VM 实现 Listener 供接线层把 uiConnector 扇出的回调
    // 原样路由进来；不自行 setListener（共享连接由 [ServiceWire.manager] 进程级单例持有，
    // 其内部包装监听把事件喂给调用方 connListener 并扇出到 [ServiceWire.uiConnector]）。

    /** 连接态回调（Listener 入口）：委托 [onConnectionStateChanged] 保持公开 API 不变。 */
    override fun onStateChanged(state: ConnectionState) = onConnectionStateChanged(state)

    override fun onFrame(frame: FramePayload) {
        when (frame) {
            is ListingFrame -> applyListing(frame)
            is ListDeltaFrame -> applyDelta(frame)
            is Level2Frame -> applyLevel2(frame)
            is Level2HeartbeatFrame -> applyLevel2Heartbeat(frame)
            else -> Unit
        }
    }

    override fun onBinary(frame: BinaryFrame) = Unit

    override fun onLocalDecodeError(code: FrameError, message: String) {
        DiagLog.record("level2", "decode failed code=$code reason=$message")
        if (subscribedWorkspace != null) {
            _level2.update { it.copy(banner = "二级帧解码失败 code=$code reason=$message") }
        }
    }

    override fun onInputResult(reqId: Long, ok: Boolean, reason: String?) = Unit

    override fun onReconnect(attempt: Int, delayMs: Long) = Unit

    fun onConnectionStateChanged(state: ConnectionState) {
        _uiState.update { it.copy(connection = state.toUi()) }
        if (state == ConnectionState.READY) {
            subscribedWorkspace?.let { subscribeLevel2(it) }
        }
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

    private fun applyLevel2(frame: Level2Frame) {
        // 快照整表替换，status 以本帧为准（同一会话身份 working→idle 必须换徽章）。
        // 即使当前没订着这个 cwd（离开二级的缝里），也先写入缓存，避免再进去仍是旧状态。
        val incoming = frame.sessions.map { it.toL2Entry() }
        val next = L2UiState(sessions = incoming, seq = frame.seq, banner = null)
        rememberLevel2(frame.workspace, next)
        val ws = subscribedWorkspace
        if (ws == null || frame.workspace != ws) {
            DiagLog.record(
                "level2",
                "workspace mismatch frame=${frame.workspace} subscribed=${ws ?: "<none>"} seq=${frame.seq} cached=${incoming.size}",
            )
            return
        }
        lastLevel2AtMs = nowMs()
        publishLevel2(next)
    }

    /** 记下该 cwd 最近一次快照。空表不覆盖已有缓存（避免「先空白」写进记忆）。 */
    private fun rememberLevel2(cwd: String, state: L2UiState) {
        if (state.sessions.isEmpty()) return
        level2Cache[cwd] = state
    }

    private fun publishLevel2(state: L2UiState) {
        _level2.value = state
    }

    private fun applyLevel2Heartbeat(frame: Level2HeartbeatFrame) {
        val ws = subscribedWorkspace
        if (ws == null || frame.workspace != ws) {
            DiagLog.record(
                "level2",
                "heartbeat workspace mismatch frame=${frame.workspace} subscribed=${ws ?: "<none>"} seq=${frame.seq}",
            )
            return
        }
        lastLevel2AtMs = nowMs()
        _level2.update { it.copy(seq = frame.seq, banner = null) }
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
