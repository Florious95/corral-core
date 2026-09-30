package dev.agentmirror.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.shapes.RoundedRectangle
import dev.agentmirror.app.tsnet.ConnectionPath
import dev.agentmirror.app.ui.components.AppText
import dev.agentmirror.app.ui.components.GlassCircleBackButton
import dev.agentmirror.app.ui.components.CanonicalProviderMarks
import dev.agentmirror.app.ui.components.ExtractedProviderIcon
import dev.agentmirror.app.ui.components.GlassButton
import dev.agentmirror.app.ui.components.GlassModalLayer
import dev.agentmirror.app.ui.components.HeaderRule
import dev.agentmirror.app.ui.components.LanPill
import dev.agentmirror.app.ui.components.LiquidToggle
import dev.agentmirror.app.ui.components.LocalFloatingNavInset
import dev.agentmirror.app.ui.components.LocalGlassBackdrop
import dev.agentmirror.app.ui.components.PathText
import dev.agentmirror.app.ui.components.SessionRow
import dev.agentmirror.app.ui.components.TokenText
import dev.agentmirror.app.ui.components.glassControl
import dev.agentmirror.app.ui.components.modalPanel
import dev.agentmirror.app.ui.components.rememberPressProgress
import dev.agentmirror.app.ui.components.ruledSurface
import dev.agentmirror.app.ui.model.SessionItem
import dev.agentmirror.app.ui.theme.Dims
import dev.agentmirror.app.ui.theme.HeaderStyle
import dev.agentmirror.app.ui.theme.LocalThemeSuite
import dev.agentmirror.app.ui.theme.ModernistDarkTokens
import dev.agentmirror.app.ui.theme.ModernistLightTokens
import dev.agentmirror.app.ui.theme.Motion
import dev.agentmirror.app.ui.theme.Radii
import dev.agentmirror.app.workspace.AgentLauncherUi
import dev.agentmirror.app.workspace.CreateAgentUiState
import dev.agentmirror.app.ui.theme.LocalAppPalette
import dev.agentmirror.app.ui.theme.TypeSizes

/**
 * 会话列表（二级，某个工作区内）。
 * 顶部：圆形液态玻璃返回 + 右侧独占「+ Agent」；下面是工作区大标题 + 并排网络标识（LAN/Tailnet） + 完整路径。
 * 行结构：CLI 工作灯 → 会话显示名 + cwd 路径（66dp）→ 右侧官方 Provider 图标。
 * 整行是唯一手势 owner：短按打开（在线），长按弹出收藏/取消收藏。
 *
 * @contract
 * @pre sessions carry fail-closed DTO provider/activity/health
 * @post every row is the shared 66dp title+path SessionRow
 * @err none
 * @inv creation is limited to server-advertised launchers and the exact selected anchor
 * @consumes dev.agentmirror.app.tsnet
 * @consumes dev.agentmirror.app.ui.components
 * @consumes dev.agentmirror.app.ui.model
 * @consumes dev.agentmirror.app.ui.theme
 */
