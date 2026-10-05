package dev.agentmirror.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.shapes.RoundedRectangle
import dev.agentmirror.app.ui.components.AppText
import dev.agentmirror.app.ui.components.GlassCircleBackButton
import dev.agentmirror.app.ui.components.TokenText
import dev.agentmirror.app.ui.components.flatGlass
import dev.agentmirror.app.ui.components.ruledSurface
import dev.agentmirror.app.ui.theme.Dims
import dev.agentmirror.app.ui.theme.HeaderStyle
import dev.agentmirror.app.ui.theme.LocalAppPalette
import dev.agentmirror.app.ui.theme.LocalThemeSuite
import dev.agentmirror.app.ui.theme.Motion
import dev.agentmirror.app.ui.theme.TypeSizes
import dev.agentmirror.app.ui.theme.isDark

/*
 * 设置页分组套件：iOS 分组卡 + 彩色图标砖 + 发丝分隔。
 * 设置页处在 ThreePane 的 layerBackdrop 录制树内，这里全部是 FlatGlass（background / border / shadow），
 * ⛔ 不采样任何 backdrop。深色卡面走偏蓝的半透板，压在 #070B14 上保持纯暗沉浸。
 */

internal data class SettingsGlassTokens(
    val fill: Color,
    val hairline: Color,
    val topGlint: Color,
    val bottomShade: Color,
    val pressed: Color,
    val divider: Color,
)

private val LightSettingsGlass = SettingsGlassTokens(
    fill = Color(0xF5FFFFFF),
    hairline = Color(0x14101828),
    topGlint = Color(0xFFFFFFFF),
    bottomShade = Color(0x0D101828),
    pressed = Color(0x0B101828),
    divider = Color(0x12101828),
)

private val DarkSettingsGlass = SettingsGlassTokens(
    fill = Color(0xC2111A2B),
    hairline = Color(0x2478A0FF),
    topGlint = Color(0x2EC4D8FF),
    bottomShade = Color(0x59000000),
    pressed = Color(0x0FFFFFFF),
    divider = Color(0x1778A0FF),
)

@Composable
internal fun settingsGlassTokens(): SettingsGlassTokens {
    val p = LocalAppPalette.current
    val kit = LocalThemeSuite.current
    return when {
        // 直角风格：不透明卡面 + 实色描边，无高光 / 暗边。
        !kit.surfaces.isGlass -> SettingsGlassTokens(
            fill = p.cardBackground,
            hairline = p.cardBorder,
            topGlint = Color.Transparent,
            bottomShade = Color.Transparent,
            pressed = p.chipPressed,
            divider = kit.colors.separator,
        )
        p.isDark -> DarkSettingsGlass
        else -> LightSettingsGlass
    }
}

internal val SettingsGroupShape = RoundedRectangle(20.dp)

/** 分组卡材质：浅色带一层极淡的浮起投影，深色不投影（深底上投影只会变成黑边）。 */
@Composable
internal fun Modifier.settingsGlass(shape: RoundedRectangle = SettingsGroupShape): Modifier {
    val g = settingsGlassTokens()
    val kit = LocalThemeSuite.current
    if (!kit.surfaces.isGlass) return ruledSurface(fill = g.fill, stroke = g.hairline, strokeWidth = kit.geometry.hairline)
    val lifted = if (LocalAppPalette.current.isDark) {
        this
    } else {
        shadow(4.dp, shape, clip = false, ambientColor = LiftShadow, spotColor = LiftShadow)
    }
    return lifted.flatGlass(
        shape = shape,
        fill = g.fill,
        hairline = g.hairline,
        topGlint = g.topGlint,
        bottomShade = g.bottomShade,
    )
}

private val LiftShadow = Color(0x24101828)

/** 一组设置行的玻璃卡。行之间用 [SettingsGroupDivider] 分隔。 */
@Composable
internal fun SettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().settingsGlass(), content = content)
}

/** 行间发丝线：从图标砖右侧的文字列起始，iOS 分组列表同款内缩。 */
@Composable
internal fun SettingsGroupDivider() {
    val kit = LocalThemeSuite.current
    if (!kit.surfaces.isGlass) {
        // 直角风格：通栏 1dp 发丝线，不内缩。
        Box(Modifier.fillMaxWidth().height(kit.geometry.hairline).background(kit.colors.separator))
        return
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = RowHPadding + IconTileSize + IconTextGap)
            .height(0.5.dp)
            .background(settingsGlassTokens().divider),
    )
}

