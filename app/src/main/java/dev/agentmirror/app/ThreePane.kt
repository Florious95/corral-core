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

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlin.math.abs
import dev.agentmirror.app.diag.DiagLog
import dev.agentmirror.app.service.ServiceWire
import dev.agentmirror.app.pairing.HostRouter
import dev.agentmirror.app.tsnet.ConnectionPath
import dev.agentmirror.app.ui.components.AppBottomNav
import dev.agentmirror.app.ui.components.GlassModalHost
import dev.agentmirror.app.ui.components.LocalFloatingNavInset
import dev.agentmirror.app.ui.components.LocalGlassBackdrop
import dev.agentmirror.app.ui.model.NavTab
import dev.agentmirror.app.ui.theme.Appearance
import dev.agentmirror.app.ui.theme.Dims
import dev.agentmirror.app.ui.theme.LocalAppPalette
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

    // 子页顶栏自己吃 statusBarsPadding（edge-to-edge），这里不再垫任何系统栏。
    val tabPagerNestedScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // 主导竖直时吞掉横向分量，避免斜滑被 HorizontalPager 锁成切页。
                // 主导横向时放行，067 §4.1 横滑切页仍在。
                return if (abs(available.y) > abs(available.x)) Offset(available.x, 0f) else Offset.Zero
            }
        }
    }
    // 液态玻璃三层：分页内容被 layerBackdrop 录成背景；悬浮导航胶囊与页内模态在录制边界之外采样它。
    // 内容延伸到胶囊下方（被折射），滚动容器经 LocalFloatingNavInset 补底部留白，末行才能滚出胶囊。
    val screenBackground = LocalAppPalette.current.screenBackground
    // The L1→L2 Push already animates a full-screen content layer. Keep the recorded backdrop
    // static while that transition is active/settled; the infinite ambient phase otherwise
    // invalidates the backdrop every frame and competes with LiquidGlass blur on RenderThread.
    // Returning to L1 recreates the same ambient animation without changing the glass contract.
    val ambientEnabled = navState.selectedWorkspaceCwd == null
    LaunchedEffect(ambientEnabled) {
        DiagLog.record(
            "fluid-ambient",
            "enabled=$ambientEnabled reason=${if (ambientEnabled) "l1-settled" else "l1-l2-transition-or-l2"}",
        )
    }
    val ambientTransition = rememberInfiniteTransition(label = "fluidAmbient")
    val ambientPhase = ambientTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (ambientEnabled) 1f else 0f,
        animationSpec = infiniteRepeatable(
            // Keep the infinite clock at the normal cadence even while the ambient
            // layer is suppressed. A 1ms repeat would turn the disabled branch into a
            // frame-invalidation storm instead of pausing it.
            animation = tween(12_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "fluidAmbientPhase",
    )
    val backdrop = rememberLayerBackdrop {
        drawRect(screenBackground)
        drawContent()
        if (ambientEnabled) {
            // Keep the ambient layer inside the recorded backdrop so every glass
            // surface refracts the drifting blue/violet light, not just the page.
            val blueCenter = Offset(
                x = size.width * (0.84f - 0.06f * ambientPhase.value),
                y = size.height * (0.14f + 0.04f * ambientPhase.value),
            )
            val violetCenter = Offset(
                x = size.width * (0.14f + 0.05f * ambientPhase.value),
                y = size.height * (0.82f - 0.05f * ambientPhase.value),
            )
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFFB9D8FF).copy(alpha = 0.20f), Color.Transparent),
                    center = blueCenter,
                    radius = size.maxDimension * 0.72f,
                ),
            )
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFFD8C7FF).copy(alpha = 0.17f), Color.Transparent),
                    center = violetCenter,
                    radius = size.maxDimension * 0.66f,
                ),
            )
        }
    }
    val navInset = with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() } +
        Dims.navBarHeight + Dims.navFloatMargin
    GlassModalHost(
        modifier = Modifier
            .fillMaxSize()
            .testTag("three-pane"),
    ) {
        CompositionLocalProvider(
            LocalGlassBackdrop provides backdrop,
            LocalFloatingNavInset provides navInset,
        ) {
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 0,
                pageNestedScrollConnection = tabPagerNestedScroll,
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop),
            ) { page ->
                when (ThreePane.entries[page]) {
                    ThreePane.Favorites -> FavoritesPane(
                        viewModel = workspaceViewModel,
                        isActive = navState.homePane == ThreePane.Favorites && navState.activeSession == null,
                        connectionPath = ServiceWire.connectionPath(),
                        onOpenSession = { ref, name -> navState.openSession(ref, name) },
                    )
                    ThreePane.Sessions -> WorkspaceScreen(
                        viewModel = workspaceViewModel,
                        isActive = navState.homePane == ThreePane.Sessions && navState.activeSession == null,
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
            AppBottomNav(
                selected = navState.homePane.toNavTab(),
                onSelect = { tab ->
                    val pane = tab.toPane()
                    navState.homePane = pane
                    navState.showSettings = pane == ThreePane.Settings
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun FavoritesPane(
    viewModel: WorkspaceViewModel,
    isActive: Boolean,
    connectionPath: ConnectionPath?,
    onOpenSession: (ref: String, name: String) -> Unit,
) {
    val favorites by viewModel.favorites.collectAsState()
    val liveGen by viewModel.favoriteLiveGen.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    // 底栏收藏页：全量 favoriteRows()。失联行仍在，标「不在线」，短按不打开。
    // 不要在这里 filter isOnline。会话列表 / 会话页收藏入口各走自己的投影。
    val rows = remember(favorites, liveGen) {
        viewModel.favoriteRows()
    }
    DisposableEffect(viewModel, isActive) {
        val lease = if (isActive) viewModel.enterFavorites() else null
        onDispose { if (lease != null) viewModel.leaveFavorites(lease) }
    }
    LaunchedEffect(viewModel, isActive) {
        if (!isActive) return@LaunchedEffect
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
