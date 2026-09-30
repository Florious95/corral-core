package dev.agentmirror.app.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.agentmirror.app.ui.components.AppText
import dev.agentmirror.app.ui.components.LanPill
import dev.agentmirror.app.ui.components.TonalTextButton
import dev.agentmirror.app.ui.theme.Dims
import dev.agentmirror.app.ui.theme.Elevations
import dev.agentmirror.app.ui.theme.LocalAppPalette
import dev.agentmirror.app.ui.theme.Radii
import dev.agentmirror.app.ui.theme.TypeSizes
import androidx.compose.material3.Surface

/** 功能键排上的键。Ctrl-C 单独标 danger。 */
enum class TerminalKey(val label: String, val danger: Boolean = false) {
    Esc("Esc"),
    Tab("Tab"),
    Up("↑"),
    Down("↓"),
    Left("←"),
    Right("→"),
    CtrlC("Ctrl-C", danger = true),
}

/**
 * 会话页外壳：顶栏 + 终端槽位 + 底部控制台（功能键排 + 输入条）。
 *
 * 🔴 终端正文不在这里 —— 通过 terminalContent 槽位塞进来，
 *    你那边放 AndroidView { SurfaceView }。本 Composable 只负责它的位置、外框和圆角。
 *
 * 底部两条浅色栏合并成一整块 consoleBackground，共用一条顶部分割线：
 * 视觉重量减半，正文区占比从约 62% 提到 74%。
 */
@Composable
fun SessionShellScreen(
    sessionDisplayName: String,
    running: Boolean,
    lanConnected: Boolean,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onBack: () -> Unit,
    onOpenSwitcher: () -> Unit,
    onKeyPress: (TerminalKey) -> Unit,
    onAttach: () -> Unit,
    modifier: Modifier = Modifier,
    terminalContent: @Composable () -> Unit,
) {
    val p = LocalAppPalette.current
    Column(modifier.fillMaxSize().background(p.screenBackground)) {
        SessionTopBar(
            sessionDisplayName = sessionDisplayName,
            running = running,
            lanConnected = lanConnected,
            onBack = onBack,
            onOpenSwitcher = onOpenSwitcher,
        )
        // 终端屏：四周 8dp 呼吸 + 14dp 圆角，做成「内嵌屏幕」
        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(Dims.terminalCardMargin),
            shape = RoundedCornerShape(Radii.terminalCard),
            color = dev.agentmirror.app.ui.theme.currentTerminalPalette().background,
            tonalElevation = Elevations.none,
            shadowElevation = if (p === dev.agentmirror.app.ui.theme.DarkPalette) Elevations.terminalCardDark else Elevations.terminalCardLight,
            border = if (p === dev.agentmirror.app.ui.theme.DarkPalette)
                BorderStroke(Dims.hairline, p.divider) else null,
        ) {
            terminalContent()
        }
        ConsoleBar(
            draft = draft,
            onDraftChange = onDraftChange,
            onSend = onSend,
            onKeyPress = onKeyPress,
            onAttach = onAttach,
        )
    }
}

/**
 * 顶栏。
 * 🔴 标题是会话的真实显示名，可能是中文（远控 leader / team-leader-2）。
 *    用系统默认字体测宽，⛔ 不按 ASCII 宽度排版。
 *    右侧只有 LAN 胶囊 + 「查看」，给标题留出尽量宽的空间。
 * 名字极长时的取舍见文末说明。
 */
