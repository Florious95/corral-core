package dev.agentmirror.app.ui.model

/** 会话运行状态。UI 只认这两个，映射由你那边做。 */
enum class SessionStatus { Busy, Idle }

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
)

/** 底部导航的三个位置 */
enum class NavTab { Favorites, Sessions, Settings }
