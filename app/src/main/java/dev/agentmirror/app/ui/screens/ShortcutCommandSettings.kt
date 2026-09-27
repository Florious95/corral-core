package dev.agentmirror.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.shapes.RoundedRectangle
import dev.agentmirror.app.session.ShortcutCommand
import dev.agentmirror.app.session.ShortcutProvider
import dev.agentmirror.app.ui.components.AppText
import dev.agentmirror.app.ui.components.CardTonalButton
import dev.agentmirror.app.ui.components.GlassButton
import dev.agentmirror.app.ui.components.GlassModalLayer
import dev.agentmirror.app.ui.components.LocalFloatingNavInset
import dev.agentmirror.app.ui.components.LocalGlassBackdrop
import dev.agentmirror.app.ui.components.ProviderMark
import dev.agentmirror.app.ui.components.glassPanel
import dev.agentmirror.app.ui.components.glassReadable
import dev.agentmirror.app.ui.theme.Dims
import dev.agentmirror.app.ui.theme.LocalAppPalette
import dev.agentmirror.app.ui.theme.Motion
import dev.agentmirror.app.ui.theme.Radii
import dev.agentmirror.app.ui.theme.TypeSizes

/**
 * 设置主页「快捷命令」行的副标题：各 Provider 已配置条数，未添加时给一句用途说明。
 */
internal fun shortcutCoverageSummary(commands: List<ShortcutCommand>): String =
    if (commands.isEmpty()) {
        "尚未添加 · 在会话输入栏一键插入常用指令"
    } else {
        ShortcutProvider.entries.joinToString(" · ") { provider ->
            "${provider.label} ${commands.count { !it.providerCommands[provider.id].isNullOrEmpty() }}"
        }
    }

/**
 * 编辑表单 → 待保存的命令。
 * @contract
 * @pre [providerTexts] 以 provider id 为键（pi / codex / grok）
 * @post 名称去首尾空白后为空返回 null（不保存）；编辑时沿用原 id，新建时取 [newId]；
 *   指令原样保存（不 trim，尾随空格是有意义的前缀）；空串的 Provider 被移除；
 *   原命令里编辑器不认识的 Provider 键原样保留，不因一次编辑丢数据
 * @err none
 */
internal fun buildShortcutCommand(
    original: ShortcutCommand?,
    name: String,
    providerTexts: Map<String, String>,
    newId: () -> String,
): ShortcutCommand? {
    val cleanName = name.trim()
    if (cleanName.isEmpty()) return null
    return ShortcutCommand(
        id = original?.id ?: newId(),
        name = cleanName,
        providerCommands = (original?.providerCommands.orEmpty() + providerTexts).filterValues { it.isNotEmpty() },
    )
}

/** 打开一次编辑器（新建时 [original] 为 null）；每次打开一个新实例，表单随之重置。 */
private class ShortcutEditorRequest(val original: ShortcutCommand?)

/**
 * 快捷命令二级页：设置主页只留入口，命令列表、Provider 筛选与增删改都在这里。
 * 顶栏圆形玻璃返回 + 「+ 新增」；列表是 FlatGlass 卡片行，点行进入底部玻璃编辑面板。
 */
