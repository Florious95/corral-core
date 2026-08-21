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

package dev.agentmirror.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import dev.agentmirror.app.conn.ConnectionState
import dev.agentmirror.app.conn.Level2Frame
import dev.agentmirror.app.conn.ListingFrame
import dev.agentmirror.app.conn.Session
import dev.agentmirror.app.conn.Workspace
import dev.agentmirror.app.tsnet.ConnectionPath
import dev.agentmirror.app.ui.theme.AgentMirrorTheme
import dev.agentmirror.app.workspace.MemoryFavoriteStore
import dev.agentmirror.app.workspace.WorkspaceScreen
import dev.agentmirror.app.workspace.WorkspaceViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 076 §2：底部三栏的顶部空隙 / 横滑抢竖滑 / 右上角「设置」重复入口。
 *
 * A-ly-scroll 必须同时记下：
 * - contentHeight vs viewportHeight（有没有可滚余量）
 * - 竖滑后 pager 页码变没变（手势有没有被横滑消费）
 * 才能把「滚不动」和「能滚但内容被裁掉」分开。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h520dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TabScrollTest {

    @get:Rule
    val compose = createComposeRule()

    private fun vm() = WorkspaceViewModel(
        requestList = {},
        subscribeLevel2 = {},
        unsubscribeLevel2 = {},
        favoriteStore = MemoryFavoriteStore(),
    )

    @Test
    fun settingsTabScrollReachesLastControlWithoutPagerConsumingVertical() {
        val nav = MainNavState(initialShowPairing = false)
        nav.homePane = ThreePane.Settings
        compose.setContent {
            AgentMirrorTheme {
                ThreePaneHome(navState = nav, workspaceViewModel = vm())
            }
        }
        compose.waitForIdle()

        val before = snapshot("settings-scroll", nav.homePane.ordinal)
        val lastBefore = compose.onAllNodesWithText("跟随系统").fetchSemanticsNodes()
            .maxBy { it.boundsInRoot.bottom }
        val tabsBefore = compose.onNodeWithTag("bottom-tabs").fetchSemanticsNode()
        val overflowBefore = lastBefore.boundsInRoot.bottom - tabsBefore.boundsInRoot.top

        compose.onNodeWithTag("settings-scroll").performTouchInput { swipeUp() }
        compose.waitForIdle()
        repeat(3) {
            compose.onNodeWithTag("settings-scroll").performTouchInput { swipeUp() }
            compose.waitForIdle()
        }

        val after = snapshot("settings-scroll", nav.homePane.ordinal)
        compose.onNodeWithTag("settings-launch").assertIsDisplayed()
        val lastAfter = compose.onAllNodesWithText("跟随系统").fetchSemanticsNodes()
            .maxBy { it.boundsInRoot.bottom }
        val tabsAfter = compose.onNodeWithTag("bottom-tabs").fetchSemanticsNode()
        val lastFullyAboveTabs = lastAfter.boundsInRoot.bottom <= tabsAfter.boundsInRoot.top + 1f

        assertTrue(
            "设置页必须先溢出视口才谈得上滚（contentPx=${before.contentPx} viewportPx=${before.viewportPx} overflowBefore=$overflowBefore scrollMax=${before.scrollMax}）",
            before.contentPx > before.viewportPx || overflowBefore > 0f,
        )
        assertTrue(
            "滚不动：scrollMax=${before.scrollMax}（0/NaN=没有竖直滚动参与者）。能滚但裁掉：scrollMax>0 且最后一项仍被切断。contentPx=${after.contentPx} viewportPx=${after.viewportPx} overflowBefore=$overflowBefore lastFullyAboveTabs=$lastFullyAboveTabs pagerBefore=${before.pagerPage} pagerAfter=${after.pagerPage}",
            before.scrollMax > 0f && lastFullyAboveTabs,
        )
        assertEquals(
            "竖滑不得被 HorizontalPager 消费成切页 pagerBefore=${before.pagerPage} pagerAfter=${after.pagerPage} scrollValue ${before.scrollValue}→${after.scrollValue}",
            ThreePane.Settings.ordinal,
            after.pagerPage,
        )
        assertTrue(
            "竖滑必须真正推进滚动 offset ${before.scrollValue}→${after.scrollValue} max=${after.scrollMax}",
            after.scrollValue > before.scrollValue,
        )
        compose.onNodeWithTag("bottom-tabs").assertExists()
    }

    @Test
    fun sessionListTabScrollReachesLastRowWithoutPagerConsumingVertical() {
        val nav = MainNavState(initialShowPairing = false)
        nav.selectedWorkspaceCwd = "/proj/a"
        val vm = vm()
        vm.onConnectionStateChanged(ConnectionState.READY)
        vm.onFrame(
            ListingFrame(
                reqId = 1,
                seq = 1,
                workspaces = listOf(Workspace(cwd = "/proj/a", sessionCount = 16)),
            ),
        )
        vm.enterLevel2("/proj/a")
        vm.onFrame(
            Level2Frame(
                workspace = "/proj/a",
                seq = 1,
                sessions = (0..15).map { i -> session("ref-$i", "sess-$i") },
            ),
        )

        compose.setContent {
            AgentMirrorTheme {
                ThreePaneHome(navState = nav, workspaceViewModel = vm)
            }
        }
        compose.waitForIdle()

        val before = snapshot("l2-session-list-scroll", nav.homePane.ordinal)
        compose.onNodeWithTag("l2-session-list-scroll").performTouchInput { swipeUp() }
        compose.waitForIdle()
        repeat(5) {
            compose.onNodeWithTag("l2-session-list-scroll").performTouchInput { swipeUp() }
            compose.waitForIdle()
        }
        val after = snapshot("l2-session-list-scroll", nav.homePane.ordinal)

        compose.onNodeWithText("sess-9").assertIsDisplayed()
        assertTrue(
            "会话列表必须溢出视口 contentPx=${before.contentPx} viewportPx=${before.viewportPx} scrollMax=${before.scrollMax}",
            before.contentPx > before.viewportPx && before.scrollMax > 0f,
        )
        assertEquals(
            "竖滑不得切页 pagerBefore=${before.pagerPage} pagerAfter=${after.pagerPage}",
            ThreePane.Sessions.ordinal,
            after.pagerPage,
        )
        assertTrue(
            "竖滑必须推进列表 offset ${before.scrollValue}→${after.scrollValue}",
            after.scrollValue > before.scrollValue,
        )
    }

    @Test
    fun horizontalSwipeStillChangesTab() {
        val nav = MainNavState(initialShowPairing = false)
        compose.setContent {
            AgentMirrorTheme {
                ThreePaneHome(navState = nav, workspaceViewModel = vm())
            }
        }
        compose.waitForIdle()
        assertEquals(ThreePane.Sessions, nav.homePane)
        compose.onNodeWithTag("three-pane").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(
            "067 §4.1：横滑切页必须仍在。实际=${nav.homePane}",
            ThreePane.Settings,
            nav.homePane,
        )
    }

    @Test
    fun l1TopBarDropSettingsKeepLan() {
        assertTopBarDropsSettingsKeepsLan(selectedCwd = null)
    }

    @Test
    fun l2TopBarDropSettingsKeepLan() {
        assertTopBarDropsSettingsKeepsLan(selectedCwd = "/proj/a")
    }

    private fun assertTopBarDropsSettingsKeepsLan(selectedCwd: String?) {
        val vm = vm()
        vm.onConnectionStateChanged(ConnectionState.READY)
        vm.onFrame(
            ListingFrame(
                reqId = 1,
                seq = 1,
                workspaces = listOf(Workspace(cwd = "/proj/a", sessionCount = 1)),
            ),
        )
        compose.setContent {
            AgentMirrorTheme {
                WorkspaceScreen(
                    viewModel = vm,
                    selectedWorkspaceCwd = selectedCwd,
                    connectionPath = ConnectionPath.LAN,
                    onSelectWorkspace = {},
                    onBackToList = {},
                    onOpenSettings = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("LAN").assertIsDisplayed()
        compose.onAllNodesWithText("设置").assertCountEquals(0)
    }

    @Test
    fun settingsTitleNotDoubleStatusBarGap() {
        val nav = MainNavState(initialShowPairing = false)
        nav.homePane = ThreePane.Settings
        compose.setContent {
            AgentMirrorTheme {
                ThreePaneHome(navState = nav, workspaceViewModel = vm())
            }
        }
        compose.waitForIdle()
        val title = compose.onAllNodesWithText("设置").fetchSemanticsNodes()
            .minBy { it.boundsInRoot.top }
        val root = compose.onNodeWithTag("three-pane").fetchSemanticsNode()
        val ratio = title.boundsInRoot.top / root.size.height.toFloat()
        assertTrue(
            "状态栏到标题的空隙不得约占屏高 1/8：titleTop=${title.boundsInRoot.top} rootH=${root.size.height} ratio=$ratio",
            ratio < 0.12f,
        )
    }

    private data class ScrollSnap(
        val viewportPx: Int,
        val contentPx: Float,
        val scrollValue: Float,
        val scrollMax: Float,
        val pagerPage: Int,
    )

    private fun snapshot(tag: String, pagerPage: Int): ScrollSnap {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        val range = node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
        val value = range?.value?.invoke() ?: Float.NaN
        val max = range?.maxValue?.invoke() ?: Float.NaN
        val viewport = node.size.height
        val content = if (!max.isNaN()) viewport + max else Float.NaN
        return ScrollSnap(viewport, content, value, max, pagerPage)
    }

    private fun session(ref: String, name: String) = Session(
        ref = ref,
        name = name,
        cwd = "/proj/a",
        rows = 24,
        cols = 80,
        title = "decoy-title",
        status = "idle",
        sessionName = name,
        windowIndex = "1",
        windowName = name,
    )
}
