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

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.agentmirror.app.conn.Level2Frame
import dev.agentmirror.app.conn.Session
import dev.agentmirror.app.ui.theme.AgentMirrorTheme
import dev.agentmirror.app.workspace.MemoryFavoriteStore
import dev.agentmirror.app.workspace.WorkspaceViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 067 三栏：左收藏 / 中会话 / 右设置；冷启动中间页；收藏行直达会话。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TestThreePane {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun panesAreFavoritesSessionsSettingsAndStartOnSessions() {
        assertEquals(
            listOf(ThreePane.Favorites, ThreePane.Sessions, ThreePane.Settings),
            ThreePane.entries.toList(),
        )
        assertEquals(1, ThreePane.Sessions.ordinal)
        assertEquals(ThreePane.Sessions, MainNavState(initialShowPairing = false).homePane)
    }

    @Test
    fun coldStartRendersSessionsPageNotFavorites() {
        val nav = MainNavState(initialShowPairing = false)
        val vm = WorkspaceViewModel(
            requestList = {},
            subscribeLevel2 = {},
            unsubscribeLevel2 = {},
            favoriteStore = MemoryFavoriteStore(),
        )
        compose.setContent {
            AgentMirrorApp(navState = nav, workspaceViewModel = vm)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("three-pane").assertExists()
        compose.onNodeWithText("工作区").assertExists()
        assertEquals(ThreePane.Sessions, nav.homePane)
    }

    @Test
    fun favoriteRowOpensSessionWithoutGoingThroughMiddlePane() {
        val nav = MainNavState(initialShowPairing = false)
        nav.homePane = ThreePane.Favorites
        val vm = WorkspaceViewModel(
            requestList = {},
            subscribeLevel2 = {},
            unsubscribeLevel2 = {},
            favoriteStore = MemoryFavoriteStore(),
        )
        vm.enterLevel2("/proj/a")
        vm.onFrame(
            Level2Frame(
                workspace = "/proj/a",
                seq = 1,
                sessions = listOf(
                    Session(
                        ref = "ref-fav",
                        name = "ignored",
                        cwd = "/proj/a",
                        rows = 24,
                        cols = 80,
                        title = "must-not-be-nav-identity",
                        status = "idle",
                        sessionName = "sess-fav",
                        windowIndex = "3",
                        windowName = "win-fav",
                    ),
                ),
            ),
        )
        vm.toggleFavorite(vm.level2.value.sessions.single())
        val pre = vm.favoriteRows()
        assertEquals(1, pre.size)
        assertEquals(true, pre.single().isOnline)
        assertEquals("ref-fav", pre.single().ref)

        compose.setContent {
            AgentMirrorTheme {
                ThreePaneHome(navState = nav, workspaceViewModel = vm)
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("收藏").assertExists()
        compose.onNodeWithText("win-fav").performClick()
        compose.runOnIdle {
            assertEquals("ref-fav" to "win-fav", nav.activeSession)
            assertEquals(ThreePane.Favorites, nav.homePane)
        }
    }

    @Test
    fun offlineFavoriteDoesNotOpen() {
        val nav = MainNavState(initialShowPairing = false)
        nav.homePane = ThreePane.Favorites
        val vm = WorkspaceViewModel(
            requestList = {},
            subscribeLevel2 = {},
            unsubscribeLevel2 = {},
            favoriteStore = MemoryFavoriteStore(),
        )
        vm.enterLevel2("/proj/a")
        vm.onFrame(
            Level2Frame(
                workspace = "/proj/a",
                seq = 1,
                sessions = listOf(
                    Session(
                        ref = "ref-gone",
                        name = "ignored",
                        cwd = "/proj/a",
                        rows = 24,
                        cols = 80,
                        title = "t",
                        status = "idle",
                        sessionName = "sess-gone",
                        windowIndex = "8",
                        windowName = "win-gone",
                    ),
                ),
            ),
        )
        vm.toggleFavorite(vm.level2.value.sessions.single())
        vm.onFrame(Level2Frame(workspace = "/proj/a", seq = 2, sessions = emptyList()))

        compose.setContent {
            AgentMirrorTheme {
                ThreePaneHome(navState = nav, workspaceViewModel = vm)
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("fav-row-sess-gone-8-win-gone").performClick()
        compose.runOnIdle { assertNull(nav.activeSession) }
    }
}
