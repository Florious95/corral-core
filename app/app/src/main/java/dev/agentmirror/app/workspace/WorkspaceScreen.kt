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

package dev.agentmirror.app.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.agentmirror.app.ui.theme.MonoFontFamily
import dev.agentmirror.app.ui.theme.Spacing

/**
 * 工作区两级导航（需求 001 舰队视角 → 002 两级分组），018 全面重设计版。
 *
 * 图28 实锤缺陷修复清单：
 * - safe-area：顶栏 statusBarsPadding、列表 navigationBarsPadding（018 §一.2）；
 * - 标题栏：一级「工作区」/二级目录名，AnimatedContent 平滑切换（此前无标题栏）；
 * - 行层级：主（目录名）/辅（全路径中段省略）/次（徽章+会话数）三级分明（此前四行撑爆）；
 * - 加载态：CONNECTING 且无数据时专门设计（此前直接渲染空 LazyColumn = 白屏）；
 * - 转场：一二级横向滑动 + 淡入淡出；行点击 ripple（Surface onClick）。
 *
 * 状态全部来自 [WorkspaceViewModel]；聚合字段是服务端权威值，本屏只渲染不重算（012）。
 */
@Composable
fun WorkspaceScreen(
    viewModel: WorkspaceViewModel,
    onOpenSession: (ref: String, name: String) -> Unit,
) {
    val state by viewModel.uiState.collectAsState()

    var selectedCwd by remember { mutableStateOf<String?>(null) }
    val selectedWorkspace = state.workspaces.firstOrNull { it.cwd == selectedCwd }

    // 系统返回键语义：二级会话列表 → 一级工作区（此前只能点顶部文字返回）。
    BackHandler(enabled = selectedWorkspace != null) { selectedCwd = null }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TopBar(
            selected = selectedWorkspace,
            onBack = { selectedCwd = null },
        )
        ConnectionBanner(connection = state.connection)

        // 一级⇄二级转场：以选中 cwd 为键横向滑动（进入右滑入，返回左滑入），018 §一.6。
        AnimatedContent(
            targetState = selectedWorkspace?.cwd,
            transitionSpec = {
                if (targetState != null) {
                    (slideInHorizontally { it / 4 } + fadeIn())
                        .togetherWith(slideOutHorizontally { -it / 4 } + fadeOut())
                } else {
                    (slideInHorizontally { -it / 4 } + fadeIn())
                        .togetherWith(slideOutHorizontally { it / 4 } + fadeOut())
                }
            },
            modifier = Modifier.fillMaxSize(),
            label = "workspace-level",
        ) { cwd ->
            val workspace = state.workspaces.firstOrNull { it.cwd == cwd }
            when {
                cwd != null && workspace != null -> SessionList(
                    workspace = workspace,
                    onOpenSession = onOpenSession,
                )
                // 连接中且还没有任何数据：专门加载态（修旧版空 LazyColumn 白屏缺陷）。
                state.connection == ConnectionUi.CONNECTING && state.workspaces.isEmpty() ->
                    LoadingContent()
                state.isDisconnected && state.workspaces.isEmpty() -> DisconnectedEmptyContent(state)
                state.isEmpty -> EmptyGuideContent()
                else -> WorkspaceList(
                    workspaces = state.workspaces,
                    onOpenWorkspace = { selectedCwd = it.cwd },
                )
            }
        }
    }
}

/**
 * 顶栏：一级显「工作区」标题；二级显返回钮 + 目录名（单行尾省略）。
 * statusBarsPadding 在容器上：背景延伸进状态栏（edge-to-edge），内容不叠压（018 §一.2）。
 */
@Composable
private fun TopBar(
    selected: WorkspaceUi?,
    onBack: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .defaultMinSize(minHeight = 56.dp)
                .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected == null) {
                Text(
                    text = "工作区",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(horizontal = Spacing.sm),
                )
            } else {
                // 「‹ 工作区」文案沿用旧版（e2e 语义树兼容），视觉压为次层级色。
                TextButton(onClick = onBack) {
                    Text("‹ 工作区", style = MaterialTheme.typography.labelLarge)
                }
                Text(
                    text = cwdDisplayName(selected.cwd),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = Spacing.xs, end = Spacing.sm),
                )
            }
        }
    }
}

