/**
 * ─────────────────────────────────────────────────────────────
 * DockSecondRow.kt — 倒数第二行的三态容器
 *
 * 对应设计稿：Sessions（默认）/ Menu / Hotkeys 三形态在同一 40dp
 * 槽位内切换。「查看」不占形态——它触发宿主的原生弹出菜单
 * （onOpenViewMenu），本行停留在 Menu 态。
 *
 * 动画决策：只用 AnimatedVisibility（仓库约束）：进入 fade+上滑 12dp、
 * 离开 fade，150ms 内完成——对应 HTML 版 rowIn 动效。三个态叠在同一
 * Box 中，容器高度恒 40dp，切换不引起 dock 抖动。
 * 状态全部提升：mode 由宿主持有（DockRowMode），本组件无 remember。
 * ─────────────────────────────────────────────────────────────
 */
package dev.agentmirror.app.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
fun DockSecondRow(
    mode: DockRowMode,
    onModeChange: (DockRowMode) -> Unit,
    sessions: List<SessionChipUi>,
    onSessionSelect: (String) -> Unit,
    onKeyToken: (String) -> Unit,
    onOpenViewMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enter = fadeIn() + slideInVertically(initialOffsetY = { it / 3 })
    val exit = fadeOut()
    Box(modifier.fillMaxWidth().height(40.dp)) {
        AnimatedVisibility(visible = mode == DockRowMode.Sessions, enter = enter, exit = exit) {
            SessionSwitcherRow(
                sessions = sessions,
                onSessionSelect = onSessionSelect,
                onBackToMenu = { onModeChange(DockRowMode.Menu) },
            )
        }
        AnimatedVisibility(visible = mode == DockRowMode.Menu, enter = enter, exit = exit) {
            DockMenuRow(
                onOpenHotkeys = { onModeChange(DockRowMode.Hotkeys) },
                onOpenViewMenu = onOpenViewMenu,
                onOpenSessions = { onModeChange(DockRowMode.Sessions) },
            )
        }
        AnimatedVisibility(visible = mode == DockRowMode.Hotkeys, enter = enter, exit = exit) {
            HotkeyRow(
                onKeyToken = onKeyToken,
                onBackToMenu = { onModeChange(DockRowMode.Menu) },
            )
        }
    }
}

private val previewSessions = listOf(
    SessionChipUi("1", "编排开发", isActive = true, isRunning = true),
    SessionChipUi("2", "讨论team-agent", isActive = false, isRunning = false),
)

@Preview(name = "DockSecondRow · Light", showBackground = true)
@Composable
private fun PreviewDockSecondRowLight() {
    MaterialTheme(colorScheme = lightColorScheme()) {
        DockSecondRow(DockRowMode.Sessions, {}, previewSessions, {}, {}, {}, Modifier.padding(8.dp))
    }
}

@Preview(name = "DockSecondRow · Dark", showBackground = true, backgroundColor = 0xFF161826)
@Composable
private fun PreviewDockSecondRowDark() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        DockSecondRow(DockRowMode.Menu, {}, previewSessions, {}, {}, {}, Modifier.padding(8.dp))
    }
}
