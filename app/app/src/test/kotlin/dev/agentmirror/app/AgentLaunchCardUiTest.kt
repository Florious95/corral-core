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

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import dev.agentmirror.app.ui.components.providerDisplayName
import dev.agentmirror.app.ui.theme.AppTheme
import dev.agentmirror.app.ui.theme.Appearance
import dev.agentmirror.app.workspace.ProviderIds
import dev.agentmirror.app.workspace.ProviderLaunchDefaults
import dev.agentmirror.app.workspace.SharedPreferencesProviderLaunchStore
import dev.agentmirror.app.workspace.buildArgv
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 契约 092 §3：设置页每个 Provider 带卡通图标、等宽多行命令、恢复默认。
 * argv 仍由 [buildArgv] 组装（088 §7 原样下发）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentLaunchCardUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun clearLaunchPrefs() {
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("provider_launch", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun eachProviderHasIconAndReset_editKeepsArgv_resetRestoresDefault() {
        compose.setContent {
            AppTheme(appearance = Appearance.Light) {
                SettingsScreen(onBack = {}, onRePair = {})
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("settings-launch").performScrollTo()
        compose.waitForIdle()

        compose.onNodeWithTag("settings-launch").assertExists()
        compose.onAllNodesWithText("恢复默认").assertCountEquals(6)

        ProviderIds.forEach { id ->
            compose.onNodeWithTag("settings-launch-row-$id").assertExists()
            compose.onNodeWithTag("settings-launch-command-$id").assertExists()
            compose.onNodeWithTag("settings-launch-bypass-$id").assertExists()
            compose.onNodeWithTag("settings-launch-reset-$id").assertExists()
            compose.onNodeWithContentDescription(providerDisplayName(id)).assertExists()
        }

        val grokCmd = compose.onNodeWithTag("settings-launch-command-grok")
        grokCmd.performScrollTo()
        grokCmd.performTextReplacement("grok-local --model x")
        compose.waitForIdle()

        val stored = SharedPreferencesProviderLaunchStore(RuntimeEnvironment.getApplication()).load()
        val grok = stored.single { it.providerId == "grok" }
        assertEquals("grok-local --model x", grok.command)
        assertEquals(
            listOf("grok-local", "--model", "x", "--always-approve"),
            buildArgv(grok, bypass = true),
        )
        assertEquals(listOf("grok-local", "--model", "x"), buildArgv(grok, bypass = false))

        compose.onNodeWithTag("settings-launch-reset-grok").performScrollTo()
        compose.onNodeWithTag("settings-launch-reset-grok").performClick()
        compose.waitForIdle()

        val after = SharedPreferencesProviderLaunchStore(RuntimeEnvironment.getApplication()).load()
        val grokAfter = after.single { it.providerId == "grok" }
        assertEquals(ProviderLaunchDefaults.byId("grok"), grokAfter)
        assertEquals(
            listOf("grok", "--always-approve"),
            buildArgv(grokAfter, bypass = true),
        )
    }

    @Test
    fun commandFieldAcceptsMultiline() {
        compose.setContent {
            AppTheme(appearance = Appearance.Light) {
                SettingsScreen(onBack = {}, onRePair = {})
            }
        }
        compose.waitForIdle()
        val field = compose.onNodeWithTag("settings-launch-command-claude_code")
        field.performScrollTo()
        field.performTextReplacement("claude-local\n--resume")
        compose.waitForIdle()
        val stored = SharedPreferencesProviderLaunchStore(RuntimeEnvironment.getApplication()).load()
        val claude = stored.single { it.providerId == "claude_code" }
        assertEquals("claude-local\n--resume", claude.command)
        assertEquals(listOf("claude-local", "--resume"), buildArgv(claude, bypass = false))
    }
}