@Composable
fun SessionListScreen(
    workspaceName: String,
    workspacePath: String,
    sessions: List<SessionItem>,
    onBack: () -> Unit,
    onSessionClick: (SessionItem) -> Unit,
    onToggleStar: (SessionItem) -> Unit,
    onCloseSession: (SessionItem) -> Unit = {},
    closingSessionRef: String? = null,
    modifier: Modifier = Modifier,
    connectionPath: ConnectionPath? = null,
    connectionBanner: String? = null,
    bottomBar: @Composable () -> Unit = {},
    agentLaunchers: List<AgentLauncherUi> = emptyList(),
    createAgentState: CreateAgentUiState = CreateAgentUiState(),
    onCreateAgent: (anchorRef: String, provider: String, name: String, bypass: Boolean) -> Unit = { _, _, _, _ -> },
    onCreateAgentErrorCleared: () -> Unit = {},
    headerAction: (@Composable () -> Unit)? = null,
) {
    val p = LocalAppPalette.current
    val kit = LocalThemeSuite.current
    Column(modifier.fillMaxSize().background(p.screenBackground).statusBarsPadding()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(Dims.topBarHeight)
                .padding(start = 2.dp, end = Dims.screenHPadding)
                .testTag("session-list-topbar"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassCircleBackButton(onBack = onBack)
            Box(Modifier.weight(1f))
            if (headerAction != null) {
                headerAction()
                Spacer(Modifier.width(6.dp))
            }
            var showCreateDialog by remember { mutableStateOf(false) }
            // 录制树内：emptyBackdrop，与弹窗「创建」同款 tint，⛔ 不采样 LocalGlassBackdrop
            GlassButton(
                text = "+ Agent",
                onClick = {
                    showCreateDialog = true
                    onCreateAgentErrorCleared()
                },
                enabled = agentLaunchers.isNotEmpty() && !createAgentState.inFlight,
                tint = p.accent,
                textColor = p.onAccent,
                height = 36.dp,
                minWidth = 72.dp,
                backdrop = emptyBackdrop(),
                modifier = Modifier.testTag("create-agent-button"),
            )
            if (showCreateDialog && agentLaunchers.isNotEmpty()) {
                CreateAgentDialog(
                    workspaceName = workspaceName,
                    sessions = sessions,
                    launchers = agentLaunchers,
                    state = createAgentState,
                    onDismiss = { showCreateDialog = false },
                    onCreate = onCreateAgent,
                )
            }
        }
        Column(Modifier.padding(start = Dims.screenHPadding, end = Dims.screenHPadding, top = 2.dp, bottom = 13.dp)) {
            if (kit.recipes.header == HeaderStyle.Display) {
                TokenText("工作区 · ${sessions.size} 个会话", kit.colors.eyebrow, kit.typography.eyebrow, maxLines = 1)
                Box(Modifier.height(6.dp))
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TokenText(
                    text = workspaceName,
                    color = p.titleText,
                    token = kit.typography.sectionTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (connectionPath != null) {
                    Spacer(Modifier.width(8.dp))
                    LanPill(connectionPath)
                }
            }
            Box(Modifier.height(5.dp))
            PathText(workspacePath)
        }
        HeaderRule()
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            SessionListRows(
                sessions = sessions,
                onSessionClick = onSessionClick,
                onToggleStar = onToggleStar,
                onCloseSession = onCloseSession,
                closingSessionRef = closingSessionRef,
                modifier = Modifier.fillMaxSize(),
            )
            if (connectionBanner != null) {
                AppText(
                    text = connectionBanner,
                    color = p.metaText,
                    fontSize = TypeSizes.statusChip,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(p.consoleBackground)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("connection-banner"),
                )
            }
        }
        bottomBar()
    }
}