/** 分组上方的小标题（不与行标题同名，免得语义树出现重名节点）。 */
@Composable
internal fun SettingsSectionLabel(text: String) {
    val kit = LocalThemeSuite.current
    if (kit.recipes.header == HeaderStyle.Display) {
        TokenText(
            text = text,
            color = kit.colors.eyebrow,
            token = kit.typography.eyebrow,
            modifier = Modifier.padding(start = 6.dp, top = 14.dp, bottom = 4.dp),
        )
        return
    }
    AppText(
        text = text,
        color = LocalAppPalette.current.metaText,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
        lineHeightMultiplier = 1.2f,
        modifier = Modifier.padding(start = 6.dp, top = 10.dp, bottom = 2.dp),
    )
}

private val RowHPadding = 14.dp
private val IconTileSize = 30.dp
private val IconTextGap = 12.dp

/**
 * 分组卡内的一行：图标砖 + 标题 / 说明 + 右侧附件；[below] 放满宽的内联控件（档位、分段、按钮）。
 * [onClick] 非空时整行可点，按压叠一层轻暗（深色轻亮）蒙层。
 */
@Composable
internal fun SettingsRow(
    glyph: SettingsGlyph,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
    below: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val p = LocalAppPalette.current
    val g = settingsGlassTokens()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val overlay by animateColorAsState(
        targetValue = if (pressed) g.pressed else Color.Transparent,
        animationSpec = tween(Motion.pressFeedback),
        label = "settingsRowPress",
    )
    Column(
        modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .background(overlay)
            .padding(horizontal = RowHPadding, vertical = 13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingsIconTile(glyph)
            Spacer(Modifier.width(IconTextGap))
            Column(Modifier.weight(1f)) {
                AppText(
                    text = title,
                    color = p.rowTitleText,
                    fontSize = TypeSizes.cardTitle,
                    fontWeight = FontWeight.SemiBold,
                    lineHeightMultiplier = 1.25f,
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(2.dp))
                    AppText(
                        text = subtitle,
                        color = p.bodyText,
                        fontSize = 12.sp,
                        lineHeightMultiplier = 1.45f,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            trailing()
        }
        if (below != null) {
            Spacer(Modifier.height(12.dp))
            below()
        }
    }
}

/** 行尾数值 / 状态文字（主色小号）。 */
@Composable
internal fun SettingsValueText(text: String, modifier: Modifier = Modifier) {
    AppText(
        text = text,
        color = LocalAppPalette.current.accent,
        fontSize = 12.5f.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeightMultiplier = 1f,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** 行尾前进折线（几何路径，不用 `›` 字符，墨迹才居中）。 */
@Composable
internal fun ForwardChevron(tint: Color = LocalAppPalette.current.metaText) {
    Canvas(Modifier.size(16.dp)) {
        val path = Path().apply {
            moveTo(size.width * 0.38f, size.height * 0.2f)
            lineTo(size.width * 0.66f, size.height * 0.5f)
            lineTo(size.width * 0.38f, size.height * 0.8f)
        }
        drawPath(path, tint, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * 二级设置页顶栏：与会话二级页同款圆形玻璃返回 + 大标题（可带等宽 meta）+ 右侧动作。
 * 返回钮在录制树内，[GlassCircleBackButton] 默认 emptyBackdrop，不采样。
 */
@Composable
internal fun SettingsSubPageHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    meta: String? = null,
    backTag: String? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val p = LocalAppPalette.current
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(Dims.topBarHeight)
                .padding(start = 2.dp, end = Dims.screenHPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassCircleBackButton(
                onBack = onBack,
                modifier = if (backTag != null) Modifier.testTag(backTag) else Modifier,
            )
            Spacer(Modifier.weight(1f))
            trailing()
        }
        Column(Modifier.padding(start = Dims.screenHPadding + 2.dp, end = Dims.screenHPadding, top = 2.dp, bottom = 12.dp)) {
            TokenText(
                text = title,
                color = p.titleText,
                token = LocalThemeSuite.current.typography.sectionTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta != null) {
                Spacer(Modifier.height(4.dp))
                AppText(
                    text = meta,
                    color = p.metaText,
                    fontSize = TypeSizes.headerMeta,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    lineHeightMultiplier = 1f,
                    letterSpacing = 0.2.sp,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// 图标砖：渐变底 + 顶部一线玻璃高光，白色几何图形（Canvas 画，不依赖符号字体 / emoji 回退）
// ─────────────────────────────────────────────────────────────

internal enum class SettingsGlyph(val top: Color, val bottom: Color) {
    Host(Color(0xFF5B95FF), Color(0xFF2F6BEA)),
    Shortcut(Color(0xFFA78BFA), Color(0xFF7C3AED)),
    Sync(Color(0xFF2DD4BF), Color(0xFF0F9F8F)),
    Retain(Color(0xFF8B93F8), Color(0xFF5B5BD6)),
    Font(Color(0xFF38BDF8), Color(0xFF0284C7)),
    Theme(Color(0xFFF472B6), Color(0xFFDB2777)),
    Appearance(Color(0xFFFBBF24), Color(0xFFEA8A0B)),
    Logs(Color(0xFF94A3B8), Color(0xFF64748B)),
    Style(Color(0xFF4ADE80), Color(0xFF16A34A)),
    Conversation(Color(0xFF818CF8), Color(0xFF4F46E5)),
}

@Composable
internal fun SettingsIconTile(glyph: SettingsGlyph, size: Dp = IconTileSize) {
    if (!LocalThemeSuite.current.surfaces.isGlass) {
        // 直角风格：不画彩色砖，只留墨色线性图标，占位不变。
        val ink = LocalAppPalette.current.rowTitleText
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            if (glyph == SettingsGlyph.Font) {
                AppText("Aa", ink, (size.value * 0.42f).sp, fontWeight = FontWeight.Bold, lineHeightMultiplier = 1f)
            } else {
                Canvas(Modifier.size(size * 0.6f)) { drawGlyph(glyph, ink) }
            }
        }
        return
    }
    val shape = RoundedRectangle(size * 0.3f)
    // flatGlass 的两道描边画在内层渐变之上：顶部一线玻璃高光 + 发丝边
    Box(
        Modifier
            .size(size)
            .flatGlass(
                shape = shape,
                fill = Color.Transparent,
                hairline = Color.White.copy(alpha = 0.18f),
                topGlint = Color.White.copy(alpha = 0.45f),
                bottomShade = Color.Black.copy(alpha = 0.10f),
            )
            .background(Brush.verticalGradient(listOf(glyph.top, glyph.bottom))),
        contentAlignment = Alignment.Center,
    ) {
        if (glyph == SettingsGlyph.Font) {
            AppText(
                text = "Aa",
                color = Color.White,
                fontSize = (size.value * 0.42f).sp,
                fontWeight = FontWeight.Bold,
                lineHeightMultiplier = 1f,
            )
        } else {
            Canvas(Modifier.size(size * 0.6f)) { drawGlyph(glyph, Color.White) }
        }
    }
}

private fun DrawScope.drawGlyph(glyph: SettingsGlyph, ink: Color) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = w * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun polyline(vararg points: Pair<Float, Float>) {
        val path = Path()
        points.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(w * x, h * y) else path.lineTo(w * x, h * y) }
        drawPath(path, ink, style = stroke)
    }
    when (glyph) {
        // 显示器：屏幕 + 支架
        SettingsGlyph.Host -> {
            drawRoundRect(ink, Offset(w * 0.08f, h * 0.14f), Size(w * 0.84f, h * 0.56f), CornerRadius(w * 0.12f), style = stroke)
            polyline(0.5f to 0.72f, 0.5f to 0.86f)
            polyline(0.3f to 0.88f, 0.7f to 0.88f)
        }
        // 闪电：一键插入
        SettingsGlyph.Shortcut -> {
            val bolt = Path().apply {
                moveTo(w * 0.58f, h * 0.04f)
                lineTo(w * 0.18f, h * 0.56f)
                lineTo(w * 0.46f, h * 0.56f)
                lineTo(w * 0.40f, h * 0.96f)
                lineTo(w * 0.82f, h * 0.42f)
                lineTo(w * 0.54f, h * 0.42f)
                close()
            }
            drawPath(bolt, ink)
        }
        // 双向箭头：实时同步
        SettingsGlyph.Sync -> {
            polyline(0.14f to 0.34f, 0.84f to 0.34f)
            polyline(0.66f to 0.16f, 0.84f to 0.34f, 0.66f to 0.52f)
            polyline(0.86f to 0.66f, 0.16f to 0.66f)
            polyline(0.34f to 0.48f, 0.16f to 0.66f, 0.34f to 0.84f)
        }
        // 四角取景框 + 实心内核：尺寸驻留
        SettingsGlyph.Retain -> {
            polyline(0.1f to 0.36f, 0.1f to 0.1f, 0.36f to 0.1f)
            polyline(0.64f to 0.1f, 0.9f to 0.1f, 0.9f to 0.36f)
            polyline(0.9f to 0.64f, 0.9f to 0.9f, 0.64f to 0.9f)
            polyline(0.36f to 0.9f, 0.1f to 0.9f, 0.1f to 0.64f)
            drawRoundRect(ink, Offset(w * 0.34f, h * 0.34f), Size(w * 0.32f, h * 0.32f), CornerRadius(w * 0.06f))
        }
        // 终端窗口 + 提示符
        SettingsGlyph.Theme -> {
            drawRoundRect(ink, Offset(w * 0.06f, h * 0.14f), Size(w * 0.88f, h * 0.72f), CornerRadius(w * 0.14f), style = stroke)
            polyline(0.26f to 0.38f, 0.42f to 0.5f, 0.26f to 0.62f)
            polyline(0.5f to 0.64f, 0.72f to 0.64f)
        }
        // 半满圆：浅 / 深
        SettingsGlyph.Appearance -> {
            val r = w * 0.4f
            drawCircle(ink, r, center, style = stroke)
            drawArc(ink, -90f, 180f, true, Offset(center.x - r, center.y - r), Size(r * 2, r * 2))
        }
        // 文档 + 文字行
        SettingsGlyph.Logs -> {
            drawRoundRect(ink, Offset(w * 0.18f, h * 0.06f), Size(w * 0.64f, h * 0.88f), CornerRadius(w * 0.12f), style = stroke)
            polyline(0.34f to 0.34f, 0.66f to 0.34f)
            polyline(0.34f to 0.52f, 0.66f to 0.52f)
            polyline(0.34f to 0.70f, 0.54f to 0.70f)
        }
        // 田字格：一实三空，示意界面风格
        SettingsGlyph.Style -> {
            drawRect(ink, Offset(w * 0.08f, h * 0.08f), Size(w * 0.36f, h * 0.36f))
            drawRect(ink, Offset(w * 0.56f, h * 0.08f), Size(w * 0.36f, h * 0.36f), style = stroke)
            drawRect(ink, Offset(w * 0.08f, h * 0.56f), Size(w * 0.36f, h * 0.36f), style = stroke)
            drawRect(ink, Offset(w * 0.56f, h * 0.56f), Size(w * 0.36f, h * 0.36f), style = stroke)
        }
        // 对话气泡 + 两行文字：原生对话
        SettingsGlyph.Conversation -> {
            val bubble = Path().apply {
                moveTo(w * 0.22f, h * 0.12f)
                lineTo(w * 0.78f, h * 0.12f)
                quadraticTo(w * 0.94f, h * 0.12f, w * 0.94f, h * 0.28f)
                lineTo(w * 0.94f, h * 0.58f)
                quadraticTo(w * 0.94f, h * 0.74f, w * 0.78f, h * 0.74f)
                lineTo(w * 0.42f, h * 0.74f)
                lineTo(w * 0.22f, h * 0.92f)
                lineTo(w * 0.24f, h * 0.74f)
                quadraticTo(w * 0.06f, h * 0.72f, w * 0.06f, h * 0.56f)
                lineTo(w * 0.06f, h * 0.28f)
                quadraticTo(w * 0.06f, h * 0.12f, w * 0.22f, h * 0.12f)
                close()
            }
            drawPath(bubble, ink, style = stroke)
            polyline(0.28f to 0.36f, 0.72f to 0.36f)
            polyline(0.28f to 0.52f, 0.56f to 0.52f)
        }
        SettingsGlyph.Font -> Unit
    }
}
