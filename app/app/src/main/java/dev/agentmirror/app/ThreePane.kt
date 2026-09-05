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

package dev.agentmirror.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.testTag
import kotlin.math.abs
import dev.agentmirror.app.service.ServiceWire
import dev.agentmirror.app.pairing.HostRouter
import dev.agentmirror.app.tsnet.ConnectionPath
import dev.agentmirror.app.ui.components.AppBottomNav
import dev.agentmirror.app.ui.model.NavTab
import dev.agentmirror.app.ui.theme.Appearance
import dev.agentmirror.app.ui.theme.Spacing
import dev.agentmirror.app.workspace.ConnectionUi
import dev.agentmirror.app.workspace.FavoriteList
import dev.agentmirror.app.workspace.WorkspaceScreen
import dev.agentmirror.app.workspace.WorkspaceViewModel
import dev.agentmirror.app.workspace.connectionBannerText

/**
 * 窄屏三栏（067 §4.1）：底部标签栏 收藏 / 会话 / 设置。
 * 滑动可保留为附加手势，不得作为唯一入口。冷启动默认「会话」。
 */
enum class ThreePane(val tabLabel: String, val tabIcon: String, val tabTag: String) {
    Favorites("收藏", "★", "bottom-tab-favorites"),
    Sessions("会话", "☰", "bottom-tab-sessions"),
    Settings("设置", "⚙", "bottom-tab-settings"),
}

private fun ThreePane.toNavTab(): NavTab = when (this) {
    ThreePane.Favorites -> NavTab.Favorites
    ThreePane.Sessions -> NavTab.Sessions
    ThreePane.Settings -> NavTab.Settings
}

private fun NavTab.toPane(): ThreePane = when (this) {
    NavTab.Favorites -> ThreePane.Favorites
    NavTab.Sessions -> ThreePane.Sessions
    NavTab.Settings -> ThreePane.Settings
}

@Composable
internal fun ThreePaneHome(
    navState: MainNavState,
    workspaceViewModel: WorkspaceViewModel,
    appearance: Appearance = Appearance.System,
    onAppearanceChange: (Appearance) -> Unit = {},
) {
    val pagerState = rememberPagerState(
        initialPage = navState.homePane.ordinal,
        pageCount = { ThreePane.entries.size },
    )
    LaunchedEffect(navState.homePane) {
        val target = navState.homePane.ordinal
        if (pagerState.currentPage != target) {
            pagerState.scrollToPage(target)
        }
    }
    // 首帧 settledPage 仍是 initialPage，不能回写，否则会把外部指定的 Favorites 冲掉。
    val pagerToNavArmed = remember { mutableStateOf(false) }
    LaunchedEffect(pagerState.settledPage) {
        if (!pagerToNavArmed.value) {
            pagerToNavArmed.value = true
            return@LaunchedEffect
        }
        val pane = ThreePane.entries[pagerState.settledPage]
        if (navState.homePane != pane) navState.homePane = pane
        navState.showSettings = pane == ThreePane.Settings
    }

    // 子页顶栏自己吃 statusBarsPadding（edge-to-edge）。Scaffold 默认 contentWindowInsets
    // 再垫一层 statusBars，叠出设置页「状态栏到标题约屏高 1/8」的空洞（076 §2a）。
    // bottomBar 仍通过 innerPadding 占位，不在这里清零。
    val tabPagerNestedScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // 主导竖直时吞掉横向分量，避免斜滑被 HorizontalPager 锁成切页。
                // 主导横向时放行，067 §4.1 横滑切页仍在。
                return if (abs(available.y) > abs(available.x)) Offset(available.x, 0f) else Offset.Zero
            }
        }
    }
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("three-pane"),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            AppBottomNav(
                selected = navState.homePane.toNavTab(),
                onSelect = { tab ->
                    val pane = tab.toPane()
                    navState.homePane = pane
                    navState.showSettings = pane == ThreePane.Settings
                },
            )
        },
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 0,
            pageNestedScrollConnection = tabPagerNestedScroll,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) { page ->
            when (ThreePane.entries[page]) {
                ThreePane.Favorites -> FavoritesPane(
                    viewModel = workspaceViewModel,
                    connectionPath = ServiceWire.connectionPath(),
                    onOpenSession = { ref, name -> navState.openSession(ref, name) },
                )
                ThreePane.Sessions -> WorkspaceScreen(
                    viewModel = workspaceViewModel,
                    selectedWorkspaceCwd = navState.selectedWorkspaceCwd,
                    connectionPath = ServiceWire.connectionPath(),
                    hostBound = ServiceWire.currentConfig()?.hostId?.let(HostRouter::isValidHostId) == true,
                    retainLevel2OnDispose = { navState.activeSession != null },
                    onSelectWorkspace = { navState.selectedWorkspaceCwd = it },
                    onBackToList = { navState.selectedWorkspaceCwd = null },
                    onOpenSettings = {
                        navState.showSettings = true
                        navState.homePane = ThreePane.Settings
                    },
                    onOpenSession = { ref, name -> navState.openSession(ref, name) },
                )
                ThreePane.Settings -> SettingsScreen(
                    onBack = {
                        navState.showSettings = false
                        navState.homePane = ThreePane.Sessions
                    },
                    onRePair = {
                        navState.showSettings = false
                        navState.homePane = ThreePane.Sessions
                        navState.showPairing = true
                    },
                    enableBackHandler = pagerState.currentPage == ThreePane.Settings.ordinal,
                    appearance = appearance,
                    onAppearanceChange = onAppearanceChange,
                )
            }
        }
    }
}

@Composable
private fun FavoritesPane(
    viewModel: WorkspaceViewModel,
    connectionPath: ConnectionPath?,
    onOpenSession: (ref: String, name: String) -> Unit,
) {
    val favorites by viewModel.favorites.collectAsState()
    val liveGen by viewModel.favoriteLiveGen.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val rows = remember(favorites, liveGen) { viewModel.favoriteRows() }
    DisposableEffect(Unit) {
        viewModel.enterFavorites()
        onDispose { viewModel.leaveFavorites() }
    }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1_000)
            viewModel.checkFavoriteFetch()
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("three-pane-favorites"),
    ) {
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    modifier = Modifier.padding(horizontal = Spacing.xl),
                ) {
                    Text(
                        text = "暂无收藏",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "在会话列表里点星星即可收藏。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            FavoriteList(
                rows = rows,
                onOpenSession = onOpenSession,
                onUnfavorite = viewModel::toggleFavorite,
                connectionPath = connectionPath.takeIf { uiState.connection == ConnectionUi.READY },
                connectionBanner = connectionBannerText(uiState.connection),
            )
        }
    }
}
