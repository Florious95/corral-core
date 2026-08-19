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

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.agentmirror.app.conn.Session
import dev.agentmirror.app.ui.theme.AgentMirrorTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 073 A-fav-uniq / A-fav-path：跨 socket 的同名 leader 必须是两条身份。
 *
 * 两个工作区各有 window_name=claude_code，收藏键若漏 socket 会：
 * 收藏一个 ⇒ 另一个也显示已收藏；列表只剩一条；点进去落到后写入的那个。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FavoriteIdentityTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun starringOneClaudeCodeDoesNotFavoriteTheOtherOnADifferentSocket() {
        val store = MemoryFavoriteStore()
        val vm = WorkspaceViewModel(
            requestList = {},
            subscribeLevel2 = {},
            unsubscribeLevel2 = {},
            nowMs = { 1_700_000_222_000L },
            favoriteStore = store,
        )
        val a = leader(
            ref = "/tmp/tmux-501/ident-a\u001f%1",
            cwd = "/tmp/e2e-ident/ws-甲",
        )
        val b = leader(
            ref = "/tmp/tmux-501/ident-b\u001f%1",
            cwd = "/tmp/e2e-ident/ws-乙",
        )
        assertEquals("claude_code", a.identityLabel)
        assertEquals("claude_code", b.identityLabel)
        assertNotEquals(a.ref, b.ref)

        vm.toggleFavorite(a)

        val keys = vm.favorites.value.map { it.key }.toSet()
        assertTrue("starred session must be in the book", keys.contains(a.favoriteKey()))
        assertFalse(
            "A-fav-uniq: starring one claude_code must leave the other unfavorited",
            keys.contains(b.favoriteKey()),
        )
        assertEquals(1, store.load().size)

        val afterOne = vm.favoriteRows(listOf(a, b))
        assertEquals(1, afterOne.size)
        assertEquals(
            "the single favorite must resolve to the starred pane, not the other socket",
            a.ref,
            afterOne.single().ref,
        )
        assertFalse(afterOne.single().gray)
    }

    @Test
    fun twoFavoritesOpenTheirOwnRefsAndKeepDistinctCwdSubtitles() {
        val vm = WorkspaceViewModel(
            requestList = {},
            subscribeLevel2 = {},
            unsubscribeLevel2 = {},
            nowMs = { 42L },
            favoriteStore = MemoryFavoriteStore(),
        )
        val a = leader(
            ref = "/tmp/tmux-501/ident-a\u001f%3",
            cwd = "/tmp/e2e-ident/ws-甲",
        )
        val b = leader(
            ref = "/tmp/tmux-501/ident-b\u001f%7",
            cwd = "/tmp/e2e-ident/ws-乙",
        )
        vm.toggleFavorite(a)
        vm.toggleFavorite(b)

        val rows = vm.favoriteRows(listOf(a, b))
        assertEquals("A-fav-uniq: each star writes its own row", 2, rows.size)
        assertEquals(setOf(a.ref, b.ref), rows.map { it.ref }.toSet())
        assertTrue("A-fav-path: every favorite row carries a cwd", rows.all { it.cwd.isNotEmpty() })
        assertNotEquals(
            "A-fav-path: same-named leaders must keep distinct cwd subtitles",
            rows[0].cwd,
            rows[1].cwd,
        )
        assertEquals(setOf(a.cwd, b.cwd), rows.map { it.cwd }.toSet())
        assertTrue(rows.all { it.identityLabel == "claude_code" })

        val opened = ArrayList<String>()
        compose.setContent {
            AgentMirrorTheme {
                FavoriteList(
                    rows = rows,
                    onOpenSession = { ref, _ -> opened.add(ref) },
                    onUnfavorite = {},
                )
            }
        }
        compose.onNodeWithText(a.cwd, substring = true).performClick()
        compose.onNodeWithText(b.cwd, substring = true).performClick()
        compose.runOnIdle {
            assertEquals(listOf(a.ref, b.ref), opened)
        }
    }

    @Test
    fun favoriteListRendersNonEmptyDistinctCwdSubtitles() {
        val rows = listOf(
            FavoriteRow(
                sessionName = "team",
                windowIndex = "0",
                windowName = "claude_code",
                addedAt = 2L,
                isOnline = true,
                ref = "/tmp/sock-a\u001f%1",
                cwd = "/tmp/e2e-ident/ws-甲",
            ),
            FavoriteRow(
                sessionName = "team",
                windowIndex = "0",
                windowName = "claude_code",
                addedAt = 1L,
                isOnline = true,
                ref = "/tmp/sock-b\u001f%1",
                cwd = "/tmp/e2e-ident/ws-乙",
            ),
        )
        compose.setContent {
            AgentMirrorTheme {
                FavoriteList(
                    rows = rows,
                    onOpenSession = { _, _ -> },
                    onUnfavorite = {},
                )
            }
        }
        compose.onNodeWithText("/tmp/e2e-ident/ws-甲", substring = true).assertExists()
        compose.onNodeWithText("/tmp/e2e-ident/ws-乙", substring = true).assertExists()
    }

    @Test
    fun twoSameNamedLeadersMustNotShareAFavoriteKey() {
        val a = leader("/tmp/tmux-501/ident-a\u001f%12", "/ws/甲")
        val b = leader("/tmp/tmux-501/ident-b\u001f%12", "/ws/乙")
        assertEquals(FavoriteKey(a.ref), a.favoriteKey())
        assertEquals(FavoriteKey(b.ref), b.favoriteKey())
        assertNotEquals(
            "A-fav-uniq: favorite key must include socket (the server ref), not just window_name",
            a.favoriteKey(),
            b.favoriteKey(),
        )
    }

    @Test
    fun legacyRecordsWithoutRefAreDroppedAndDoNotCrashTheList() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("favorites", Context.MODE_PRIVATE).edit()
            .putString(
                "records",
                """[{"session_name":"team","window_index":"0","window_name":"claude_code","added_at":1},""" +
                    """{"session_name":"team","window_index":"0","window_name":"claude_code","added_at":2},""" +
                    """{"ref":"/tmp/sock-live","session_name":"team","window_index":"0",""" +
                    """"window_name":"claude_code","cwd":"/tmp/e2e-ident/ws-甲","added_at":3}]""",
            )
            .commit()
        val kept = SharedPreferencesFavoriteStore(ctx).load()
        assertEquals(1, kept.size)
        assertEquals("/tmp/sock-live", kept.single().ref)

        compose.setContent {
            AgentMirrorTheme {
                FavoriteList(
                    rows = listOf(
                        FavoriteRow(
                            sessionName = "team",
                            windowIndex = "0",
                            windowName = "claude_code",
                            addedAt = 2L,
                            isOnline = false,
                            ref = "",
                            cwd = "/old/a",
                        ),
                        FavoriteRow(
                            sessionName = "team",
                            windowIndex = "0",
                            windowName = "claude_code",
                            addedAt = 1L,
                            isOnline = false,
                            ref = "",
                            cwd = "/old/b",
                        ),
                    ),
                    onOpenSession = { _, _ -> },
                    onUnfavorite = {},
                )
            }
        }
        compose.onNodeWithText("/old/a", substring = true).assertExists()
        compose.onNodeWithText("/old/b", substring = true).assertExists()
    }

    private fun leader(ref: String, cwd: String): L2Entry = Session(
        ref = ref,
        name = "claude_code",
        cwd = cwd,
        rows = 24,
        cols = 80,
        title = "◐  PROBE_TITLE_MUST_NOT_BE_KEY",
        status = "idle",
        sessionName = "team",
        windowIndex = "0",
        windowName = "claude_code",
    ).toL2Entry()
}
