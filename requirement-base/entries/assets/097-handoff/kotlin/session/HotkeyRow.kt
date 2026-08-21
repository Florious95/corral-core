/**
 * ─────────────────────────────────────────────────────────────
 * HotkeyRow.kt — 倒数第二行 · 终端按键条
 *
 * 对应设计稿：菜单里点「快捷键」后替换出的一行：
 *   Esc  Tab  ↑ ↓ ← →  Ctrl-C   + 右侧「返回菜单」钮。
 *
 * 三种质感（对齐原始截图的层次）：
 * - Esc/Tab：常规独立键，surface 底、outlineVariant 描边、圆角 8dp；
 * - 方向键：视觉成「一簇」——更深的 surfaceVariant 底、圆角收到 4dp、
 *   簇内间距 4dp（其余间距 8dp）；
 * - Ctrl-C：中断语义，error 描边 + error 前景（原稿即红粉描边）。
 * 宽度决策：七键按权重平分整行（Esc/Tab 1.3、方向 1、Ctrl-C 1.8），
 * 不横滑，保证 Ctrl-C 永远可见。
 * 键文案用 FontFamily.Monospace（终端语境）。
 * ─────────────────────────────────────────────────────────────
 */
package dev.agentmirror.app.session

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/** 键的三种质感 */
private enum class KeyKind { Plain, Arrow, Interrupt }

/**
 * @param label 显示文案；@param token 上报给 onKeyToken 的语义值
 * （token 采用 "Esc"/"Tab"/"Up"/"Down"/"Left"/"Right"/"Ctrl-C"，
 *  若你们现有协议不同，改这张表即可）。
 */
private data class KeySpec(val label: String, val token: String, val weight: Float, val kind: KeyKind)

private val KEYS = listOf(
    KeySpec("Esc", "Esc", 1.3f, KeyKind.Plain),
    KeySpec("Tab", "Tab", 1.3f, KeyKind.Plain),
    KeySpec("↑", "Up", 1f, KeyKind.Arrow),
    KeySpec("↓", "Down", 1f, KeyKind.Arrow),
    KeySpec("←", "Left", 1f, KeyKind.Arrow),
    KeySpec("→", "Right", 1f, KeyKind.Arrow),
    KeySpec("Ctrl-C", "Ctrl-C", 1.8f, KeyKind.Interrupt),
)

@Composable
fun HotkeyRow(
    onKeyToken: (String) -> Unit,
    onBackToMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(40.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.weight(1f).fillMaxHeight()) {
            KEYS.forEachIndexed { i, key ->
                // 簇内 4dp、簇间 8dp：方向键彼此更近，读作一组
                val gap = when {
                    i == 0 -> 0.dp
                    key.kind == KeyKind.Arrow && KEYS[i - 1].kind == KeyKind.Arrow -> 4.dp
                    else -> 8.dp
                }
                Box(Modifier.padding(start = gap).weight(key.weight).fillMaxHeight()) {
                    HotKey(key, onClick = { onKeyToken(key.token) })
                }
            }
        }
        DockIconButton(DockIconReturn, contentDescription = "返回菜单", onClick = onBackToMenu)
    }
}

@Composable
private fun HotKey(key: KeySpec, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val (bg, border, fg) = when (key.kind) {
        KeyKind.Plain -> Triple(cs.surface, cs.outlineVariant, cs.onSurfaceVariant)
        KeyKind.Arrow -> Triple(cs.surfaceVariant, cs.outline, cs.onSurface)
        KeyKind.Interrupt -> Triple(cs.surface, cs.error, cs.error)
    }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxHeight(),
        shape = RoundedCornerShape(if (key.kind == KeyKind.Arrow) 4.dp else 8.dp),
        color = bg,
        border = BorderStroke(1.dp, border),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                key.label,
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
                color = fg,
                maxLines = 1,
            )
        }
    }
}

@Preview(name = "HotkeyRow · Light", showBackground = true)
@Composable
private fun PreviewHotkeyLight() {
    MaterialTheme(colorScheme = lightColorScheme()) {
        HotkeyRow({}, {}, Modifier.padding(8.dp))
    }
}

@Preview(name = "HotkeyRow · Dark", showBackground = true, backgroundColor = 0xFF161826)
@Composable
private fun PreviewHotkeyDark() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        HotkeyRow({}, {}, Modifier.padding(8.dp))
    }
}
