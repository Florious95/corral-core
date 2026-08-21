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

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.agentmirror.app.ui.theme.AgentMirrorTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 契约 092 §2：Provider 是带图标的卡片，不是裸 RadioButton；bypass 有说明；确认钮在底。
 * argv 组装仍由 [buildNewAgentArgv] 覆盖，本测只断言选择器 UI。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NewAgentDialogUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun providerCardsUseIconsNotRadioGlyphs_bypassHintAndConfirmAtBottom() {
        var picked = "claude_code"
        compose.setContent {
            AgentMirrorTheme {
                var provider by remember { mutableStateOf("claude_code") }
                NewAgentDialog(
                    ui = WorkspaceViewModel.NewAgentUi(
                        cwds = listOf("/iso"),
                        cwd = "/iso",
                        providerId = provider,
                    ),
                    onSelectCwd = {},
                    onSelectProvider = {
                        provider = it
                        picked = it
                    },
                    onToggleBypass = {},
                    onConfirm = {},
                    onCancel = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("○ Claude Code").assertDoesNotExist()
        compose.onNodeWithText("● Claude Code").assertDoesNotExist()
        NewAgentProviders.ids.forEach { id ->
            compose.onNodeWithTag("new-agent-provider-$id").assertIsDisplayed()
            compose.onNodeWithContentDescription(NewAgentProviders.displayName(id))
                .assertIsDisplayed()
        }
        compose.onNodeWithTag("new-agent-bypass-hint", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(NEW_AGENT_BYPASS_HINT, useUnmergedTree = true).assertIsDisplayed()

        val grok = compose.onNodeWithTag("new-agent-provider-grok").getUnclippedBoundsInRoot()
        val ok = compose.onNodeWithTag("new-agent-ok").getUnclippedBoundsInRoot()
        assertTrue(
            "确认钮必须置底：ok.top=${ok.top} grok.bottom=${grok.bottom}",
            ok.top >= grok.bottom,
        )

        compose.onNodeWithTag("new-agent-provider-grok").performClick()
        compose.waitForIdle()
        assertEquals("grok", picked)
    }
}
