package dev.agentmirror.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.agentmirror.app.ui.components.AppText
import dev.agentmirror.app.ui.components.CardOutlineButton
import dev.agentmirror.app.ui.components.CardTonalButton
import dev.agentmirror.app.ui.components.MicroPill
import dev.agentmirror.app.ui.components.ScreenHeader
import dev.agentmirror.app.ui.components.SettingsCard
import dev.agentmirror.app.ui.theme.Appearance
import dev.agentmirror.app.ui.theme.Dims
import dev.agentmirror.app.ui.theme.LocalAppPalette
import dev.agentmirror.app.ui.theme.Radii
import dev.agentmirror.app.ui.theme.TerminalMetrics
import dev.agentmirror.app.ui.theme.TypeSizes
import dev.agentmirror.app.ui.theme.currentTerminalPalette

/**
 * 设置页。四张卡：主机配对 / 字体大小 / 诊断日志 / 外观。
 * 原来四个满宽实心蓝按钮改成一主多辅：每张卡最多一个着色按钮，日志两个并排。
 */
@Composable
fun SettingsScreen(
    paired: Boolean,
    terminalFontSize: Int,
    appearance: Appearance,
    buildLabel: String,
    onRepair: () -> Unit,
    onFontSizeChange: (Int) -> Unit,
    onAppearanceChange: (Appearance) -> Unit,
    onExportLogs: () -> Unit,
    onViewLogs: () -> Unit,
    modifier: Modifier = Modifier,
    bottomBar: @Composable () -> Unit = {},
) {
    val p = LocalAppPalette.current
    Column(modifier.fillMaxSize().background(p.screenBackground).statusBarsPadding()) {
        ScreenHeader(title = "设置", meta = null)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .testTag("settings-scroll")
                .padding(start = 14.dp, end = 14.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(Dims.cardGap),
        ) {
            // ── 主机配对 ──
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppText("主机配对", p.rowTitleText, TypeSizes.cardTitle, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (paired) MicroPill("PAIRED", p.busyChipText, p.busyChipBg)
                }
                Box(Modifier.height(8.dp))
                CardBody("当前只保留一个主机档案。重新配对成功后会覆盖现有档案。")
                Box(Modifier.height(13.dp))
                CardTonalButton("重新配对", onRepair, Modifier.fillMaxWidth())
            }

            // ── 字体大小 ──
            SettingsCard {
                Row(verticalAlignment = Alignment.Bottom) {
                    AppText("字体大小", p.rowTitleText, TypeSizes.cardTitle, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    AppText("$terminalFontSize pt", p.accent, 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, lineHeightMultiplier = 1f)
                }
                Box(Modifier.height(8.dp))
                CardBody("取代原捏合缩放：终端字号在此设置，进入会话前已确定，会话中不再变化。")
                Box(Modifier.height(12.dp))
                // 9 个档位单行等分，⛔ 不要折行（折行会回到 5+4 的老问题）
                Row(horizontalArrangement = Arrangement.spacedBy(Dims.chipGap)) {
                    TerminalMetrics.fontSizeSteps.forEach { size ->
                        FontSizeChip(
                            value = size,
                            selected = size == terminalFontSize,
                            onClick = { onFontSizeChange(size) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Box(Modifier.height(12.dp))
                TerminalPreviewLine(fontSize = terminalFontSize)
            }

            // ── 诊断日志 ──
            SettingsCard {
                AppText("诊断日志", p.rowTitleText, TypeSizes.cardTitle, fontWeight = FontWeight.SemiBold)
                Box(Modifier.height(8.dp))
                CardBody("一键导出诊断日志，帮助我们定位问题。日志会自动脱敏（配对 token、密钥等不会包含）。")
                Box(Modifier.height(13.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    CardTonalButton("导出", onExportLogs, Modifier.weight(1f))
                    CardOutlineButton("查看", onViewLogs, Modifier.weight(1f))
                }
            }

            // ── 外观 ──
            SettingsCard {
                Row(verticalAlignment = Alignment.Bottom) {
                    AppText("外观", p.rowTitleText, TypeSizes.cardTitle, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    AppText(appearance.label(), p.accent, 11.5f.sp, fontWeight = FontWeight.SemiBold, lineHeightMultiplier = 1f)
                }
                Box(Modifier.height(8.dp))
                CardBody("终端正文始终保持深色，这里只切换列表、设置和外壳。")
                Box(Modifier.height(12.dp))
                AppearanceSegmented(selected = appearance, onSelect = onAppearanceChange)
            }

            AppText(
                text = buildLabel,
                color = p.pathText,
                fontSize = TypeSizes.footnote,
                fontFamily = FontFamily.Monospace,
                lineHeightMultiplier = 1.5f,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            )
        }
        bottomBar()
    }
}

@Composable
private fun CardBody(text: String) {
    val p = LocalAppPalette.current
    AppText(
        text = text,
        color = p.bodyText,
        fontSize = TypeSizes.cardBody,
        lineHeightMultiplier = TypeSizes.bodyLineHeight,
    )
}

@Composable
private fun FontSizeChip(
    value: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalAppPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg = when {
        selected -> p.chipSelectedBg
        pressed -> p.chipPressed
        else -> p.chipBg
    }
    Box(
        modifier = modifier
            .height(Dims.chipHeight)
            .clip(RoundedCornerShape(Radii.chip))
            .background(bg)
            .clickable(interactionSource = interaction, indication = null, enabled = !selected, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AppText(
            text = value.toString(),
            color = if (selected) p.chipSelectedText else p.chipText,
            fontSize = TypeSizes.chip,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            lineHeightMultiplier = 1f,
        )
    }
}

/** 所选字号的实时预览，直接用终端色板画一行，改档位立刻能看出效果 */
@Composable
private fun TerminalPreviewLine(fontSize: Int) {
    val term = currentTerminalPalette()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.previewBox))
            .background(term.background)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AppText("❯", term.cursor, fontSize.sp, fontFamily = FontFamily.Monospace, lineHeightMultiplier = 1.55f)
        AppText(
            text = "claim-leader --team wiki-team",
            color = term.foreground,
            fontSize = fontSize.sp,
            fontFamily = FontFamily.Monospace,
            lineHeightMultiplier = 1.55f,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AppearanceSegmented(
    selected: Appearance,
    onSelect: (Appearance) -> Unit,
) {
    val p = LocalAppPalette.current
    val options = remember { listOf(Appearance.Light, Appearance.Dark, Appearance.System) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.segmentedTrack))
            .background(p.segmentedTrack)
            .padding(Dims.segmentedTrackPadding),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { option ->
            val isOn = option == selected
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            Box(
                Modifier
                    .weight(1f)
                    .height(Dims.segmentedItemHeight)
                    .clip(RoundedCornerShape(Radii.segmentedItem))
                    .background(
                        when {
                            isOn -> p.segmentedSelectedBg
                            pressed -> p.chipPressed
                            else -> Color.Transparent
                        }
                    )
                    .clickable(interactionSource = interaction, indication = null, enabled = !isOn) { onSelect(option) },
                contentAlignment = Alignment.Center,
            ) {
                AppText(
                    text = option.label(),
                    color = if (isOn) p.segmentedSelectedText else p.segmentedText,
                    fontSize = TypeSizes.segmentedItem,
                    fontWeight = if (isOn) FontWeight.SemiBold else FontWeight.Medium,
                    lineHeightMultiplier = 1f,
                )
            }
        }
    }
}

private fun Appearance.label(): String = when (this) {
    Appearance.Light -> "浅色"
    Appearance.Dark -> "深色"
    Appearance.System -> "跟随系统"
}
