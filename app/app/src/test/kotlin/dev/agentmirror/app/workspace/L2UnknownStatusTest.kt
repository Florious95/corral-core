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
import androidx.compose.ui.test.onNodeWithText
import dev.agentmirror.app.conn.Level2Frame
import dev.agentmirror.app.conn.Session
import dev.agentmirror.app.ui.theme.AgentMirrorTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 061：状态为未知时就显示「未知」，不许显示成空闲。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class L2UnknownStatusTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun unknownWireStatusRendersUnknownNotIdleEvenIfTitleLooksIdle() {
        val vm = WorkspaceViewModel(
            requestList = {},
            subscribeLevel2 = {},
            unsubscribeLevel2 = {},
        )
        vm.enterLevel2("/proj/a")
        vm.onFrame(
            Level2Frame(
                workspace = "/proj/a",
                seq = 1,
                sessions = listOf(
                    Session(
                        ref = "ref-unk",
                        name = "sess-unk",
                        cwd = "/proj/a",
                        rows = 24,
                        cols = 80,
                        title = "✳  looks-idle-but-status-is-unknown",
                        status = "unknown",
                        sessionName = "sess-unk",
                        windowIndex = "3",
                        windowName = "sess-unk",
                    ),
                ),
            ),
        )

        val entry = vm.level2.value.sessions.single()
        assertEquals(L2Status.UNKNOWN, entry.status)
        assertNotEquals(L2Status.IDLE, entry.status)
        assertEquals("✳  looks-idle-but-status-is-unknown", entry.title)

        compose.setContent {
            AgentMirrorTheme {
                L2SessionList(
                    sessions = vm.level2.value.sessions,
                    onOpenSession = { _, _ -> },
                )
            }
        }
        compose.onNodeWithText("未知").assertExists("unknown 必须显示「未知」")
        compose.onNodeWithText("空闲").assertDoesNotExist()
    }

    @Test
    fun fourAxesCarryThroughAndDivergenceFailsClosed() {
        val good = Session(
            ref = "r", name = "n", cwd = "/w", rows = 24, cols = 80,
            provider = "pi", activity = "working", status = "working",
            sessionName = null, health = "normal", title = "✳ must-not-infer",
        ).toL2Entry()
        assertEquals("pi", good.provider)
        assertEquals(L2Status.WORKING, good.activity)
        assertEquals(L2Status.WORKING, good.status)
        assertEquals("n", good.sessionName)
        assertEquals("normal", good.health)

        val divergent = Session(
            ref = "r", name = "n", cwd = "/w", rows = 24, cols = 80,
            provider = "pi", activity = "working", status = "idle", health = "broken",
            title = "◐ must-not-infer",
        ).toL2Entry()
        assertEquals(L2Status.UNKNOWN, divergent.activity)
        assertEquals(L2Status.UNKNOWN, divergent.status)
        assertEquals("unknown", divergent.health)
    }

    @Test
    fun missingOrGarbageStatusStaysUnknownNotIdle() {
        val vm = WorkspaceViewModel(
            requestList = {},
            subscribeLevel2 = {},
            unsubscribeLevel2 = {},
        )
        vm.enterLevel2("/proj/a")
        vm.onFrame(
            Level2Frame(
                workspace = "/proj/a",
                seq = 2,
                sessions = listOf(
                    Session(
                        ref = "ref-empty",
                        name = "empty-status",
                        cwd = "/proj/a",
                        rows = 24,
                        cols = 80,
                        title = "✳  idle-glyph",
                        status = "",
                    ),
                    Session(
                        ref = "ref-junk",
                        name = "junk-status",
                        cwd = "/proj/a",
                        rows = 24,
                        cols = 80,
                        title = "◐  working-glyph",
                        status = "busy",
                    ),
                ),
            ),
        )
        val statuses = vm.level2.value.sessions.map { it.status }
        assertEquals(listOf(L2Status.UNKNOWN, L2Status.UNKNOWN), statuses)
        assertEquals(0, statuses.count { it == L2Status.IDLE })
    }
}
