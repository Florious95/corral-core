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
 * - 块间距 8dp，块内水平 padding 13dp、点文间距 7dp，圆角 8dp；
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
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Horizontally scrollable favorite-session chips plus the fixed return action. */
@Composable
fun SessionSwitcherRow(
    sessions: List<SessionChipUi>,
    listState: LazyListState,
    onSessionSelect: (String) -> Unit,
    onBackToMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(40.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LazyRow(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxHeight().testTag("favorite-session-list"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Keep the numeric viewport fixed when current-session exclusion swaps one chip.
            items(sessions) { chip ->
                SessionChip(chip, onClick = { onSessionSelect(chip.id) })
            }
        }
        DockIconButton(DockIconReturn, contentDescription = "返回菜单", onClick = onBackToMenu)
    }
}

@Composable
private fun SessionChip(chip: SessionChipUi, onClick: () -> Unit) {
    val source = sessionDockSourceTokens()
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxHeight(),
        shape = RoundedCornerShape(8.dp),
        color = if (chip.isActive) source.accent900 else source.neutral900,
        border = BorderStroke(1.dp, if (chip.isActive) source.accent700 else source.neutral800),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .background(
                        if (chip.isRunning) Color(0xFF7DD3A0) else source.neutral600,
                        CircleShape,
                    )
                    .semantics {
                        contentDescription = if (chip.isRunning) "Running" else "Idle"
                    },
            )
            Text(
                chip.name,
                style = TextStyle(
                    fontFamily = SessionDockSans,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Normal,
                ),
                color = if (chip.isActive) source.accent200 else source.neutral300,
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
        SessionSwitcherRow(
            previewSessions,
            androidx.compose.foundation.lazy.rememberLazyListState(),
            {},
            {},
            Modifier.padding(8.dp),
        )
    }
}

@Preview(name = "SessionSwitcherRow · Dark", showBackground = true, backgroundColor = 0xFF161826)
@Composable
private fun PreviewSessionSwitcherDark() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        SessionSwitcherRow(
            previewSessions,
            androidx.compose.foundation.lazy.rememberLazyListState(),
            {},
            {},
            Modifier.padding(8.dp),
        )
    }
}
