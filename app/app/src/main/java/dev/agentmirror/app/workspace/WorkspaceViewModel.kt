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
 * 连接状态的 UI 映射（展示层只用这些态渲染顶栏/引导）。
 *
 * CONNECTING 合并 conn 层的 CONNECTING 与 AUTHENTICATING（对用户同为"连接中"）。
 * UNBOUND 是无 pairing 记录、未启动常驻连接的入口态，不是 READY、也不是 STOPPED。
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

    /** 未绑定：无 pairing 记录，不拨号。 */
    UNBOUND,
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

    /** 未绑定且无列表 = 安静空工作区，不是加载、也不是 READY 空引导。 */
    val isQuietEmpty: Boolean get() = connection == ConnectionUi.UNBOUND && workspaces.isEmpty()

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
 * 刷新模型（2026-08-15 用户裁定 + 069 + 075）：[enterLevel1] / [refresh] 发一次 list；
 * [enterLevel2] 画缓存后发一次 level2 订阅（含同连接再进）。新 [ListingFrame] /
 * [Level2Frame] 到达后复位刷新标记。一级若首帧已在（补播/缓存），[enterLevel1]
 * 仍发 list 但不把 [refreshing] 再挂 true——否则 PullToRefresh 指示器悬到下一次
 * listing，而 handleList 重扫一卡住就永不回弹。**零周期性自动刷新**。
 * [onConfigurationChange] 与 [onListScroll] 不是进入，不发帧。
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
    private var lastQuietDiagnosticStale: Boolean? = null

    /**
     * 旋转/配置变更重建时置位：下一次 [enterLevel1] / [enterLevel2] 不得再发 list
     * 或重订阅（069：进入不含旋转，否则退化成高频扫描）。
     */
    private var suppressEnterRefresh: Boolean = false

    /**
     * Activity 从 savedInstanceState 重建时调用。下一次进菜单入口只恢复画面，不发刷新。
     */
    fun suppressNextEnterRefresh() {
        suppressEnterRefresh = true
        DiagLog.record(
            "refresh",
            "suppressNextEnterRefresh set=true subscribed=${subscribedWorkspace ?: "<none>"} " +
                "l1_cached=${workspaceCounts.size} l2_cached=${level2Cache.size}",
        )
    }

    /** 本轮组合已消费抑制位（旋转首帧过后，用户再进菜单必须恢复刷新）。 */
    fun clearEnterRefreshSuppress() {
        if (suppressEnterRefresh) {
            DiagLog.record("refresh", "clearEnterRefreshSuppress was=true")
        }
        suppressEnterRefresh = false
    }

    fun shouldSuppressEnterRefresh(): Boolean = suppressEnterRefresh

    /**
     * 进入一级：先保留已画列表（062），再发一次 [requestList]。
     * 旋转抑制时只保留画面，不发 list。
     * 首帧已在时只发 list、不置 [refreshing]（075：指示器跟的是「还在等首帧」，
     * 不是「又发了一次 list」）。无首帧时走 [refresh] 置转圈，等 [applyListing]。
     *
     * @post 未抑制时发出一次 list；[uiState].workspaces 不被本方法清空
     * @inv 本方法不写空列表
     */
    fun enterLevel1() {
        if (_uiState.value.connection == ConnectionUi.UNBOUND) {
            DiagLog.record(
                "refresh",
                "enterLevel1 skipped unbound=true cached=${_uiState.value.workspaces.size} " +
                    "refreshing_prev=${_refreshing.value} refreshing_next=${_refreshing.value}",
            )
            return
        }
        val cached = _uiState.value.workspaces.size
        val prevRefreshing = _refreshing.value
        if (suppressEnterRefresh) {
            DiagLog.record(
                "refresh",
                "enterLevel1 skipped suppress=true cached=$cached " +
                    "refreshing_prev=$prevRefreshing refreshing_next=$prevRefreshing",
            )
            return
        }
        if (cached > 0) {
            // 075：首帧已到（补播/上次 listing）。069 仍发 list；不得再把
            // PullToRefresh 挂成 refreshing=true 干等到下一次 listing。
            requestList()
            DiagLog.record(
                "refresh",
                "enterLevel1 list cached=$cached first_frame=already " +
                    "refreshing_prev=$prevRefreshing refreshing_next=${_refreshing.value}",
            )
            return
        }
        DiagLog.record(
            "refresh",
            "enterLevel1 list cached=$cached first_frame=missing " +
                "refreshing_prev=$prevRefreshing → refresh()",
        )
        refresh()
    }

    /**
     * 进入二级：立刻画该 cwd 的缓存（没有才空），再发一次 level2 订阅。
     * 同 cwd 再进也重发订阅——服务端同一连接再订会 wakeLevel2（069），
     * 不能只靠 0→1。旋转抑制时只画缓存，不重订。
     * 不调用 [requestList]。不先清空再画。
     *
     * @post [level2] 为该 cwd 缓存，或首次进入时为空；未抑制时已发订阅
     * @inv 有缓存时本方法不会把 [level2].sessions 写成空表
     */
    fun enterLevel2(cwd: String) {
        if (subscribedWorkspace != cwd) {
            subscribedWorkspace?.let { prev ->
                rememberLevel2(prev, _level2.value)
                if (!suppressEnterRefresh) {
                    unsubscribeLevel2(prev)
                }
            }
            subscribedWorkspace = cwd
            lastLevel2AtMs = 0L
            // 有缓存就立刻画旧列表；从未进过才允许空态。禁止先写空再写缓存。
            publishLevel2(level2Cache[cwd] ?: L2UiState())
        }
        val painted = _level2.value.sessions.size
        if (suppressEnterRefresh) {
            DiagLog.record(
                "refresh",
                "enterLevel2 skipped subscribe suppress=true cwd=$cwd painted=$painted",
            )
            return
        }
        DiagLog.record(
            "refresh",
            "enterLevel2 subscribe cwd=$cwd painted=$painted cached=${level2Cache[cwd]?.sessions?.size ?: 0}",
        )
        subscribeLevel2(cwd)
    }

    /**
     * 旋转/配置变更：不是「进入」。不得发 list、不得重订二级。
     */
    fun onConfigurationChange() {
        DiagLog.record(
            "refresh",
            "onConfigurationChange no-op subscribed=${subscribedWorkspace ?: "<none>"} " +
                "l1=${_uiState.value.workspaces.size} l2=${_level2.value.sessions.size}",
        )
    }

    /**
     * 列表内滚动：不是「进入」。不得发 list、不得重订二级。
     */
    fun onListScroll() {
        DiagLog.record(
            "refresh",
            "onListScroll no-op subscribed=${subscribedWorkspace ?: "<none>"}",
        )
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
        val stale = quietFor >= quietTimeoutMs
        if (lastQuietDiagnosticStale != stale) {
            DiagLog.record(
                "level2",
                "quiet_check workspace=$ws last_at=$lastLevel2AtMs now=$now quiet_for=$quietFor " +
                    "timeout=$quietTimeoutMs stale=$stale",
            )
            lastQuietDiagnosticStale = stale
        }
        val banner = if (stale) {
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
        if (_uiState.value.connection == ConnectionUi.UNBOUND) {
            DiagLog.record(
                "refresh",
                "refresh() skipped unbound=true refreshing_prev=${_refreshing.value} " +
                    "cached=${_uiState.value.workspaces.size}",
            )
            return
        }
        val prev = _refreshing.value
        _refreshing.value = true
        DiagLog.record(
            "refresh",
            "refresh() refreshing_prev=$prev refreshing_next=true cached=${_uiState.value.workspaces.size}",
        )
        requestList()
    }

    fun toggleFavorite(entry: L2Entry) {
        DiagLog.record(
            "favorite",
            "toggleFavorite src=star ref=${entry.ref} session_name=${entry.sessionName} " +
                "window_index=${entry.windowIndex} window_name=${entry.windowName} " +
                "cwd=${entry.cwd} name=${entry.name} title_len=${entry.title.length}",
        )
        favoriteBook.toggle(
            ref = entry.ref,
            sessionName = entry.sessionName,
            windowIndex = entry.windowIndex,
            windowName = entry.windowName,
            cwd = entry.cwd,
        )
        _favorites.value = favoriteBook.records()
        bumpFavoriteLive()
    }

    fun toggleFavorite(row: FavoriteRow) {
        favoriteBook.toggle(
            ref = row.ref,
            sessionName = row.sessionName,
            windowIndex = row.windowIndex,
            windowName = row.windowName,
            cwd = row.cwd,
        )
        _favorites.value = favoriteBook.records()
        bumpFavoriteLive()
    }

    /**
     * 「查看」浮层的数据源：按**当前会话**解析工作区，再取该工作区缓存。
     * 不读 [_level2] 单例——它会被最后一次 [enterLevel2] / 收藏写成别的工作区。
     *
     * @contract
     * @pre [sessionRef] 为当前三级会话的结构 ref（可空串；空则空表）
     * @post [ViewMenuSource.overlayWorkspace] == [ViewMenuSource.currentWorkspace]；
     *       sessions 全属该工作区；[lastPublishedWorkspace] 是对照用的单例键
     * @err 解析不到工作区时 sessions 为空，overlay 键为空，**不回落单例**
     * @inv 不改身份键、不改 [_level2]、不改收藏簿
     */
    fun viewMenuSource(sessionRef: String): ViewMenuSource {
        val currentWs = resolveWorkspaceForSession(sessionRef)
        val currentSocket = socketPrefixFromRef(sessionRef)
        val lastPublished = subscribedWorkspace.orEmpty().ifEmpty {
            _level2.value.sessions.firstOrNull()?.cwd.orEmpty()
        }
        val sessions = if (currentWs.isEmpty()) {
            emptyList()
        } else {
            level2Cache[currentWs]?.sessions
                ?: if (subscribedWorkspace == currentWs) _level2.value.sessions else emptyList()
        }
        val overlayWs = currentWs
        val overlaySocket = sessions.firstOrNull()?.let { socketPrefixFromRef(it.ref) }
            .orEmpty()
            .ifEmpty { currentSocket }
        DiagLog.record(
            "view-menu",
            "viewMenuSource current_ref=$sessionRef current_ws=$currentWs " +
                "current_socket=$currentSocket overlay_ws=$overlayWs " +
                "overlay_socket=$overlaySocket last_published_ws=$lastPublished " +
                "sessions=${sessions.size} source_match=${overlayWs == currentWs} " +
                "singleton_mismatch=${overlayWs != lastPublished}",
        )
        return ViewMenuSource(
            currentSessionRef = sessionRef,
            currentWorkspace = currentWs,
            currentSocket = currentSocket,
            overlayWorkspace = overlayWs,
            overlaySocket = overlaySocket,
            lastPublishedWorkspace = lastPublished,
            sessions = sessions,
        )
    }

    /**
     * 会话页在屏：订当前会话工作区的 level2，给顶栏灯和「查看」同一份推送。
     *
     * 收藏进会话不会经过 [enterLevel2]（三栏 HorizontalPager 在收藏页时不组 Sessions），
     * 若不在这里订，overlay 停在进页瞬间的缓存或空表，灯卡在 Idle/Unknown。
     * 已订同一 cwd 则不再发订阅（069：不是周期扫描）。
     *
     * @contract
     * @pre [sessionRef] 为当前三级会话结构 ref
     * @post cwd 可解析且尚未订阅时发出一次 [enterLevel2]；已订同一 cwd 为 no-op
     * @err cwd 解析不到（收藏/缓存/hint 都空）只记操作数，不猜、不订
     * @inv 不改身份键、不改收藏簿；unknown 不回落 idle
     */
    fun enterSessionLive(sessionRef: String, workspaceHint: String? = null) {
        val src = viewMenuSource(sessionRef)
        val resolved = src.currentWorkspace
        val cwd = resolved.ifEmpty { workspaceHint.orEmpty() }
        val already = cwd.isNotEmpty() && subscribedWorkspace == cwd
        val willSubscribe = cwd.isNotEmpty() && !already
        DiagLog.record(
            "session-live",
            "enterSessionLive src=session-route ref=$sessionRef " +
                "resolved_ws=${resolved.ifEmpty { "<empty>" }} " +
                "hint_ws=${workspaceHint?.ifEmpty { "<empty>" } ?: "<none>"} " +
                "use_ws=${cwd.ifEmpty { "<empty>" }} " +
                "subscribed=${subscribedWorkspace ?: "<none>"} already=$already " +
                "cache_sessions=${src.sessions.size} will_subscribe=$willSubscribe",
        )
        if (!willSubscribe) return
        enterLevel2(cwd)
    }

    /**
     * 当前会话所属工作区：收藏簿 cwd → 各工作区二级缓存里的 ref → 当前单例里的 ref。
     * 顺序故意把单例放最后，且命中单例时仍用该行自己的 cwd，不用 subscribedWorkspace 兜底成「最后收藏的」。
     */
    private fun resolveWorkspaceForSession(ref: String): String {
        if (ref.isEmpty()) return ""
        _favorites.value.firstOrNull { it.ref == ref }?.cwd?.takeIf { it.isNotEmpty() }?.let { return it }
        for ((cwd, state) in level2Cache) {
            if (state.sessions.any { it.ref == ref }) {
                return cwd.ifEmpty { state.sessions.firstOrNull { it.ref == ref }?.cwd.orEmpty() }
            }
        }
        _level2.value.sessions.firstOrNull { it.ref == ref }?.cwd?.takeIf { it.isNotEmpty() }?.let { return it }
        return ""
    }

    /**
     * 进入收藏页时的取数账本（082）。
     * favoriteWorkspaceCount = 收藏项覆盖的工作区数；
     * fetchedWorkspaceCount = 实际发出过 level2 订阅并收到帧（或超时跳过不算）的工作区数。
     */
    data class FavoriteFetchStats(
        val favoriteWorkspaceCount: Int = 0,
        val fetchedWorkspaceCount: Int = 0,
    )

    private var favoriteWorkspaceCount: Int = 0
    private val favoriteFetched = LinkedHashSet<String>()
    private val favoriteFetchQueue = ArrayDeque<String>()
    private var favoriteFetchInFlight: String? = null
    private var favoriteFetchStartedAtMs: Long = 0L

    private val _favoriteLiveGen = MutableStateFlow(0)

    /** 任一工作区缓存更新时递增，收藏页据此重算对账，不依赖二级单例。 */
    val favoriteLiveGen: StateFlow<Int> = _favoriteLiveGen.asStateFlow()

    fun favoriteFetchStats(): FavoriteFetchStats = FavoriteFetchStats(
        favoriteWorkspaceCount = favoriteWorkspaceCount,
        fetchedWorkspaceCount = favoriteFetched.size,
    )

    /**
     * 进入收藏页：按**每个收藏项自己的工作区**各发一次 level2 订阅（串行，
     * 服务端每连接只绑一个 workspace）。不是下拉刷新，也不是周期轮询（061）。
     */
    fun enterFavorites() {
        val cwds = LinkedHashSet<String>()
        for (rec in _favorites.value) {
            if (rec.cwd.isNotEmpty()) cwds.add(rec.cwd)
        }
        favoriteWorkspaceCount = cwds.size
        favoriteFetched.clear()
        favoriteFetchQueue.clear()
        favoriteFetchInFlight = null
        favoriteFetchStartedAtMs = 0L
        favoriteFetchQueue.addAll(cwds)
        DiagLog.record(
            "favorite",
            "enterFavorites favorite_workspaces=$favoriteWorkspaceCount " +
                "fetched_workspaces=${favoriteFetched.size} " +
                "cwds=${cwds.joinToString()} queue=${favoriteFetchQueue.size}",
        )
        bumpFavoriteLive()
        pumpFavoriteFetch()
    }

    /** 离开收藏页：停队列；退订正在飞的那一个（当前二级工作区除外）。 */
    fun leaveFavorites() {
        favoriteFetchQueue.clear()
        val inflight = favoriteFetchInFlight
        favoriteFetchInFlight = null
        favoriteFetchStartedAtMs = 0L
        if (inflight != null && inflight != subscribedWorkspace) {
            unsubscribeLevel2(inflight)
        }
        DiagLog.record(
            "favorite",
            "leaveFavorites favorite_workspaces=$favoriteWorkspaceCount " +
                "fetched_workspaces=${favoriteFetched.size} inflight=${inflight ?: "<none>"}",
        )
    }

    /**
     * 收藏取数超时（UI 带 now 调用，不自起定时器）。超时只跳到下一个工作区，不重发、不轮询。
     */
    fun checkFavoriteFetch(now: Long = nowMs(), timeoutMs: Long = 8_000L) {
        val inflight = favoriteFetchInFlight ?: return
        if (favoriteFetchStartedAtMs == 0L) return
        val waited = now - favoriteFetchStartedAtMs
        if (waited < timeoutMs) return
        DiagLog.record(
            "favorite",
            "favoriteFetch timeout cwd=$inflight waited_ms=$waited timeout_ms=$timeoutMs " +
                "favorite_workspaces=$favoriteWorkspaceCount fetched_workspaces=${favoriteFetched.size}",
        )
        if (inflight != subscribedWorkspace) unsubscribeLevel2(inflight)
        favoriteFetchInFlight = null
        favoriteFetchStartedAtMs = 0L
        pumpFavoriteFetch()
    }

    private fun pumpFavoriteFetch() {
        if (favoriteFetchInFlight != null) return
        val next = favoriteFetchQueue.removeFirstOrNull() ?: return
        favoriteFetchInFlight = next
        favoriteFetchStartedAtMs = nowMs()
        DiagLog.record(
            "favorite",
            "favoriteFetch start cwd=$next favorite_workspaces=$favoriteWorkspaceCount " +
                "fetched_workspaces=${favoriteFetched.size} queue_left=${favoriteFetchQueue.size}",
        )
        subscribeLevel2(next)
    }

    private fun onFavoriteWorkspaceFetched(cwd: String) {
        if (favoriteFetchInFlight != cwd) return
        favoriteFetched.add(cwd)
        DiagLog.record(
            "favorite",
            "favoriteFetch done cwd=$cwd favorite_workspaces=$favoriteWorkspaceCount " +
                "fetched_workspaces=${favoriteFetched.size} queue_left=${favoriteFetchQueue.size}",
        )
        if (cwd != subscribedWorkspace) unsubscribeLevel2(cwd)
        favoriteFetchInFlight = null
        favoriteFetchStartedAtMs = 0L
        pumpFavoriteFetch()
    }

    private fun bumpFavoriteLive() {
        _favoriteLiveGen.update { it + 1 }
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
        val prev = _refreshing.value
        _refreshing.value = false
        DiagLog.record(
            "refresh",
            "applyListing seq=${frame.seq} workspaces=${frame.workspaces.size} " +
                "refreshing_prev=$prev refreshing_next=false",
        )
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
        val prevByRef = (level2Cache[frame.workspace]?.sessions ?: emptyList())
            .associate { it.ref to it.status }
        rememberLevel2(frame.workspace, next)
        bumpFavoriteLive()
        onFavoriteWorkspaceFetched(frame.workspace)
        val ws = subscribedWorkspace
        val publish = ws != null && frame.workspace == ws
        val statusOps = incoming.joinToString(",") { e ->
            val prev = prevByRef[e.ref]?.wire ?: "<none>"
            "${e.ref.takeLast(24)}:$prev->${e.status.wire}"
        }
        DiagLog.record(
            "level2",
            "applyLevel2 src=level2-push workspace=${frame.workspace} seq=${frame.seq} " +
                "subscribed=${ws ?: "<none>"} incoming=${incoming.size} " +
                "publish=$publish statuses=$statusOps",
        )
        if (!publish) return
        lastLevel2AtMs = nowMs()
        publishLevel2(next)
        // 074：转圈跟的是 listing 往返；二级首帧是 level2_frame。首帧到了就必须停转。
        _refreshing.value = false
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
