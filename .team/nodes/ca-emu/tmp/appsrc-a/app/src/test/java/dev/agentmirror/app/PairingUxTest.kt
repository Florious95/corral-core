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
import dev.agentmirror.app.pairing.PairingConfig
import dev.agentmirror.app.pairing.PairingConfigStore
import dev.agentmirror.app.pairing.PairingScreen
import dev.agentmirror.app.pairing.PairingViewModel
import dev.agentmirror.app.workspace.WorkspaceViewModel
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
