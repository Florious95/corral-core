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
import androidx.compose.ui.test.onNodeWithText
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
    fun sixProviderMarksAndPiWorkingPulseCycleAreExactAndFailClosed() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            AppTheme {
                Column {
                    SessionRow(row("claude", SessionStatus.Idle, "claude_code", "normal", "Claude"), "l2", {}, {}, false)
                    SessionRow(row("codex", SessionStatus.Idle, "codex", "normal", "Codex"), "l2", {}, {}, false)
                    SessionRow(row("copilot", SessionStatus.Idle, "copilot", "normal", "Copilot"), "l2", {}, {}, false)
                    SessionRow(row("grok", SessionStatus.Idle, "grok", "normal", "Grok"), "l2", {}, {}, false)
                    SessionRow(row("cursor", SessionStatus.Idle, "cursor", "normal", "Cursor"), "l2", {}, {}, false)
                    SessionRow(row("pi-w", SessionStatus.Busy, "pi", "normal", "pi-real-session"), "l2", {}, {}, false)
                    SessionRow(row("pi-i", SessionStatus.Idle, "pi", "normal", "Pi Idle"), "l2", {}, {}, false)
                    SessionRow(row("pi-u", SessionStatus.Unknown, "pi", "unknown", "Pi Unknown"), "l2", {}, {}, false)
                    SessionRow(row("pi-a", SessionStatus.Busy, "pi", "abnormal", "Pi Abnormal"), "l2", {}, {}, false)
                }
            }
        }
        fun motion(id: String): String {
            val node = rule.onNodeWithTag("l2-motion-$id", useUnmergedTree = true).fetchSemanticsNode()
            return node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
                .joinToString()
        }
        val w0 = motion("pi-w")
        assertTrue("pi working lamp is working:* got=$w0", w0.startsWith("working:glyph=•:"))
        assertEquals("working:glyph=•:elapsed=0:position=0:intensity=0.0", w0)
        assertEquals("idle:static", motion("pi-i"))
        assertTrue(motion("pi-u").isEmpty())
        assertTrue(motion("pi-a").isEmpty())
        listOf("claude", "codex", "copilot", "grok", "cursor", "pi-w").forEach { id ->
            rule.onNodeWithTag("l2-provider-$id", useUnmergedTree = true).assertExists()
        }
        rule.onNodeWithText("pi-real-session").assertExists()

        val frames = buildList {
            add(w0)
            repeat(62) {
                rule.mainClock.advanceTimeBy(32)
                add(motion("pi-w"))
            }
        }
        val elapsed = frames.map { field(it, "elapsed") }
        assertEquals((0..1984 step 32).toList(), elapsed)
        val positions = frames.map { field(it, "position") }
        assertEquals((0..1984 step 32).map { it * 21 / 2000 }, positions)
        assertEquals(21, positions.toSet().size)
        assertEquals(0, positions.first())
        assertEquals(20, positions.last())
        assertTrue(frames.any { it.contains(":intensity=1.0") })

        rule.mainClock.advanceTimeBy(16)
        assertEquals("working:glyph=•:elapsed=0:position=0:intensity=0.0", motion("pi-w"))
        assertEquals("idle:static", motion("pi-i"))
        assertTrue(motion("pi-u").isEmpty())
        assertTrue(motion("pi-a").isEmpty())
    }

    private fun field(description: String, name: String): Int =
        description.substringAfter(":$name=").substringBefore(":").toInt()

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
