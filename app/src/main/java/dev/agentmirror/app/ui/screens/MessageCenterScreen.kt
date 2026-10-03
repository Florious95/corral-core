/*
 * Copyright 2026 AgentMirror Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.agentmirror.app.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.shapes.RoundedRectangle
import dev.agentmirror.app.notify.NotificationItem
import dev.agentmirror.app.notify.NotificationKey
import dev.agentmirror.app.notify.NotificationLevel
import dev.agentmirror.app.notify.NotificationRecord
import dev.agentmirror.app.notify.NotificationSupport
import dev.agentmirror.app.notify.canOpenSession
import dev.agentmirror.app.service.NotificationHelper
import dev.agentmirror.app.ui.components.AppText
import dev.agentmirror.app.ui.components.CanonicalProviderMarks
import dev.agentmirror.app.ui.components.CardTonalButton
import dev.agentmirror.app.ui.components.GlassButton
import dev.agentmirror.app.ui.components.GlassCircleBackButton
import dev.agentmirror.app.ui.components.GlassIconButton
import dev.agentmirror.app.ui.components.HeaderRule
import dev.agentmirror.app.ui.components.RuleEdge
import dev.agentmirror.app.ui.components.TokenText
import dev.agentmirror.app.ui.components.edgeRule
import dev.agentmirror.app.ui.theme.Dims
import dev.agentmirror.app.ui.theme.HeaderStyle
import dev.agentmirror.app.ui.theme.ListRowStyle
import dev.agentmirror.app.ui.theme.LocalAppPalette
import dev.agentmirror.app.ui.theme.LocalThemeSuite
import dev.agentmirror.app.ui.theme.ModernistDarkTokens
import dev.agentmirror.app.ui.theme.ModernistLightTokens
import dev.agentmirror.app.ui.theme.Motion
import dev.agentmirror.app.ui.theme.TypeSizes
import dev.agentmirror.app.ui.theme.isDark
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 消息中心（Issue #42）：Agent 经 corral-notify 主动推送的任务消息。
 *
 * 卡片头：Agent 品牌图标砖（已知 Provider 用官方标，未知用终端符号）+ Agent 名 + 级别 + 时间，
 * 下方工作区目录条；正文完整显示（保留换行、可选择复制，零截断）。有会话链接时整张卡可点：
 * 标已读并直达该 Agent 的终端（「进入终端」按钮同效）；无链接时点卡只标已读。
 * 液态玻璃：浮动 FlatGlass 卡（半透 + 顶亮发丝边 + 环境光）；直角风格：通栏直角条 + 发丝分隔 +
 * 未读左侧主色竖线。整页不采样 backdrop。
 *
 * @contract
 * @pre items 已按时间倒序
 * @post 只经回调改已读 / 导航；收到消息本身不导航
 * @inv 正文不截断、不当 HTML / ANSI 解析
 */
