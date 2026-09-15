package dev.agentmirror.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.agentmirror.app.tsnet.ConnectionPath
import dev.agentmirror.app.ui.components.AppText
import dev.agentmirror.app.ui.components.BackAffordance
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
            if (connectionPath != null) LanPill(connectionPath)
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
    var providerMenuExpanded by remember { mutableStateOf(false) }
    var selectedAnchor by remember { mutableStateOf(sessions.firstOrNull()?.id.orEmpty()) }
    var anchorMenuExpanded by remember { mutableStateOf(false) }
    var bypass by remember { mutableStateOf(false) }
    val launcher = launchers.firstOrNull { it.provider == selectedProvider }
    val anchors = sessions
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
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
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
                Box(Modifier.fillMaxWidth()) {
                    AgentSelectorField(
                        label = "Agent 类型",
                        value = launcher?.displayName ?: "选择 Provider",
                        expanded = providerMenuExpanded,
                        onClick = { providerMenuExpanded = true },
                        showArrow = true,
                        modifier = Modifier.testTag("create-agent-provider"),
                    )
                    DropdownMenu(
                        expanded = providerMenuExpanded,
                        onDismissRequest = { providerMenuExpanded = false },
                    ) {
                        launchers.forEach { option ->
                            DropdownMenuItem(
                                text = { androidx.compose.material3.Text(option.displayName) },
                                onClick = {
                                    selectedProvider = option.provider
                                    providerMenuExpanded = false
                                },
                            )
                        }
                    }
                }
                if (anchors.isNotEmpty()) {
                    Box(Modifier.fillMaxWidth()) {
                        val selectedSession = anchors.firstOrNull { it.id == selectedAnchor }
                        AgentSelectorField(
                            label = "目标会话",
                            value = selectedSession?.displayName ?: "暂无可用会话",
                            expanded = anchorMenuExpanded,
                            onClick = { anchorMenuExpanded = true },
                            showArrow = anchors.size > 1,
                            enabled = anchors.size > 1 && !state.inFlight,
                            modifier = Modifier.testTag("create-agent-anchor"),
                        )
                        if (anchors.size > 1) {
                            DropdownMenu(
                                expanded = anchorMenuExpanded,
                                onDismissRequest = { anchorMenuExpanded = false },
                            ) {
                                anchors.forEach { item ->
                                    DropdownMenuItem(
                                        text = { androidx.compose.material3.Text(item.displayName) },
                                        onClick = {
                                            selectedAnchor = item.id
                                            anchorMenuExpanded = false
                                        },
                                    )
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
                    Switch(
                        checked = bypass,
                        onCheckedChange = { bypass = it },
                        enabled = launcher?.supportsBypass == true && !state.inFlight,
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
                state.error?.let {
                    androidx.compose.material3.Text(
                        "创建失败：$it",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("create-agent-error"),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && selectedAnchor.isNotBlank() && launcher != null && !state.inFlight,
                onClick = { onCreate(selectedAnchor, launcher!!.provider, name, bypass) },
                modifier = Modifier.testTag("create-agent-confirm"),
            ) {
                androidx.compose.material3.Text(if (state.inFlight) "创建中…" else "创建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.inFlight) {
                androidx.compose.material3.Text("取消")
            }
        },
    )
}

@Composable
internal fun AgentSelectorField(
    label: String,
    value: String,
    expanded: Boolean,
    onClick: () -> Unit,
    showArrow: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val fieldModifier = modifier
        .fillMaxWidth()
        .height(56.dp)
        .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        androidx.compose.material3.Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
        Surface(
            modifier = fieldModifier,
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 0.55f else 0.35f),
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Text(
                    text = value,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (showArrow) {
                    androidx.compose.material3.Text(
                        text = if (expanded) "▴" else "▾",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
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
