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

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import dev.agentmirror.app.conn.ConnectionState
import dev.agentmirror.app.conn.ListDeltaFrame
import dev.agentmirror.app.conn.ListingFrame
import dev.agentmirror.app.conn.Workspace
import dev.agentmirror.app.ui.theme.AgentMirrorTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 075 A-sp-idle / A-sp-loop：一级菜单首帧到达后转圈必须消失；静置 10s list 不线性涨。
 *
 * 先验红针对的是用户世界：列表内容已到（5 个工作区），但 PullToRefresh 指示器不回弹。
 * 三种世界靠两个操作数区分，不许只记「转圈还在」：
 * - W1 请求一直在飞：10s 内 list 随时间涨
 * - W2 请求早停了但状态没回写：list 停住，[refreshing] 末值仍 true
 * - W3 状态回写了但 UI 没重组：[refreshing]=false，树上仍有不确定进度指示器
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkspaceScreenSpinnerIdleTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun enterLevel1_afterFirstListing_spinnerClears_andIdleDoesNotGrowList() {
        var lists = 0
        val flips = mutableListOf<Boolean>()
        val vm = WorkspaceViewModel(requestList = { lists++ })
        val job = Job()
        CoroutineScope(Dispatchers.Unconfined + job).launch {
            vm.refreshing.collect { flips.add(it) }
        }

        vm.onConnectionStateChanged(ConnectionState.READY)
        vm.onFrame(fiveWorkspaces(seq = 1))
        assertEquals(5, vm.uiState.value.workspaces.size)
        assertFalse("首帧落地时 refreshing 必须是 false", vm.refreshing.value)

        compose.setContent {
            AgentMirrorTheme {
                WorkspaceScreen(
                    viewModel = vm,
                    selectedWorkspaceCwd = null,
                    onSelectWorkspace = {},
                    onBackToList = {},
                    onOpenSettings = {},
                )
            }
        }
        compose.waitForIdle()

        val listsOnEnter = lists
        val refreshingAfterEnter = vm.refreshing.value
        val indicatorsAfterEnter = countIndeterminateProgress()

        // 069：进一级必须发 list。首帧已经在组合前到达（ServiceWire lastListing 补播同构）。
        assertTrue("069 进一级必须发 list，got=$listsOnEnter", listsOnEnter in 1..2)

        // 首帧已到：指示器不得再悬着。判不红就是白写。
        assertFalse(
            "A-sp-idle：首帧已到后 refreshing 必须 false " +
                world(listsOnEnter, listsOnEnter, refreshingAfterEnter, indicatorsAfterEnter, flips),
            refreshingAfterEnter,
        )
        assertEquals(
            "A-sp-idle：首帧已到后树上不得再有不确定进度指示器 " +
                world(listsOnEnter, listsOnEnter, refreshingAfterEnter, indicatorsAfterEnter, flips),
            0,
            indicatorsAfterEnter,
        )
        assertEquals(
            "062：发 list 不得清空已画列表",
            5,
            vm.uiState.value.workspaces.size,
        )

        // 静置 10s：只推 list_delta（一级真实推送），不得再自激 list。
        repeat(6) { i ->
            vm.onFrame(
                ListDeltaFrame(
                    seq = (i + 2).toLong(),
                    changedWorkspaces = listOf(
                        Workspace(cwd = "/proj/远程Agent安卓", sessionCount = 15 + i),
                    ),
                ),
            )
            compose.mainClock.advanceTimeBy(1_000L)
            compose.waitForIdle()
        }
        compose.mainClock.advanceTimeBy(4_000L)
        compose.waitForIdle()

        val listsAfterIdle = lists
        val refreshingAfterIdle = vm.refreshing.value
        val indicatorsAfterIdle = countIndeterminateProgress()

        assertEquals(
            "A-sp-loop：静置 10s 推送不得再触发 list " +
                world(listsOnEnter, listsAfterIdle, refreshingAfterIdle, indicatorsAfterIdle, flips),
            listsOnEnter,
            listsAfterIdle,
        )
        assertTrue("A-sp-loop：10s 内 list=$listsAfterIdle 必须 ≤ 2", listsAfterIdle <= 2)
        assertFalse(
            "静置后 refreshing 仍须 false " +
                world(listsOnEnter, listsAfterIdle, refreshingAfterIdle, indicatorsAfterIdle, flips),
            refreshingAfterIdle,
        )
        assertEquals(
            "W3 对照：refreshing=false 时 UI 指示器必须一起消失 " +
                world(listsOnEnter, listsAfterIdle, refreshingAfterIdle, indicatorsAfterIdle, flips),
            0,
            indicatorsAfterIdle,
        )
        assertEquals(5, vm.uiState.value.workspaces.size)
        job.cancel()
    }

    @Test
    fun pullToRefresh_stillSetsRefreshing_andClearsOnListing() {
        var lists = 0
        val vm = WorkspaceViewModel(requestList = { lists++ })
        vm.onConnectionStateChanged(ConnectionState.READY)
        vm.onFrame(fiveWorkspaces(seq = 1))

        compose.setContent {
            AgentMirrorTheme {
                WorkspaceScreen(
                    viewModel = vm,
                    selectedWorkspaceCwd = null,
                    onSelectWorkspace = {},
                    onBackToList = {},
                    onOpenSettings = {},
                )
            }
        }
        compose.waitForIdle()
        val before = lists
        assertFalse(vm.refreshing.value)

        compose.onNodeWithTag("workspace-pull-refresh").performTouchInput {
            down(center)
            repeat(12) {
                moveBy(Offset(0f, 40f), delayMillis = 32)
            }
            up()
        }
        compose.waitForIdle()

        assertTrue("下拉手动刷新不得关掉：下拉前=$before 下拉后=$lists", lists > before)
        assertTrue("下拉必须置 refreshing", vm.refreshing.value)

        vm.onFrame(fiveWorkspaces(seq = 2))
        compose.waitForIdle()
        assertFalse("下拉的 listing 到达后必须复位", vm.refreshing.value)
        assertEquals(0, countIndeterminateProgress())
    }

    private fun countIndeterminateProgress(): Int =
        compose.onAllNodes(
            SemanticsMatcher("indeterminate-progress") { node ->
                node.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) ==
                    ProgressBarRangeInfo.Indeterminate
            },
            useUnmergedTree = true,
        ).fetchSemanticsNodes().size
}

