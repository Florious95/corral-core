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

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.agentmirror.app.pairing.HostCandidate
import dev.agentmirror.app.pairing.HostEndpoint
import dev.agentmirror.app.pairing.HostEndpointSource
import dev.agentmirror.app.pairing.PairingConfig
import dev.agentmirror.app.pairing.PairingConfigStore
import dev.agentmirror.app.pairing.PairingScreen
import dev.agentmirror.app.pairing.PairingViewModel
import dev.agentmirror.app.tsnet.ConnectionPath
import dev.agentmirror.app.workspace.WorkspaceViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Dogfood regressions: pairing credentials stay hidden and re-pair remains reachable. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PairingUxTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun lanScanningState_showsScanningGuidance_andRemovesMisleadingTailscalePrompt() {
        val vm = PairingViewModel(
            configStore = MemoryConfigStore(),
            connectionFactory = { error("no connect") },
        )
        vm.beginLanDiscovery()

        compose.setContent {
            PairingScreen(viewModel = vm, onPaired = {}, onSkip = {})
        }
        compose.waitForIdle()

        compose.onNodeWithText("正在扫描附近局域网主机…").assertExists()
        compose.onAllNodesWithText("可选填 Tailscale auth key 后自动发现主机。").assertCountEquals(0)
        compose.onAllNodesWithText("绑定主机").assertCountEquals(0)
    }

    @Test
    fun discoveredHost_rendersHumanName_hostId_andLanBadge_andExpandsOnSelection() {
        val vm = PairingViewModel(
            configStore = MemoryConfigStore(),
            connectionFactory = { error("no connect") },
        )
        val endpoint = HostEndpoint("192.168.31.116", 9900, ConnectionPath.LAN, HostEndpointSource.NSD)
        vm.addDiscoveredHost(HostCandidate("host-mac-001", "MacBook-Pro.local", listOf(endpoint)))

        compose.setContent {
            PairingScreen(viewModel = vm, onPaired = {}, onSkip = {})
        }
        compose.waitForIdle()

        compose.onNodeWithText("MacBook-Pro.local").assertExists()
        compose.onNodeWithText("ID: host-mac-001").assertExists()
        compose.onNodeWithText("LAN").assertExists()
        // 未选中时不展示输入 Token
        compose.onAllNodesWithText("请输入此电脑显示的配对 Token").assertCountEquals(0)

        // 点击卡片展开
        compose.onNodeWithText("MacBook-Pro.local").performClick()
        compose.waitForIdle()

        assertEquals("host-mac-001", vm.selectedHostId)
        compose.onNodeWithText("请输入此电脑显示的配对 Token").assertExists()
        compose.onAllNodesWithText("连接主机").assertCountEquals(2) // 顶栏标题 + 卡片按钮
    }

    @Test
    fun manualDirectConnect_rendersDirectInputs() {
        val vm = PairingViewModel(
            configStore = MemoryConfigStore(),
            connectionFactory = { error("no connect") },
        )

        compose.setContent {
            PairingScreen(viewModel = vm, onPaired = {}, onSkip = {})
        }
        compose.waitForIdle()

        compose.onNodeWithText("手动直连").assertExists()
        compose.onNodeWithText("局域网主机地址（IP:端口）").assertExists()
        compose.onNodeWithText("连接").assertExists()
    }

    @Test
    fun manualToken_isAbsentFromVisibleTextNodes() {
        val vm = PairingViewModel(
            configStore = MemoryConfigStore(),
            connectionFactory = { error("connection must not start while only editing") },
        )
        vm.manualToken = "visible-secret-sentinel"

        compose.setContent {
            PairingScreen(viewModel = vm, onPaired = {}, onSkip = {})
        }
        compose.waitForIdle()

        val visibleTextContainsToken = SemanticsMatcher("visible text contains token") { node ->
            val text = node.config.getOrNull(SemanticsProperties.Text).orEmpty()
            val editableText = node.config.getOrNull(SemanticsProperties.EditableText)
            text.any { it.text.contains("visible-secret-sentinel") } ||
                editableText?.text?.contains("visible-secret-sentinel") == true
        }
        compose.onAllNodes(visibleTextContainsToken)
            .assertCountEquals(0)
    }

    @Test
    fun workspaceSettings_reachesSingleProfileRepair() {
        val nav = MainNavState(initialShowPairing = false)
        compose.setContent {
            AgentMirrorApp(navState = nav, workspaceViewModel = WorkspaceViewModel())
        }

        compose.onAllNodesWithText("设置").onFirst().performClick()
        compose.onNodeWithText("重新配对").assertExists().performClick()
        compose.waitForIdle()

        assertTrue("重新配对入口必须切到配对页", nav.showPairing)
    }

    private class MemoryConfigStore : PairingConfigStore {
        override fun load(): PairingConfig? = null
        override fun save(config: PairingConfig) = Unit
        override fun clear() = Unit
    }
}
