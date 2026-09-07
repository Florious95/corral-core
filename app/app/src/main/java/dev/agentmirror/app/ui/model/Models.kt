package dev.agentmirror.app.ui.model

import dev.agentmirror.app.workspace.L2Status

/**
 * 会话运行状态。本工程是三态（062）：Busy / Idle / Unknown。
 * ⛔ unknown 绝不能当 Idle —— 那是把「不知道」染成「确定空闲」。
 */
enum class SessionStatus { Busy, Idle, Unknown }

/**
 * 把本工程现有三态 [L2Status] 转成设计包 [SessionStatus]。
 *
 * @contract
 * @pre none
 * @post WORKING→Busy, IDLE→Idle, UNKNOWN→Unknown；unknown 不会变成 Idle
 * @err none
 * @inv unknown never becomes Idle
 */
fun sessionStatusFromL2(status: L2Status): SessionStatus = when (status) {
    L2Status.WORKING -> SessionStatus.Busy
    L2Status.IDLE -> SessionStatus.Idle
    L2Status.UNKNOWN -> SessionStatus.Unknown
}

/**
 * 线协议三态 → [SessionStatus]。缺省 / 乱值一律 Unknown，不得回落 Idle。
 *
 * @contract
 * @pre none
 * @post working→Busy, idle→Idle, 其余（含 unknown/空串/垃圾）→Unknown
 * @err none
 * @inv garbage and empty strings map to Unknown, never Idle
 */
fun sessionStatusFromWire(raw: String): SessionStatus =
    sessionStatusFromL2(L2Status.fromWire(raw))

/** 工作区列表（一级） */
data class WorkspaceItem(
    val id: String,
    val name: String,
    val path: String,
    val sessionCount: Int,
)

/**
 * 会话。
 * displayName 是会话的真实显示名，可能是中文（例如「远控 leader」），
 * ⛔ 不要按 ASCII 宽度排版，也不要在这里预截断。
 */
data class SessionItem(
    val id: String,
    val displayName: String,
    val path: String,
    val status: SessionStatus,
    val starred: Boolean,
    /** 收藏页失联行：false 时标「不在线」，不得当成 Idle。默认在线（二级列表）。 */
    val isOnline: Boolean = true,
    /** Canonical provider id from the status-core DTO. APP must not guess. */
    val provider: String = "unknown",
    /** Closed health axis from the status-core DTO. */
    val health: String = "unknown",
)

/**
 * Left-slot motion for the unified external list row.
 *
 * Working or idle motion follows already-resolved activity on an online row.
 * Health remains an independent metadata axis and never changes this motion.
 * Unknown activity and offline rows remain quiet.
 *
 * @contract
 * @pre [activity] is already fail-closed (unknown on divergence/garbage)
 * @post Online+known activity renders; offline or unknown activity yields None
 * @err none
 * @inv health never changes motion; unknown activity and offline never animate
 * @consumes dev.agentmirror.app.workspace
 */
enum class SessionRowMotion { Working, Idle, None }

/**
 * Fail-closed left-slot motion from the resolved activity and online state.
 *
 * @contract
 * @pre activity is already fail-closed (unknown on divergence/garbage)
 * @post Online+Busy yields Working; online+Idle yields Idle; otherwise None
 * @err none
 * @inv health metadata is not consulted; no motion is invented for unknown/offline rows
 */
fun sessionRowMotion(
    activity: SessionStatus,
    isOnline: Boolean,
): SessionRowMotion {
    if (!isOnline) return SessionRowMotion.None
    return when (activity) {
        SessionStatus.Busy -> SessionRowMotion.Working
        SessionStatus.Idle -> SessionRowMotion.Idle
        SessionStatus.Unknown -> SessionRowMotion.None
    }
}

/** 底部导航的三个位置 */
enum class NavTab { Favorites, Sessions, Settings }