@Composable
private fun CreateAgentDialog(
    workspaceName: String = "",
    sessions: List<SessionItem>,
    launchers: List<AgentLauncherUi>,
    state: CreateAgentUiState,
    onDismiss: () -> Unit,
    onCreate: (String, String, String, Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var selectedProvider by remember { mutableStateOf(launchers.first().provider) }
    var bypass by remember { mutableStateOf(false) }
    val launcher = launchers.firstOrNull { it.provider == selectedProvider }
    val internalAnchor = remember(sessions) { sessions.firstOrNull()?.id.orEmpty() }
    LaunchedEffect(launcher?.supportsBypass) {
        if (launcher?.supportsBypass != true) bypass = false
    }
    // 晶莹液态玻璃弹窗：居中面板折射其下的会话列表；表单控件再采样面板自身。
    // 现代主义直角弹窗：冷白底色与深炭黑描边。
    // 提交进行中锁住遮罩与返回键；创建成功（无错且已填名）播完退场再关闭。
    GlassModalLayer(onDismiss = onDismiss, dismissible = !state.inFlight) {
        val requestDismiss = ::dismiss
        LaunchedEffect(state.inFlight, state.error) {
            if (!state.inFlight && state.error == null && name.isNotEmpty()) requestDismiss()
        }
        val p = LocalAppPalette.current
        val kit = LocalThemeSuite.current
        val panelBackdrop = rememberLayerBackdrop()
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .animateEnterExit(
                    enter = fadeIn(tween(Motion.scrimFade)) +
                        scaleIn(tween(Motion.sheetSlideIn, easing = Motion.sheetEnter), initialScale = 0.92f),
                    exit = fadeOut(tween(Motion.sheetSlideOut)) +
                        scaleOut(tween(Motion.sheetSlideOut), targetScale = 0.96f),
                )
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .pointerInput(Unit) {} // 面板内空白处的点击不落到遮罩
                .modalPanel(DialogPanelShape, panelBackdrop)
                .padding(22.dp)
                .testTag("create-agent-dialog"),
        ) {
            if (!kit.surfaces.isGlass && workspaceName.isNotBlank()) {
                AppText(
                    text = workspaceName,
                    color = kit.colors.eyebrow,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeightMultiplier = 1f,
                    letterSpacing = 1.3.sp,
                )
                Spacer(Modifier.height(4.dp))
            }
            AppText(
                text = "新建 Agent",
                color = p.titleText,
                fontSize = if (kit.surfaces.isGlass) 20.sp else 24.sp,
                fontWeight = if (kit.surfaces.isGlass) FontWeight.Bold else FontWeight.Black,
                lineHeightMultiplier = 1.2f,
                letterSpacing = (-0.3).sp,
            )
            Spacer(Modifier.height(16.dp))
            CompositionLocalProvider(LocalGlassBackdrop provides panelBackdrop) {
                CreateAgentFormContent(
                    name = name,
                    onNameChange = { name = it },
                    launchers = launchers,
                    selectedProvider = selectedProvider,
                    onSelectProvider = { selectedProvider = it },
                    bypass = bypass,
                    onBypassChange = { bypass = it },
                    supportsBypass = launcher?.supportsBypass == true,
                    inFlight = state.inFlight,
                    error = state.error,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                ) {
                    GlassButton(
                        text = "取消",
                        onClick = { requestDismiss() },
                        enabled = !state.inFlight,
                    )
                    GlassButton(
                        text = if (state.inFlight) "创建中…" else "创建",
                        onClick = { onCreate(internalAnchor, launcher!!.provider, name.trim(), bypass) },
                        enabled = name.isNotBlank() && launcher != null && !state.inFlight,
                        tint = p.accent,
                        textColor = p.onAccent,
                        minWidth = 84.dp,
                        modifier = Modifier.testTag("create-agent-confirm"),
                    )
                }
            }
        }
    }
}

private val DialogPanelShape = RoundedRectangle(Radii.glassPanel)
private val AgentCardShape = RoundedRectangle(Radii.glassControl)