@Composable
fun MessageCenterScreen(
    items: List<NotificationItem>,
    sourceHostId: String?,
    support: NotificationSupport,
    onBack: () -> Unit,
    onOpenSession: (NotificationItem) -> Unit,
    onMarkRead: (NotificationKey) -> Unit,
    onMarkAllRead: () -> Unit,
    modifier: Modifier = Modifier,
    providerOf: (NotificationRecord) -> String? = { null },
    workspaceFilter: String? = null,
) {
    BackHandler(onBack = onBack)
    val p = LocalAppPalette.current
    val kit = LocalThemeSuite.current
    val unread = items.count { !it.read }
    // 相对时间每分钟刷新一次（只在页面在屏时）。
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = System.currentTimeMillis()
        }
    }
    Box(
        modifier
            .fillMaxSize()
            .background(p.screenBackground)
            .then(if (kit.surfaces.ambientLight) Modifier.ambientSheen() else Modifier)
            // 盖在三栏主页之上：空白处的触摸不得漏到下层列表。
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .testTag("message-center-screen"),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            MessageCenterHeader(unread = unread, total = items.size, onBack = onBack, onMarkAllRead = onMarkAllRead)
            HeaderRule()
            SystemAlertBanner()
            if (support == NotificationSupport.Unsupported) {
                AppText(
                    text = "当前服务端未启用通知能力，仅显示本机已缓存的消息。",
                    color = p.metaText,
                    fontSize = 12.sp,
                    lineHeightMultiplier = 1.45f,
                    modifier = Modifier
                        .padding(horizontal = Dims.screenHPadding, vertical = 10.dp)
                        .testTag("message-center-unsupported"),
                )
            }
            if (items.isEmpty()) {
                MessageEmptyState(workspaceFilter = workspaceFilter, modifier = Modifier.weight(1f))
            } else {
                val floating = kit.recipes.listRow == ListRowStyle.FloatingCard
                val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag("message-center-list"),
                    contentPadding = PaddingValues(
                        start = if (floating) 14.dp else 0.dp,
                        end = if (floating) 14.dp else 0.dp,
                        top = if (floating) 12.dp else 0.dp,
                        bottom = 20.dp + navBottom,
                    ),
                    verticalArrangement = Arrangement.spacedBy(if (floating) 12.dp else 0.dp),
                ) {
                    items(items, key = { "${it.record.hostId}/${it.record.id}" }) { item ->
                        val linkable = item.record.canOpenSession(sourceHostId)
                        MessageCard(
                            item = item,
                            nowMillis = now,
                            linkable = linkable,
                            provider = providerOf(item.record) ?: inferProvider(item.record.agentName),
                            onClick = {
                                if (linkable) onOpenSession(item) else if (!item.read) onMarkRead(item.key)
                            },
                            onOpenSession = { onOpenSession(item) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageCenterHeader(unread: Int, total: Int, onBack: () -> Unit, onMarkAllRead: () -> Unit) {
    val p = LocalAppPalette.current
    val kit = LocalThemeSuite.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(Dims.topBarHeight)
            .padding(start = 2.dp, end = Dims.screenHPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassCircleBackButton(onBack = onBack, modifier = Modifier.testTag("message-center-back"))
        Spacer(Modifier.weight(1f))
        GlassButton(
            text = "全部已读",
            onClick = onMarkAllRead,
            enabled = unread > 0,
            height = 36.dp,
            minWidth = 72.dp,
            backdrop = emptyBackdrop(),
            modifier = Modifier.testTag("message-center-mark-all"),
        )
    }
    Column(Modifier.padding(start = Dims.screenHPadding, end = Dims.screenHPadding, top = 2.dp, bottom = 13.dp)) {
        if (kit.recipes.header == HeaderStyle.Display) {
            TokenText("Agent 通知 · $unread 条未读", kit.colors.eyebrow, kit.typography.eyebrow, maxLines = 1)
            Spacer(Modifier.height(6.dp))
        }
        TokenText(
            text = "消息中心",
            color = p.titleText,
            token = kit.typography.sectionTitle,
            maxLines = 1,
            modifier = Modifier.testTag("message-center-title"),
        )
        if (kit.recipes.header != HeaderStyle.Display) {
            Spacer(Modifier.height(5.dp))
            AppText(
                text = "$unread UNREAD · $total MESSAGES",
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

/**
 * 一条消息卡：Agent 图标砖 + 名称 + 级别 + 时间 / 工作区目录 / 标题 / 完整正文 / 进入终端。
 * [onClick] 由调用方决定：可直达时整卡进终端，否则只标已读。
 */
@Composable
private fun MessageCard(
    item: NotificationItem,
    nowMillis: Long,
    linkable: Boolean,
    provider: String?,
    onClick: () -> Unit,
    onOpenSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalAppPalette.current
    val kit = LocalThemeSuite.current
    val record = item.record
    val g = settingsGlassTokens()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val overlay by animateColorAsState(
        targetValue = if (pressed) g.pressed else Color.Transparent,
        animationSpec = tween(Motion.pressFeedback),
        label = "messageCardPress",
    )
    val floating = kit.recipes.listRow == ListRowStyle.FloatingCard
    val shell = if (floating) {
        Modifier.settingsGlass(MessageCardShape)
    } else {
        Modifier
            .background(p.glassCardFill)
            .edgeRule(RuleEdge.Bottom, kit.colors.separator, kit.geometry.hairline)
            .then(if (!item.read) Modifier.edgeRule(RuleEdge.Start, p.accent, 4.dp) else Modifier)
    }
    Column(
        modifier
            .fillMaxWidth()
            .then(shell)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = if (linkable) "进入终端" else "标为已读",
                onClick = onClick,
            )
            .background(overlay)
            .padding(
                horizontal = if (floating) 16.dp else kit.geometry.pageInset,
                vertical = if (floating) 15.dp else 18.dp,
            )
            .testTag("message-card-${record.id}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AgentTile(provider = provider, modifier = Modifier.testTag("message-agent-icon-${record.id}"))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                AppText(
                    text = record.agentName?.takeIf { it.isNotBlank() }
                        ?: provider?.let { CanonicalProviderMarks.of(it)?.displayName }
                        ?: "Agent",
                    color = p.rowTitleText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeightMultiplier = 1.2f,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("message-agent-name-${record.id}"),
                )
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LevelPill(record.levelKind)
                    Spacer(Modifier.width(8.dp))
                    AppText(
                        text = formatNotificationTime(record.timestamp, nowMillis),
                        color = p.metaText,
                        fontSize = 11.5f.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeightMultiplier = 1f,
                        maxLines = 1,
                    )
                }
            }
            if (!item.read) {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .align(Alignment.Top)
                        .padding(top = 4.dp)
                        .size(8.dp)
                        .clip(kit.geometry.shape(CircleShape))
                        .background(p.accent)
                        .testTag("message-unread-${record.id}"),
                )
            }
        }
        record.workspace?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(12.dp))
            WorkspaceChip(path = it, modifier = Modifier.testTag("message-workspace-${record.id}"))
        }
        Spacer(Modifier.height(12.dp))
        AppText(
            text = record.title,
            color = p.titleText,
            fontSize = 16.sp,
            fontWeight = if (kit.surfaces.isGlass) FontWeight.Bold else FontWeight.Black,
            lineHeightMultiplier = 1.3f,
            letterSpacing = if (kit.surfaces.isGlass) 0.sp else (-0.2).sp,
        )
        Spacer(Modifier.height(6.dp))
        // 正文全文：保留换行 / 空格，可长按选择复制，绝不截断。
        SelectionContainer {
            AppText(
                text = record.body,
                color = p.rowTitleText.copy(alpha = 0.86f),
                fontSize = 14.sp,
                lineHeightMultiplier = 1.55f,
                modifier = Modifier.testTag("message-body-${record.id}"),
            )
        }
        if (record.sessionRef != null) {
            Spacer(Modifier.height(14.dp))
            if (linkable) {
                GlassButton(
                    text = "进入终端",
                    onClick = onOpenSession,
                    tint = p.accent,
                    textColor = p.onAccent,
                    height = 40.dp,
                    minWidth = 120.dp,
                    backdrop = emptyBackdrop(),
                    trailingIcon = { ArrowGlyph(p.onAccent) },
                    modifier = Modifier.testTag("message-open-session-${record.id}"),
                )
            } else {
                AppText(
                    text = "来自其他主机，无法直达终端",
                    color = p.metaText,
                    fontSize = 12.sp,
                    lineHeightMultiplier = 1.3f,
                )
            }
        }
    }
}

private val MessageCardShape = RoundedRectangle(20.dp)

/**
 * Agent 图标砖（36dp）：已知 Provider 画官方品牌标，未知画终端符号（主色）。
 * 玻璃为半透磨砂圆角砖 + 发丝边；直角为冷银渐变方砖 + 墨色发丝框。
 */
@Composable
private fun AgentTile(provider: String?, modifier: Modifier = Modifier) {
    val p = LocalAppPalette.current
    val kit = LocalThemeSuite.current
    val glass = kit.surfaces.isGlass
    val t = if (kit.isDark) ModernistDarkTokens else ModernistLightTokens
    val known = provider?.let { CanonicalProviderMarks.of(it) }
    val shell = if (glass) {
        val shape = RoundedRectangle(11.dp)
        val fill = when {
            known == null -> listOf(p.accent.copy(alpha = if (p.isDark) 0.26f else 0.14f), p.accent.copy(alpha = if (p.isDark) 0.16f else 0.08f))
            p.isDark -> listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.07f))
            else -> listOf(Color.White.copy(alpha = 0.92f), Color.White.copy(alpha = 0.58f))
        }
        Modifier
            .clip(shape)
            .background(Brush.verticalGradient(fill))
            .border(1.dp, p.glassStroke, shape)
    } else {
        Modifier
            .background(Brush.verticalGradient(listOf(t.surface, t.diffuseEnd)))
            .border(1.dp, t.divider)
    }
    Box(
        modifier
            .size(36.dp)
            .then(shell)
            .semantics { contentDescription = known?.displayName ?: "Agent" },
        contentAlignment = Alignment.Center,
    ) {
        if (known != null) {
            AgentBrandIcon(canonicalId = known.id, tint = if (glass) p.providerMarkColor else t.ink, modifier = Modifier.size(21.dp))
        } else {
            TerminalGlyph(tint = if (glass) p.accent else t.accent)
        }
    }
}

