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

import androidx.compose.ui.test.junit4.createComposeRule
import dev.agentmirror.app.AgentMirrorApp
import dev.agentmirror.app.MainNavState
import dev.agentmirror.app.conn.ConnectionState
import dev.agentmirror.app.conn.ListingFrame
import dev.agentmirror.app.conn.Workspace
import dev.agentmirror.app.service.ServiceWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 工作区接线层 Compose 测试（fix-workspace-wiring 验收 `--tests "*Workspace*"`）。
 *
 * 红测先行：修复前 AgentMirrorApp 裸建 WorkspaceViewModel 从未挂 ServiceWire.uiConnector，
 * 配对成功切工作区后 VM 收不到 READY/list_delta，列表永不显示（T1 真机阻断）。
 *
 * 驱动方式：直接渲染 [AgentMirrorApp]（createComposeRule，w-test-appseams 引入的 ui-test
 * 基建），而非真实 MainActivity——本层断言的是 Compose 副作用（DisposableEffect 挂载/
 * 卸载 uiConnector），createComposeRule 的 waitForIdle/runOnUiThread 可靠驱动重组与
 * onDispose；真实 Activity + 普通 idle() 不保证推进 Compose 重组帧，会导致离屏复位断言
 * 不稳（leave 场景实测红）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkspaceWiringTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** 直进工作区：showPairing=false + activeSession=null → 工作区分支在屏。 */
    private fun navState() = MainNavState(initialShowPairing = false)

    /** 含一个工作区的 listing（缺陷现场：app 已收到 listing 但渲染不出）。 */
    private fun listing(): ListingFrame = ListingFrame(
        reqId = 1,
        seq = 42,
        workspaces = listOf(
            Workspace(
                cwd = "/proj/a",
                sessionCount = 1,
            ),
        ),
    )

    @Test
    fun workspaceViewModel_isWiredToUiConnector() {
        // 红测：修复前 uiConnector 恒 null（VM 裸建从未接线）→ 断言失败。
        val vm = WorkspaceViewModel()
        composeRule.setContent { AgentMirrorApp(navState = navState(), workspaceViewModel = vm) }
        composeRule.waitForIdle()

        assertSame("工作区 VM 必须接入 ServiceWire.uiConnector（配对后列表渲染前提）", vm, ServiceWire.uiConnector)
    }

    @Test
    fun uiConnector_feedsReadyAndListing_intoListState() {
        // 红测：修复前 uiConnector 为 null，取 connector 即空指针失败（缺陷现场：
        // 配对成功 → 收不到 READY+listing → 永远"连接中…"空白）。
        val vm = WorkspaceViewModel()
        composeRule.setContent { AgentMirrorApp(navState = navState(), workspaceViewModel = vm) }
        composeRule.waitForIdle()

        val connector = ServiceWire.uiConnector
        assertNotNull("工作区 VM 未接线：uiConnector 为空", connector)

        // 经 uiConnector 扇出投递（正是 ServiceWire 把 conn 层回调原样转投的路径）：
        // READY + 含真实会话的 listing → 工作区 VM 必须进入列表渲染状态。
        connector!!.onStateChanged(ConnectionState.READY)
        connector.onFrame(listing())

        val s = vm.uiState.value
        assertEquals(ConnectionUi.READY, s.connection)
        assertEquals(1, s.workspaces.size)
        assertEquals("/proj/a", s.workspaces.single().cwd)
        assertEquals(1, s.workspaces.single().sessionCount)
    }

    @Test
    fun leavingWorkspace_unwiresUiConnector() {
        // 防泄漏守卫：离开工作区（进会话页）→ DisposableEffect onDispose 复位 uiConnector，
        // 避免旧 VM 残留占槽（断连重挂由 READY+全量 listing 恢复，004 语义）。
        val nav = navState()
        val vm = WorkspaceViewModel()
        composeRule.setContent { AgentMirrorApp(navState = nav, workspaceViewModel = vm) }
        composeRule.waitForIdle()
        assertSame("前置：工作区在屏时已接线", vm, ServiceWire.uiConnector)

        // 切到会话页：activeSession 变更 → 重组 → 工作区分支离开组合 → onDispose 复位。
        composeRule.runOnUiThread { nav.activeSession = "ref-A" to "Agent A" }
        composeRule.waitForIdle()
        assertNull("离开工作区后 uiConnector 必须复位（防泄漏）", ServiceWire.uiConnector)
    }
}
