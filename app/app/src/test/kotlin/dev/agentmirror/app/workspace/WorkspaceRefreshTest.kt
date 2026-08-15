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

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import dev.agentmirror.app.conn.AgentState
import dev.agentmirror.app.conn.ConnectionState
import dev.agentmirror.app.conn.ListingFrame
import dev.agentmirror.app.conn.Session
import dev.agentmirror.app.conn.Workspace
import dev.agentmirror.app.ui.theme.AgentMirrorTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 刷新模型 Compose 层红测（ledger.refresh-and-contract.v2 t.refresh-impl）。
 *
 * 锁两条 UI 不变量（用户 2026-08-15 裁定）：
 * - 进入屏即触发一次刷新（LaunchedEffect(…refreshTrigger…) 消费 → requestList）；
 * - 手指下滑（下拉手势）触发刷新（PullToRefreshBox onRefresh → requestList）。
 *
 * 计数断言用注入的 requestList spy（不经真实 manager/网络）。Robolectric JVM 渲染，
 * 不起模拟器（第 2 层暂停，用户真机为权威验收）。
 *
 * 红测先行：修复前 WorkspaceScreen 无刷新入口 → 进入组合不触发、无下拉容器 → 断言红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkspaceRefreshTest {

    @get:Rule
    val compose = createComposeRule()

    private fun listing(): ListingFrame = ListingFrame(
        reqId = 1,
        seq = 42,
        workspaces = listOf(
            Workspace(
                cwd = "/proj/a",
                sessionCount = 1,
                aggregateState = AgentState.WORKING,
                sessions = listOf(
                    Session(ref = "s1", name = "claude", cwd = "/proj/a", state = AgentState.WORKING, rows = 24, cols = 80),
                ),
            ),
        ),
    )

    /** 渲染一级列表并喂入 READY+listing；返回刷新请求计数闭包。 */
    private fun renderWorkspace(): () -> Int {
        var requested = 0
        val vm = WorkspaceViewModel(requestList = { requested++ })
        vm.onConnectionStateChanged(ConnectionState.READY)
        vm.onFrame(listing())

        compose.setContent {
            AgentMirrorTheme {
                WorkspaceScreen(
                    viewModel = vm,
                    selectedWorkspaceCwd = null,
                    onSelectWorkspace = {},
                    onBackToList = {},
                    onOpenSession = { _, _ -> },
                    onOpenSettings = {},
                )
            }
        }
        compose.waitForIdle()

        // 进入即刷已触发（requested≥1），刷新在途标记被置位；再喂一份 listing 让它复位——
        // 下拉刷新测试需要 isRefreshing=false（PullToRefreshBox 在刷新在途时不再触发 onRefresh，
        // 这正是"刷新中不重复刷"的产品语义，见 WorkspaceViewModel.refresh KDoc）。
        vm.onFrame(listing())
        compose.waitForIdle()
        return { requested }
    }

    @Test
    fun enteringWorkspace_triggersOneRefresh() {
        val requested = renderWorkspace()
        // 进入一级即自动刷一次全量列表（用户：每次到一级自动刷一遍拉最新）。
        assertTrue(
            "进入工作区列表必须自动触发一次刷新，实际计数=${requested()}",
            requested() >= 1,
        )
    }

    @Test
    fun pullDown_triggersRefresh() {
        val requested = renderWorkspace()

        val before = requested()
        // 手指下滑：触发一次刷新（下拉容器 onRefresh → requestList）。下拉刷新手势通过
        // 嵌套滚动分发位移；需要缓慢、持续、大幅超过 80dp 阈值（Robolectric 密度 1x，
        // 80dp ≈ 80px）。分多步慢拖 480px 保证越过阈值并让 onPostScroll 判定 onRefresh。
        compose.onNodeWithTag("workspace-pull-refresh").performTouchInput {
            down(center)
            repeat(12) {
                moveBy(Offset(0f, 40f), delayMillis = 32)
            }
            up()
        }
        compose.waitForIdle()

        assertTrue(
            "手指下滑必须触发一次刷新（下拉前=$before，下拉后=${requested()}）",
            requested() > before,
        )
    }
}
