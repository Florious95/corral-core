package dev.agentmirror.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.agentmirror.app.ui.theme.Dims
import dev.agentmirror.app.ui.theme.LocalAppPalette
import dev.agentmirror.app.ui.theme.Radii

/**
 * 首列 Provider 自绘图标。许可不允许再分发官方标 → 六家同一套几何，不是官方 SVG。
 *
 * @contract
 * @pre provider 为白名单 id 或空/未知
 * @post contentDescription 为 Display 名；空/未知为 Agent，不得写成 Claude
 * @err none
 */
@Composable
fun ProviderIcon(
    provider: String,
    modifier: Modifier = Modifier,
) {
    val p = LocalAppPalette.current
    val label = providerDisplayName(provider)
    Box(
        modifier
            .size(Dims.tapTargetMin)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(Dims.providerIconBox)
                .clip(RoundedCornerShape(Radii.providerIconBox))
                .background(p.providerIconWell)
                .padding(Dims.providerIconPad),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = providerGlyph(provider),
                contentDescription = null,
                tint = p.rowTitleText,
                modifier = Modifier.size(20.dp),
            )
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

private fun providerGlyph(id: String): ImageVector = when (id) {
    "claude_code" -> Glyph.spark
    "codex" -> Glyph.brackets
    "copilot" -> Glyph.chevrons
    "cursor" -> Glyph.caret
    "grok" -> Glyph.ring
    "pi" -> Glyph.pi
    else -> Glyph.dot
}

private object Glyph {
    val spark = vec("spark") {
        moveTo(10f, 1.5f)
        lineTo(11.6f, 7.6f)
        lineTo(17.8f, 10f)
        lineTo(11.6f, 12.4f)
        lineTo(10f, 18.5f)
        lineTo(8.4f, 12.4f)
        lineTo(2.2f, 10f)
        lineTo(8.4f, 7.6f)
        close()
    }
    val brackets = vec("brackets") {
        moveTo(6.2f, 3.5f)
        lineTo(3.5f, 3.5f)
        lineTo(3.5f, 16.5f)
        lineTo(6.2f, 16.5f)
        moveTo(13.8f, 3.5f)
        lineTo(16.5f, 3.5f)
        lineTo(16.5f, 16.5f)
        lineTo(13.8f, 16.5f)
    }
    val chevrons = vec("chevrons") {
        moveTo(3.5f, 5f)
        lineTo(8.5f, 10f)
        lineTo(3.5f, 15f)
        moveTo(10.5f, 5f)
        lineTo(15.5f, 10f)
        lineTo(10.5f, 15f)
    }
    val caret = vec("caret") {
        moveTo(8f, 3.5f)
        lineTo(12.5f, 10f)
        lineTo(8f, 16.5f)
        moveTo(7.2f, 16.5f)
        lineTo(13.5f, 16.5f)
    }
    val ring = vec("ring") {
        moveTo(15.8f, 5.4f)
        arcTo(7f, 7f, 0f, true, false, 16.2f, 14.2f)
    }
    val pi = vec("pi") {
        moveTo(4f, 6f)
        lineTo(16f, 6f)
        moveTo(7.2f, 6f)
        lineTo(7.2f, 16.5f)
        moveTo(12.8f, 6f)
        lineTo(12.8f, 16.5f)
        lineTo(14.6f, 16.5f)
    }
    val dot = vec("dot") {
        moveTo(10f, 5.5f)
        arcTo(4.5f, 4.5f, 0f, true, true, 9.99f, 5.5f)
        close()
    }

    private fun vec(
        name: String,
        block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 20.dp,
        defaultHeight = 20.dp,
        viewportWidth = 20f,
        viewportHeight = 20f,
    ).apply {
        path(
            fill = SolidColor(Color.Transparent),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.7f,
        ) { block() }
    }.build()
}