class ViewModelSpinnerIdleTest {

    @Test
    fun enterLevel1_afterCachedListing_requestsList_withoutLeavingRefreshingTrue() {
        var lists = 0
        val flips = mutableListOf<Boolean>()
        val vm = WorkspaceViewModel(requestList = { lists++ })
        val job = Job()
        CoroutineScope(Dispatchers.Unconfined + job).launch {
            vm.refreshing.collect { flips.add(it) }
        }

        vm.onConnectionStateChanged(ConnectionState.READY)
        vm.onFrame(fiveWorkspaces(seq = 1))
        assertFalse(vm.refreshing.value)

        vm.enterLevel1()

        assertEquals("069 即时刷新不得关掉", 1, lists)
        assertEquals(5, vm.uiState.value.workspaces.size)
        assertFalse(
            "首帧已到：enterLevel1 不得把 refreshing 留在 true " +
                "lists=$lists last=${vm.refreshing.value} flips=$flips",
            vm.refreshing.value,
        )
        assertFalse(
            "翻转序列末值必须是 false flips=$flips",
            flips.last(),
        )
        job.cancel()
    }

    @Test
    fun enterLevel1_empty_stillSetsRefreshing_untilListing() {
        var lists = 0
        val vm = WorkspaceViewModel(requestList = { lists++ })
        vm.enterLevel1()
        assertEquals(1, lists)
        assertTrue("无首帧时进一级必须置转圈", vm.refreshing.value)
        vm.onFrame(fiveWorkspaces(seq = 1))
        assertFalse("首帧到达后必须复位", vm.refreshing.value)
        assertEquals(5, vm.uiState.value.workspaces.size)
    }
}

private fun fiveWorkspaces(seq: Long) = ListingFrame(
    reqId = seq,
    seq = seq,
    workspaces = listOf(
        Workspace(cwd = "/proj/多agent协作", sessionCount = 12),
        Workspace(cwd = "/proj/grok开启team", sessionCount = 1),
        Workspace(cwd = "/proj/无等编排", sessionCount = 5),
        Workspace(cwd = "/proj/讨论team-agent", sessionCount = 1),
        Workspace(cwd = "/proj/远程Agent安卓", sessionCount = 15),
    ),
)

/** 把三个世界的操作数写进失败消息，光写「转圈还在」等于没记。 */
private fun world(
    listsOnEnter: Int,
    listsNow: Int,
    refreshing: Boolean,
    indicatorCount: Int,
    flips: List<Boolean>,
): String {
    val label = when {
        listsNow > listsOnEnter ->
            "W1 请求一直在飞 lists_enter=$listsOnEnter lists_now=$listsNow"
        refreshing ->
            "W2 请求早停了但状态没回写 lists=$listsNow refreshing=true"
        indicatorCount > 0 ->
            "W3 状态回写了但 UI 没重组 refreshing=false indicators=$indicatorCount"
        else ->
            "ok lists=$listsNow refreshing=false indicators=0"
    }
    return "$label flips=$flips"
}