@Composable
internal fun ShortcutCommandsScreen(
    commands: List<ShortcutCommand>,
    onSave: (ShortcutCommand) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val p = LocalAppPalette.current
    var filter by remember { mutableStateOf<ShortcutProvider?>(null) }
    var editor by remember { mutableStateOf<ShortcutEditorRequest?>(null) }
    val visible = filter?.let { f -> commands.filter { !it.providerCommands[f.id].isNullOrEmpty() } } ?: commands
    Column(
        modifier
            .fillMaxSize()
            .background(p.screenBackground)
            .statusBarsPadding()
            .testTag("shortcut-commands-screen"),
    ) {
        SettingsSubPageHeader(
            title = "快捷命令",
            meta = "${commands.size} COMMANDS",
            onBack = onBack,
            backTag = "shortcut-commands-back",
            trailing = {
                // 录制树内：emptyBackdrop，与二级页「+ Agent」同款主色玻璃钮
                GlassButton(
                    text = "+ 新增",
                    onClick = { editor = ShortcutEditorRequest(null) },
                    tint = p.accent,
                    textColor = p.onAccent,
                    height = 36.dp,
                    minWidth = 72.dp,
                    backdrop = emptyBackdrop(),
                    modifier = Modifier.testTag("shortcut-command-add"),
                )
            },
        )
        AppText(
            text = "每个 Provider 各存一条指令，会话快捷菜单按当前 Provider 取用，未配置时不回退。",
            color = p.bodyText,
            fontSize = 12.sp,
            lineHeightMultiplier = 1.5f,
            modifier = Modifier.padding(start = Dims.screenHPadding + 2.dp, end = Dims.screenHPadding, bottom = 12.dp),
        )
        if (commands.isNotEmpty()) {
            ProviderFilterBar(commands = commands, selected = filter, onSelect = { filter = it })
        }
        if (visible.isEmpty()) {
            ShortcutEmptyState(
                filter = filter,
                onAdd = { editor = ShortcutEditorRequest(null) },
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("shortcut-command-list"),
                contentPadding = PaddingValues(
                    start = 14.dp,
                    end = 14.dp,
                    top = 4.dp,
                    bottom = 12.dp + LocalFloatingNavInset.current,
                ),
                verticalArrangement = Arrangement.spacedBy(Dims.cardVGap),
            ) {
                items(visible, key = { it.id }) { command ->
                    ShortcutCommandRow(
                        command = command,
                        focus = filter,
                        onClick = { editor = ShortcutEditorRequest(command) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
    editor?.let { request ->
        key(request) {
            ShortcutCommandEditorSheet(
                original = request.original,
                onSave = onSave,
                onDelete = onDelete,
                onDismiss = { if (editor === request) editor = null },
            )
        }
    }
}

/** Provider 筛选胶囊：全部 / Pi / Codex / Grok，各带已配置条数；命令多时按 Provider 聚焦。 */
@Composable
private fun ProviderFilterBar(
    commands: List<ShortcutCommand>,
    selected: ShortcutProvider?,
    onSelect: (ShortcutProvider?) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterPill(
            label = "全部",
            count = commands.size,
            selected = selected == null,
            onClick = { onSelect(null) },
            tag = "shortcut-filter-all",
        )
        ShortcutProvider.entries.forEach { provider ->
            FilterPill(
                label = provider.label,
                count = commands.count { !it.providerCommands[provider.id].isNullOrEmpty() },
                selected = selected == provider,
                onClick = { onSelect(provider) },
                tag = "shortcut-filter-${provider.id}",
                providerId = provider.id,
            )
        }
    }
}

@Composable
private fun FilterPill(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    tag: String,
    providerId: String? = null,
) {
    val p = LocalAppPalette.current
    val g = settingsGlassTokens()
    val fill by animateColorAsState(
        targetValue = if (selected) p.accent.copy(alpha = 0.2f) else g.fill,
        animationSpec = tween(Motion.toggle),
        label = "shortcutFilterFill",
    )
    val stroke by animateColorAsState(
        targetValue = if (selected) p.accent.copy(alpha = 0.55f) else g.hairline,
        animationSpec = tween(Motion.toggle),
        label = "shortcutFilterStroke",
    )
    Row(
        Modifier
            .height(34.dp)
            .clip(CircleShape)
            .background(fill)
            .border(0.5.dp, stroke, CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .semantics { this.selected = selected }
            .testTag(tag)
            .padding(start = if (providerId != null) 6.dp else 13.dp, end = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (providerId != null) {
            ProviderMark(canonicalId = providerId, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(4.dp))
        }
        AppText(
            text = label,
            color = if (selected) p.accent else p.rowTitleText,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeightMultiplier = 1f,
        )
        Spacer(Modifier.width(6.dp))
        AppText(
            text = count.toString(),
            color = if (selected) p.accent else p.metaText,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            lineHeightMultiplier = 1f,
        )
    }
}

private val CommandRowShape = RoundedRectangle(18.dp)

/**
 * 一条快捷命令：名称 + 各 Provider 的指令预览（Provider 图标 + 等宽正文，单行省略）。
 * 筛选某个 Provider 时只预览那一条；一条都没配时明确提示「不可用」。
 */
@Composable
private fun ShortcutCommandRow(
    command: ShortcutCommand,
    focus: ShortcutProvider?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalAppPalette.current
    val g = settingsGlassTokens()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val overlay by animateColorAsState(
        targetValue = if (pressed) g.pressed else Color.Transparent,
        animationSpec = tween(Motion.pressFeedback),
        label = "shortcutRowPress",
    )
    val previews = ShortcutProvider.entries
        .filter { focus == null || it == focus }
        .mapNotNull { provider -> command.providerCommands[provider.id]?.takeIf { it.isNotEmpty() }?.let { provider to it } }
    Row(
        modifier
            .fillMaxWidth()
            .settingsGlass(CommandRowShape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .background(overlay)
            .testTag("shortcut-command-row-${command.id}")
            .padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            AppText(
                text = command.name,
                color = p.rowTitleText,
                fontSize = TypeSizes.rowTitle,
                fontWeight = FontWeight.SemiBold,
                lineHeightMultiplier = 1.25f,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            if (previews.isEmpty()) {
                AppText(
                    text = "未配置任何 Provider，会话中不可用",
                    color = p.unknownChipText,
                    fontSize = 12.sp,
                    lineHeightMultiplier = 1.3f,
                )
            }
            previews.forEachIndexed { index, (provider, text) ->
                if (index > 0) Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProviderMark(canonicalId = provider.id, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    AppText(
                        text = text,
                        color = p.bodyText,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeightMultiplier = 1.3f,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        ForwardChevron()
    }
}

@Composable
private fun ShortcutEmptyState(
    filter: ShortcutProvider?,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalAppPalette.current
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp)
            .padding(top = 48.dp)
            .testTag("shortcut-command-empty"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SettingsIconTile(SettingsGlyph.Shortcut, size = 56.dp)
        Spacer(Modifier.height(16.dp))
        AppText(
            text = if (filter == null) "还没有快捷命令" else "没有配置 ${filter.label} 指令的快捷命令",
            color = p.rowTitleText,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        if (filter == null) {
            Spacer(Modifier.height(6.dp))
            AppText(
                text = "把常用的 Skill、斜杠命令存成快捷命令，\n在会话输入栏一键插入。",
                color = p.bodyText,
                fontSize = 12.5f.sp,
                lineHeightMultiplier = 1.5f,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            CardTonalButton("新增快捷命令", onAdd, Modifier.widthIn(min = 180.dp))
        }
    }
}

private val EditorPanelShape = RoundedRectangle(Radii.glassPanel)

/**
 * 底部液态玻璃编辑面板：名称 + 每个 Provider 一行指令。面板折射其下的二级页（经 [GlassModalLayer]
 * 抬到录制边界之外），面板内按钮再采样面板自身。随输入法上移（ime ∪ 导航栏）。
 * 名称为空时「保存」禁用；删除需二次确认。
 */
@Composable
private fun ShortcutCommandEditorSheet(
    original: ShortcutCommand?,
    onSave: (ShortcutCommand) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(original?.name.orEmpty()) }
    val texts = remember {
        mutableStateMapOf<String, String>().apply {
            ShortcutProvider.entries.forEach { put(it.id, original?.providerCommands?.get(it.id).orEmpty()) }
        }
    }
    var confirmDelete by remember { mutableStateOf(false) }
    GlassModalLayer(onDismiss = onDismiss) {
        val p = LocalAppPalette.current
        val danger = MaterialTheme.colorScheme.error
        val panelBackdrop = rememberLayerBackdrop()
        val requestDismiss = ::dismiss
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .animateEnterExit(
                    enter = slideInVertically(tween(Motion.sheetSlideIn, easing = Motion.sheetEnter)) { it },
                    exit = slideOutVertically(tween(Motion.sheetSlideOut)) { it },
                )
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .padding(12.dp)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .pointerInput(Unit) {} // 面板内空白处的点击不落到遮罩
                .glassPanel(
                    backdrop = LocalGlassBackdrop.current,
                    shape = EditorPanelShape,
                    surface = p.glassSurface.glassReadable(),
                    exportedBackdrop = panelBackdrop,
                )
                .padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 18.dp)
                .testTag("shortcut-command-editor"),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(Dims.sheetGrabberWidth)
                    .height(Dims.sheetGrabberHeight)
                    .clip(CircleShape)
                    .background(p.sheetGrabber),
            )
            Spacer(Modifier.height(14.dp))
            AppText(
                text = if (original == null) "新增快捷命令" else "编辑快捷命令",
                color = p.titleText,
                fontSize = TypeSizes.sheetTitle,
                fontWeight = FontWeight.Bold,
                lineHeightMultiplier = 1.2f,
            )
            Spacer(Modifier.height(14.dp))
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                EditorLabel("名称")
                Spacer(Modifier.height(8.dp))
                EditorField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = "例如：交接",
                    tag = "shortcut-command-name",
                    monospace = false,
                )
                Spacer(Modifier.height(16.dp))
                EditorLabel("按 Provider 填写")
                ShortcutProvider.entries.forEach { provider ->
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProviderMark(canonicalId = provider.id, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(6.dp))
                        AppText(
                            text = provider.label,
                            color = p.rowTitleText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeightMultiplier = 1f,
                            modifier = Modifier.width(48.dp),
                        )
                        EditorField(
                            value = texts[provider.id].orEmpty(),
                            onValueChange = { texts[provider.id] = it },
                            placeholder = "未配置",
                            tag = "shortcut-command-${provider.id}",
                            monospace = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                AppText(
                    text = "留空的 Provider 在会话中不可用，也不会回退到其他 Provider 的指令。",
                    color = p.bodyText,
                    fontSize = TypeSizes.footnote,
                    lineHeightMultiplier = 1.45f,
                )
            }
            Spacer(Modifier.height(18.dp))
            CompositionLocalProvider(LocalGlassBackdrop provides panelBackdrop) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (original != null) {
                        GlassButton(
                            text = if (confirmDelete) "确认删除" else "删除",
                            onClick = {
                                if (confirmDelete) {
                                    onDelete(original.id)
                                    requestDismiss()
                                } else {
                                    confirmDelete = true
                                }
                            },
                            tint = if (confirmDelete) danger else Color.Unspecified,
                            textColor = if (confirmDelete) Color.White else danger,
                            modifier = Modifier.testTag("shortcut-command-delete"),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    GlassButton(
                        text = "取消",
                        onClick = { requestDismiss() },
                        modifier = Modifier.testTag("shortcut-command-cancel"),
                    )
                    GlassButton(
                        text = "保存",
                        onClick = {
                            buildShortcutCommand(
                                original = original,
                                name = name,
                                providerTexts = texts.toMap(),
                                newId = { "shortcut-${System.currentTimeMillis()}" },
                            )?.let { command ->
                                onSave(command)
                                requestDismiss()
                            }
                        },
                        enabled = name.isNotBlank(),
                        tint = p.accent,
                        textColor = p.onAccent,
                        minWidth = 84.dp,
                        modifier = Modifier.testTag("shortcut-command-save"),
                    )
                }
            }
        }
    }
}

@Composable
private fun EditorLabel(text: String) {
    AppText(
        text = text,
        color = LocalAppPalette.current.metaText,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.4.sp,
        lineHeightMultiplier = 1.2f,
    )
}

/** 面板内输入框：半透底 + 发丝边，聚焦时边线过渡到主色；空值显示占位。 */
@Composable
private fun EditorField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    tag: String,
    monospace: Boolean,
    modifier: Modifier = Modifier,
) {
    val p = LocalAppPalette.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val border by animateColorAsState(
        targetValue = if (focused) p.accent.copy(alpha = 0.85f) else p.inputBorder,
        animationSpec = tween(Motion.toggle),
        label = "shortcutFieldBorder",
    )
    val shape = RoundedCornerShape(Radii.input)
    val family = if (monospace) FontFamily.Monospace else FontFamily.Default
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = p.inputText, fontSize = TypeSizes.inputText, fontFamily = family),
        cursorBrush = SolidColor(p.accent),
        interactionSource = interaction,
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.inputBackground.copy(alpha = 0.72f))
            .border(1.dp, border, shape)
            .padding(horizontal = 12.dp, vertical = 11.dp)
            .testTag(tag),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    AppText(
                        text = placeholder,
                        color = p.inputPlaceholder,
                        fontSize = TypeSizes.inputText,
                        fontFamily = family,
                        lineHeightMultiplier = 1.2f,
                    )
                }
                inner()
            }
        },
    )
}
