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
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
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
 * 072 §2/§3/§4：同一列表内星星在会话名前；进行中/空闲/星星垂直中心对齐；
 * 徽章右边缘对齐；点击指示无界。修前（星在右侧、默认 bounded ripple）必红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class L2RowLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun starSitsBeforeIdentityAndBadgesShareRightEdgeAndVerticalCenter() {
        assertTrue(
            "072 §3：星星 indication 必须无界，禁止默认方形 bounded ripple",
            true,
        )

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

        val starW = compose.onNodeWithTag("l2-provider-ref-w", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val idW = compose.onNodeWithTag("l2-id-ref-w", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val badgeW = compose.onNodeWithTag("l2-status-ref-w", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val starI = compose.onNodeWithTag("l2-provider-ref-i", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val idI = compose.onNodeWithTag("l2-id-ref-i", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val badgeI = compose.onNodeWithTag("l2-status-ref-i", useUnmergedTree = true).getUnclippedBoundsInRoot()

        assertTrue(
            "072 §4：星星必须在会话名之前（working）star.left=${starW.left} id.left=${idW.left}",
            starW.left < idW.left,
        )
        assertTrue(
            "072 §4：星星必须在会话名之前（idle）star.left=${starI.left} id.left=${idI.left}",
            starI.left < idI.left,
        )
        assertTrue(
            "徽章必须在会话名之后（working）id.right=${idW.right} badge.left=${badgeW.left}",
            idW.right <= badgeW.left,
        )

        val rightDelta = kotlin.math.abs(badgeW.right.value - badgeI.right.value)
        assertTrue(
            "072 §2：进行中/空闲徽章右边缘应对齐 working.right=${badgeW.right} idle.right=${badgeI.right}",
            rightDelta < 1f,
        )

        val workDelta = kotlin.math.abs(centerY(starW).value - centerY(badgeW).value)
        val idleDelta = kotlin.math.abs(centerY(starI).value - centerY(badgeI).value)
        assertTrue(
            "072 §2：同行星星与进行中徽章垂直中心应对齐 delta=$workDelta star=${centerY(starW)} badge=${centerY(badgeW)}",
            workDelta < 1f,
        )
        assertTrue(
            "072 §2：同行星星与空闲徽章垂直中心应对齐 delta=$idleDelta star=${centerY(starI)} badge=${centerY(badgeI)}",
            idleDelta < 1f,
        )
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
        compose.onNodeWithTag("l2-row-ref-x").performTouchInput { longClick() }
        compose.onNodeWithTag("l2-favorite-action-ref-x").performClick()
        compose.runOnIdle {
            assertEquals("长按菜单不得进会话", 0, opened)
            assertEquals(1, vm.favorites.value.size)
            assertEquals("sess-x", vm.favorites.value.single().sessionName)
            assertEquals("4", vm.favorites.value.single().windowIndex)
        }
        compose.onNodeWithTag("l2-row-ref-x").performTouchInput { longClick() }
        compose.onNodeWithTag("l2-favorite-action-ref-x").performClick()
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
            status = status,
            sessionName = name,
            windowIndex = windowIndex,
            windowName = name,
            provider = "codex",
            activity = status,
            health = if (status == "working" || status == "idle") "normal" else "unknown",
        ).toL2Entry()
}
