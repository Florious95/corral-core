/**
 * ─────────────────────────────────────────────────────────────
 * DockMenuRow.kt — 倒数第二行 · 三按钮菜单态
 *
 * 对应设计稿：点会话行右侧「返回」后出现的三枚等宽按钮：
 *   ⌨ 快捷键 → 切按键条态   👁 查看 → onOpenViewMenu()（弹出由宿主原生实现）
 *   ▦ 会话  → 切回会话块态
 *
 * 布局决策：
 * - 三钮 weight(1f) 等分整行，间距 8dp，行高 40dp；
 * - 图标 18dp + 文字 labelLarge，图文间距 8dp——符号在前保证「一眼可认」，
 *   文字兜底防歧义；
 * - 底 surfaceVariant + outlineVariant 描边（Nocturne：按钮是描边不是填充）。
 * ─────────────────────────────────────────────────────────────
 */
package dev.agentmirror.app.session

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/** Exact three-action source menu for hotkeys, existing view list, and favorites. */
@Composable
fun DockMenuRow(
    onOpenHotkeys: () -> Unit,
    onOpenViewMenu: () -> Unit,
    onOpenSessions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(40.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MenuButton(
            DockIconKeyboard,
            "常用快捷键",
            onOpenHotkeys,
            Modifier.weight(1f).testTag("dock-open-hotkeys"),
        )
        MenuButton(
            DockIconEye,
            "查看",
            onOpenViewMenu,
            Modifier.weight(1f).testTag("session-overlay-open"),
        )
        MenuButton(
            DockIconGrid,
            "收藏会话",
            onOpenSessions,
            Modifier.weight(1f).testTag("dock-open-favorites"),
        )
    }
}

@Composable
private fun MenuButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = cs.surfaceVariant,
        border = BorderStroke(1.dp, cs.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.width(18.dp), tint = cs.onSurfaceVariant)
            Text(label, style = MaterialTheme.typography.labelLarge, color = cs.onSurface, maxLines = 1)
        }
    }
}

@Preview(name = "DockMenuRow · Light", showBackground = true)
@Composable
private fun PreviewDockMenuLight() {
    MaterialTheme(colorScheme = lightColorScheme()) {
        DockMenuRow({}, {}, {}, Modifier.padding(8.dp))
    }
}

@Preview(name = "DockMenuRow · Dark", showBackground = true, backgroundColor = 0xFF161826)
@Composable
private fun PreviewDockMenuDark() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        DockMenuRow({}, {}, {}, Modifier.padding(8.dp))
    }
}
