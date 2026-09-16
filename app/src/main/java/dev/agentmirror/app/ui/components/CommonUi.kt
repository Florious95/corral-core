package dev.agentmirror.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.agentmirror.app.tsnet.ConnectionPath
import dev.agentmirror.app.ui.model.SessionStatus
import dev.agentmirror.app.ui.theme.AppPalette
import dev.agentmirror.app.ui.theme.DarkPalette
import dev.agentmirror.app.ui.theme.Dims
import dev.agentmirror.app.ui.theme.LocalAppPalette
import dev.agentmirror.app.ui.theme.Motion
import dev.agentmirror.app.ui.theme.Radii
import dev.agentmirror.app.ui.theme.TypeSizes

/** StatusChip / RunningDot 共用的三态色与文案。颜色全部来自 [AppPalette]，组件内无字面量色值。 */
data class StatusVisuals(
    val chipBg: Color,
    val chipText: Color,
    val lamp: Color,
    val label: String,
    val pulse: Boolean,
)

fun statusVisuals(p: AppPalette, status: SessionStatus): StatusVisuals = when (status) {
    SessionStatus.Busy -> StatusVisuals(
        chipBg = p.busyChipBg,
        chipText = p.busyChipText,
        lamp = p.busyDot,
        label = "进行中",
        pulse = true,
    )
    SessionStatus.Idle -> StatusVisuals(
        chipBg = p.idleChipBg,
        chipText = p.idleChipText,
        lamp = p.idleChipText,
        label = "空闲",
        pulse = false,
    )
    SessionStatus.Unknown -> StatusVisuals(
        chipBg = p.unknownChipBg,
        chipText = p.unknownChipText,
        lamp = p.unknownDot,
        label = "未知",
        pulse = false,
    )
}

fun runningDotColor(p: AppPalette, status: SessionStatus): Color = statusVisuals(p, status).lamp

/**
 * 所有文字都过这一个入口，避免行高 / 字族在各处漂移。
 * 中文默认 FontFamily.Default；只有 ASCII 标识符 / 路径 / 数字 / 按键才传 Monospace。
 */
@Composable
fun AppText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight = FontWeight.Normal,
    fontFamily: FontFamily = FontFamily.Default,
    lineHeightMultiplier: Float = TypeSizes.rowLineHeight,
    letterSpacing: TextUnit = 0.sp,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
) {
    Text(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            fontFamily = fontFamily,
            lineHeight = fontSize * lineHeightMultiplier,
            letterSpacing = letterSpacing,
            textAlign = textAlign ?: TextAlign.Unspecified,
        ),
        maxLines = maxLines,
        overflow = overflow,
    )
}

