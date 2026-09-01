package dev.agentmirror.app.ui.model

import dev.agentmirror.app.workspace.L2Status

/**
 * 会话运行状态。本工程是三态（062）：Busy / Idle / Unknown。
 * ⛔ unknown 绝不能当 Idle —— 那是把「不知道」染成「确定空闲」。
 */
enum class SessionStatus { Busy, Idle, Abnormal, Unknown }

/** Provider 标记的封闭状态集；未知或非法轴值只能投影为 [Unknown]。 */
enum class ProviderMarkState { Running, Idle, Abnormal, Unknown }

/** Provider 标记渲染所需的规范身份、资源键、状态与强调度。 */
data class ProviderPresentation(
    val providerId: String,
    val label: String,
    val assetKey: String?,
    val state: ProviderMarkState,
    val emphasis: Float,
)

private val providerLabels = mapOf(
    "claude_code" to "Claude Code",
    "codex" to "Codex",
    "copilot" to "Copilot",
    "grok" to "Grok Code",
    "cursor" to "Cursor",
    "pi" to "Pi",
)

fun sessionStatusFromAxes(activity: String, health: String): SessionStatus = when {
    health == "abnormal" -> SessionStatus.Abnormal
    health == "normal" && activity == "working" -> SessionStatus.Busy
    health == "normal" && activity == "idle" -> SessionStatus.Idle
    else -> SessionStatus.Unknown
}

fun providerPresentation(provider: String, activity: String, health: String): ProviderPresentation {
    val id = provider.takeIf { it in providerLabels } ?: "unknown"
    val state = when {
        health == "abnormal" -> ProviderMarkState.Abnormal
        health == "normal" && activity == "working" -> ProviderMarkState.Running
        health == "normal" && activity == "idle" -> ProviderMarkState.Idle
        else -> ProviderMarkState.Unknown
    }
    return ProviderPresentation(id, providerLabels[id] ?: "未知 Provider", id.takeUnless { it == "unknown" }, state, if (state == ProviderMarkState.Idle || state == ProviderMarkState.Unknown) .4f else 1f)
}

/**
 * 把本工程现有三态 [L2Status] 转成设计包 [SessionStatus]。
 *
 * @contract
 * @pre none
 * @post WORKING→Busy, IDLE→Idle, UNKNOWN→Unknown；unknown 不会变成 Idle
 * @err none
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
    val provider: String = "unknown",
    val activity: String = "unknown",
    val health: String = "unknown",
)

/** 底部导航的三个位置 */
enum class NavTab { Favorites, Sessions, Settings }
