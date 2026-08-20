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

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.agentmirror.app.pairing.PairingRoute
import dev.agentmirror.app.pairing.SharedPreferencesPairingConfigStore
import dev.agentmirror.app.service.OnScreenFallbackPump
import dev.agentmirror.app.service.ServiceWire
import dev.agentmirror.app.session.SessionRoute
import dev.agentmirror.app.ui.components.NavDirection
import dev.agentmirror.app.ui.components.navTransition
import dev.agentmirror.app.ui.theme.AgentMirrorTheme
import dev.agentmirror.app.ui.theme.AppTheme
import dev.agentmirror.app.ui.theme.Appearance
import dev.agentmirror.app.ui.theme.SharedPreferencesAppearanceStore
import dev.agentmirror.app.workspace.WorkspaceViewModel

/**
 * Compose 应用根组合。
 *
 * 依需求 004「客户端无状态」，本组件只做路由，不持有任何业务状态。
 * 首启路由（pairing-ui 知识基底 §1）：
 * - 无配对配置 → 配对页（扫码/手填，可跳过进空工作区）；
 * - 有配对配置 → 直进工作区列表；
 * - 配对页可从设置/重配入口重进（重新配对）。
 * 会话页跳转沿用 session-ui 挂载的 [SessionRoute]。
 *
 * 导航态由 [navState]（D-3 修复）注入：MainActivity 在
 * onSaveInstanceState 持久化、重建恢复，深链/旋转都不丢导航位置（审计 D-2/D-3）。
 *
 * 工作区 VM（[workspaceViewModel]）由 MainActivity 持有（fix-workspace-wiring 修复，
 * navState 同模式提升），本组件只负责在工作区分支用 [DisposableEffect] 把它接入
 * [ServiceWire.uiConnector]——配对成功后列表能渲染（此前 VM 裸建从未接线，uiConnector
 * 全仓无调用点，见 fix-workspace-wiring 知识基底）。
 *
 * @consumes dev.agentmirror.app.pairing
 * @consumes dev.agentmirror.app.service
 * @consumes dev.agentmirror.app.session
 * @consumes dev.agentmirror.app.termview
 * @consumes dev.agentmirror.app.ui.theme
 * @consumes dev.agentmirror.app.workspace
 */
