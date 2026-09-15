package dev.agentmirror.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.agentmirror.app.tsnet.ConnectionPath
import dev.agentmirror.app.ui.components.AppText
import dev.agentmirror.app.ui.components.BackAffordance
import dev.agentmirror.app.ui.components.CanonicalProviderMarks
import dev.agentmirror.app.ui.components.ExtractedProviderIcon
import dev.agentmirror.app.ui.components.LanPill
import dev.agentmirror.app.ui.components.PathText
import dev.agentmirror.app.ui.components.RowDivider
import dev.agentmirror.app.ui.components.SessionRow
import dev.agentmirror.app.ui.model.SessionItem
import dev.agentmirror.app.ui.theme.Dims
import dev.agentmirror.app.workspace.AgentLauncherUi
import dev.agentmirror.app.workspace.CreateAgentUiState
import dev.agentmirror.app.ui.theme.LocalAppPalette
import dev.agentmirror.app.ui.theme.TypeSizes

/**
 * 会话列表（二级，某个工作区内）。
 * 顶部：‹ 工作区 + LAN，下面是工作区名 + 完整路径。
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
    modifier: Modifier = Modifier,
    connectionPath: ConnectionPath? = null,
    connectionBanner: String? = null,
    bottomBar: @Composable () -> Unit = {},
    agentLaunchers: List<AgentLauncherUi> = emptyList(),
    createAgentState: CreateAgentUiState = CreateAgentUiState(),
    onCreateAgent: (anchorRef: String, provider: String, name: String, bypass: Boolean) -> Unit = { _, _, _, _ -> },
    onCreateAgentErrorCleared: () -> Unit = {},
) {
    val p = LocalAppPalette.current
    Column(modifier.fillMaxSize().background(p.screenBackground)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(Dims.topBarHeight)
                .padding(start = 2.dp, end = Dims.screenHPadding)
                .testTag("session-list-topbar"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackAffordance(label = "工作区", onBack = onBack)
            Box(Modifier.weight(1f))
            var showCreateDialog by remember { mutableStateOf(false) }
            TextButton(
                onClick = {
                    showCreateDialog = true
                    onCreateAgentErrorCleared()
                },
                enabled = agentLaunchers.isNotEmpty() && !createAgentState.inFlight,
                modifier = Modifier.testTag("create-agent-button"),
            ) {
                androidx.compose.material3.Text("+ 新建 Agent")
            }
            if (connectionPath != null) {
                Spacer(Modifier.width(8.dp))
                LanPill(connectionPath)
            }
            if (showCreateDialog && agentLaunchers.isNotEmpty()) {
                CreateAgentDialog(
                    sessions = sessions,
                    launchers = agentLaunchers,
                    state = createAgentState,
                    onDismiss = { showCreateDialog = false },
                    onCreate = onCreateAgent,
                )
            }
        }
        Column(Modifier.padding(start = Dims.screenHPadding, end = Dims.screenHPadding, top = 2.dp, bottom = 13.dp)) {
            AppText(
                text = workspaceName,
                color = p.titleText,
                fontSize = TypeSizes.screenTitleSecondary,
                fontWeight = FontWeight.Bold,
                lineHeightMultiplier = 1.2f,
                letterSpacing = (-0.4).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(Modifier.height(5.dp))
            PathText(workspacePath)
        }
        Box(Modifier.fillMaxWidth().height(Dims.hairline).background(p.divider))
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(p.listBackground),
        ) {
            SessionListRows(
                sessions = sessions,
                onSessionClick = onSessionClick,
                onToggleStar = onToggleStar,
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
    LaunchedEffect(state.inFlight, state.error) {
        if (!state.inFlight && state.error == null && name.isNotEmpty()) onDismiss()
    }
    AlertDialog(
        onDismissRequest = { if (!state.inFlight) onDismiss() },
        title = { androidx.compose.material3.Text("新建 Agent") },
        text = {
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
            )
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && launcher != null && !state.inFlight,
                onClick = { onCreate(internalAnchor, launcher!!.provider, name.trim(), bypass) },
                modifier = Modifier
                    .defaultMinSize(minWidth = 84.dp)
                    .testTag("create-agent-confirm"),
            ) {
                androidx.compose.material3.Text(if (state.inFlight) "创建中…" else "创建")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !state.inFlight,
                modifier = Modifier.defaultMinSize(minWidth = 64.dp),
            ) {
                androidx.compose.material3.Text("取消")
            }
        },
    )
}

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
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("create-agent-name"),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.Text(
                text = "Agent 类型",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
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
            Switch(
                checked = bypass,
                onCheckedChange = onBypassChange,
                enabled = supportsBypass && !inFlight,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    uncheckedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
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

@Composable
internal fun AgentIconCard(
    launcher: AgentLauncherUi,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    val borderWidth = if (isSelected) 2.dp else 1.dp
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val contentTint = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier
            .width(78.dp)
            .height(82.dp)
            .semantics { selected = isSelected }
            .testTag("agent-card-${launcher.provider}"),
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        border = BorderStroke(borderWidth, borderColor),
        onClick = onClick,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 5.dp, end = 5.dp)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .testTag("agent-card-check-${launcher.provider}"),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.Text(
                        text = "✓",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
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
                androidx.compose.material3.Text(
                    text = CanonicalProviderMarks.of(launcher.provider)?.displayName ?: launcher.displayName,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    fontSize = 11.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
 */
@Composable
fun SessionListRows(
    sessions: List<SessionItem>,
    onSessionClick: (SessionItem) -> Unit,
    onToggleStar: (SessionItem) -> Unit,
    modifier: Modifier = Modifier,
    tagPrefix: String = "l2",
    listTestTag: String = "l2-session-list-scroll",
) {
    LazyColumn(modifier.testTag(listTestTag)) {
        items(sessions, key = { it.id }) { item ->
            SessionRow(
                item = item,
                tagPrefix = tagPrefix,
                onClick = { onSessionClick(item) },
                onToggleFavorite = { onToggleStar(item) },
                unfavoriteOnly = false,
            )
            RowDivider()
        }
    }
}