@Composable
internal fun CreateAgentFormContent(
    name: String,
    onNameChange: (String) -> Unit,
    launchers: List<AgentLauncherUi>,
    selectedProvider: String,
    onSelectProvider: (String) -> Unit,
    bypass: Boolean,
    onBypassChange: (Boolean) -> Unit,
    supportsBypass: Boolean,
    inFlight: Boolean,
    error: String?,
    modifier: Modifier = Modifier,
) {
    val p = LocalAppPalette.current
    val kit = LocalThemeSuite.current
    val t = if (kit.isDark) ModernistDarkTokens else ModernistLightTokens
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { androidx.compose.material3.Text("名称") },
            placeholder = { androidx.compose.material3.Text("例如：代码助手") },
            singleLine = true,
            shape = kit.geometry.shape(RoundedCornerShape(Radii.glassControl)),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = if (kit.surfaces.isGlass) p.glassCardFill else p.inputBackground,
                unfocusedContainerColor = if (kit.surfaces.isGlass) p.glassCardFill else p.inputBackground,
                focusedBorderColor = if (kit.surfaces.isGlass) MaterialTheme.colorScheme.primary else p.accent,
                unfocusedBorderColor = if (kit.surfaces.isGlass) MaterialTheme.colorScheme.onSurfaceVariant else p.inputBorder,
                cursorColor = if (kit.surfaces.isGlass) MaterialTheme.colorScheme.primary else p.accent,
                focusedTextColor = if (kit.surfaces.isGlass) MaterialTheme.colorScheme.onSurface else p.inputText,
                unfocusedTextColor = if (kit.surfaces.isGlass) MaterialTheme.colorScheme.onSurface else p.inputText,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("create-agent-name"),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.Text(
                text = "Agent 类型",
                color = if (kit.surfaces.isGlass) MaterialTheme.colorScheme.onSurfaceVariant else t.ink,
                style = MaterialTheme.typography.labelMedium,
            )
            if (kit.surfaces.isGlass) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .testTag("create-agent-launcher-row"),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    launchers.forEach { option ->
                        AgentIconCard(
                            launcher = option,
                            isSelected = option.provider == selectedProvider,
                            onClick = { onSelectProvider(option.provider) },
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(84.dp)
                        .border(2.dp, t.ink, RectangleShape)
                        .testTag("create-agent-launcher-row"),
                ) {
                    launchers.forEachIndexed { index, option ->
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            AgentIconCard(
                                launcher = option,
                                isSelected = option.provider == selectedProvider,
                                onClick = { onSelectProvider(option.provider) },
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (index > 0) {
                                Box(
                                    Modifier
                                        .align(Alignment.CenterStart)
                                        .fillMaxHeight()
                                        .width(1.dp)
                                        .background(t.divider),
                                )
                            }
                        }
                    }
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Column(Modifier.weight(1f)) {
                androidx.compose.material3.Text(
                    "绕过权限确认",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(2.dp))
                androidx.compose.material3.Text(
                    "启用后，Agent 可跳过操作确认",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            LiquidToggle(
                checked = bypass,
                onCheckedChange = onBypassChange,
                enabled = supportsBypass && !inFlight,
                modifier = Modifier.testTag("create-agent-bypass"),
            )
        }
        error?.let {
            androidx.compose.material3.Text(
                "创建失败：$it",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("create-agent-error"),
            )
        }
    }
}

/**
 * Agent 品牌图标卡（Pi / Codex / Cursor / Grok 官方图标）：玻璃主题保留原卡片，现代主义使用金属面板。
 */
@Composable
internal fun AgentIconCard(
    launcher: AgentLauncherUi,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalAppPalette.current
    val kit = LocalThemeSuite.current
    val t = if (kit.isDark) ModernistDarkTokens else ModernistLightTokens
    val interaction = remember { MutableInteractionSource() }
    val press = rememberPressProgress(interaction)
    val contentTint = if (kit.surfaces.isGlass) {
        if (isSelected) p.accent else p.rowTitleText
    } else if (isSelected) {
        t.canvas
    } else {
        t.ink
    }

    Box(
        modifier = modifier
            .then(
                if (kit.surfaces.isGlass) {
                    Modifier.width(78.dp).height(82.dp)
                } else {
                    Modifier.height(84.dp).defaultMinSize(minWidth = 78.dp)
                },
            )
            .semantics { selected = isSelected }
            .testTag("agent-card-${launcher.provider}")
            .then(
                if (kit.surfaces.isGlass) {
                    Modifier.glassControl(
                        backdrop = LocalGlassBackdrop.current,
                        shape = AgentCardShape,
                        surface = if (isSelected) Color.Unspecified else p.glassSurface.copy(alpha = 0.35f),
                        tint = if (isSelected) p.accent else Color.Unspecified,
                        tintAlpha = 0.12f,
                        glow = if (isSelected) p.accent else Color.Unspecified,
                        pressProgress = press,
                        blurRadius = 2.dp,
                        lensHeight = 5.dp,
                        lensAmount = 12.dp,
                    )
                } else {
                    Modifier.background(
                        if (isSelected) {
                            SolidColor(t.ink)
                        } else {
                            Brush.verticalGradient(
                                colors = listOf(t.surface, t.diffuseEnd),
                            )
                        },
                        shape = RectangleShape,
                    )
                },
            )
            .then(
                if (isSelected && kit.surfaces.isGlass) {
                    Modifier.border(1.dp, p.accent, kit.geometry.shape(RoundedCornerShape(Radii.glassControl)))
                } else {
                    Modifier
                },
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.RadioButton,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (kit.surfaces.isGlass) {
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 5.dp, end = 5.dp)
                        .size(16.dp)
                        .clip(kit.geometry.shape(CircleShape))
                        .background(p.accent)
                        .testTag("agent-card-check-${launcher.provider}"),
                    contentAlignment = Alignment.Center,
                ) {
                    AppText(
                        text = "✓",
                        color = p.onAccent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeightMultiplier = 1f,
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            ) {
                AgentBrandIcon(
                    canonicalId = launcher.provider,
                    tint = contentTint,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.height(6.dp))
                AppText(
                    text = CanonicalProviderMarks.of(launcher.provider)?.displayName ?: launcher.displayName,
                    color = if (isSelected) p.accent else p.rowTitleText,
                    fontSize = 11.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    lineHeightMultiplier = 1f,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.Start,
            ) {
                AgentBrandIcon(
                    canonicalId = launcher.provider,
                    tint = contentTint,
                    modifier = Modifier.size(26.dp),
                )
                AppText(
                    text = CanonicalProviderMarks.of(launcher.provider)?.displayName ?: launcher.displayName,
                    color = contentTint,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                    lineHeightMultiplier = 1f,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isSelected) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(10.dp)
                        .background(t.accent, RectangleShape)
                        .testTag("agent-card-selection-marker-${launcher.provider}"),
                )
            }
        }
    }
}

@Composable
internal fun AgentBrandIcon(
    canonicalId: String,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val res = CanonicalProviderMarks.drawableRes(canonicalId)
    val spec = CanonicalProviderMarks.of(canonicalId)
    val desc = spec?.displayName ?: canonicalId
    when {
        ExtractedProviderIcon.draws(canonicalId) -> {
            Canvas(modifier = modifier) {
                ExtractedProviderIcon.draw(this, canonicalId, tint)
            }
        }
        res != null -> {
            Image(
                painter = painterResource(res),
                contentDescription = desc,
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(tint),
                modifier = modifier,
            )
        }
        else -> {
            Box(modifier = modifier, contentAlignment = Alignment.Center) {
                androidx.compose.material3.Text(
                    text = canonicalId.take(2).uppercase(),
                    color = tint,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

/**
 * 会话行列表（无页头）。二级全屏走 [SessionListScreen]；悬浮窗 / 单测走本函数。
 * 行是微玻璃卡，卡间留 [Dims.cardVGap]；列表末尾多留 [LocalFloatingNavInset]，末卡能滚出悬浮导航。
 */
@Composable
fun SessionListRows(
    sessions: List<SessionItem>,
    onSessionClick: (SessionItem) -> Unit,
    onToggleStar: (SessionItem) -> Unit,
    onCloseSession: (SessionItem) -> Unit = {},
    closingSessionRef: String? = null,
    modifier: Modifier = Modifier,
    tagPrefix: String = "l2",
    listTestTag: String = "l2-session-list-scroll",
) {
    val orderedSessions = sessions.sortedByDescending { it.starred }
    val rowGap = LocalThemeSuite.current.geometry.listRowGap
    LazyColumn(
        modifier = modifier.testTag(listTestTag),
        contentPadding = PaddingValues(top = rowGap, bottom = rowGap + LocalFloatingNavInset.current),
        verticalArrangement = Arrangement.spacedBy(rowGap),
    ) {
        items(orderedSessions, key = { it.id }) { item ->
            val isClosing = closingSessionRef == item.id
            AnimatedVisibility(
                modifier = Modifier.animateItem(),
                visible = !isClosing,
                exit = fadeOut(animationSpec = tween(250)) + shrinkVertically(animationSpec = tween(250)),
            ) {
                SessionRow(
                    item = item,
                    tagPrefix = tagPrefix,
                    onClick = { onSessionClick(item) },
                    onToggleFavorite = { onToggleStar(item) },
                    unfavoriteOnly = false,
                    onCloseSession = { onCloseSession(item) },
                )
            }
        }
    }
}
