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

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.DpRect
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 076 §3 FavRowParity：收藏行与会话列表同构。
 *
 * 3a claude_code 显示名取 pane_title 剥 062 前导符号，互不相同且可辨识；
 *    grok 仍取 window_name，不得改用会变的任务摘要。
 * 3b 状态标与会话列表同一套 [L2Status]，禁止收藏页从 title 再算一遍。
 * 3c 星在行首、标题、目录副标题、右侧状态标。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FavRowParityTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun threeClaudeCodeFavoritesShowDistinctStrippedTitlesNotNumberedWindowName() {
        val a = claude(
            ref = "/tmp/sock-a\u001f%1",
            cwd = "/Volumes/nvme/Projects/远程Agent安卓",
            title = "✳ 远控 leader",
            status = "idle",
        )
        val b = claude(
            ref = "/tmp/sock-b\u001f%1",
            cwd = "/Volumes/nvme/Projects/讨论team-agent",
            title = "✳ 讨论 team-agent",
            status = "working",
        )
        val c = claude(
            ref = "/tmp/sock-c\u001f%1",
            cwd = "/Users/alauda/Documents/code/agent前沿探索/多agent协作",
            title = "◐ 多agent协作",
            status = "unknown",
        )
        val grok = grokAdvisor()

        assertEquals("远控 leader", a.identityLabel)
        assertEquals("讨论 team-agent", b.identityLabel)
        assertEquals("多agent协作", c.identityLabel)
        assertEquals("advisor", grok.identityLabel)
        assertEquals(
            "导航名仍走结构字段 window_name，显示名不回写身份",
            "claude_code",
            a.navigationName,
        )
        assertEquals("advisor", grok.navigationName)

        val labels = listOf(a, b, c).map { it.identityLabel }
        assertEquals(3, labels.toSet().size)
        labels.forEach { label ->
            assertFalse("编号不算可辨识: $label", label.matches(Regex("""claude_code \(\d+\)""")))
            assertNotEquals("claude_code", label)
        }
    }

    @Test
    fun favoriteRowsReuseLiveL2StatusAndDoNotRecomputeFromTitleGlyph() {
        val live = claude(
            ref = "/tmp/sock-w\u001f%2",
            cwd = "/ws/a",
            title = "✳ looks-idle-glyph-must-not-override-wire-status",
            status = "working",
        )
        val vm = WorkspaceViewModel(
            requestList = {},
            subscribeLevel2 = {},
            unsubscribeLevel2 = {},
            nowMs = { 11L },
            favoriteStore = MemoryFavoriteStore(),
        )
        vm.toggleFavorite(live)
        val row = vm.favoriteRows(listOf(live)).single()
        assertEquals(L2Status.WORKING, row.status)
        assertEquals(live.status, row.status)
        assertEquals("looks-idle-glyph-must-not-override-wire-status", row.identityLabel)
        assertEquals("进行中", row.status.label)
    }

    @Test
    fun favoriteListStarLeadsTitleAndStatusBadgeSitsOnTheRight() {
        val live = listOf(
            claude("/tmp/sock-a\u001f%1", "/ws/甲", "✳ 远控 leader", "idle"),
            claude("/tmp/sock-b\u001f%1", "/ws/乙", "◐ 多agent协作", "working"),
        )
        val vm = WorkspaceViewModel(
            requestList = {},
            subscribeLevel2 = {},
            unsubscribeLevel2 = {},
            nowMs = { 3L },
            favoriteStore = MemoryFavoriteStore(),
        )
        live.forEach { vm.toggleFavorite(it) }
        val rows = vm.favoriteRows(live)

        compose.setContent {
            AgentMirrorTheme {
                FavoriteList(
                    rows = rows,
                    onOpenSession = { _, _ -> },
                    onUnfavorite = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("远控 leader").assertExists()
        compose.onNodeWithText("多agent协作").assertExists()
        compose.onNodeWithText("空闲").assertExists()
        compose.onNodeWithText("进行中").assertExists()
        compose.onNodeWithText("claude_code").assertDoesNotExist()

        val refA = live[0].ref
        val star = compose.onNodeWithTag("fav-provider-$refA").getUnclippedBoundsInRoot()
        val id = compose.onNodeWithTag("fav-id-$refA", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val badge = compose.onNodeWithTag("fav-status-$refA").getUnclippedBoundsInRoot()
        assertTrue(
            "076 §3c：星必须在标题之前 star.left=${star.left} id.left=${id.left}",
            star.left < id.left,
        )
        assertTrue(
            "076 §3c：状态标必须在标题之后 id.right=${id.right} badge.left=${badge.left}",
            id.right <= badge.left,
        )
        val delta = kotlin.math.abs(centerY(star).value - centerY(badge).value)
        assertTrue("星与状态标垂直中心应对齐 delta=$delta", delta < 1f)
    }

    private fun centerY(rect: DpRect) = (rect.top + rect.bottom) / 2

    private fun claude(ref: String, cwd: String, title: String, status: String): L2Entry = Session(
        ref = ref,
        name = "claude_code",
        cwd = cwd,
        rows = 24,
        cols = 80,
        title = title,
        status = status,
        sessionName = "team",
        windowIndex = "0",
        windowName = "claude_code",
    ).toL2Entry()

    private fun grokAdvisor(): L2Entry = Session(
        ref = "/tmp/grok\u001f%3",
        name = "advisor",
        cwd = "/ws/grok",
        rows = 24,
        cols = 80,
        title = "Team Agent message from leader: … - grok",
        status = "working",
        sessionName = "team-grok-l2",
        windowIndex = "3",
        windowName = "advisor",
    ).toL2Entry()
}
