/**
 * ─────────────────────────────────────────────────────────────
 * HotkeyRow.kt — 倒数第二行 · 终端按键条
 *
 * 对应设计稿：菜单里点「快捷键」后替换出的一行：
 *   Esc  Tab  ↑ ↓ ← →  Ctrl-C   + 右侧「返回菜单」钮。
 *
 * 三种质感（直接对应导出源码的层次）：
 * - Esc/Tab：常规独立键，surface 底、outlineVariant 描边、圆角 8dp；
 * - 方向键：视觉成「一簇」——更深的 surfaceVariant 底、圆角收到 4dp、
 *   簇内间距 4dp（其余间距 8dp）；
 * - Ctrl-C：中断语义，error 描边 + error 前景（原稿即红粉描边）。
 * 宽度严格复用 390px 源画布的 CSS flex 结果，整行填满且保留源码负 margin。
 * 键文案用 FontFamily.Monospace（终端语境）。
 * ─────────────────────────────────────────────────────────────
 */
package dev.agentmirror.app.session

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** 键的三种质感 */
private enum class KeyKind { Plain, Arrow, Interrupt }

/**
 * @param label 显示文案；@param token 上报给 onKeyToken 的语义值
 * （token 采用 "Esc"/"Tab"/"Up"/"Down"/"Left"/"Right"/"Ctrl-C"，
 *  若你们现有协议不同，改这张表即可）。
 */
private data class KeySpec(
    val label: String,
    val token: String,
    val sourceX: Float,
    val sourceWidth: Float,
    val kind: KeyKind,
)

/** Browser layout values exported by the fixed 390px source canvas (320px key area). */
private val KEYS = listOf(
    KeySpec("Esc", "Esc", 0f, 43.9375f, KeyKind.Plain),
    KeySpec("Tab", "Tab", 49.9375f, 43.9375f, KeyKind.Plain),
    KeySpec("↑", "Up", 103.875f, 34.25f, KeyKind.Arrow),
    KeySpec("↓", "Down", 141.125f, 34.265625f, KeyKind.Arrow),
    KeySpec("←", "Left", 178.390625f, 34.265625f, KeyKind.Arrow),
    KeySpec("→", "Right", 215.65625f, 34.265625f, KeyKind.Arrow),
    KeySpec("Ctrl-C", "Ctrl-C", 259.921875f, 60.078125f, KeyKind.Interrupt),
)

/** Source hotkey strip in the exported key order. */
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
        SourceHotkeyButtons(
            onKeyToken = onKeyToken,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        DockIconButton(DockIconReturn, contentDescription = "返回菜单", onClick = onBackToMenu)
    }
}

@Composable
private fun SourceHotkeyButtons(onKeyToken: (String) -> Unit, modifier: Modifier = Modifier) {
    Layout(
        content = { KEYS.forEach { key -> HotKey(key, onClick = { onKeyToken(key.token) }) } },
        modifier = modifier,
    ) { measurables, constraints ->
        val scale = constraints.maxWidth / 320f
        val height = constraints.maxHeight
        val placeables = measurables.mapIndexed { index, measurable ->
            measurable.measure(
                Constraints.fixed(
                    width = (KEYS[index].sourceWidth * scale).roundToInt(),
                    height = height,
                ),
            )
        }
        layout(constraints.maxWidth, height) {
            placeables.forEachIndexed { index, placeable ->
                placeable.placeRelative((KEYS[index].sourceX * scale).roundToInt(), 0)
            }
        }
    }
}

@Composable
private fun HotKey(key: KeySpec, onClick: () -> Unit) {
    val source = sessionDockSourceTokens()
    val (bg, border, fg) = when (key.kind) {
        KeyKind.Plain -> Triple(source.neutral900, source.neutral800, source.neutral400)
        KeyKind.Arrow -> Triple(source.neutral800, source.neutral700, source.neutral200)
        KeyKind.Interrupt -> Triple(source.neutral900, source.accent600, source.accent300)
    }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxSize().testTag("hotkey-${key.token}"),
        shape = RoundedCornerShape(if (key.kind == KeyKind.Arrow) 4.dp else 8.dp),
        color = bg,
        border = BorderStroke(1.dp, border),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                key.label,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                ),
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