@Composable
private fun SessionTopBar(
    sessionDisplayName: String,
    running: Boolean,
    lanConnected: Boolean,
    onBack: () -> Unit,
    onOpenSwitcher: () -> Unit,
) {
    val p = LocalAppPalette.current
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .height(Dims.topBarHeight)
                .padding(start = 4.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconGlyphButton(glyph = "‹", size = Dims.backButtonSize, fontSize = 26.sp, tint = p.accent, onClick = onBack)
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RunningDot(running = running)
                AppText(
                    text = sessionDisplayName,
                    color = p.rowTitleText,
                    fontSize = TypeSizes.topBarTitle,
                    fontWeight = FontWeight.SemiBold,
                    // ⛔ 不用 FontFamily.Monospace —— 中文落到等宽上宽度不稳
                    fontFamily = FontFamily.Default,
                    lineHeightMultiplier = 1.2f,
                    letterSpacing = (-0.2).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                    // 名字过长想跑马灯而不是省略号，加上这一句（ExperimentalFoundationApi，无新依赖）：
                    // modifier = Modifier.weight(1f).basicMarquee()
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (lanConnected) LanPill()
                TonalTextButton("查看", onOpenSwitcher)
                Box(Modifier.width(4.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(Dims.hairline).background(p.divider))
    }
}

@Composable
private fun RunningDot(running: Boolean) {
    val p = LocalAppPalette.current
    val transition = rememberInfiniteTransition(label = "runDot")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (running) 0.35f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                dev.agentmirror.app.ui.theme.Motion.statusDotPulse / 2,
                easing = dev.agentmirror.app.ui.theme.Motion.emphasized,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "runDotAlpha",
    )
    Box(
        Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(if (running) p.busyDot.copy(alpha = alpha) else p.idleChipText)
    )
}

@Composable
private fun IconGlyphButton(
    glyph: String,
    size: Dp,
    fontSize: androidx.compose.ui.unit.TextUnit,
    tint: Color,
    onClick: () -> Unit,
) {
    val p = LocalAppPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (pressed) p.accentContainer else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AppText(glyph, tint, fontSize, fontFamily = FontFamily.Monospace, lineHeightMultiplier = 1f)
    }
}

/** 功能键排 + 输入条，同一块表面、同一条顶部分割线 */
@Composable
private fun ConsoleBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onKeyPress: (TerminalKey) -> Unit,
    onAttach: () -> Unit,
) {
    val p = LocalAppPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(p.consoleBackground)
    ) {
        Box(Modifier.fillMaxWidth().height(Dims.hairline).background(p.divider))
        Column(
            Modifier.padding(
                start = Dims.consoleHPadding,
                end = Dims.consoleHPadding,
                top = Dims.consoleVPadding,
                bottom = 10.dp,
            )
        ) {
            // 三组等距：Esc/Tab | 方向键簇 | Ctrl-C
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(Dims.keyGap)) {
                    KeyCap(TerminalKey.Esc, Dims.keyWidthText, onKeyPress)
                    KeyCap(TerminalKey.Tab, Dims.keyWidthText, onKeyPress)
                }
                Row(
                    Modifier
                        .clip(RoundedCornerShape(Radii.arrowCluster))
                        .background(p.arrowClusterTrack)
                        .padding(Dims.arrowClusterPadding),
                    horizontalArrangement = Arrangement.spacedBy(Dims.arrowClusterGap),
                ) {
                    listOf(TerminalKey.Up, TerminalKey.Down, TerminalKey.Left, TerminalKey.Right).forEach {
                        ArrowKeyCap(it, onKeyPress)
                    }
                }
                KeyCap(TerminalKey.CtrlC, Dims.keyWidthDanger, onKeyPress)
            }
            Box(Modifier.height(Dims.composerTopGap))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dims.composerRowGap),
            ) {
                PlusButton(onAttach)
                DraftField(draft = draft, onDraftChange = onDraftChange, onSend = onSend, modifier = Modifier.weight(1f))
                SendButton(enabled = draft.isNotBlank(), onSend = onSend)
            }
        }
    }
}

/**
 * 键帽。
 * 🔴 深色下 ⛔ 不要外投影 —— 会在键底留一条黑边（这是之前被指出的问题）。
 *    立体感改由顶部 1dp 内高光 + 上边框提亮来给。
 */
