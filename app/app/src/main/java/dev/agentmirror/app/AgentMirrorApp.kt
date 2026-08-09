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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.agentmirror.app.pairing.PairingConfigStore
import dev.agentmirror.app.pairing.PairingRoute
import dev.agentmirror.app.pairing.SharedPreferencesPairingConfigStore
import dev.agentmirror.app.session.SessionRoute
import dev.agentmirror.app.ui.theme.AgentMirrorTheme
import dev.agentmirror.app.workspace.WorkspaceScreen
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
 * 导航态（activeSession/showPairing）由 [navState]（D-3 修复）注入：MainActivity 在
 * onSaveInstanceState 持久化、重建恢复，深链/旋转都不丢导航位置（审计 D-2/D-3）。
 */
@Composable
fun AgentMirrorApp(
    navState: MainNavState,
) {
    AgentMirrorTheme {
        val context = LocalContext.current
        // 配对配置存储（SharedPreferences）：首启判定 + 重配入口共用。
        val configStore = remember { SharedPreferencesPairingConfigStore(context) }
        // 根级 ViewModel：接线层（service 任务）将把 ConnectionManager 回调接进来。
        val viewModel = remember { WorkspaceViewModel() }
        val session = navState.activeSession

        when {
            // 会话页优先（在屏会话不被重配打断）。
            session != null -> SessionRoute(ref = session.first, name = session.second) {
                navState.activeSession = null
            }
            // 配对页：首启无配置，或用户从设置/重配入口进入。
            navState.showPairing -> PairingRoute(
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
            // 工作区：有配置直进；"重新配对"从设置入口进入（见 WorkspaceScreen 顶栏设置钮）。
            else -> WorkspaceScreen(
                viewModel = viewModel,
                onOpenSession = { ref, name -> navState.activeSession = ref to name },
            )
        }
    }
}
