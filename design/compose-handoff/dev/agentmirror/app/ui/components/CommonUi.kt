package dev.agentmirror.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.agentmirror.app.ui.model.SessionStatus
import dev.agentmirror.app.ui.theme.Dims
import dev.agentmirror.app.ui.theme.LocalAppPalette
import dev.agentmirror.app.ui.theme.Motion
import dev.agentmirror.app.ui.theme.Radii
import dev.agentmirror.app.ui.theme.TypeSizes

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

/** 「进行中 / 空闲」状态标 */
@Composable
fun StatusChip(status: SessionStatus, modifier: Modifier = Modifier) {
    val p = LocalAppPalette.current
    val busy = status == SessionStatus.Busy
    Row(
        modifier = modifier
            .height(Dims.statusChipHeight)
            .clip(RoundedCornerShape(Radii.statusChip))
            .background(if (busy) p.busyChipBg else p.idleChipBg)
            .padding(horizontal = Dims.statusChipHPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (busy) {
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
                    .background(p.busyDot)
            )
        }
        AppText(
            text = if (busy) "进行中" else "空闲",
            color = if (busy) p.busyChipText else p.idleChipText,
            fontSize = TypeSizes.statusChip,
            fontWeight = if (busy) FontWeight.SemiBold else FontWeight.Medium,
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

@Composable
fun LanPill(modifier: Modifier = Modifier) {
    val p = LocalAppPalette.current
    MicroPill("LAN", p.statusPillText, p.statusPillBg, modifier)
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
            .padding(start = Dims.screenHPadding, end = Dims.screenHPadding, top = 14.dp, bottom = 12.dp),
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

/** 二级页返回：‹ + 上级名称，44dp 高触控区 */
@Composable
fun BackAffordance(
    label: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalAppPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = modifier
            .height(Dims.tapTargetMin)
            .clip(RoundedCornerShape(20.dp))
            .background(if (pressed) p.accentContainer else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onBack)
            .padding(start = 6.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        AppText("‹", p.accent, 22.sp, fontFamily = FontFamily.Monospace, lineHeightMultiplier = 1f)
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

/** 设置卡片里的主要按钮（轻着色，⛔ 不用满宽实心） */
@Composable
fun CardTonalButton(
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
            .background(if (pressed) p.accentContainerPressed else p.accentContainer)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AppText(text, p.accent, TypeSizes.cardButton, fontWeight = FontWeight.SemiBold, lineHeightMultiplier = 1f)
    }
}

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

/** 设置页卡片外壳 */
@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val p = LocalAppPalette.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.card))
            .background(p.cardBackground)
            .border(Dims.hairline, p.cardBorder, RoundedCornerShape(Radii.card))
            .padding(Dims.cardPadding),
        content = content,
    )
}
