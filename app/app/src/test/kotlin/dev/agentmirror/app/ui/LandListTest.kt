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

package dev.agentmirror.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import dev.agentmirror.app.conn.Level2Frame
import dev.agentmirror.app.conn.Session
import dev.agentmirror.app.ui.components.statusVisuals
import dev.agentmirror.app.ui.model.SessionStatus
import dev.agentmirror.app.ui.theme.DarkPalette
import dev.agentmirror.app.ui.theme.Dims
import dev.agentmirror.app.ui.theme.LightPalette
import dev.agentmirror.app.ui.theme.TypeSizes
import dev.agentmirror.app.workspace.FavoriteList
import dev.agentmirror.app.workspace.L2SessionList
import dev.agentmirror.app.workspace.MemoryFavoriteStore
import dev.agentmirror.app.workspace.WorkspaceViewModel
import dev.agentmirror.app.workspace.toL2Entry
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
 * t.list 落位：设计版三屏接线、三态 token、082 跨工作区收藏取数。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LandListTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun landListTokensMatchDesignHandoff() {
        assertEquals(66, Dims.rowHeightWithSubtitle.value.toInt())
        assertEquals(60, Dims.rowHeightSingleLine.value.toInt())
        assertEquals(40, Dims.tapTargetMin.value.toInt())
        assertEquals(24, Dims.statusChipHeight.value.toInt())
        assertEquals(5, Dims.statusDotSize.value.toInt())
        assertEquals(15, TypeSizes.rowTitle.value.toInt())
        assertEquals(Color(0xFF12A594), LightPalette.busyDot)
        assertEquals(Color(0xFF5F6980), LightPalette.idleChipText)
        assertEquals(Color(0xFFC03A62), LightPalette.unknownDot)
        assertEquals(Color(0xFF4FD1C0), DarkPalette.busyDot)
        assertEquals(Color(0xFF8497B8), DarkPalette.idleChipText)
        assertEquals(Color(0xFFF0879F), DarkPalette.unknownDot)
        val busy = statusVisuals(LightPalette, SessionStatus.Busy)
        val idle = statusVisuals(LightPalette, SessionStatus.Idle)
        val unknown = statusVisuals(LightPalette, SessionStatus.Unknown)
        assertEquals("进行中", busy.label)
        assertEquals("空闲", idle.label)
        assertEquals("未知", unknown.label)
        assertTrue(busy.pulse)
        assertFalse(idle.pulse)
        assertFalse(unknown.pulse)
        assertNotEquals(unknown.lamp, idle.lamp)
        assertNotEquals(unknown.chipBg, idle.chipBg)
    }

    @Test
    fun landListRendersThreeStatusLabelsAndDoesNotPreTruncateChineseName() {
        val working = claude("/tmp/a\u001f%1", "/ws/甲", "✳ 远控 leader", "working")
        val idle = claude("/tmp/b\u001f%1", "/ws/乙", "◐ team-leader-2", "idle")
        val unknown = claude("/tmp/c\u001f%1", "/ws/丙", "远控 leader 未探测", "unknown")
        compose.setContent {
            L2SessionList(
                sessions = listOf(working, idle, unknown),
                onOpenSession = { _, _ -> },
            )
        }
        compose.waitForIdle()
        compose.onNodeWithText("远控 leader").assertExists()
        compose.onNodeWithText("team-leader-2").assertExists()
        compose.onNodeWithText("远控 leader 未探测").assertExists()
        compose.onNodeWithText("进行中").assertDoesNotExist()
        compose.onNodeWithText("空闲").assertDoesNotExist()
        compose.onNodeWithText("未知").assertDoesNotExist()
        compose.onNodeWithText("claude_code").assertDoesNotExist()
    }

    @Test
    fun landListFavoriteRowIsStarTitlePathStatus() {
        val live = listOf(
            claude("/tmp/a\u001f%1", "/ws/甲", "✳ 远控 leader", "idle"),
            claude("/tmp/b\u001f%1", "/ws/乙", "◐ team-leader-2", "working"),
            claude("/tmp/c\u001f%1", "/ws/丙", "讨论 team-agent", "unknown"),
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
            FavoriteList(rows = rows, onOpenSession = { _, _ -> }, onUnfavorite = {})
        }
        compose.waitForIdle()
        val ref = live[0].ref
        compose.onNodeWithTag("fav-star-$ref").assertDoesNotExist()
        val mark = compose.onNodeWithTag("fav-provider-$ref", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val lamp = compose.onNodeWithTag("fav-motion-$ref", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val id = compose.onNodeWithTag("fav-id-$ref", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val path = compose.onNodeWithTag("fav-path-$ref", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val row = compose.onNodeWithTag("fav-row-$ref").getUnclippedBoundsInRoot()
        assertTrue("灯在行首 lamp.left=${lamp.left} id.left=${id.left}", lamp.left < id.left)
        assertTrue("provider mark right of title mark.left=${mark.left} id.right=${id.right}", mark.left > id.right)
        assertTrue("cwd path slot present", path.bottom.value - path.top.value > 0f)
        assertEquals(66.0, (row.bottom.value - row.top.value).toDouble(), 1.0)
        compose.onNodeWithText("远控 leader").assertExists()
        compose.onNodeWithText("team-leader-2").assertExists()
        compose.onNodeWithText("讨论 team-agent").assertExists()
        compose.onNodeWithText("/ws/甲").assertExists()
        compose.onNodeWithText("空闲").assertDoesNotExist()
        compose.onNodeWithText("进行中").assertDoesNotExist()
        compose.onNodeWithText("未知").assertDoesNotExist()
        compose.onNodeWithText("claude_code").assertDoesNotExist()
    }

    @Test
    fun landListFavoriteFetchCoversEveryWorkspaceWithoutEnteringLevel2() {
        val subs = mutableListOf<String>()
        val vm = WorkspaceViewModel(
            requestList = { error("061: enterFavorites must not list()") },
            subscribeLevel2 = { subs.add(it) },
            unsubscribeLevel2 = {},
            nowMs = { 10L },
            favoriteStore = MemoryFavoriteStore(),
        )
        val a = claude("/tmp/a\u001f%1", "/ws/讨论team-agent", "✳ 讨论 team-agent", "idle")
        val b = claude("/tmp/b\u001f%1", "/ws/远程Agent安卓", "✳ 远控 leader", "working")
        val c = claude("/tmp/c\u001f%1", "/ws/多agent协作", "◐ team-leader-2", "idle")
        vm.toggleFavorite(a)
        vm.toggleFavorite(b)
        vm.toggleFavorite(c)

        val before = vm.favoriteRows()
        assertEquals("未取数时三行都应在（keep_gray）", 3, before.size)
        assertEquals("改前：没进二级 ⇒ 0 行在线", 0, before.count { it.isOnline })
        assertTrue(before.all { it.identityLabel == "claude_code" || !it.isOnline })
        assertEquals(0, vm.favoriteFetchStats().fetchedWorkspaceCount)
        assertEquals(0, subs.size)

        vm.enterFavorites()
        val covered = vm.favoriteFetchStats().favoriteWorkspaceCount
        var fetched = vm.favoriteFetchStats().fetchedWorkspaceCount
        assertEquals("收藏项覆盖 3 个工作区", 3, covered)
        assertEquals("刚进入：只发出第一个工作区的订阅，fetched 仍 0", 0, fetched)
        assertEquals("串行：同时只订 1 个", 1, subs.size)

        val byCwd = listOf(a, b, c).associateBy { it.cwd }
        fun frame(entry: dev.agentmirror.app.workspace.L2Entry) = Level2Frame(
            workspace = entry.cwd,
            seq = 1,
            sessions = listOf(
                Session(
                    ref = entry.ref,
                    name = entry.name,
                    cwd = entry.cwd,
                    rows = 24,
                    cols = 80,
                    title = entry.title,
                    status = entry.status.wire,
                    sessionName = entry.sessionName,
                    windowIndex = entry.windowIndex,
                    windowName = entry.windowName,
                ),
            ),
        )
        // 服务端每连接只绑一个 workspace：必须等当前帧回来再订下一个。
        while (vm.favoriteFetchStats().fetchedWorkspaceCount < 3) {
            val cwd = subs.last()
            val entry = byCwd.getValue(cwd)
            vm.onFrame(frame(entry))
        }
        fetched = vm.favoriteFetchStats().fetchedWorkspaceCount
        assertEquals("实际取过数的工作区必须是 3，不是 1", 3, fetched)
        assertEquals(3, covered)
        assertEquals(3, subs.size)
        assertEquals(3, subs.toSet().size)

        val rows = vm.favoriteRows()
        assertEquals(3, rows.size)
        assertTrue("A-fv-online: 每一行都必须在线，不能只绿一行", rows.all { it.isOnline })
        val names = rows.map { it.identityLabel }.toSet()
        assertEquals(3, names.size)
        assertFalse("A-fv-name-offline: 不得出现 claude_code", names.contains("claude_code"))
        assertTrue(names.contains("远控 leader"))
        assertTrue(names.contains("team-leader-2"))
        assertTrue(names.contains("讨论 team-agent"))
        vm.leaveFavorites()
    }

    private fun claude(ref: String, cwd: String, title: String, status: String) = Session(
        ref = ref,
        name = "claude_code",
        cwd = cwd,
        rows = 24,
        cols = 80,
        title = title,
        provider = "claude_code",
        activity = status,
        status = status,
        health = if (status == "unknown") "unknown" else "normal",
        sessionName = "team",
        windowIndex = "0",
        windowName = "claude_code",
    ).toL2Entry()
}