/**
 * 连接状态横幅：断连/连接中给 tonal 提示条，READY 平滑收起（AnimatedVisibility——
 * 018 §一.6 连接状态变化平滑呈现，替代旧版 2dp Spacer 闪跳）。
 * conn 层自动重连，UI 只反映状态（004）。
 */
@Composable
private fun ConnectionBanner(connection: ConnectionUi) {
    AnimatedVisibility(visible = connection != ConnectionUi.READY) {
        val (text, isError) = when (connection) {
            ConnectionUi.CONNECTING -> "连接中…" to false
            ConnectionUi.RECONNECTING -> "重连中…" to false
            ConnectionUi.STOPPED -> "连接已关闭" to true
            ConnectionUi.READY -> "" to false // 不可达：READY 不进本分支
        }
        Surface(
            color = if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.pageH, vertical = Spacing.xs),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!isError) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(14.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isError) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                )
            }
        }
    }
}

/** 加载态（018 §一.5 每页专门设计）：连接尚未就绪且无缓存列表时的等待画面。 */
@Composable
private fun LoadingContent() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            CircularProgressIndicator()
            Text(
                text = "正在连接主机…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 断连且无缓存列表：错误态设计（有缓存列表时走列表+顶部横幅，004 不阻塞浏览）。 */
@Composable
private fun DisconnectedEmptyContent(state: WorkspaceUiState) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.padding(horizontal = Spacing.xl),
        ) {
            GlyphMark(error = true)
            Text(
                text = if (state.connection == ConnectionUi.STOPPED) "连接已关闭" else "正在重连…",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (state.connection == ConnectionUi.STOPPED) {
                    "与主机的连接已终止。\n请检查主机守护进程，或重新配对。"
                } else {
                    "连接已断开，正在自动重连。\n恢复后列表会自动刷新。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 空态（018 §一.5）：就绪但主机无工作区 → 图形锚点 + 分层引导文案（无工作区 ≠ 错误）。 */
@Composable
private fun EmptyGuideContent() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.padding(horizontal = Spacing.xl),
        ) {
            GlyphMark(error = false)
            Text(
                text = "暂无工作区",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "先在主机上启动一个 tmux 中的 Agent CLI，\n它会被自动纳管到这里。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 空/错态图形锚点：终端提示符字形（零图标库依赖，产品身份语言与列表行图标同源）。 */
@Composable
private fun GlyphMark(error: Boolean) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .background(
                color = if (error) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                shape = MaterialTheme.shapes.medium,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "❯_",
            style = MaterialTheme.typography.titleLarge,
            fontFamily = MonoFontFamily,
            color = if (error) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** 一级：工作目录（cwd 聚合）列表。行内容布局见 [WorkspaceRow]（层级重做）。 */
@Composable
private fun WorkspaceList(
    workspaces: List<WorkspaceUi>,
    onOpenWorkspace: (WorkspaceUi) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
    ) {
        items(workspaces, key = { it.cwd }) { ws ->
            // Surface onClick：ripple 点击态 + 48dp 最小触控目标（018 §一.4/一.6）。
            Surface(
                onClick = { onOpenWorkspace(ws) },
                color = MaterialTheme.colorScheme.background,
            ) {
                WorkspaceRow(
                    cwd = ws.cwd,
                    sessionCount = ws.sessionCount,
                    aggregateState = ws.aggregateState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.pageH, vertical = Spacing.rowV),
                )
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = 0.5.dp,
                modifier = Modifier.padding(start = Spacing.pageH + 40.dp + Spacing.md),
            )
        }
    }
}

/** 二级：选中 cwd 下的会话列表（ref 寻址、name 展示；unknown 灰显不阻塞，008）。 */
@Composable
private fun SessionList(
    workspace: WorkspaceUi,
    onOpenSession: (ref: String, name: String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
    ) {
        items(workspace.sessions, key = { it.ref }) { s ->
            Surface(
                onClick = { onOpenSession(s.ref, s.name) },
                color = MaterialTheme.colorScheme.background,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 56.dp)
                        .padding(horizontal = Spacing.pageH, vertical = Spacing.rowV),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 会话名等宽单行中段省略：tmux 会话名首尾都是辨识信息
                    // （前缀=类型、尾缀=序号），中段省略两头都保（018 §一.3）。
                    Text(
                        text = s.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = MonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                        modifier = Modifier.weight(1f),
                    )
                    StateBadge(state = s.state)
                }
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = 0.5.dp,
                modifier = Modifier.padding(start = Spacing.pageH),
            )
        }
    }
}
