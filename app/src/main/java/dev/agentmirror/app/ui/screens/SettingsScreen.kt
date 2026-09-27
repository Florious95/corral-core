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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.ScrollState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.agentmirror.app.session.ShortcutCommand
import dev.agentmirror.app.ui.components.AppText
import dev.agentmirror.app.ui.components.CardOutlineButton
import dev.agentmirror.app.ui.components.CardTonalButton
import dev.agentmirror.app.ui.components.LocalFloatingNavInset
import dev.agentmirror.app.ui.components.MicroPill
import dev.agentmirror.app.ui.components.ScreenHeader
import dev.agentmirror.app.ui.components.StandaloneLiquidToggle
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
 * 设置页：iOS 分组卡结构，五组——主机配对 / 会话与输入 / 终端 / 界面 / 支持。
 * 快捷命令不再平铺在本页，只留一行入口（数量 + 各 Provider 覆盖），点进二级页管理，
 * 命令再多本页高度也不变。每组一张 FlatGlass 卡，行首彩色图标砖，行间发丝线。
 * [scrollState] 由容器持有：进出二级页后回到原滚动位置。
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
    inputSyncEnabled: Boolean = true,
    onInputSyncEnabledChange: (Boolean) -> Unit = {},
    retainPaneSizeEnabled: Boolean = false,
    onRetainPaneSizeEnabledChange: (Boolean) -> Unit = {},
    shortcutCommands: List<ShortcutCommand> = emptyList(),
    onOpenShortcutCommands: () -> Unit = {},
    scrollState: ScrollState = rememberScrollState(),
) {
    val p = LocalAppPalette.current
    Column(modifier.fillMaxSize().background(p.screenBackground).statusBarsPadding()) {
        ScreenHeader(title = "设置", meta = null)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .testTag("settings-scroll")
                // 底部多留悬浮导航压住的高度，最后一张卡才能滚出胶囊（卡底与胶囊顶留 4dp）
                .padding(start = 14.dp, end = 14.dp, bottom = 4.dp + LocalFloatingNavInset.current),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ── 主机配对 ──
            SettingsGroup {
                SettingsRow(
                    glyph = SettingsGlyph.Host,
                    title = "主机配对",
                    subtitle = "当前只保留一个主机档案。重新配对成功后会覆盖现有档案。",
                    trailing = {
                        if (paired) {
                            MicroPill("PAIRED", p.busyChipText, p.busyChipBg)
                        } else {
                            MicroPill("UNPAIRED", p.unknownChipText, p.unknownChipBg)
                        }
                    },
                    below = { CardTonalButton("重新配对", onRepair, Modifier.fillMaxWidth()) },
                )
            }

            // ── 会话与输入 ──
            SettingsSectionLabel("会话与输入")
            SettingsGroup {
                SettingsRow(
                    glyph = SettingsGlyph.Shortcut,
                    title = "快捷命令",
                    subtitle = shortcutCoverageSummary(shortcutCommands),
                    onClick = onOpenShortcutCommands,
                    modifier = Modifier.testTag("settings-shortcut-entry"),
                    trailing = {
                        if (shortcutCommands.isNotEmpty()) {
                            AppText(
                                text = shortcutCommands.size.toString(),
                                color = p.metaText,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace,
                                lineHeightMultiplier = 1f,
                                modifier = Modifier.testTag("settings-shortcut-count"),
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        ForwardChevron()
                    },
                )
                SettingsGroupDivider()
                SettingsToggleRow(
                    glyph = SettingsGlyph.Sync,
                    title = "输入框实时同步",
                    subtitle = "开启时打字实时显示在 CLI 终端，关闭时仅在点击发送后一次性投递。",
                    checked = inputSyncEnabled,
                    onCheckedChange = onInputSyncEnabledChange,
                    switchTag = "input-sync-switch",
                )
                SettingsGroupDivider()
                SettingsToggleRow(
                    glyph = SettingsGlyph.Retain,
                    title = "退出保留排版（尺寸驻留）",
                    subtitle = "退出会话时不还原原生终端尺寸，再次进入实现极速秒开",
                    checked = retainPaneSizeEnabled,
                    onCheckedChange = onRetainPaneSizeEnabledChange,
                    switchTag = "retain-pane-size-switch",
                )
            }

            // ── 终端 ──
            SettingsSectionLabel("终端")
            SettingsGroup {
                SettingsRow(
                    glyph = SettingsGlyph.Font,
                    title = "字体大小",
                    subtitle = "取代原捏合缩放：终端字号在此设置，进入会话前已确定，会话中不再变化。",
                    trailing = {
                        AppText(
                            "$terminalFontSize pt",
                            p.accent,
                            12.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                            lineHeightMultiplier = 1f,
                        )
                    },
                    below = {
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
                        Box(Modifier.height(10.dp))
                        TerminalPreviewLine(fontSize = terminalFontSize)
                    },
                )
                SettingsGroupDivider()
                SettingsRow(
                    glyph = SettingsGlyph.Theme,
                    title = "终端主题",
                    subtitle = "「外观」决定此刻用浅槽还是深槽，每个槽各自记住一个主题族。",
                    below = {
                        TermThemeSlotRow(
                            label = "浅色时",
                            testTag = "term-theme-light-row",
                            family = familyOrDefault(lightFamilyId),
                            darkSlot = false,
                            onClick = onOpenLightTheme,
                        )
                        Box(Modifier.height(6.dp))
                        TermThemeSlotRow(
                            label = "深色时",
                            testTag = "term-theme-dark-row",
                            family = familyOrDefault(darkFamilyId),
                            darkSlot = true,
                            onClick = onOpenDarkTheme,
                        )
                    },
                )
            }

            // ── 界面 ──
            SettingsSectionLabel("界面")
            SettingsGroup {
                SettingsRow(
                    glyph = SettingsGlyph.Appearance,
                    title = "外观",
                    subtitle = "列表、设置和外壳跟随这里；终端正文见「终端主题」。",
                    trailing = { SettingsValueText(appearance.label()) },
                    below = { AppearanceSegmented(selected = appearance, onSelect = onAppearanceChange) },
                )
            }

            // ── 支持 ──
            SettingsSectionLabel("支持")
            SettingsGroup {
                SettingsRow(
                    glyph = SettingsGlyph.Logs,
                    title = "诊断日志",
                    subtitle = "一键导出诊断日志，帮助我们定位问题。日志会自动脱敏（配对 token、密钥等不会包含）。",
                    below = {
                        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            CardTonalButton("导出", onExportLogs, Modifier.weight(1f))
                            CardOutlineButton("查看", onViewLogs, Modifier.weight(1f))
                        }
                    },
                )
            }

            AppText(
                text = buildLabel,
                color = p.pathText,
                fontSize = TypeSizes.footnote,
                fontFamily = FontFamily.Monospace,
                lineHeightMultiplier = 1.5f,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 4.dp),
            )
        }
        bottomBar()
    }
}

