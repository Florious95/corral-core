package dev.agentmirror.app.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
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
import dev.agentmirror.app.ui.theme.TermSchemeCatalog
import dev.agentmirror.app.ui.theme.TermSchemeColors
import dev.agentmirror.app.ui.theme.TermThemeFamilyDef
import dev.agentmirror.app.ui.theme.TermThemeStore
import dev.agentmirror.app.ui.theme.TerminalMetrics
import dev.agentmirror.app.ui.theme.TypeSizes
import dev.agentmirror.app.ui.theme.currentTerminalPalette

/**
 * 设置页。五张卡：主机配对 / 字体大小 / 诊断日志 / 外观 / 终端主题。
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
    lightFamilyId: String = TermThemeStore.DEFAULT_FAMILY_ID,
    darkFamilyId: String = TermThemeStore.DEFAULT_FAMILY_ID,
    onOpenLightTheme: () -> Unit = {},
    onOpenDarkTheme: () -> Unit = {},
    extraCards: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {},
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
                CardBody("列表、设置和外壳走这里。终端正文按下面选的浅槽 / 深槽主题画。")
                Box(Modifier.height(12.dp))
                AppearanceSegmented(selected = appearance, onSelect = onAppearanceChange)
            }

            SettingsCard {
                AppText("终端主题", p.rowTitleText, TypeSizes.cardTitle, fontWeight = FontWeight.SemiBold)
                Box(Modifier.height(8.dp))
                CardBody("外观决定此刻用浅槽还是深槽。每个槽各自记住一个主题族。")
                Box(Modifier.height(8.dp))
                TermThemeSlotRow(
                    label = "浅色时",
                    testTag = "term-theme-light-row",
                    family = familyOrDefault(lightFamilyId),
                    darkSlot = false,
                    onClick = onOpenLightTheme,
                )
                Box(Modifier.height(4.dp))
                TermThemeSlotRow(
                    label = "深色时",
                    testTag = "term-theme-dark-row",
                    family = familyOrDefault(darkFamilyId),
                    darkSlot = true,
                    onClick = onOpenDarkTheme,
                )
            }

            extraCards()

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
    TerminalPreviewLine(
        fontSize = fontSize,
        background = term.background,
        foreground = term.foreground,
        cursor = term.cursor,
    )
}

@Composable
private fun TerminalPreviewLine(
    fontSize: Int,
    background: Color,
    foreground: Color,
    cursor: Color,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.previewBox))
            .background(background)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AppText("❯", cursor, fontSize.sp, fontFamily = FontFamily.Monospace, lineHeightMultiplier = 1.55f)
        AppText(
            text = "claim-leader --team wiki-team",
            color = foreground,
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

@Composable
internal fun TermThemePickerScreen(
    darkSlot: Boolean,
    selectedFamilyId: String,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val p = LocalAppPalette.current
    var query by remember { mutableStateOf("") }
    val selected = familyOrDefault(selectedFamilyId)
    val slotColors = slotColors(selected, darkSlot)
    val q = query.trim()
    val visible = TermSchemeCatalog.families.filter { family ->
        q.isEmpty() || family.title.contains(q, ignoreCase = true)
    }
    val paired = visible.filter { it.lightSource != it.darkSource }
    val darkOnly = visible.filter { it.lightSource == it.darkSource }
    Column(modifier.fillMaxSize().background(p.screenBackground).statusBarsPadding()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onBack)
                .padding(start = Dims.screenHPadding, end = Dims.screenHPadding, top = 8.dp)
                .testTag("term-theme-picker-back"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppText("‹ 返回", p.accent, TypeSizes.cardBody, fontWeight = FontWeight.Medium)
        }
        ScreenHeader(title = if (darkSlot) "深色时的主题" else "浅色时的主题", meta = null)
        Column(Modifier.padding(horizontal = 14.dp)) {
            TerminalPreviewLine(
                fontSize = 14,
                background = argbColor(slotColors.background),
                foreground = argbColor(slotColors.foreground),
                cursor = argbColor(slotColors.cursor),
            )
            Box(Modifier.height(10.dp))
            ThemeSearchField(query = query, onQueryChange = { query = it })
            Box(Modifier.height(8.dp))
        }
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("term-theme-picker-list"),
        ) {
            if (paired.isNotEmpty()) {
                item(key = "hdr-paired") { ThemeGroupHeader("成对深浅") }
                items(paired, key = { "p-${it.id}" }) { family ->
                    TermThemeFamilyRow(
                        family = family,
                        darkSlot = darkSlot,
                        selected = family.id == selected.id,
                        onClick = { onSelect(family.id) },
                    )
                }
            }
            if (darkOnly.isNotEmpty()) {
                item(key = "hdr-dark") { ThemeGroupHeader("仅深色") }
                items(darkOnly, key = { "d-${it.id}" }) { family ->
                    TermThemeFamilyRow(
                        family = family,
                        darkSlot = darkSlot,
                        selected = family.id == selected.id,
                        onClick = { onSelect(family.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeSearchField(query: String, onQueryChange: (String) -> Unit) {
    val p = LocalAppPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(Radii.input))
            .background(p.inputBackground)
            .border(Dims.hairline, p.inputBorder, RoundedCornerShape(Radii.input))
            .padding(horizontal = 12.dp)
            .testTag("term-theme-search"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                AppText("搜索主题", p.inputPlaceholder, TypeSizes.inputText, lineHeightMultiplier = 1f)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = p.inputText, fontSize = TypeSizes.inputText),
                cursorBrush = SolidColor(p.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("term-theme-search-input"),
            )
        }
    }
}

@Composable
private fun ThemeGroupHeader(title: String) {
    val p = LocalAppPalette.current
    AppText(
        text = title,
        color = p.metaText,
        fontSize = TypeSizes.footnote,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 18.dp, end = 14.dp, top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun TermThemeSlotRow(
    label: String,
    testTag: String,
    family: TermThemeFamilyDef,
    darkSlot: Boolean,
    onClick: () -> Unit,
) {
    val p = LocalAppPalette.current
    val colors = slotColors(family, darkSlot)
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(Radii.chip))
            .clickable(onClick = onClick)
            .testTag(testTag)
            .semantics { contentDescription = testTag }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppText(label, p.rowTitleText, TypeSizes.cardBody, modifier = Modifier.weight(1f))
        MiniSwatch(colors)
        AppText(
            text = family.title,
            color = p.accent,
            fontSize = TypeSizes.chip,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("term-theme-family-${family.id}"),
        )
    }
}

@Composable
private fun TermThemeFamilyRow(
    family: TermThemeFamilyDef,
    darkSlot: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val p = LocalAppPalette.current
    val colors = slotColors(family, darkSlot)
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clickable(onClick = onClick)
            .testTag("term-theme-family-${family.id}")
            .semantics {
                contentDescription = "term-theme-family-${family.id}"
                this.selected = selected
            }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PickerSwatch(colors)
        Column(Modifier.weight(1f)) {
            AppText(
                text = family.title,
                color = p.rowTitleText,
                fontSize = TypeSizes.cardBody,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            AppText(
                text = familyBlurb(family.id),
                color = p.bodyText,
                fontSize = TypeSizes.footnote,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                lineHeightMultiplier = 1.1f,
            )
        }
        if (selected) {
            AppText("✓", p.accent, TypeSizes.cardTitle, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun MiniSwatch(colors: TermSchemeColors) {
    Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        swatchArgb(colors).forEach { argb ->
            Box(
                Modifier
                    .size(8.dp)
                    .background(argbColor(argb)),
            )
        }
    }
}

@Composable
private fun PickerSwatch(colors: TermSchemeColors) {
    Row(
        Modifier
            .width(54.dp)
            .height(38.dp)
            .clip(RoundedCornerShape(4.dp)),
    ) {
        swatchArgb(colors).forEach { argb ->
            Box(
                Modifier
                    .weight(1f)
                    .height(38.dp)
                    .background(argbColor(argb)),
            )
        }
    }
}

private fun familyOrDefault(id: String): TermThemeFamilyDef =
    TermSchemeCatalog.families.find { it.id == id }
        ?: TermSchemeCatalog.families.first { it.id == TermThemeStore.DEFAULT_FAMILY_ID }

private fun slotColors(family: TermThemeFamilyDef, darkSlot: Boolean): TermSchemeColors {
    val source = if (darkSlot) family.darkSource else family.lightSource
    return TermSchemeCatalog.colors(source)
}

private fun swatchArgb(colors: TermSchemeColors): List<Int> = listOf(
    colors.background,
    colors.ansi[1],
    colors.ansi[2],
    colors.ansi[4],
    colors.ansi[6],
    colors.foreground,
    colors.cursor,
    colors.ansi[3],
)

private fun argbColor(argb: Int): Color = Color(argb)

private fun familyBlurb(id: String): String = when (id) {
    "follow-system" -> "浅槽 Alabaster，深槽 Afterglow。"
    "vesper" -> "暖中性黑底，出厂默认。"
    "apple-system-colors" -> "贴近系统强调色的浅深成对。"
    "dracula" -> "紫粉暗底，两槽同一份。"
    "solarized" -> "低对比浅深成对。"
    "catppuccin" -> "Latte 浅、Mocha 深。"
    "tokyo-night" -> "白昼与夜晚成对。"
    "gruvbox" -> "复古暖棕浅深成对。"
    "nord" -> "北欧冷色浅深成对。"
    "monokai-pro" -> "高饱和编辑器配色成对。"
    "rose-pine" -> "Dawn 浅、松木深。"
    "ayu" -> "金棕浅深成对。"
    "one-half" -> "一半浅、一半深。"
    "kanagawa" -> "莲花浅、波浪深。"
    "everforest" -> "森系中等浅、硬深。"
    "github" -> "GitHub 默认浅深。"
    "night-owl" -> "猫头鹰浅与夜。"
    "iceberg" -> "冰蓝浅深。"
    "flexoki" -> "印刷油墨感浅深。"
    "selenized" -> "高可读浅深。"
    "modus" -> "无障碍取向浅深。"
    "tomorrow" -> "明日浅、今夜深。"
    "melange" -> "暖灰浅深。"
    "zenbones" -> "低饱和浅深。"
    "atom-one-dark" -> "只有深色半。"
    "snazzy" -> "亮色暗底，两槽同一份。"
    "oceanic-next" -> "海色暗底。"
    "poimandres" -> "冷青暗底。"
    "horizon" -> "暖橙暗底。"
    "zenburn" -> "低刺激深色。"
    else -> "上游 iTerm2 色板。"
}
