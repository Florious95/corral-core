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

import android.app.Activity
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.agentmirror.app.tsnet.ConnectionPath
import dev.agentmirror.app.ui.screens.SessionListScreen
import dev.agentmirror.app.ui.screens.WorkspaceListScreen
import dev.agentmirror.app.ui.theme.AppTheme
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
 * 061：选中工作区后渲染二级列表（标识 + 状态标）。二级状态读 [WorkspaceViewModel.level2]。
 *
 * @contract
 * @pre viewModel 提供当前工作区模型；selectedWorkspaceCwd 可为 null
 * @post 渲染一级或二级列表；选择/返回/开会话经回调上抛
 * @inv 二级不向服务端轮询状态（只订一次、只收推送）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    viewModel: WorkspaceViewModel,
    selectedWorkspaceCwd: String?,
    connectionPath: ConnectionPath? = null,
    retainLevel2OnDispose: () -> Boolean = { false },
    onSelectWorkspace: (cwd: String) -> Unit,
    onBackToList: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onOpenSettings: () -> Unit,
    onOpenSession: (ref: String, name: String) -> Unit = { _, _ -> },
) {
    val state by viewModel.uiState.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()
    val level2 by viewModel.level2.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val activity = LocalContext.current as? Activity

    // 进入即刷（069）：一级发 list，二级由 enterLevel2 重订。键是菜单身份，不是滚动。
    // 旋转重建走 suppressNextEnterRefresh，本拍不发 list。下拉见 onRefresh。
    LaunchedEffect(selectedWorkspaceCwd) {
        if (selectedWorkspaceCwd == null) {
            viewModel.enterLevel1()
        } else if (!viewModel.shouldSuppressEnterRefresh()) {
            // 074：进二级仍发一次 list（转圈由 listing/首帧复位）；不是滚动触发。
            viewModel.refresh()
        }
        viewModel.clearEnterRefreshSuppress()
    }

    val readyPath = connectionPath.takeIf { state.connection == ConnectionUi.READY }
    val reconnectBanner = connectionBannerText(state.connection)
    val showingDesignList = selectedWorkspaceCwd != null ||
        (!state.isLoading && !state.isEmpty && !(state.isDisconnected && state.workspaces.isEmpty()))

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (!showingDesignList) {
            TopBar(
                selectedCwd = selectedWorkspaceCwd,
                // 拨号工厂记录的是本次尝试路径；只有 READY 后才可称为当前已连接路径。
                connectionPath = connectionPath.takeIf { state.connection == ConnectionUi.READY },
                onBack = onBackToList,
            )
        }
        if (!showingDesignList) {
            ConnectionBanner(connection = state.connection)
        }

        // 下拉手动刷（2026-08-15 用户裁定）：手指下滑触发一次全量刷新（PullToRefreshBox）
        // 的 onRefresh → viewModel.refresh）。isRefreshing 驱动指示器，新 listing 到达后由
        // ViewModel 复位。两级的公共容器（一级/二级都允许下拉刷）。
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .testTag("workspace-pull-refresh"),
        ) {
            val level2Cwd = selectedWorkspaceCwd
            if (level2Cwd != null) {
                DisposableEffect(level2Cwd) {
                    viewModel.enterLevel2(level2Cwd)
                    onDispose {
                        // 旋转销毁组合不是离开菜单：退订会逼新组合再订，撞 069 经济红线。
                        // 进会话页时 ThreePane 离屏，但二级订阅必须留下给顶栏灯（083 §10）。
                        if (activity?.isChangingConfigurations != true && !retainLevel2OnDispose()) {
                            viewModel.leaveLevel2()
                        }
                    }
                }
                LaunchedEffect(level2Cwd) {
                    while (true) {
                        kotlinx.coroutines.delay(1_000)
                        viewModel.checkLevel2Quiet()
                    }
                }
                val starred = favorites.map { it.key }.toSet()
                Column(Modifier.fillMaxSize()) {
                    val banner = level2.banner
                    if (banner != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.pageH, vertical = Spacing.xs)
                                .testTag("l2-stale-banner"),
                        ) {
                            Text(
                                text = banner,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                            )
                        }
                    }
                    AppTheme {
                        SessionListScreen(
                            workspaceName = cwdDisplayName(level2Cwd),
                            workspacePath = level2Cwd,
                            sessions = level2.sessions.map { it.toSessionItem(starred.contains(it.favoriteKey())) },
                            onBack = onBackToList,
                            onSessionClick = { item -> onOpenSession(item.id, item.displayName) },
                            onToggleStar = { item ->
                                level2.sessions.firstOrNull { it.ref == item.id }?.let(viewModel::toggleFavorite)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .statusBarsPadding(),
                            connectionPath = readyPath,
                            connectionBanner = reconnectBanner,
                        )
                    }
                }
                return@PullToRefreshBox
            }
            // 一级列表 + 加载/空/错态。
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
                    else -> AppTheme {
                        WorkspaceListScreen(
                            workspaces = workspaces.map { it.toWorkspaceItem() },
                            onWorkspaceClick = { onSelectWorkspace(it.path) },
                            modifier = Modifier
                                .fillMaxSize()
                                .statusBarsPadding(),
                            connectionPath = readyPath,
                            connectionBanner = reconnectBanner,
                        )
                    }
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
        }
    }
}

/**
 * 连接状态横幅：断连/连接中给 tonal 提示条，READY 平滑收起（AnimatedVisibility——
 * 018 §一.6 连接状态变化平滑呈现，替代旧版 2dp Spacer 闪跳）。
 * conn 层自动重连，UI 只反映状态（004）。
 */
internal fun connectionBannerText(connection: ConnectionUi): String? = when (connection) {
    ConnectionUi.CONNECTING -> "连接中…"
    ConnectionUi.RECONNECTING -> "重连中…"
    ConnectionUi.STOPPED -> "连接已关闭"
    ConnectionUi.READY -> null
}

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
            .navigationBarsPadding()
            .testTag("workspace-list-scroll"),
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