/** 目录路径 —— 等宽、单行、尾部省略 */
@Composable
fun PathText(path: String, modifier: Modifier = Modifier) {
    val p = LocalAppPalette.current
    AppText(
        text = path,
        color = p.pathText,
        fontSize = TypeSizes.rowSubtitle,
        fontFamily = FontFamily.Monospace,
        lineHeightMultiplier = 1.3f,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * 会话显示名 —— 可能是中文。
 * 🔴 用系统默认字体测宽，⛔ 不做 ASCII 宽度假设。
 * 行内单行显示，宽度不够时才尾部省略；顶栏另有不截断的处理，见 SessionTopBar。
 */
@Composable
fun SessionNameText(name: String, modifier: Modifier = Modifier) {
    val p = LocalAppPalette.current
    AppText(
        text = name,
        color = p.rowTitleText,
        fontSize = TypeSizes.rowTitle,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** 「进行中 / 空闲 / 未知」状态标。Busy 保留脉冲绿灯；Idle 灰灯；Unknown 红灯。 */
@Composable
fun StatusChip(status: SessionStatus, modifier: Modifier = Modifier) {
    val p = LocalAppPalette.current
    val v = statusVisuals(p, status)
    Row(
        modifier = modifier
            .height(Dims.statusChipHeight)
            .clip(RoundedCornerShape(Radii.statusChip))
            .background(v.chipBg)
            .padding(horizontal = Dims.statusChipHPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (v.pulse) {
            val transition = rememberInfiniteTransition(label = "busyDot")
            val pulse by transition.animateFloat(
                initialValue = 1f,
                targetValue = 0.35f,
                animationSpec = infiniteRepeatable(
                    animation = tween(Motion.statusDotPulse / 2, easing = Motion.emphasized),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "busyDotAlpha",
            )
            Box(
                Modifier
                    .size(Dims.statusDotSize)
                    .alpha(pulse)
                    .clip(CircleShape)
                    .background(v.lamp)
            )
        } else {
            Box(
                Modifier
                    .size(Dims.statusDotSize)
                    .clip(CircleShape)
                    .background(v.lamp)
            )
        }
        AppText(
            text = v.label,
            color = v.chipText,
            fontSize = TypeSizes.statusChip,
            fontWeight = if (v.pulse) FontWeight.SemiBold else FontWeight.Medium,
            lineHeightMultiplier = 1f,
        )
    }
}

/** LAN / PAIRED 之类的微型状态胶囊（只表状态，⛔ 不要做成可点的蓝色链接） */
@Composable
fun MicroPill(
    text: String,
    textColor: Color,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Radii.pill))
            .background(backgroundColor)
            .padding(horizontal = 7.dp, vertical = 5.dp)
    ) {
        AppText(
            text = text,
            color = textColor,
            fontSize = TypeSizes.microPill,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            lineHeightMultiplier = 1f,
            letterSpacing = 0.9.sp,
        )
    }
}

/**
 * 连接通道胶囊。文案必须是 [ConnectionPath.label]（transport 真实选路），
 * ⛔ 不许写死 LAN、不许按「能不能连通」猜。
 *
 * @contract
 * @pre path 为本次拨号记录的通道
 * @post 显示 path.label
 * @err none
 * @inv does not infer or mutate the connection path
 */
@Composable
fun LanPill(path: ConnectionPath, modifier: Modifier = Modifier) {
    val p = LocalAppPalette.current
    MicroPill(
        path.label,
        p.statusPillText,
        p.statusPillBg,
        modifier.testTag("lan-pill").semantics { contentDescription = path.label },
    )
}

/**
 * 顶栏返回chevron。用几何路径而不是 `‹` 字符：字符墨迹偏上，排版盒对齐看起来没对齐（083 §8）。
 * 路径关于垂直中心对称，视觉重心 = 几何中心。
 */
internal object BackChevronGeometry {
    /** 对称折线，垂直中心即视觉中心。坐标相对 [w]×[h] 盒子。 */
    fun addTo(path: androidx.compose.ui.graphics.Path, w: Float, h: Float) {
        path.moveTo(w * 0.64f, h * 0.22f)
        path.lineTo(w * 0.34f, h * 0.50f)
        path.lineTo(w * 0.64f, h * 0.78f)
    }

    fun inkCenterY(h: Float): Float = h * 0.50f
}

/** Geometrically centered back chevron with an accessibility label. */
@Composable
fun BackChevron(
    tint: Color,
    modifier: Modifier = Modifier,
    contentDescription: String = "返回",
) {
    Canvas(
        modifier
            .size(22.dp)
            .semantics { this.contentDescription = contentDescription },
    ) {
        val stroke = Stroke(
            width = 2.2.dp.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        val path = Path().apply { BackChevronGeometry.addTo(this, size.width, size.height) }
        drawPath(path, tint, style = stroke)
    }
}

/** 星标 —— 40dp 圆形触控区，行首位置。★ = 已收藏，☆ = 未收藏。 */
@Composable
fun StarButton(
    starred: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalAppPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .size(Dims.tapTargetMin)
            .clip(CircleShape)
            .background(if (pressed) p.rowPressed else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        AppText(
            text = if (starred) "★" else "☆",
            color = if (starred) p.starOn else p.starOff,
            fontSize = 17.sp,
            lineHeightMultiplier = 1f,
        )
    }
}

/** 行间发丝线，从文字起始位置内缩 */
@Composable
fun RowDivider(startInset: androidx.compose.ui.unit.Dp = 0.dp) {
    val p = LocalAppPalette.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = startInset)
            .height(Dims.hairline)
            .background(p.divider)
    )
}

/**
 * 一级页页头：大标题 + 等宽计数 + 右侧 LAN。
 * meta 传 null 就不显示第二行。
 */
@Composable
fun ScreenHeader(
    title: String,
    meta: String?,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    val p = LocalAppPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Dims.screenHPadding, end = Dims.screenHPadding, top = 14.dp, bottom = 12.dp)
            .testTag("screen-header"),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            AppText(
                text = title,
                color = p.titleText,
                fontSize = TypeSizes.screenTitle,
                fontWeight = FontWeight.Bold,
                lineHeightMultiplier = TypeSizes.titleLineHeight,
                letterSpacing = (-0.5).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta != null) {
                Box(Modifier.height(5.dp))
                AppText(
                    text = meta,
                    color = p.metaText,
                    fontSize = TypeSizes.headerMeta,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    lineHeightMultiplier = 1f,
                    letterSpacing = 0.2.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Box(Modifier.width(10.dp))
            trailing()
        }
    }
}

/**
 * 顶栏 FlatGlass 药丸按钮：44dp 触控地板（[Dims.pillTouchFloor]）内嵌 34dp 可见胶囊（[Dims.pillHeight]），
 * 半透微透底 + 0.5dp 发丝光边 + 顶亮/底暗，按压叠一层变暗蒙层；禁用整体 45% 透明。
 * 纯 [flatGlass]（不采样），可放在 ThreePane 录制树内的页面顶栏。语义合并到触控盒（Role.Button）。
 */
@Composable
fun GlassPillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val glass = flatGlassTokens()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .height(Dims.pillTouchFloor)
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(Dims.pillHeight)
                .flatGlass(
                    shape = CircleShape,
                    fill = glass.fill,
                    hairline = glass.hairline,
                    topGlint = glass.topGlint,
                    bottomShade = glass.bottomShade,
                    overlay = if (pressed && enabled) glass.pressDim else Color.Transparent,
                )
                .padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/**
 * iOS 标志性圆形液态玻璃返回按钮：44dp 触控地板内嵌 36dp 纯圆微透底盘 +
 * 0.5dp 发丝光边 + 居中折线箭头，无任何汉字。纯 [flatGlass]，不采样。
 */
@Composable
fun GlassCircleBackButton(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalAppPalette.current
    val glass = flatGlassTokens()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .size(Dims.pillTouchFloor)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onBack,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(Dims.circleBackDisc)
                .flatGlass(
                    shape = CircleShape,
                    fill = glass.fill,
                    hairline = glass.hairline,
                    topGlint = glass.topGlint,
                    bottomShade = glass.bottomShade,
                    overlay = if (pressed) glass.pressDim else Color.Transparent,
                ),
            contentAlignment = Alignment.Center,
        ) {
            BackChevron(tint = p.accent)
        }
    }
}