/** 开关行：整行可点切换（更大的触控面），开关本身保留独立 testTag 与 Role.Switch 语义。 */
@Composable
private fun SettingsToggleRow(
    glyph: SettingsGlyph,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    switchTag: String,
) {
    SettingsRow(
        glyph = glyph,
        title = title,
        subtitle = subtitle,
        onClick = { onCheckedChange(!checked) },
        trailing = {
            // 自包含液态开关：⛔ 不读 LocalGlassBackdrop（设置页在录制树内，反向采样会 SIGSEGV）
            StandaloneLiquidToggle(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.testTag(switchTag),
            )
        },
    )
}

/*
 * 设置页控件的 FlatGlass 常量：未选中微透底板（中性灰 8%）+ 中性细边，
 * 选中主色薄染（accent @ 0.25）+ 主色细光边；0.5dp 发丝边。
 * 全部是 background / border（设置页在 layerBackdrop 录制树内，⛔ 不采样）。
 */
private val FlatChipFill = Color(0x15808080)
private val FlatChipStroke = Color(0x26808080)
private val FlatHairline = 0.5.dp
private const val SelectedTintAlpha = 0.25f
private const val SelectedStrokeAlpha = 0.55f

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
    val shape = RoundedCornerShape(Radii.chip)
    val bg = when {
        selected -> p.accent.copy(alpha = SelectedTintAlpha)
        pressed -> p.chipPressed
        else -> FlatChipFill
    }
    val stroke = if (selected) p.accent.copy(alpha = SelectedStrokeAlpha) else FlatChipStroke
    Box(
        modifier = modifier
            .height(Dims.chipHeight)
            .clip(shape)
            .background(bg)
            .border(FlatHairline, stroke, shape)
            .clickable(interactionSource = interaction, indication = null, enabled = !selected, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // 选中底只是薄染，数字改用主色实字保证清晰，不再用白字。
        AppText(
            text = value.toString(),
            color = if (selected) p.accent else p.chipText,
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
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.previewBox))
            .background(background)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
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
    val trackShape = RoundedCornerShape(Radii.segmentedTrack)
    val itemShape = RoundedCornerShape(Radii.segmentedItem)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(trackShape)
            .background(FlatChipFill)
            .border(FlatHairline, FlatChipStroke, trackShape)
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
                    .clip(itemShape)
                    .background(
                        when {
                            isOn -> p.accent.copy(alpha = SelectedTintAlpha)
                            pressed -> p.chipPressed
                            else -> Color.Transparent
                        }
                    )
                    .then(
                        if (isOn) Modifier.border(FlatHairline, p.accent.copy(alpha = SelectedStrokeAlpha), itemShape) else Modifier,
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
        SettingsSubPageHeader(
            title = if (darkSlot) "深色时的主题" else "浅色时的主题",
            onBack = onBack,
            backTag = "term-theme-picker-back",
        )
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

/** 主题槽：嵌在「终端主题」行下的内凹小行——槽名 + 色板条 + 主题名 + 前进折线。 */
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
    val shape = RoundedCornerShape(Radii.cardButton)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(shape)
            .background(if (pressed) p.chipPressed else FlatChipFill)
            .border(FlatHairline, FlatChipStroke, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .testTag(testTag)
            .semantics { contentDescription = testTag }
            .padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppText(label, p.rowTitleText, TypeSizes.cardBody, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        MiniSwatch(colors)
        AppText(
            text = family.title,
            color = p.accent,
            fontSize = TypeSizes.chip,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 120.dp).testTag("term-theme-family-${family.id}"),
        )
        ForwardChevron()
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
    Row(Modifier.clip(RoundedCornerShape(3.dp))) {
        swatchArgb(colors).forEach { argb ->
            Box(
                Modifier
                    .size(width = 7.dp, height = 14.dp)
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
