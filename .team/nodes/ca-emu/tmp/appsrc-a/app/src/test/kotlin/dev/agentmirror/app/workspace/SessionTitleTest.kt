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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * 077 §1：会话页顶栏必须复用 076 §3a 的 [sessionDisplayName]，
 * 不得把 navigationName（window_name=claude_code）再送进顶栏。
 *
 * 身份仍走 ref；本用例只断言打开会话时交出的**显示名**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SessionTitleTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun openingClaudeCodeRowPassesSessionDisplayNameNotWindowName() {
        val entry = claude(
            ref = "/tmp/sock-a\u001f%1",
            cwd = "/Volumes/nvme/Projects/远程Agent安卓",
            title = "✳ 远控 leader",
        )
        val want = sessionDisplayName(
            windowName = entry.windowName,
            sessionName = entry.sessionName,
            name = entry.name,
            title = entry.title,
        )
        assertEquals("远控 leader", want)
        assertEquals(want, entry.identityLabel)
        assertEquals("claude_code", entry.navigationName)
        assertNotEquals(entry.identityLabel, entry.navigationName)

        var openedRef: String? = null
        var openedName: String? = null
        compose.setContent {
            AgentMirrorTheme {
                L2SessionList(
                    sessions = listOf(entry),
                    onOpenSession = { ref, name ->
                        openedRef = ref
                        openedName = name
                    },
                )
            }
        }
        compose.onNodeWithText("远控 leader").assertExists()
        compose.onNodeWithTag("l2-row-${entry.ref}").performClick()
        compose.runOnIdle {
            assertEquals(entry.ref, openedRef)
            assertEquals(
                "点进会话必须交出 sessionDisplayName，不能再交 window_name=claude_code",
                want,
                openedName,
            )
            assertNotEquals("claude_code", openedName)
        }
    }

    @Test
    fun openingFavoriteClaudeCodeRowPassesSessionDisplayNameNotWindowName() {
        val row = FavoriteRow(
            sessionName = "team",
            windowIndex = "0",
            windowName = "claude_code",
            addedAt = 1L,
            isOnline = true,
            ref = "/tmp/sock-a\u001f%1",
            cwd = "/Volumes/nvme/Projects/远程Agent安卓",
            title = "✳ 远控 leader",
            status = L2Status.WORKING,
        )
        val want = sessionDisplayName(
            windowName = row.windowName,
            sessionName = row.sessionName,
            name = row.windowName,
            title = row.title,
        )
        assertEquals("远控 leader", want)
        assertEquals(want, row.identityLabel)

        var openedRef: String? = null
        var openedName: String? = null
        compose.setContent {
            AgentMirrorTheme {
                FavoriteList(
                    rows = listOf(row),
                    onOpenSession = { ref, name ->
                        openedRef = ref
                        openedName = name
                    },
                    onUnfavorite = {},
                )
            }
        }
        compose.onNodeWithText("远控 leader").performClick()
        compose.runOnIdle {
            assertEquals(row.ref, openedRef)
            assertEquals(
                "收藏行点进会话必须交出 sessionDisplayName，不能再交 window_name=claude_code",
                want,
                openedName,
            )
            assertNotEquals("claude_code", openedName)
        }
    }

    private fun claude(ref: String, cwd: String, title: String) = Session(
        ref = ref,
        name = "claude_code",
        cwd = cwd,
        rows = 24,
        cols = 80,
        title = title,
        status = "working",
        sessionName = "team",
        windowIndex = "0",
        windowName = "claude_code",
    ).toL2Entry()
}