@Composable
private fun KeyCap(
    key: TerminalKey,
    width: Dp,
    onKeyPress: (TerminalKey) -> Unit,
) {
    val p = LocalAppPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val border = if (key.danger) p.keycapDangerBorder else p.keycapBorder
    val highlight = if (key.danger) p.keycapDangerTopHighlight else p.keycapTopHighlight
    val bg = when {
        pressed && key.danger -> p.keycapDangerPressed
        pressed -> p.keycapPressed
        else -> p.keycapBackground
    }
    Box(
        Modifier
            .width(width)
            .height(Dims.keyHeight)
            .clip(RoundedCornerShape(Radii.key))
            .background(bg)
            .border(Dims.hairline, border, RoundedCornerShape(Radii.key))
            .clickable(interactionSource = interaction, indication = null) { onKeyPress(key) },
        contentAlignment = Alignment.Center,
    ) {
        if (highlight.alpha > 0f && !pressed) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(Dims.hairline)
                    .background(highlight)
            )
        }
        AppText(
            text = key.label,
            color = if (key.danger) p.keycapDangerText else p.keycapText,
            fontSize = if (key.danger) TypeSizes.keyLabelDanger else TypeSizes.keyLabel,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            lineHeightMultiplier = 1f,
        )
    }
}

@Composable
private fun ArrowKeyCap(key: TerminalKey, onKeyPress: (TerminalKey) -> Unit) {
    val p = LocalAppPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .width(Dims.keyWidthArrow)
            .height(Dims.keyHeightArrow)
            .clip(RoundedCornerShape(Radii.keyArrow))
            .background(if (pressed) p.keycapPressed else p.keycapBackground)
            .border(Dims.hairline, p.keycapBorder, RoundedCornerShape(Radii.keyArrow))
            .clickable(interactionSource = interaction, indication = null) { onKeyPress(key) },
        contentAlignment = Alignment.Center,
    ) {
        AppText(key.label, p.keycapText, TypeSizes.keyGlyphArrow, fontFamily = FontFamily.Monospace, lineHeightMultiplier = 1f)
    }
}

@Composable
private fun PlusButton(onAttach: () -> Unit) {
    val p = LocalAppPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .size(Dims.plusButtonSize)
            .clip(RoundedCornerShape(Radii.plusButton))
            .background(if (pressed) p.accentContainerPressed else p.accentContainer)
            .clickable(interactionSource = interaction, indication = null, onClick = onAttach),
        contentAlignment = Alignment.Center,
    ) {
        AppText("+", p.accent, 24.sp, fontWeight = FontWeight.Light, fontFamily = FontFamily.Monospace, lineHeightMultiplier = 1f)
    }
}

@Composable
private fun DraftField(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalAppPalette.current
    Row(
        modifier
            .height(Dims.inputHeight)
            .clip(RoundedCornerShape(Radii.input))
            .background(p.inputBackground)
            .border(Dims.hairline, p.inputBorder, RoundedCornerShape(Radii.input))
            .padding(horizontal = Dims.inputHPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppText("❯", p.promptGlyph, 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, lineHeightMultiplier = 1f)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (draft.isEmpty()) {
                AppText("输入指令…", p.inputPlaceholder, TypeSizes.inputText, lineHeightMultiplier = 1f)
            }
            BasicTextField(
                value = draft,
                onValueChange = onDraftChange,
                singleLine = true,
                textStyle = LocalTextStyle.current.merge(
                    TextStyle(
                        color = p.inputText,
                        fontSize = TypeSizes.inputText,
                        // 中英混排，走系统默认字体
                        fontFamily = FontFamily.Default,
                    )
                ),
                cursorBrush = SolidColor(p.accent),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { onSend() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SendButton(enabled: Boolean, onSend: () -> Unit) {
    val p = LocalAppPalette.current
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(Dims.sendButtonSize)
            .clip(RoundedCornerShape(Radii.sendButton))
            .background(if (enabled) p.sendEnabledBg else p.sendDisabledBg)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onSend),
        contentAlignment = Alignment.Center,
    ) {
        AppText(
            text = "↑",
            color = if (enabled) p.sendEnabledFg else p.sendDisabledFg,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            lineHeightMultiplier = 1f,
        )
    }
}
