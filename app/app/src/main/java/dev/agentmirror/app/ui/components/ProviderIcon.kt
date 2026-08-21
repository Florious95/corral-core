package dev.agentmirror.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

fun providerDisplayName(id: String): String = when (id) {
    "claude_code" -> "Claude Code"
    "codex" -> "Codex"
    "copilot" -> "Copilot"
    "cursor" -> "Cursor"
    "grok" -> "Grok"
    "pi" -> "Pi"
    else -> "Agent"
}

internal enum class ProviderKind { Claude, Codex, Copilot, Cursor, Grok, Pi, Agent }

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

private fun glyphBody(kind: ProviderKind): Path = when (kind) {
    ProviderKind.Claude -> Path().apply {
        addRoundRect(RoundRect(Rect(3.4f, 6.2f, 16.6f, 18.2f), CornerRadius(6.4f, 6.4f)))
        // 头顶天线小球
        addOval(Rect(8.2f, 1.4f, 11.8f, 5.0f))
        addRoundRect(RoundRect(Rect(9.25f, 4.4f, 10.75f, 7.0f), CornerRadius(0.7f, 0.7f)))
    }
    ProviderKind.Codex -> Path().apply {
        addRoundRect(RoundRect(Rect(4.2f, 3.6f, 15.8f, 17.6f), CornerRadius(3.2f, 3.2f)))
        addRoundRect(RoundRect(Rect(1.6f, 5.4f, 4.4f, 15.8f), CornerRadius(1.3f, 1.3f)))
        addRoundRect(RoundRect(Rect(15.6f, 5.4f, 18.4f, 15.8f), CornerRadius(1.3f, 1.3f)))
    }
    ProviderKind.Copilot -> Path().apply {
        addOval(Rect(4.6f, 4.4f, 15.4f, 16.8f))
        moveTo(2.2f, 7.2f); lineTo(6.4f, 10.6f); lineTo(2.2f, 14.0f); close()
        moveTo(17.8f, 7.2f); lineTo(13.6f, 10.6f); lineTo(17.8f, 14.0f); close()
    }
    ProviderKind.Cursor -> Path().apply {
        moveTo(5.0f, 2.6f)
        lineTo(16.8f, 10.2f)
        lineTo(10.4f, 11.6f)
        lineTo(8.2f, 18.0f)
        close()
    }
    ProviderKind.Grok -> Path().apply {
        addOval(Rect(4.8f, 5.2f, 15.2f, 15.6f))
    }
    ProviderKind.Pi -> Path().apply {
        addRoundRect(RoundRect(Rect(3.8f, 3.8f, 16.2f, 12.6f), CornerRadius(5.6f, 5.6f)))
        addRoundRect(RoundRect(Rect(5.6f, 11.4f, 8.4f, 18.2f), CornerRadius(1.4f, 1.4f)))
        addRoundRect(RoundRect(Rect(11.6f, 11.4f, 14.4f, 18.2f), CornerRadius(1.4f, 1.4f)))
    }
    ProviderKind.Agent -> Path().apply {
        addOval(Rect(4.2f, 4.0f, 15.8f, 16.6f))
    }
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
