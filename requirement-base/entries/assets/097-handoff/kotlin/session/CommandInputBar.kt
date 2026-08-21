/**
 * ─────────────────────────────────────────────────────────────
 * CommandInputBar.kt — 最底行 · 输入胶囊
 *
 * 对应设计稿：输入框「完全包裹」加号与发送——一只圆角胶囊 Surface，
 * 内部从左到右：加号图标钮（无底色）· 受控 BasicTextField · 发送小圆钮
 * （primary 描边圆形，主流 Chat App 式样）。
 *
 * 交互（已验收）：
 * - 单行起步；获得焦点（IME 弹出）时文本区高度 animateDpAsState 膨胀到
 *   expandedLines 行（默认 3），失焦收回单行；
 * - 发送后由宿主清空 value 并可收起焦点（见接线说明）；发送钮在文本非空
 *   时底色填 primaryContainer 作可用暗示；
 * - 胶囊描边聚焦时 animateColorAsState 过渡到 primary。
 *
 * 尺寸决策（1px≈1dp，取整到 4dp 网格）：
 * - 胶囊圆角 24dp、内边距 4dp（左）/8dp（右）/4dp（上下）；
 * - 单行文本区高 32dp、每行行高 20sp → 展开高 = 24dp × 行数；
 * - 加号 36×32dp 纯图标；发送 32dp 圆钮。
 * - 等宽字体：终端指令语境，FontFamily.Monospace。
 * 仅有的本地 remember 是「焦点视觉态」（非业务状态，仓库规范允许）。
 * ─────────────────────────────────────────────────────────────
 */
package dev.agentmirror.app.session

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CommandInputBar(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSendText: (String) -> Unit,
    onPickAttachment: () -> Unit,
    modifier: Modifier = Modifier,
    expandedLines: Int = 3,
) {
    val cs = MaterialTheme.colorScheme
    // 焦点视觉态（非业务状态）：驱动膨胀与描边高亮
    var focused by remember { mutableStateOf(false) }
    val fieldHeight by animateDpAsState(
        targetValue = if (focused) (24 * expandedLines).dp else 32.dp,
        animationSpec = tween(250),
        label = "inputFieldHeight",
    )
    val borderColor by animateColorAsState(
        targetValue = if (focused) cs.primary else cs.outlineVariant,
        animationSpec = tween(200),
        label = "inputBorder",
    )
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = cs.surfaceVariant,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Row(
            // 底对齐：膨胀时加号/发送钉在底边，与主流 Chat App 一致
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Surface(
                onClick = onPickAttachment,
                shape = CircleShape,
                color = Color.Transparent,
                modifier = Modifier.size(width = 36.dp, height = 32.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        DockIconPlus, contentDescription = "添加附件",
                        modifier = Modifier.width(20.dp), tint = cs.onSurfaceVariant,
                    )
                }
            }
            Box(Modifier.weight(1f).height(fieldHeight), contentAlignment = Alignment.CenterStart) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    maxLines = if (focused) expandedLines else 1,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        color = cs.onSurface,
                        lineHeight = 20.sp,
                    ),
                    cursorBrush = SolidColor(cs.primary),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (value.text.isEmpty()) {
                                Text(
                                    "输入指令…",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                    color = cs.onSurfaceVariant,
                                )
                            }
                            inner()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused },
                )
            }
            val hasText = value.text.isNotBlank()
            Surface(
                onClick = { if (hasText) onSendText(value.text) },
                shape = CircleShape,
                color = if (hasText) cs.primaryContainer else Color.Transparent,
                border = BorderStroke(1.dp, cs.primary),
                modifier = Modifier.size(32.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        DockIconArrowUp, contentDescription = "发送",
                        modifier = Modifier.width(16.dp),
                        tint = if (hasText) cs.onPrimaryContainer else cs.primary,
                    )
                }
            }
        }
    }
}

@Preview(name = "CommandInputBar · Light", showBackground = true)
@Composable
private fun PreviewInputLight() {
    MaterialTheme(colorScheme = lightColorScheme()) {
        CommandInputBar(TextFieldValue(""), {}, {}, {}, Modifier.padding(8.dp))
    }
}

@Preview(name = "CommandInputBar · Dark", showBackground = true, backgroundColor = 0xFF161826)
@Composable
private fun PreviewInputDark() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        CommandInputBar(TextFieldValue("git status"), {}, {}, {}, Modifier.padding(8.dp))
    }
}
