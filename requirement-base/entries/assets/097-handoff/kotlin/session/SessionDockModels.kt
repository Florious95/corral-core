/**
 * ─────────────────────────────────────────────────────────────
 * SessionDockModels.kt — 对话页底部 dock 的纯数据模型
 *
 * 对应设计稿：底部「倒数第二行」的三种形态与会话快捷块。
 * 无任何逻辑与状态；全部由宿主（现有 SessionScreen.kt）持有并下发。
 * ─────────────────────────────────────────────────────────────
 */
package dev.agentmirror.app.session

/** 倒数第二行的三种互斥形态。默认进入 [Sessions]（设计验收决议）。 */
enum class DockRowMode {
    /** 其他会话的快捷跳转块（默认态） */
    Sessions,
    /** 三按钮菜单：快捷键 / 查看 / 会话 */
    Menu,
    /** 终端按键条：Esc Tab ↑ ↓ ← → Ctrl-C */
    Hotkeys,
}

/**
 * 会话快捷块的展示模型。
 * @param id       稳定标识，回调 onSessionSelect(id) 用
 * @param name     会话名（块上文字）
 * @param isActive 是否为当前显示在终端画布上的会话（accent 高亮）
 * @param isRunning 是否有 shell 在跑（绿色状态点；空闲为灰点）
 */
data class SessionChipUi(
    val id: String,
    val name: String,
    val isActive: Boolean,
    val isRunning: Boolean,
)
