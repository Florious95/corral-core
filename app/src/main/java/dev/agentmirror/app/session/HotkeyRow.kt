/**
 * ─────────────────────────────────────────────────────────────
 * HotkeyRow.kt — 倒数第二行 · 终端按键条（常驻展示）
 *
 * 对应设计稿：常驻于输入条上方的终端按键条：
 *   Esc  Tab  ↑ ↓ ← →  Ctrl-C
 * 无多余返回/收起按键，按键整行填满、左右对称。
 *
 * 三种质感（标杆 GlassButton / glassControl，emptyBackdrop 不采样）：
 * - Esc/Tab：中性 glassSurface@0.35 + 按压缩放，圆角 glassControl；
 * - 方向键：坐在统一微透轨道上，键帽上下内缩 2dp，圆角 8dp；
 * - Ctrl-C：同款中性底 + error 字色，⛔ 无红色大面积底。
 * 键位几何与源画布一致。文案 FontFamily.Monospace。
 * ─────────────────────────────────────────────────────────────
 */
package dev.agentmirror.app.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.shapes.RoundedRectangle
import dev.agentmirror.app.ui.components.glassControl
import dev.agentmirror.app.ui.components.rememberPressProgress
import dev.agentmirror.app.ui.theme.LocalAppPalette
import dev.agentmirror.app.ui.theme.Radii
import kotlin.math.roundToInt

/** 键的三种质感 */
private enum class KeyKind { Plain, Arrow, Interrupt }

/**
 * @param label 显示文案；@param token 上报给 onKeyToken 的语义值
 * （token 采用 "Esc"/"Tab"/"Up"/"Down"/"Left"/"Right"/"Ctrl-C"）。
 */
private data class KeySpec(
    val label: String,
    val token: String,
    val sourceX: Float,
    val sourceWidth: Float,
    val kind: KeyKind,
)

/** Browser layout values exported by the fixed 390px source canvas (320px key area, scaled to full width). */
private val KEYS = listOf(
    KeySpec("Esc", "Esc", 0f, 43.9375f, KeyKind.Plain),
    KeySpec("Tab", "Tab", 49.9375f, 43.9375f, KeyKind.Plain),
    KeySpec("↑", "Up", 103.875f, 34.25f, KeyKind.Arrow),
    KeySpec("↓", "Down", 141.125f, 34.265625f, KeyKind.Arrow),
    KeySpec("←", "Left", 178.390625f, 34.265625f, KeyKind.Arrow),
    KeySpec("→", "Right", 215.65625f, 34.265625f, KeyKind.Arrow),
    KeySpec("Ctrl-C", "Ctrl-C", 259.921875f, 60.078125f, KeyKind.Interrupt),
)

/** 方向键簇在源画布上的左右边界（源 px），轨道据此铺底。 */
private val ARROW_CLUSTER_START: Float = KEYS.first { it.kind == KeyKind.Arrow }.sourceX
private val ARROW_CLUSTER_END: Float = KEYS.last { it.kind == KeyKind.Arrow }.let { it.sourceX + it.sourceWidth }

/** 方向键帽相对簇轨道的内缩：键帽上下各缩 2dp，轨道左右各外扩 2dp，四周留同宽的轨道边。 */
private val ArrowClusterInset = 2.dp
private val ArrowClusterRadius = 8.dp

/** 常驻终端按键条：整排填满，无多余返回按钮。 */
@Composable
fun HotkeyRow(
    onKeyToken: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    SourceHotkeyButtons(
        onKeyToken = onKeyToken,
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .testTag("session-dock-hotkeys"),
    )
}

@Composable
private fun SourceHotkeyButtons(onKeyToken: (String) -> Unit, modifier: Modifier = Modifier) {
    Layout(
        content = {
            // 轨道先进 content，先测先摆，画在四个方向键之下。
            ArrowClusterTrack()
            KEYS.forEach { key -> HotKey(key, onClick = { onKeyToken(key.token) }) }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val scale = constraints.maxWidth / 320f
        val height = constraints.maxHeight
        val inset = ArrowClusterInset.roundToPx()
        val trackLeft = (ARROW_CLUSTER_START * scale).roundToInt() - inset
        val trackRight = (ARROW_CLUSTER_END * scale).roundToInt() + inset
        val track = measurables.first().measure(Constraints.fixed(trackRight - trackLeft, height))
        // 键位几何与源画布完全一致（含 testTag 盒），轨道只是多出的一层底。
        val keys = measurables.drop(1).mapIndexed { index, measurable ->
            measurable.measure(
                Constraints.fixed(
                    width = (KEYS[index].sourceWidth * scale).roundToInt(),
                    height = height,
                ),
            )
        }
        layout(constraints.maxWidth, height) {
            track.placeRelative(trackLeft, 0)
            keys.forEachIndexed { index, placeable ->
                placeable.placeRelative((KEYS[index].sourceX * scale).roundToInt(), 0)
            }
        }
    }
}

/** 方向键簇统一微透轨道：标杆中性玻璃更薄一层，无按压。 */
@Composable
private fun ArrowClusterTrack() {
    val p = LocalAppPalette.current
    Box(
        Modifier.glassControl(
            backdrop = emptyBackdrop(),
            shape = RoundedRectangle(ArrowClusterRadius),
            surface = p.glassSurface.copy(alpha = 0.18f),
        ),
    )
}

@Composable
private fun HotKey(key: KeySpec, onClick: () -> Unit) {
    val p = LocalAppPalette.current
    val source = sessionDockSourceTokens()
    val error = MaterialTheme.colorScheme.error
    val interaction = remember { MutableInteractionSource() }
    val press = rememberPressProgress(interaction)
    val fg = when (key.kind) {
        KeyKind.Plain -> source.neutral400
        KeyKind.Arrow -> source.neutral200
        KeyKind.Interrupt -> error
    }
    val shape = RoundedRectangle(if (key.kind == KeyKind.Arrow) 8.dp else Radii.glassControl)
    // testTag 盒必须铺满源几何；方向键只把玻璃画在内缩层，⛔ 不许改 tagged bounds
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("hotkey-${key.token}")
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .then(if (key.kind == KeyKind.Arrow) Modifier.padding(vertical = ArrowClusterInset) else Modifier)
                .glassControl(
                    backdrop = emptyBackdrop(),
                    shape = shape,
                    surface = p.glassSurface.copy(alpha = 0.35f),
                    pressProgress = press,
                ),
        )
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

@Preview(name = "HotkeyRow · Light", showBackground = true)
@Composable
private fun PreviewHotkeyLight() {
    MaterialTheme(colorScheme = lightColorScheme()) {
        HotkeyRow(onKeyToken = {}, modifier = Modifier.padding(8.dp))
    }
}

@Preview(name = "HotkeyRow · Dark", showBackground = true, backgroundColor = 0xFF161826)
@Composable
private fun PreviewHotkeyDark() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        HotkeyRow(onKeyToken = {}, modifier = Modifier.padding(8.dp))
    }
}