/** 工作区目录条：文件夹符号 + 等宽路径，过长时中间省略（首尾都可辨认）。 */
@Composable
private fun WorkspaceChip(path: String, modifier: Modifier = Modifier) {
    val p = LocalAppPalette.current
    val kit = LocalThemeSuite.current
    val glass = kit.surfaces.isGlass
    Row(
        modifier
            .clip(kit.geometry.shape(RoundedCornerShape(8.dp)))
            .background(if (glass) p.rowTitleText.copy(alpha = if (p.isDark) 0.08f else 0.05f) else Color.Transparent)
            .then(if (glass) Modifier else Modifier.border(1.dp, kit.colors.separator))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FolderGlyph(tint = p.pathText)
        Spacer(Modifier.width(6.dp))
        AppText(
            text = path,
            color = p.pathText,
            fontSize = 11.5f.sp,
            fontFamily = FontFamily.Monospace,
            lineHeightMultiplier = 1.2f,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
    }
}

/**
 * 记录未带可解析会话时，按 Agent 标签里的独立词推断 Provider（"Codex · 验收" → codex）。
 * 只认完整词，不做子串猜测（"pipeline" 不是 Pi）。
 */
internal fun inferProvider(agentName: String?): String? =
    agentName.orEmpty()
        .lowercase()
        .split(Regex("[^a-z0-9]+"))
        .firstNotNullOfOrNull { ProviderAliases[it] }

private val ProviderAliases = mapOf(
    "claude" to "claude_code",
    "codex" to "codex",
    "copilot" to "copilot",
    "grok" to "grok",
    "cursor" to "cursor",
    "pi" to "pi",
)

internal fun levelLabel(level: NotificationLevel): String = when (level) {
    NotificationLevel.Info -> "信息"
    NotificationLevel.Success -> "完成"
    NotificationLevel.Warning -> "警告"
    NotificationLevel.Error -> "错误"
}

internal fun levelColor(level: NotificationLevel): Color = when (level) {
    NotificationLevel.Info -> Color(0xFF0A84FF)
    NotificationLevel.Success -> Color(0xFF34C759)
    NotificationLevel.Warning -> Color(0xFFFF9500)
    NotificationLevel.Error -> Color(0xFFEC3013)
}

/** 级别胶囊：玻璃为薄染胶囊 + 同色字；直角为实心方块 + 白字。 */
@Composable
private fun LevelPill(level: NotificationLevel) {
    val p = LocalAppPalette.current
    val kit = LocalThemeSuite.current
    val color = levelColor(level)
    val glass = kit.surfaces.isGlass
    Box(
        Modifier
            .clip(kit.geometry.shape(CircleShape))
            .background(if (glass) color.copy(alpha = if (p.isDark) 0.24f else 0.14f) else color)
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .testTag("message-level-${level.wire}"),
    ) {
        AppText(
            text = levelLabel(level),
            color = when {
                // 直角实心块：亮色块（绿 / 橙）配墨字，暗色块（红 / 蓝）配白字，保证对比度。
                !glass -> if (color.luminance() > 0.4f) Color(0xFF111111) else Color.White
                p.isDark -> color
                else -> lerp(color, Color.Black, 0.28f)
            },
            fontSize = 10.5f.sp,
            fontWeight = FontWeight.Bold,
            lineHeightMultiplier = 1f,
            letterSpacing = if (glass) 0.sp else 0.6.sp,
        )
    }
}

/**
 * 系统提醒不完整时的一行提示：通知被关 / 未授权时消息只进消息中心；任务渠道被降级时
 * 只进通知栏、不以横幅弹出。两种都引导到对应的系统设置。
 */
@Composable
private fun SystemAlertBanner() {
    val context = LocalContext.current
    val p = LocalAppPalette.current
    val kit = LocalThemeSuite.current
    val helper = remember(context) { NotificationHelper(context) }
    fun enabled(): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }
    var alertsEnabled by remember { mutableStateOf(enabled()) }
    var popsUp by remember { mutableStateOf(helper.taskAlertsPopUp()) }
    var askedOnce by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                alertsEnabled = enabled()
                popsUp = helper.taskAlertsPopUp()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        askedOnce = true
        alertsEnabled = enabled()
    }
    if (alertsEnabled && popsUp) return
    val floating = kit.recipes.listRow == ListRowStyle.FloatingCard
    Row(
        Modifier
            .padding(start = if (floating) 14.dp else 0.dp, end = if (floating) 14.dp else 0.dp, top = if (floating) 12.dp else 0.dp)
            .fillMaxWidth()
            .then(
                if (floating) {
                    Modifier.settingsGlass(RoundedRectangle(16.dp))
                } else {
                    Modifier.background(p.glassCardFill).edgeRule(RuleEdge.Bottom, kit.colors.separator, kit.geometry.hairline)
                },
            )
            .padding(horizontal = if (floating) 14.dp else kit.geometry.pageInset, vertical = 12.dp)
            .testTag("message-center-alert-banner"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            AppText(
                if (alertsEnabled) "横幅提醒未开启" else "系统提醒未开启",
                p.rowTitleText,
                13.5f.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeightMultiplier = 1.25f,
            )
            Spacer(Modifier.height(2.dp))
            AppText(
                if (alertsEnabled) "新消息只进通知栏，不会在屏幕顶部弹出。" else "新消息只会出现在这里，不会弹出通知。",
                p.bodyText,
                12.sp,
                lineHeightMultiplier = 1.4f,
            )
        }
        Spacer(Modifier.width(10.dp))
        CardTonalButton(
            text = "开启",
            onClick = {
                val needsRuntime = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                if (alertsEnabled) {
                    // 通知已开但任务渠道被降级：直达该渠道的设置页把「横幅 / 弹出」打开。
                    context.startActivity(
                        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .putExtra(Settings.EXTRA_CHANNEL_ID, NotificationHelper.CHANNEL_AGENT_TASKS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } else if (needsRuntime && !askedOnce) {
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    // 已拒绝 / 渠道被关：只能去系统设置里开，不循环弹授权框。
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
            modifier = Modifier.widthIn(min = 64.dp),
        )
    }
}

@Composable
private fun MessageEmptyState(workspaceFilter: String?, modifier: Modifier = Modifier) {
    val p = LocalAppPalette.current
    val kit = LocalThemeSuite.current
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp)
            .padding(top = 72.dp)
            .testTag("message-center-empty"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .then(
                    if (kit.surfaces.isGlass) {
                        Modifier.settingsGlass(RoundedRectangle(20.dp))
                    } else {
                        Modifier.border(kit.geometry.headerRule, p.rowTitleText)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            BellGlyph(tint = if (kit.surfaces.isGlass) p.accent else p.rowTitleText, size = 28.dp)
        }
        Spacer(Modifier.height(18.dp))
        AppText(
            text = if (workspaceFilter == null) "暂无消息" else "当前工作区暂无任务消息",
            color = p.rowTitleText,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        AppText(
            text = "Agent 完成任务后通过 corral-notify 主动推送的消息，\n会完整地显示在这里。",
            color = p.bodyText,
            fontSize = 12.5f.sp,
            lineHeightMultiplier = 1.5f,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 顶栏铃铛入口：与返回钮同款 36dp 圆形玻璃（直角风格为方钮），右上角未读角标（99+ 封顶）。
 * 录制树内使用，固定 [emptyBackdrop]，不采样背景。
 */
@Composable
fun MessageBellButton(
    unreadCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalAppPalette.current
    val kit = LocalThemeSuite.current
    Box(
        modifier
            .size(Dims.pillTouchFloor)
            .testTag("message-center-bell"),
        contentAlignment = Alignment.Center,
    ) {
        GlassIconButton(
            onClick = onClick,
            size = Dims.circleBackDisc,
            backdrop = emptyBackdrop(),
            modifier = Modifier.semantics {
                contentDescription = if (unreadCount > 0) "消息中心，$unreadCount 条未读" else "消息中心"
            },
        ) {
            BellGlyph(tint = p.rowTitleText, size = 20.dp)
        }
        if (unreadCount > 0) {
            val badgeColor = if (kit.surfaces.isGlass) Color(0xFFFF3B30) else Color(0xFFEC3013)
            val shape = kit.geometry.shape(RoundedCornerShape(50))
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-1).dp, y = 3.dp)
                    .defaultMinSize(minWidth = 17.dp)
                    .height(17.dp)
                    .clip(shape)
                    .background(p.screenBackground)
                    .padding(1.5.dp)
                    .clip(shape)
                    .background(badgeColor)
                    .padding(horizontal = 4.dp)
                    .testTag("message-center-badge"),
                contentAlignment = Alignment.Center,
            ) {
                AppText(
                    text = if (unreadCount > 99) "99+" else unreadCount.toString(),
                    color = Color.White,
                    fontSize = 9.5f.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeightMultiplier = 1f,
                )
            }
        }
    }
}

/** 几何铃铛（Canvas 路径，不依赖 emoji / 符号字体）：钟体 + 口沿 + 铃舌。 */
@Composable
private fun BellGlyph(tint: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val body = Path().apply {
            moveTo(w * 0.20f, h * 0.72f)
            lineTo(w * 0.80f, h * 0.72f)
            cubicTo(w * 0.72f, h * 0.64f, w * 0.72f, h * 0.56f, w * 0.72f, h * 0.44f)
            cubicTo(w * 0.72f, h * 0.28f, w * 0.62f, h * 0.18f, w * 0.50f, h * 0.18f)
            cubicTo(w * 0.38f, h * 0.18f, w * 0.28f, h * 0.28f, w * 0.28f, h * 0.44f)
            cubicTo(w * 0.28f, h * 0.56f, w * 0.28f, h * 0.64f, w * 0.20f, h * 0.72f)
            close()
        }
        drawPath(body, tint, style = stroke)
        drawLine(tint, Offset(w * 0.50f, h * 0.10f), Offset(w * 0.50f, h * 0.18f), stroke.width, StrokeCap.Round)
        val clapper = Path().apply {
            moveTo(w * 0.41f, h * 0.80f)
            quadraticTo(w * 0.50f, h * 0.90f, w * 0.59f, h * 0.80f)
        }
        drawPath(clapper, tint, style = stroke)
    }
}

/** 未知 Agent 的终端符号：圆角窗框 + 提示符「›」+ 光标横线。 */
@Composable
private fun TerminalGlyph(tint: Color) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 1.7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.06f, h * 0.14f),
            size = Size(w * 0.88f, h * 0.72f),
            cornerRadius = CornerRadius(w * 0.14f),
            style = stroke,
        )
        val prompt = Path().apply {
            moveTo(w * 0.26f, h * 0.38f)
            lineTo(w * 0.42f, h * 0.52f)
            lineTo(w * 0.26f, h * 0.66f)
        }
        drawPath(prompt, tint, style = stroke)
        drawLine(tint, Offset(w * 0.52f, h * 0.66f), Offset(w * 0.72f, h * 0.66f), stroke.width, StrokeCap.Round)
    }
}