/** 二级页返回：‹ + 上级名称，FlatGlass 药丸，44dp 触控地板 */
@Composable
fun BackAffordance(
    label: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalAppPalette.current
    GlassPillButton(
        onClick = onBack,
        modifier = modifier,
        contentPadding = PaddingValues(start = 6.dp, end = 12.dp),
    ) {
        BackChevron(tint = p.accent)
        Box(Modifier.width(2.dp))
        AppText(label, p.accent, TypeSizes.actionButton, fontWeight = FontWeight.Medium, lineHeightMultiplier = 1f)
    }
}

/** 顶栏右侧的文字按钮（「查看」） */
@Composable
fun TonalTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalAppPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .height(Dims.actionButtonHeight)
            .clip(RoundedCornerShape(Radii.actionButton))
            .background(if (pressed) p.accentContainerPressed else p.accentContainer)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = Dims.actionButtonHPadding),
        contentAlignment = Alignment.Center,
    ) {
        AppText(text, p.accent, TypeSizes.actionButton, fontWeight = FontWeight.Medium, lineHeightMultiplier = 1f)
    }
}

/**
 * 设置卡片里的主要按钮：主色透光薄染（accent @ 0.35）+ 1dp 主色细边，按压叠一层变暗蒙层。
 * 纯 background / border，⛔ 不用满宽实心，⛔ 不采样背景。
 */
@Composable
fun CardTonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalAppPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(Radii.cardButton)
    Box(
        modifier = modifier
            .height(Dims.cardButtonHeight)
            .clip(shape)
            .background(p.accentContainer.copy(alpha = TonalButtonFillAlpha))
            .background(if (pressed) FlatGlassPressDim else Color.Transparent)
            .border(TonalButtonStrokeWidth, p.accent.copy(alpha = TonalButtonStrokeAlpha), shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AppText(text, p.accent, TypeSizes.cardButton, fontWeight = FontWeight.SemiBold, lineHeightMultiplier = 1f)
    }
}

private const val TonalButtonFillAlpha = 0.35f
private const val TonalButtonStrokeAlpha = 0.45f
private val TonalButtonStrokeWidth = 1.dp

/** 纯平薄片按压变暗蒙层（浅深通用：黑 12%）。 */
private val FlatGlassPressDim = Color(0x1F000000)

/** 设置卡片里的次要按钮（描边） */
@Composable
fun CardOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalAppPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .height(Dims.cardButtonHeight)
            .clip(RoundedCornerShape(Radii.cardButton))
            .background(if (pressed) p.outlineButtonPressed else Color.Transparent)
            .border(Dims.hairline, p.outlineButtonBorder, RoundedCornerShape(Radii.cardButton))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AppText(text, p.outlineButtonText, TypeSizes.cardButton, fontWeight = FontWeight.SemiBold, lineHeightMultiplier = 1f)
    }
}

/**
 * 设置页卡片外壳：纯平半透薄板（FlatGlass）。浅色 0xEEFFFFFF / 深色 0xCC1E1E20 的平面底
 * + 0.5dp 发丝边（浅色微暗 / 深色微光）。卡片处于 ThreePane 的 layerBackdrop 录制树内，
 * ⛔ 只能用 background / border，⛔ 不得换成 glassPanel / glassControl（自采样递归崩溃）。
 */
@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val dark = LocalAppPalette.current === DarkPalette
    val shape = RoundedCornerShape(Radii.card)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (dark) SettingsCardFillDark else SettingsCardFillLight)
            .border(SettingsCardStrokeWidth, if (dark) SettingsCardStrokeDark else SettingsCardStrokeLight, shape)
            .padding(Dims.cardPadding),
        content = content,
    )
}

private val SettingsCardFillLight = Color(0xEEFFFFFF)
private val SettingsCardFillDark = Color(0xCC1E1E20)
private val SettingsCardStrokeLight = Color(0x33000000)
private val SettingsCardStrokeDark = Color(0x33FFFFFF)
private val SettingsCardStrokeWidth = 0.5.dp
