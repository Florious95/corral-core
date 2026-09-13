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
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.agentmirror.app.conn.Level2Frame
import dev.agentmirror.app.conn.Session
import dev.agentmirror.app.session.OverlayTestHarness
import dev.agentmirror.app.session.SessionScreen
import dev.agentmirror.app.ui.theme.AgentMirrorTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 101：Agent 会话显示名只消费服务端 Session.name。
 *
 * 旧规则（076 §3a / NAME-A Codex·Pi 分支）→ 新规则：客户端不得按
 * window_name / pane_title / Provider 再算一遍。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ServerSessionNameTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun toL2EntryKeepsServerNameAndDoesNotBackfillStructuralFields() {
        val entry = Session(
            ref = "/tmp/sock\u001f%1",
            name = "远控 leader",
            cwd = "/work/project",
            rows = 24,
            cols = 80,
            title = "✳ 远控 leader",
            status = "idle",
            sessionName = "",
            windowIndex = "",
            windowName = "",
        ).toL2Entry()
        assertEquals("远控 leader", entry.name)
        assertEquals("远控 leader", entry.identityLabel)
        assertEquals("", entry.windowName)
        assertEquals("", entry.sessionName)
        assertEquals("", entry.navigationName)
    }

    @Test
    fun identityLabelIgnoresTitleWindowNameAndProviderLikeFields() {
        val claude = Session(
            ref = "r-claude",
            name = "claude_code",
            cwd = "/work/project",
            rows = 24,
            cols = 80,
            title = "✳ 远控 leader",
            status = "working",
            sessionName = "team",
            windowIndex = "0",
            windowName = "claude_code",
        ).toL2Entry()
        // 旧规则会剥标题得到「远控 leader」；新规则原样显示 Session.name。
        assertEquals("claude_code", claude.identityLabel)

        val codex = Session(
            ref = "r-codex",
            name = "官方 Codex 名",
            cwd = "/work/project",
            rows = 24,
            cols = 80,
            title = "Codex live title",
            status = "idle",
            sessionName = "",
            windowIndex = "1",
            windowName = "node-codex",
        ).toL2Entry()
        assertEquals("官方 Codex 名", codex.identityLabel)
        assertNotEquals(codex.windowName, codex.identityLabel)

        val pi = Session(
            ref = "r-pi",
            name = "服务端已解析",
            cwd = "/work/project",
            rows = 24,
            cols = 80,
            title = "not Pi",
            status = "idle",
            sessionName = "pi-native-session",
            windowIndex = "2",
            windowName = "pi",
        ).toL2Entry()
        assertEquals("服务端已解析", pi.identityLabel)
        assertNotEquals(pi.sessionName, pi.identityLabel)
    }

    @Test
    fun emptyServerNameUsesUnnamedPlaceholderNotWindowName() {
        val entry = Session(
            ref = "r-empty",
            name = "  ",
            cwd = "/work/project",
            rows = 24,
            cols = 80,
            title = "✳ 看起来像名字",
            status = "idle",
            sessionName = "team",
            windowIndex = "0",
            windowName = "advisor",
        ).toL2Entry()
        assertEquals(UNNAMED_SESSION, entry.identityLabel)
        assertEquals("advisor", entry.navigationName)
    }

    @Test
    fun listAndFavoriteRowsRenderServerNameNotRawTitle() {
        val live = Session(
            ref = "/tmp/a\u001f%1",
            name = "远控 leader",
            cwd = "/ws/甲",
            rows = 24,
            cols = 80,
            title = "✳ 远控 leader",
            status = "idle",
            sessionName = "team",
            windowIndex = "0",
            windowName = "claude_code",
        ).toL2Entry()
        val vm = WorkspaceViewModel(
            requestList = {},
            subscribeLevel2 = {},
            unsubscribeLevel2 = {},
            nowMs = { 1L },
            favoriteStore = MemoryFavoriteStore(),
        )
        vm.toggleFavorite(live)
        val rows = vm.favoriteRows(listOf(live))
        assertEquals("远控 leader", rows.single().identityLabel)
        assertEquals("远控 leader", rows.single().name)

        compose.setContent {
            AgentMirrorTheme {
                L2SessionList(sessions = listOf(live), onOpenSession = { _, _ -> })
            }
        }
        compose.onNodeWithText("远控 leader").assertExists()
        compose.onNodeWithText("claude_code").assertDoesNotExist()
        compose.onNodeWithText("✳ 远控 leader").assertDoesNotExist()
    }

    @Test
    fun offlineFavoriteShowsSavedNameNotWindowName() {
        val live = Session(
            ref = "/tmp/a\u001f%1",
            name = "多agent开发leader",
            cwd = "/work/多agent协作",
            rows = 24,
            cols = 80,
            title = "π - 多agent开发leader - 多agent协作",
            status = "idle",
            sessionName = "team",
            windowIndex = "0",
            windowName = "zsh",
        ).toL2Entry()
        val vm = WorkspaceViewModel(
            requestList = {},
            subscribeLevel2 = {},
            unsubscribeLevel2 = {},
            nowMs = { 2L },
            favoriteStore = MemoryFavoriteStore(),
        )
        vm.toggleFavorite(live)
        val offline = vm.favoriteRows(emptyList()).single()
        assertEquals(false, offline.isOnline)
        assertEquals("多agent开发leader", offline.identityLabel)
        assertNotEquals("zsh", offline.identityLabel)
        assertEquals(UNNAMED_SESSION, sessionDisplayName(""))
    }

    @Test
    fun openingRowPassesServerNameAndTopBarFollowsLiveOverlay() {
        val entry = Session(
            ref = "/tmp/sock\u001f%1",
            name = "smoke-luna",
            cwd = "/work/多agent协作",
            rows = 24,
            cols = 80,
            title = "多agent协作",
            status = "working",
            sessionName = "team",
            windowIndex = "4",
            windowName = "smoke-luna",
        ).toL2Entry()
        var opened: Pair<String, String>? = null
        compose.setContent {
            AgentMirrorTheme {
                L2SessionList(
                    sessions = listOf(entry),
                    onOpenSession = { ref, name -> opened = ref to name },
                )
            }
        }
        compose.onNodeWithTag("l2-row-${entry.ref}").performClick()
        compose.runOnIdle {
            assertEquals(entry.ref to "smoke-luna", opened)
        }

        val h = OverlayTestHarness()
        val ref = h.vm.ref
        var overlay by mutableStateOf(
            listOf(
                L2Entry(
                    ref = ref,
                    name = "click-time-stale",
                    title = "raw",
                    rows = 24,
                    cols = 80,
                    status = L2Status.IDLE,
                    cwd = "/ws",
                    windowName = "zsh",
                ),
            ),
        )
        compose.setContent {
            AgentMirrorTheme {
                SessionScreen(
                    viewModel = h.vm,
                    name = "click-time-stale",
                    overlaySessions = overlay,
                    onBack = {},
                )
            }
        }
        compose.onNodeWithTag("session-title").assertIsDisplayed()
        compose.onNodeWithText("click-time-stale").assertIsDisplayed()
        compose.runOnIdle {
            overlay = listOf(
                L2Entry(
                    ref = ref,
                    name = "live-server-name",
                    title = "raw",
                    rows = 24,
                    cols = 80,
                    status = L2Status.WORKING,
                    cwd = "/ws",
                    windowName = "zsh",
                ),
            )
        }
        compose.waitForIdle()
        compose.onNodeWithText("live-server-name").assertIsDisplayed()
        compose.onNodeWithText("click-time-stale").assertDoesNotExist()
    }

    @Test
    fun level2FrameProjectsSessionNameThroughViewModel() {
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
                        ref = "r1",
                        name = "解决中转站的问题",
                        cwd = "/work/多agent协作",
                        rows = 24,
                        cols = 80,
                        title = "解决中转站的问题",
                        status = "idle",
                        windowName = "[tmux]",
                    ),
                ),
            ),
        )
        val row = vm.level2.value.sessions.single()
        assertEquals("解决中转站的问题", row.identityLabel)
        assertEquals("[tmux]", row.windowName)
        assertEquals("[tmux]", row.navigationName)
        assertTrue(row.name == row.identityLabel)
    }
}
