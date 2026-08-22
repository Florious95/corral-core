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

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.agentmirror.app.ui.theme.AgentMirrorTheme
import dev.agentmirror.app.workspace.MemoryFavoriteStore
import dev.agentmirror.app.workspace.WorkspaceViewModel
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
 * 067 §4.1：底部标签栏是收藏的可见入口；冷启动落在「会话」；点标签切换。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BottomTabsTest {

    @get:Rule
    val compose = createComposeRule()

    private fun vm() = WorkspaceViewModel(
        requestList = {},
        subscribeLevel2 = {},
        unsubscribeLevel2 = {},
        favoriteStore = MemoryFavoriteStore(),
    )

    @Test
    fun bottomTabsVisibleOnSessionsWithoutSwipe() {
        val nav = MainNavState(initialShowPairing = false)
        compose.setContent {
            AgentMirrorApp(navState = nav, workspaceViewModel = vm())
        }
        compose.waitForIdle()
        compose.onNodeWithTag("bottom-tabs").assertExists()
        compose.onNodeWithTag("bottom-tab-favorites").assertExists()
        compose.onNodeWithTag("bottom-tab-sessions").assertExists()
        compose.onNodeWithTag("bottom-tab-settings").assertExists()
        compose.onNodeWithText("收藏").assertExists()
        compose.onNodeWithText("会话").assertExists()
        compose.onAllNodesWithText("设置").assertCountEquals(1)
        compose.onNodeWithText("工作区").assertExists()
        compose.onNodeWithTag("bottom-tab-sessions").assertIsSelected()
        assertEquals(ThreePane.Sessions, nav.homePane)
    }

    @Test
    fun tapFavoritesTabShowsFavoritesPane() {
        val nav = MainNavState(initialShowPairing = false)
        compose.setContent {
            AgentMirrorTheme {
                ThreePaneHome(navState = nav, workspaceViewModel = vm())
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("bottom-tab-favorites").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(ThreePane.Favorites, nav.homePane)
            assertFalse(nav.showSettings)
        }
        compose.onNodeWithTag("three-pane-favorites").assertExists()
        compose.onNodeWithText("暂无收藏").assertExists()
    }

    @Test
    fun tapSettingsTabShowsSettingsPane() {
        val nav = MainNavState(initialShowPairing = false)
        compose.setContent {
            AgentMirrorTheme {
                ThreePaneHome(navState = nav, workspaceViewModel = vm())
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("bottom-tab-settings").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(ThreePane.Settings, nav.homePane)
            assertTrue(nav.showSettings)
        }
        compose.onNodeWithText("重新配对").assertExists()
        compose.onNodeWithText("主机配对").assertExists()
    }

    @Test
    fun coldStartLandsOnSessionsTab() {
        val nav = MainNavState(initialShowPairing = false)
        assertEquals(ThreePane.Sessions, nav.homePane)
        compose.setContent {
            AgentMirrorApp(navState = nav, workspaceViewModel = vm())
        }
        compose.waitForIdle()
        compose.onNodeWithText("工作区").assertExists()
        compose.onNodeWithTag("three-pane-favorites").assertDoesNotExist()
        compose.onNodeWithText("重新配对").assertDoesNotExist()
        compose.onNodeWithTag("bottom-tab-sessions").assertIsSelected()
        assertEquals(ThreePane.Sessions, nav.homePane)
    }
}
