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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import dev.agentmirror.app.conn.Session
import dev.agentmirror.app.ui.theme.AgentMirrorTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 067 收藏：结构字段当键、落盘、倒序、失联置灰不删、点星不进会话。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TestFavorite {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun persistRebuildKeepsStructuralKeyNotTitle() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("favorites", Context.MODE_PRIVATE).edit().clear().commit()
        val clock = longArrayOf(1_700_000_000_123L)
        val book = FavoriteBook(SharedPreferencesFavoriteStore(ctx)) { clock[0] }
        book.toggle(
            ref = "/tmp/sock-probe\u001f%13",
            sessionName = "favprobe_sess",
            windowIndex = "13",
            windowName = "favprobe_win",
            cwd = "/proj/favprobe",
        )

        val rebuilt = SharedPreferencesFavoriteStore(ctx).load()
        assertEquals(1, rebuilt.size)
        val rec = rebuilt.single()
        assertEquals("/tmp/sock-probe\u001f%13", rec.ref)
        assertEquals("favprobe_sess", rec.sessionName)
        assertEquals("13", rec.windowIndex)
        assertEquals("favprobe_win", rec.windowName)
        assertEquals("/proj/favprobe", rec.cwd)
        assertEquals(1_700_000_000_123L, rec.addedAt)

        val blob = ctx.getSharedPreferences("favorites", Context.MODE_PRIVATE)
            .getString("records", "")
            .orEmpty()
        assertTrue("blob must carry ref: $blob", blob.contains("/tmp/sock-probe"))
        assertTrue(blob.contains("%13"))
        assertTrue("blob must carry session_name value: $blob", blob.contains("favprobe_sess"))
        assertTrue(blob.contains("13"))
        assertTrue(blob.contains("favprobe_win"))
        assertFalse(
            "persist must not store pane title",
            blob.contains("PROBE_TITLE_067_TASKSUMMARY_WILL_CHANGE_xyz"),
        )
        assertFalse(blob.contains("\"title\""))
    }

    @Test
    fun ghostKeptGrayAndReviveBySameStructuralFields() {
        val store = MemoryFavoriteStore()
        val clock = longArrayOf(10L)
        val book = FavoriteBook(store) { clock[0] }
        book.toggle("ref-a", "sess-a", "2", "win-a", "/proj/a")
        val live = listOf(entry("ref-a", "sess-a", "2", "win-a"))

        val online = book.rows(live).single()
        assertTrue(online.isOnline)
        assertFalse(online.gray)
        assertEquals("ref-a", online.ref)

        val ghost = book.rows(emptyList()).single()
        assertTrue("ghost row must remain", ghost.sessionName == "sess-a")
        assertTrue(ghost.gray)
        assertFalse(ghost.isOnline)
        assertEquals("ref-a", ghost.ref)
        assertEquals(1, store.load().size)

        val revived = book.rows(listOf(entry("ref-a", "sess-a", "2", "win-a"))).single()
        assertTrue(revived.isOnline)
        assertFalse(revived.gray)
        assertEquals("ref-a", revived.ref)
        assertEquals(1, store.load().size)
    }

    @Test
    fun newestAddedSortsFirst() {
        val store = MemoryFavoriteStore()
        val clock = longArrayOf(1L)
        val book = FavoriteBook(store) { clock[0] }
        book.toggle("ref-old", "old", "1", "old-win")
        clock[0] = 9L
        book.toggle("ref-new", "new", "2", "new-win")
        val rows = book.rows(emptyList())
        assertEquals(listOf("new", "old"), rows.map { it.sessionName })
        assertTrue(rows.all { it.gray })
    }

    @Test
    fun starToggleDoesNotOpenSession() {
        val vm = WorkspaceViewModel(
            requestList = {},
            subscribeLevel2 = {},
            unsubscribeLevel2 = {},
            nowMs = { 42L },
            favoriteStore = MemoryFavoriteStore(),
        )
        val live = entry("ref-x", "sess-x", "4", "win-x")
        var opened = 0
        compose.setContent {
            var stars by remember { mutableStateOf(vm.favorites.value.map { it.key }.toSet()) }
            AgentMirrorTheme {
                L2SessionList(
                    sessions = listOf(live),
                    onOpenSession = { _, _ -> opened += 1 },
                    favorited = stars,
                    onToggleFavorite = {
                        vm.toggleFavorite(it)
                        stars = vm.favorites.value.map { rec -> rec.key }.toSet()
                    },
                )
            }
        }
        compose.onNodeWithTag("l2-row-ref-x").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag("menu-favorite").performClick()
        compose.runOnIdle {
            assertEquals(0, opened)
            assertEquals(1, vm.favorites.value.size)
            assertEquals("ref-x", vm.favorites.value.single().ref)
            assertEquals("sess-x", vm.favorites.value.single().sessionName)
            assertEquals("4", vm.favorites.value.single().windowIndex)
            assertEquals("win-x", vm.favorites.value.single().windowName)
        }
        compose.onNodeWithTag("l2-row-ref-x").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag("menu-unfavorite").performClick()
        compose.runOnIdle {
            assertEquals(0, opened)
            assertTrue(vm.favorites.value.isEmpty())
        }
    }

    @Test
    fun offlineRowNotOpenableButStarRemoves() {
        val store = MemoryFavoriteStore()
        val book = FavoriteBook(store) { 7L }
        book.toggle("gone-ref", "gone", "9", "gone-win")
        val rows = book.rows(emptyList())
        var opened = 0
        compose.setContent {
            AgentMirrorTheme {
                FavoriteList(
                    rows = rows,
                    onOpenSession = { _, _ -> opened += 1 },
                    onUnfavorite = { row -> book.toggle(row.ref) },
                )
            }
        }
        compose.onNodeWithTag("fav-row-gone-ref").performClick()
        compose.runOnIdle { assertEquals(0, opened) }
        compose.onNodeWithText("不在线").assertExists()
        compose.onNodeWithTag("fav-row-gone-ref").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag("menu-unfavorite").performClick()
        compose.runOnIdle {
            assertEquals(0, opened)
            assertTrue(store.load().isEmpty())
        }
    }

    private fun entry(
        ref: String,
        sessionName: String,
        windowIndex: String,
        windowName: String,
    ): L2Entry = Session(
        ref = ref,
        name = "ignored-name",
        cwd = "/proj/a",
        rows = 24,
        cols = 80,
        title = "PROBE_TITLE_067_TASKSUMMARY_WILL_CHANGE_xyz",
        status = "idle",
        sessionName = sessionName,
        windowIndex = windowIndex,
        windowName = windowName,
    ).toL2Entry()
}