@Composable
fun AgentMirrorApp(
    navState: MainNavState,
    workspaceViewModel: WorkspaceViewModel,
) {
    val context = LocalContext.current
    val appearanceStore = remember { SharedPreferencesAppearanceStore(context) }
    var appearance by remember { mutableStateOf(appearanceStore.load()) }
    val darkTheme = when (appearance) {
        Appearance.Light -> false
        Appearance.Dark -> true
        Appearance.System -> isSystemInDarkTheme()
    }
    AppTheme(appearance = appearance) {
    AgentMirrorTheme(darkTheme = darkTheme) {
        // 在屏兜底时钟泵（fix-app-runtime-sa）：任一屏在屏且 App RESUMED 即挂一个兜底泵，
        // 前台服务泵不可用时接管共享连接的重连调度与输入超时裁决，服务恢复即让出（不双泵）。
        // 挂在根组合保证工作区/会话/设置/配对任一屏在屏都有兜底；服务常驻时兜底泵零工作。
        OnScreenFallbackPump()
        // 工作区 VM 常挂 listConnector：进会话后 uiConnector 被会话 VM 占用，
        // 二级推送仍要进列表数据源（083 §10 顶栏灯与列表同一套）。
        DisposableEffect(workspaceViewModel) {
            ServiceWire.listConnector = workspaceViewModel
            onDispose {
                if (ServiceWire.listConnector === workspaceViewModel) {
                    ServiceWire.listConnector = null
                }
            }
        }
        // 配对配置存储（SharedPreferences）：首启判定 + 重配入口共用。
        val configStore = remember { SharedPreferencesPairingConfigStore(context) }
        val session = navState.activeSession
        val overlayLevel2 by workspaceViewModel.level2.collectAsState()
        val overlayFavorites by workspaceViewModel.favorites.collectAsState()

        /**
         * 根返回手势接线（D-23/D-32）。
         * @contract
         * @pre [navState] 是当前根路由唯一导航事实源
         * @post 非配对根时把一次系统返回交给 [MainNavState.onSystemBack]；配对根禁用处理器
         * @err none
         * @inv 返回只经导航壳逐级裁决；本层不直接跳过会话选择/工作区列表层级
         */
        BackHandler(
            enabled = session != null || navState.selectedWorkspaceCwd != null ||
                navState.showSettings || !navState.showPairing,
        ) {
            navState.onSystemBack()
        }

        // 路由描述值（AnimatedContent 的转场键）：四分支互斥，与原 when 语义一一对应。
        val route: AppRoute = when {
            session != null -> AppRoute.Session(ref = session.first, name = session.second)
            navState.showPairing -> AppRoute.Pairing
            else -> AppRoute.Workspace
        }

        // 页面转场（018 §一.6）：淡入 + 轻微缩放进场（无方向性——三路由无严格层级栈，
        // 方向滑动会在 深链/重配 等非线性跳转下语义错乱）。转场期间新旧屏短暂共存：
        // uiConnector 挂载安全性依赖两处 DisposableEffect 的同 VM 身份守卫（见下），
        // 新屏先挂新 VM、旧屏 onDispose 发现已非自己则不复位——不误伤。
        AnimatedContent(
            targetState = route,
            transitionSpec = {
                val dir = when {
                    initialState !is AppRoute.Session && targetState is AppRoute.Session ->
                        NavDirection.Push
                    initialState is AppRoute.Session && targetState !is AppRoute.Session ->
                        NavDirection.Pop
                    else -> NavDirection.FadeThrough
                }
                navTransition(dir)
            },
            label = "app-route",
        ) { r ->
            when (r) {
                // 会话页优先（在屏会话不被重配打断）。
                is AppRoute.Session -> SessionRoute(
                    ref = r.ref,
                    name = r.name,
                    connectionPath = ServiceWire.connectionPath(),
                    onBack = { navState.activeSession = null },
                    overlaySessions = remember(r.ref, overlayLevel2, overlayFavorites) {
                        workspaceViewModel.viewMenuSource(r.ref).sessions
                    },
                    overlayFavorited = overlayFavorites.map { it.key }.toSet(),
                    onToggleOverlayFavorite = { workspaceViewModel.toggleFavorite(it) },
                    onOpenOverlaySession = { ref, name -> navState.activeSession = ref to name },
                )
                // 配对页：首启无配置，或用户从设置/重配入口进入。
                AppRoute.Pairing -> PairingRoute(
                    configStore = configStore,
                    onPaired = {
                        // 配对成功：配置已落库 + ServiceWire 注入（见 PairingRoute），切工作区。
                        navState.showPairing = false
                    },
                    onSkip = {
                        // 首启跳过：进空工作区（连接未配置 → 工作区顶栏显示连接中/重配入口）。
                        navState.showPairing = false
                    },
                )
                // 三栏主页：底部标签 收藏 / 会话 / 设置（067 §4.1）。设置不再盖一层。
                AppRoute.Workspace -> {
                    // 接线（fix-workspace-wiring）：把 Activity 持有的工作区 VM 接入
                    // ServiceWire.uiConnector 扇出。配对成功切工作区后，conn 层 READY + listing /
                    // list_delta 经此桥进入 VM，列表才渲染（此前 VM 裸建从未接线，uiConnector 全仓
                    // 无调用点，配对成功列表永不显示）。DisposableEffect 同构 SessionRoute：挂载注册 /
                    // 离屏复位，防重复注册与泄漏；断连重挂由 READY+全量 listing 恢复（004 无状态）。
                    DisposableEffect(workspaceViewModel) {
                        ServiceWire.uiConnector = workspaceViewModel
                        onDispose {
                            // 只在仍是本 VM 时复位：避免复位掉新挂载的 VM（防重复注册竞态）。
                            if (ServiceWire.uiConnector === workspaceViewModel) {
                                ServiceWire.uiConnector = null
                            }
                        }
                    }
                    ThreePaneHome(
                        navState = navState,
                        workspaceViewModel = workspaceViewModel,
                        appearance = appearance,
                        onAppearanceChange = {
                            appearance = it
                            appearanceStore.save(it)
                        },
                    )
                }
            }
        }
    }
    }
}

/** 根路由四分支的转场键（data class 让同名不同 ref 的会话切换也触发转场）。 */
private sealed interface AppRoute {
    data class Session(val ref: String, val name: String) : AppRoute
    data object Pairing : AppRoute
    data object Workspace : AppRoute
}
