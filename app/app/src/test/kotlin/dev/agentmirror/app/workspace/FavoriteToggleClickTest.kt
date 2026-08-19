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
import androidx.compose.ui.test.performClick
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
 * 067 点击行为：点星星必须把结构键写入持久化。
 *
 * 生产线上 Session 只有 name（window_name fallback session_name），
 * session_name / window_index / window_name 三元组为空。本测按这个形态构造。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FavoriteToggleClickTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun starClickWritesStructuralKeyToPersistenceNotTitle() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("favorites", Context.MODE_PRIVATE).edit().clear().commit()
        val store = SharedPreferencesFavoriteStore(ctx)
        val vm = WorkspaceViewModel(
            requestList = {},
            subscribeLevel2 = {},
            unsubscribeLevel2 = {},
            nowMs = { 1_700_000_111_000L },
            favoriteStore = store,
        )
        val changingTitle = "PROBE_TITLE_067_TASKSUMMARY_WILL_CHANGE_xyz"
        val live = Session(
            ref = "sock\u001f%2",
            name = "advisor",
            cwd = "/Volumes/nvme/Projects/远程Agent安卓",
            rows = 24,
            cols = 80,
            title = changingTitle,
            status = "idle",
            sessionName = "",
            windowIndex = "",
            windowName = "",
        ).toL2Entry()

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

        compose.onNodeWithTag("l2-star-sock\u001f%2").performClick()
        compose.runOnIdle {
            assertEquals("点星不得进会话", 0, opened)
            val written = store.load()
            assertEquals("点击必须写入一条收藏", 1, written.size)
            val rec = written.single()
            assertEquals("advisor", rec.sessionName)
            assertEquals("", rec.windowIndex)
            assertEquals("advisor", rec.windowName)
            assertEquals(1_700_000_111_000L, rec.addedAt)
            assertFalse(rec.sessionName == changingTitle)
            assertFalse(rec.windowName == changingTitle)
        }

        val rebuilt = SharedPreferencesFavoriteStore(ctx).load()
        assertEquals(1, rebuilt.size)
        assertEquals("advisor", rebuilt.single().sessionName)
        assertEquals("advisor", rebuilt.single().windowName)
        val blob = ctx.getSharedPreferences("favorites", Context.MODE_PRIVATE)
            .getString("records", "")
            .orEmpty()
        assertTrue(blob.contains("advisor"))
        assertFalse("persist must not store pane title: $blob", blob.contains(changingTitle))
        assertFalse(blob.contains("\"title\""))
    }
}