/** 工作区目录条的文件夹符号。 */
@Composable
private fun FolderGlyph(tint: Color) {
    Canvas(Modifier.size(13.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 1.3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val folder = Path().apply {
            moveTo(w * 0.08f, h * 0.24f)
            lineTo(w * 0.40f, h * 0.24f)
            lineTo(w * 0.50f, h * 0.36f)
            lineTo(w * 0.92f, h * 0.36f)
            lineTo(w * 0.92f, h * 0.84f)
            lineTo(w * 0.08f, h * 0.84f)
            close()
        }
        drawPath(folder, tint, style = stroke)
    }
}

/** 进入终端按钮的右向箭头。 */
@Composable
private fun ArrowGlyph(tint: Color) {
    Canvas(Modifier.size(14.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawLine(tint, Offset(w * 0.16f, h * 0.5f), Offset(w * 0.82f, h * 0.5f), stroke.width, StrokeCap.Round)
        val head = Path().apply {
            moveTo(w * 0.54f, h * 0.22f)
            lineTo(w * 0.84f, h * 0.5f)
            lineTo(w * 0.54f, h * 0.78f)
        }
        drawPath(head, tint, style = stroke)
    }
}

/** 液态玻璃的静态环境光（与三栏主页的蓝 / 紫光斑同色），给半透卡片一层可感知的纵深。 */
private fun Modifier.ambientSheen(): Modifier = drawBehind {
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFFB9D8FF).copy(alpha = 0.20f), Color.Transparent),
            center = Offset(size.width * 0.84f, size.height * 0.12f),
            radius = size.maxDimension * 0.70f,
        ),
    )
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFFD8C7FF).copy(alpha = 0.17f), Color.Transparent),
            center = Offset(size.width * 0.14f, size.height * 0.84f),
            radius = size.maxDimension * 0.64f,
        ),
    )
}

/**
 * 消息时间：1 分钟内「刚刚」，1 小时内「N 分钟前」，当天「HH:mm」，昨天「昨天 HH:mm」，
 * 当年「M月d日 HH:mm」，更早「yyyy年M月d日」。无法解析时原样返回。
 */
internal fun formatNotificationTime(
    timestamp: String,
    nowMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val instant = runCatching { Instant.parse(timestamp) }.getOrNull() ?: return timestamp
    val diff = nowMillis - instant.toEpochMilli()
    if (diff in 0 until 60_000L) return "刚刚"
    if (diff in 60_000L until 3_600_000L) return "${diff / 60_000L} 分钟前"
    val at = instant.atZone(zone)
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val day: LocalDate = at.toLocalDate()
    return when {
        day == today -> at.format(HourMinute)
        day == today.minusDays(1) -> "昨天 ${at.format(HourMinute)}"
        day.year == today.year -> at.format(MonthDayTime)
        else -> at.format(FullDate)
    }
}

private val HourMinute = DateTimeFormatter.ofPattern("HH:mm")
private val MonthDayTime = DateTimeFormatter.ofPattern("M月d日 HH:mm")
private val FullDate = DateTimeFormatter.ofPattern("yyyy年M月d日")
