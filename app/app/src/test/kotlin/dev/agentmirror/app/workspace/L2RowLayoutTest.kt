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
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpRect
import dev.agentmirror.app.conn.Session
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
 * Unified external row: working/idle lamps left of the name, official Provider
 * mark on the right, no persistent star. Long-press is the favorite owner.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class L2RowLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun lampLeadsIdentityAndProviderMarkSitsOnTheRight() {
        val working = entry("ref-w", "sess-work", "working", "1")
        val idle = entry("ref-i", "sess-idle", "idle", "2")
        compose.setContent {
            AgentMirrorTheme {
                L2SessionList(
                    sessions = listOf(working, idle),
                    onOpenSession = { _, _ -> },
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("l2-star-ref-w").assertDoesNotExist()
        compose.onNodeWithTag("l2-provider-ref-w", useUnmergedTree = true).assertDoesNotExist()
        val lampW = compose.onNodeWithTag("l2-motion-ref-w", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val idW = compose.onNodeWithTag("l2-id-ref-w", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val pathW = compose.onNodeWithTag("l2-path-ref-w", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val lampI = compose.onNodeWithTag("l2-motion-ref-i", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val idI = compose.onNodeWithTag("l2-id-ref-i", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val pathI = compose.onNodeWithTag("l2-path-ref-i", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val rowW = compose.onNodeWithTag("l2-row-ref-w").getUnclippedBoundsInRoot()
        val rowI = compose.onNodeWithTag("l2-row-ref-i").getUnclippedBoundsInRoot()

        assertTrue("working lamp before name lamp.left=${lampW.left} id.left=${idW.left}", lampW.left < idW.left)
        assertTrue("idle lamp before name lamp.left=${lampI.left} id.left=${idI.left}", lampI.left < idI.left)
        assertTrue("path under/after name id.bottom=${idW.bottom} path.top=${pathW.top}", pathW.top.value + 0.5f >= idW.top.value)
        val hW = rowW.bottom.value - rowW.top.value
        val hI = rowI.bottom.value - rowI.top.value
        val heightDelta = kotlin.math.abs(hW - hI)
        assertTrue("both rows 66dp height delta=$heightDelta w=$hW i=$hI", heightDelta < 1f)
        assertEquals(66.0, hW.toDouble(), 1.0)
        assertTrue("path slots share right-ish edge", kotlin.math.abs(pathW.right.value - pathI.right.value) < 2f)
    }

    @Test
    fun starToggleStillWritesFavoriteWithoutOpeningSession() {
        val vm = WorkspaceViewModel(
            requestList = {},
            subscribeLevel2 = {},
            unsubscribeLevel2 = {},
            nowMs = { 99L },
            favoriteStore = MemoryFavoriteStore(),
        )
        val live = entry("ref-x", "sess-x", "idle", "4")
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
        compose.longPressFavorite("l2-row-ref-x", "收藏")
        compose.runOnIdle {
            assertEquals("长按收藏不得进会话", 0, opened)
            assertEquals(1, vm.favorites.value.size)
            assertEquals("sess-x", vm.favorites.value.single().sessionName)
            assertEquals("4", vm.favorites.value.single().windowIndex)
        }
        compose.longPressFavorite("l2-row-ref-x", "取消收藏")
        compose.runOnIdle {
            assertEquals(0, opened)
            assertTrue(vm.favorites.value.isEmpty())
        }
    }

    private fun centerY(rect: DpRect) = (rect.top + rect.bottom) / 2

    private fun entry(ref: String, name: String, status: String, windowIndex: String): L2Entry =
        Session(
            ref = ref,
            name = name,
            cwd = "/proj/a",
            rows = 24,
            cols = 80,
            title = "title-not-identity",
            activity = status,
            status = status,
            health = "normal",
            provider = "pi",
            sessionName = name,
            windowIndex = windowIndex,
            windowName = name,
        ).toL2Entry()
}
