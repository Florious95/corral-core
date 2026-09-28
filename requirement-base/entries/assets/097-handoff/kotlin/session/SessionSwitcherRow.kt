/**
 * ─────────────────────────────────────────────────────────────
 * SessionSwitcherRow.kt — 倒数第二行 · 会话快捷跳转（默认态）
 *
 * 对应设计稿：底部 dock 第二行的横滑小块，每块 = 状态点 + 会话名；
 * 点击块 → onSessionSelect(id)，终端画布由宿主切换。
 * 行右侧固定一枚「返回菜单」钮 → 切到三按钮菜单态。
 *
 * 布局决策：
 * - 行高 40dp（与按键条/菜单行一致，dock 总高恒定，不跳动）；
 * - 块间距 8dp，块内水平 padding 12dp，圆角 8dp（Nocturne radius-md）；
 * - 当前会话块用 primaryContainer 底 + primary 描边高亮，其余
 *   surfaceVariant 底 + outlineVariant 描边；
 * - 状态点 6dp：运行中 = tertiary（待映射 success 扩展色），空闲 = outline。
 * ─────────────────────────────────────────────────────────────
 */
package dev.agentmirror.app.session

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
fun SessionSwitcherRow(
    sessions: List<SessionChipUi>,
    onSessionSelect: (String) -> Unit,
    onBackToMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(40.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LazyRow(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(sessions, key = { it.id }) { chip ->
                SessionChip(chip, onClick = { onSessionSelect(chip.id) })
            }
        }
        DockIconButton(DockIconReturn, contentDescription = "返回菜单", onClick = onBackToMenu)
    }
}

@Composable
private fun SessionChip(chip: SessionChipUi, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxHeight(),
        shape = RoundedCornerShape(8.dp),
        color = if (chip.isActive) cs.primaryContainer else cs.surfaceVariant,
        border = BorderStroke(1.dp, if (chip.isActive) cs.primary else cs.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.size(6.dp).background(
                    if (chip.isRunning) cs.tertiary else cs.outline,
                    CircleShape,
                ),
            )
            Text(
                chip.name,
                style = MaterialTheme.typography.labelLarge,
                color = if (chip.isActive) cs.onPrimaryContainer else cs.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

private val previewSessions = listOf(
    SessionChipUi("1", "编排开发", isActive = true, isRunning = true),
    SessionChipUi("2", "讨论team-agent", isActive = false, isRunning = false),
    SessionChipUi("3", "bugfix/merkle", isActive = false, isRunning = true),
    SessionChipUi("4", "文档整理", isActive = false, isRunning = false),
)

@Preview(name = "SessionSwitcherRow · Light", showBackground = true)
@Composable
private fun PreviewSessionSwitcherLight() {
    MaterialTheme(colorScheme = lightColorScheme()) {
        SessionSwitcherRow(previewSessions, {}, {}, Modifier.padding(8.dp))
    }
}

@Preview(name = "SessionSwitcherRow · Dark", showBackground = true, backgroundColor = 0xFF161826)
@Composable
private fun PreviewSessionSwitcherDark() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        SessionSwitcherRow(previewSessions, {}, {}, Modifier.padding(8.dp))
    }
}
