package dev.agentmirror.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import dev.agentmirror.app.ui.theme.Dims
import dev.agentmirror.app.ui.theme.LocalAppPalette

/**
 * 首列 Provider 自绘小怪物/小机器人。许可不允许再分发官方标 → 六家同一套圆润语言，不是官方 SVG。
 * 运行中实底彩色；空闲灰色描边。不铺不透明底。
 *
 * @contract
 * @pre provider 为白名单 id 或空/未知
 * @post contentDescription 为 Display 名；空/未知为 Agent，不得写成 Claude；busy=true 实底、false 描边
 * @err none
 * @inv 六家 glyph 互异；同一 id 两态相似但 fill/stroke 不同；不使用不透明白底
 */
@Composable
fun ProviderIcon(
    provider: String,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
) {
    val p = LocalAppPalette.current
    val label = providerDisplayName(provider)
    val kind = providerKind(provider)
    val fill = providerBusyFill(kind)
    val idle = p.metaText
    Box(
        modifier
            .size(Dims.tapTargetMin)
            .semantics {
                contentDescription = label
                stateDescription = if (busy) "运行" else "空闲"
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(Dims.providerIconBox)) {
            drawProviderGlyph(kind = kind, busy = busy, fill = fill, idle = idle)
        }
    }
}

/**
 * 补证用：六家 × 运行/空闲同屏。不进主导航；MainActivity extra `provider_icon_board=true` 才挂。
 */
@Composable
fun ProviderIconFamilyBoard(modifier: Modifier = Modifier) {
    val p = LocalAppPalette.current
    Column(
        modifier
            .fillMaxSize()
            .background(p.screenBackground)
            .padding(start = 16.dp, end = 16.dp, top = 48.dp, bottom = 24.dp)
            .testTag("provider-icon-board"),
    ) {
        Text("运行   空闲", color = p.metaText, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
        ProviderIconIds.forEach { id ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProviderIcon(id, busy = true, modifier = Modifier.testTag("board-busy-$id"))
                ProviderIcon(id, busy = false, modifier = Modifier.testTag("board-idle-$id"))
                Text(providerDisplayName(id), color = p.rowTitleText, fontSize = 16.sp)
            }
        }
    }
}

fun providerDisplayName(id: String): String = when (id) {
    "claude_code" -> "Claude Code"
    "codex" -> "Codex"
    "copilot" -> "Copilot"
    "cursor" -> "Cursor"
    "grok" -> "Grok"
    "pi" -> "Pi"
    else -> "Agent"
}

internal val ProviderIconIds = listOf("claude_code", "codex", "copilot", "cursor", "grok", "pi")

internal enum class ProviderKind { Claude, Codex, Copilot, Cursor, Grok, Pi, Agent }

/** 六家 glyph 的几何资源。复制任一家到另一家，[glyphGeom] 两两相等，单测必须红。 */
internal fun glyphGeom(kind: ProviderKind): List<String> = when (kind) {
    ProviderKind.Claude -> listOf(
        "rr 3.4 6.2 16.6 18.2 6.4",
        "ov 8.2 1.4 11.8 5.0",
        "rr 9.25 4.4 10.75 7.0 0.7",
    )
    ProviderKind.Codex -> listOf(
        "rr 4.2 3.6 15.8 17.6 3.2",
        "rr 1.6 5.4 4.4 15.8 1.3",
        "rr 15.6 5.4 18.4 15.8 1.3",
    )
    ProviderKind.Copilot -> listOf(
        "ov 4.6 4.4 15.4 16.8",
        "poly 2.2,7.2 6.4,10.6 2.2,14.0",
        "poly 17.8,7.2 13.6,10.6 17.8,14.0",
    )
    ProviderKind.Cursor -> listOf(
        "poly 5.0,2.6 16.8,10.2 10.4,11.6 8.2,18.0",
    )
    ProviderKind.Grok -> listOf(
        "ov 4.8 5.2 15.2 15.6",
        "ring 10 10.4 8.2",
    )
    ProviderKind.Pi -> listOf(
        "rr 3.8 3.8 16.2 12.6 5.6",
        "rr 5.6 11.4 8.4 18.2 1.4",
        "rr 11.6 11.4 14.4 18.2 1.4",
    )
    ProviderKind.Agent -> listOf(
        "ov 4.2 4.0 15.8 16.6",
    )
}

/** 运行=实底，空闲=描边。两态资源必须不相等。 */
internal data class ProviderIconResource(
    val geom: List<String>,
    val filled: Boolean,
    val colorArgb: Int,
)

internal fun providerIconResource(id: String, busy: Boolean, idleArgb: Int): ProviderIconResource {
    val kind = providerKind(id)
    val fill = providerBusyFill(kind)
    return ProviderIconResource(
        geom = glyphGeom(kind),
        filled = busy,
        colorArgb = if (busy) {
            android.graphics.Color.argb(
                (fill.alpha * 255).toInt(),
                (fill.red * 255).toInt(),
                (fill.green * 255).toInt(),
                (fill.blue * 255).toInt(),
            )
        } else {
            idleArgb
        },
    )
}

