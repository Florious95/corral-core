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

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import dev.agentmirror.app.conn.Session
import dev.agentmirror.app.ui.theme.AgentMirrorTheme
import dev.agentmirror.app.ui.theme.DarkPalette
import dev.agentmirror.app.ui.theme.LightPalette
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 契约 088 E10/E11/E15：首列 Provider 图标；会话页长按含关闭；收藏页长按不含关闭。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProviderIconMenuTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun l2StarsGone_providerHasWhitelistDescription() {
        val grok = Session(
            ref = "ref-g",
            name = "g",
            cwd = "/p",
            rows = 24,
            cols = 80,
            status = "idle",
            sessionName = "g",
            windowIndex = "1",
            windowName = "g",
            provider = "grok",
        ).toL2Entry()
        compose.setContent {
            AgentMirrorTheme {
                L2SessionList(sessions = listOf(grok), onOpenSession = { _, _ -> })
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("l2-star-ref-g").assertDoesNotExist()
        compose.onNodeWithTag("l2-provider-ref-g")
            .assertContentDescriptionEquals("Grok")
    }

    @Test
    fun closeScope_favoritesOmitClose() {
        val live = Session(
            ref = "ref-s",
            name = "s",
            cwd = "/p",
            rows = 24,
            cols = 80,
            status = "working",
            sessionName = "s",
            windowIndex = "1",
            windowName = "s",
            provider = "codex",
        ).toL2Entry()
        val vm = WorkspaceViewModel(
            requestList = {},
            subscribeLevel2 = {},
            unsubscribeLevel2 = {},
            favoriteStore = MemoryFavoriteStore(),
        )
        vm.toggleFavorite(live)
        val favRows = vm.favoriteRows(listOf(live))
        compose.setContent {
            AgentMirrorTheme {
                FavoriteList(
                    rows = favRows,
                    onOpenSession = { _, _ -> },
                    onUnfavorite = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("fav-star-ref-s").assertDoesNotExist()
        compose.onNodeWithTag("fav-row-ref-s").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag("menu-unfavorite").assertExists()
        compose.onNodeWithTag("menu-close").assertDoesNotExist()
    }

    @Test
    fun closeScope_sessionStarredHasClose() {
        val live = Session(
            ref = "ref-s",
            name = "s",
            cwd = "/p",
            rows = 24,
            cols = 80,
            status = "working",
            sessionName = "s",
            windowIndex = "1",
            windowName = "s",
            provider = "codex",
        ).toL2Entry()
        compose.setContent {
            AgentMirrorTheme {
                L2SessionList(
                    sessions = listOf(live),
                    onOpenSession = { _, _ -> },
                    favorited = setOf(live.favoriteKey()),
                    onToggleFavorite = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("l2-row-ref-s").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag("l2-row-menu-ref-s").assertExists()
        compose.onNodeWithTag("menu-close").assertExists()
    }

    @Test
    fun providerIconWellIsNotOpaqueWhiteBlock() {
        // Robolectric 上 captureToImage 超时（查不清像素）；改为断言 well 不是不透明白底。
        fun notWhiteBlock(c: Color): Boolean =
            c.alpha < 1f || c.red < 0.99f || c.green < 0.99f || c.blue < 0.99f
        assertTrue("浅色 well 不得是实心白底", notWhiteBlock(LightPalette.providerIconWell))
        assertTrue("深色 well 不得是实心白底", notWhiteBlock(DarkPalette.providerIconWell))
    }
}
