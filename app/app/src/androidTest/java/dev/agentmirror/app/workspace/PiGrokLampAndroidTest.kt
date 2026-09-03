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

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dev.agentmirror.app.ui.components.SessionRow
import dev.agentmirror.app.ui.model.SessionItem
import dev.agentmirror.app.ui.model.SessionStatus
import dev.agentmirror.app.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PiGrokLampAndroidTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun piWorkingBusyDotCrossesFramesIdleStaticUnknownAbnormalQuietAndGrokIsNotX() {
        rule.setContent {
            AppTheme {
                Column {
                    SessionRow(row("pi-w", SessionStatus.Busy, "pi", "normal", "Pi Working"), "l2", {}, {}, false)
                    SessionRow(row("pi-i", SessionStatus.Idle, "pi", "normal", "Pi Idle"), "l2", {}, {}, false)
                    SessionRow(row("pi-u", SessionStatus.Unknown, "pi", "unknown", "Pi Unknown"), "l2", {}, {}, false)
                    SessionRow(row("pi-a", SessionStatus.Busy, "pi", "abnormal", "Pi Abnormal"), "l2", {}, {}, false)
                    SessionRow(row("grok-n", SessionStatus.Idle, "grok", "normal", "Grok Idle"), "l2", {}, {}, false)
                }
            }
        }
        fun motion(id: String): String {
            val node = rule.onNodeWithTag("l2-motion-$id", useUnmergedTree = true).fetchSemanticsNode()
            return node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
                .joinToString()
        }
        val w0 = motion("pi-w")
        assertTrue("pi working lamp is working:* got=$w0", w0.startsWith("working:"))
        assertEquals("idle:static", motion("pi-i"))
        assertTrue(motion("pi-u").isEmpty())
        assertTrue(motion("pi-a").isEmpty())
        rule.onNodeWithTag("l2-provider-grok-n", useUnmergedTree = true).assertExists()
        val grok = rule.onNodeWithTag("l2-provider-grok-n", useUnmergedTree = true)
            .fetchSemanticsNode()
            .config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
            .joinToString()
        assertEquals("Grok", grok)
        assertTrue(!grok.contains("X"))
    }

    private fun row(
        id: String,
        status: SessionStatus,
        provider: String,
        health: String,
        name: String,
    ) = SessionItem(
        id = id,
        displayName = name,
        path = "/ws/$id",
        status = status,
        starred = false,
        isOnline = true,
        provider = provider,
        health = health,
    )
}