internal fun providerKind(id: String): ProviderKind = when (id) {
    "claude_code" -> ProviderKind.Claude
    "codex" -> ProviderKind.Codex
    "copilot" -> ProviderKind.Copilot
    "cursor" -> ProviderKind.Cursor
    "grok" -> ProviderKind.Grok
    "pi" -> ProviderKind.Pi
    else -> ProviderKind.Agent
}

/** 运行中实底色。各家不同，不是官方品牌色拷贝。 */
internal fun providerBusyFill(kind: ProviderKind): Color = when (kind) {
    ProviderKind.Claude -> Color(0xFFE07A4A)
    ProviderKind.Codex -> Color(0xFF2BB5A0)
    ProviderKind.Copilot -> Color(0xFF4C8DFF)
    ProviderKind.Cursor -> Color(0xFF8B6CFF)
    ProviderKind.Grok -> Color(0xFFE4A03C)
    ProviderKind.Pi -> Color(0xFFE56B8A)
    ProviderKind.Agent -> Color(0xFF6E7B91)
}

private fun DrawScope.drawProviderGlyph(
    kind: ProviderKind,
    busy: Boolean,
    fill: Color,
    idle: Color,
) {
    val gs = size.minDimension / 20f
    withTransform({
        translate((size.width - 20f * gs) / 2f, (size.height - 20f * gs) / 2f)
        // 默认 pivot 是画布中心，20 单位坐标系会被甩出可视区。
        scale(gs, gs, pivot = Offset.Zero)
    }) {
        val ink = if (busy) fill else idle
        val stroke = Stroke(width = 1.45f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val body = glyphBody(kind)
        if (busy) {
            drawPath(body, color = ink, style = Fill)
        } else {
            drawPath(body, color = ink, style = stroke)
        }
        drawEyes(kind, busy, ink)
        if (kind == ProviderKind.Grok) {
            drawCircle(
                color = ink,
                radius = 8.2f,
                center = Offset(10f, 10.4f),
                style = Stroke(width = if (busy) 1.35f else 1.45f, cap = StrokeCap.Round),
            )
        }
    }
}

internal fun glyphBody(kind: ProviderKind): Path {
    val path = Path()
    glyphGeom(kind).forEach { op ->
        val t = op.split(' ')
        when (t[0]) {
            "rr" -> path.addRoundRect(
                RoundRect(
                    Rect(t[1].toFloat(), t[2].toFloat(), t[3].toFloat(), t[4].toFloat()),
                    CornerRadius(t[5].toFloat(), t[5].toFloat()),
                ),
            )
            "ov" -> path.addOval(Rect(t[1].toFloat(), t[2].toFloat(), t[3].toFloat(), t[4].toFloat()))
            "poly" -> {
                val pairs = t.drop(1).flatMap { it.split(',') }
                if (pairs.size >= 4) {
                    path.moveTo(pairs[0].toFloat(), pairs[1].toFloat())
                    var i = 2
                    while (i + 1 < pairs.size) {
                        path.lineTo(pairs[i].toFloat(), pairs[i + 1].toFloat())
                        i += 2
                    }
                    path.close()
                }
            }
            "ring" -> { /* 由 drawProviderGlyph 另画描边圆，几何仍计入资源指纹 */ }
        }
    }
    return path
}

private fun DrawScope.drawEyes(kind: ProviderKind, busy: Boolean, ink: Color) {
    val (left, right) = when (kind) {
        ProviderKind.Claude -> Offset(7.6f, 11.4f) to Offset(12.4f, 11.4f)
        ProviderKind.Codex -> Offset(8.0f, 9.6f) to Offset(12.0f, 9.6f)
        ProviderKind.Copilot -> Offset(7.8f, 10.0f) to Offset(12.2f, 10.0f)
        ProviderKind.Cursor -> Offset(8.4f, 8.4f) to Offset(11.6f, 9.4f)
        ProviderKind.Grok -> Offset(7.8f, 10.2f) to Offset(12.2f, 10.2f)
        ProviderKind.Pi -> Offset(7.6f, 7.6f) to Offset(12.4f, 7.6f)
        ProviderKind.Agent -> Offset(7.6f, 9.6f) to Offset(12.4f, 9.6f)
    }
    val r = if (busy) 1.15f else 0.95f
    if (busy) {
        drawCircle(Color.White, radius = r, center = left)
        drawCircle(Color.White, radius = r, center = right)
        drawCircle(Color(0xFF2A2430), radius = 0.48f, center = left + Offset(0.22f, 0.12f))
        drawCircle(Color(0xFF2A2430), radius = 0.48f, center = right + Offset(0.22f, 0.12f))
    } else {
        val s = Stroke(width = 1.2f, cap = StrokeCap.Round)
        drawCircle(ink, radius = r, center = left, style = s)
        drawCircle(ink, radius = r, center = right, style = s)
    }
}
