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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.agentmirror.app.service.ServiceWire
import dev.agentmirror.app.tsnet.ConnectionPath
import dev.agentmirror.app.ui.theme.MonoFontFamily
import dev.agentmirror.app.ui.theme.Spacing

/**
 * 工作区一级菜单（需求 001 舰队视角 → 002 一级分组），018 全面重设计版。
 *
 * 图28 实锤缺陷修复清单：
 * - safe-area：顶栏 statusBarsPadding、列表 navigationBarsPadding（018 §一.2）；
 * - 标题栏：一级「工作区」；
 * - 行层级：主（目录名）/辅（全路径中段省略）/次（会话数）三级分明（此前四行撑爆）；
 * - 加载态：CONNECTING 且无数据时专门设计（此前直接渲染空 LazyColumn = 白屏）。
 *
 * 状态全部来自 [WorkspaceViewModel]；session_count 是服务端权威值，本屏只渲染不重算。
 *
 * 060 uproot（2026-08-15）：二级会话列表（SessionList）随状态判定整体拔除，待二级
 * 实时流重建；本屏只渲染一级工作区列表。
 *
 * @contract
 * @pre viewModel 提供当前工作区模型；selectedWorkspaceCwd 可为 null 或暂未出现在模型中的 cwd
 * @post 渲染一级工作区列表（加载/空/错态分支）；选择/返回经回调上抛；
 *       首次进入即触发一次全量刷新（2026-08-15 用户裁定：每次到一级自动刷一遍拉最新）
 * @err none
 * @inv session_count 不在 UI 重算；工作区选择态只读自入参，屏内不另建 remember 状态；
 *       零周期性自动刷新（无周期拉取结构，刷新只发生在进入/下拉时）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    viewModel: WorkspaceViewModel,
    selectedWorkspaceCwd: String?,
    connectionPath: ConnectionPath? = null,
    onSelectWorkspace: (cwd: String) -> Unit,
    onBackToList: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSession: (ref: String, name: String) -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()

    // 进入即刷（2026-08-15 用户裁定：每次到一级/二级自动刷一遍拉最新；leader 澄清：返回也算
    // 到达）。LaunchedEffect 以 selectedWorkspaceCwd 为键——一级⇄二级每次切换都触发一次刷新：
    // null → cwd 进入二级刷、cwd → null 返回一级刷、app 首次进入一级也刷。每次切换只刷一次，
    // 不周期重复（零周期禁令）。下拉手动刷见 PullToRefreshBox.onRefresh。
    LaunchedEffect(selectedWorkspaceCwd) {
        viewModel.refresh()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TopBar(
            selectedCwd = selectedWorkspaceCwd,
            // 拨号工厂记录的是本次尝试路径；只有 READY 后才可称为当前已连接路径。
            connectionPath = connectionPath.takeIf { state.connection == ConnectionUi.READY },
            onBack = onBackToList,
            onOpenSettings = onOpenSettings,
        )
        ConnectionBanner(connection = state.connection)

        // 下拉手动刷（2026-08-15 用户裁定）：手指下滑触发一次全量刷新（PullToRefreshBox
        // 的 onRefresh → viewModel.refresh）。isRefreshing 驱动指示器，新 listing 到达后由
        // ViewModel 复位。两级的公共容器（一级/二级都允许下拉刷）。
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .testTag("workspace-pull-refresh"),
        ) {
            // 二级实时流（060 重建）：选中工作区（selectedWorkspaceCwd 非空）时渲染服务端
            // 推来的会话行（标题原样 + 结构 ref 点行进三级）。Level2ViewModel 构造即订阅、
            // 离开即退订（DisposableEffect onDispose）。
            val level2Cwd = selectedWorkspaceCwd
            if (level2Cwd != null) {
                val l2Vm = remember(level2Cwd) {
                    ServiceWire.managerOrNull()?.let { Level2ViewModel(it, level2Cwd) }
                }
                DisposableEffect(l2Vm) {
                    onDispose { l2Vm?.dispose() }
                }
                if (l2Vm != null) {
                    Level2LiveStreamScreen(
                        sessions = l2Vm.sessions,
                        onOpenSession = onOpenSession,
                    )
                } else {
                    LoadingContent()
                }
                return@PullToRefreshBox
            }
            // 一级列表 + 加载/空/错态。060 uproot：一级菜单刷新模型保留（进入即刷 + 下拉刷）。
            AnimatedContent(
                targetState = state.workspaces,
                transitionSpec = { fadeIn().togetherWith(fadeOut()) },
                modifier = Modifier.fillMaxSize(),
                label = "workspace-level",
            ) { workspaces ->
                when {
                    // 连接中且还没有任何数据：专门加载态（修旧版空 LazyColumn 白屏缺陷）。
                    state.isLoading -> LoadingContent()
                    state.isDisconnected && state.workspaces.isEmpty() -> DisconnectedEmptyContent(state)
                    state.isEmpty -> EmptyGuideContent()
                    else -> WorkspaceList(
                        workspaces = workspaces,
                        onOpenWorkspace = { onSelectWorkspace(it.cwd) },
                    )
                }
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
    selectedCwd: String?,
    connectionPath: ConnectionPath?,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
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
            if (selectedCwd == null) {
                Text(
                    text = "工作区",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = Spacing.sm),
                )
            } else {
                // 「‹ 工作区」文案沿用旧版（e2e 语义树兼容），视觉压为次层级色。
                TextButton(onClick = onBack) {
                    Text("‹ 工作区", style = MaterialTheme.typography.labelLarge)
                }
                Text(
                    text = cwdDisplayName(selectedCwd),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = Spacing.xs, end = Spacing.sm),
                )
            }
            connectionPath?.let { path ->
                // READY 时旧横幅会收起；常驻标签仍明确告诉用户当前链路是 LAN 还是 tailnet。
                Text(
                    text = path.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = Spacing.sm),
                )
            }
            TextButton(onClick = onOpenSettings) { Text("设置") }
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

