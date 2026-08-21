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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.agentmirror.app.conn.Session
import dev.agentmirror.app.ui.screens.SessionListScreen
import dev.agentmirror.app.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 契约 094：收藏页次序稳定；列表点击按会话身份绑定。
 *
 * ① 运行状态变化不得改收藏列表次序（「运行中在前」只属于会话页 088）。
 * ② 任意重排后，点击行 N 得到的会话 id == 行 N 展示的会话 id（会话页 + 收藏页）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FavoriteOrderAndClickBindingTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun favoritePageOrderStaysStableWhenRunningStatusChanges() {
        val newerIdle = favRow(ref = "sess-alpha", name = "alpha", addedAt = 200L, status = L2Status.IDLE)
        val olderBusy = favRow(ref = "sess-zeta", name = "zeta", addedAt = 100L, status = L2Status.WORKING)
        var rows by mutableStateOf(listOf(newerIdle, olderBusy))

        compose.setContent {
            FavoriteList(rows = rows, onOpenSession = { _, _ -> }, onUnfavorite = {})
        }
        compose.waitForIdle()
        assertEquals(
            "收藏页必须保持加入次序（alpha 后收藏，应在 zeta 之上），不得因 zeta 运行中而前插",
            listOf("sess-alpha", "sess-zeta"),
            visualFavOrder(listOf("sess-alpha", "sess-zeta")),
        )

        compose.runOnIdle {
            rows = listOf(
                favRow(ref = "sess-alpha", name = "alpha", addedAt = 200L, status = L2Status.WORKING),
                favRow(ref = "sess-zeta", name = "zeta", addedAt = 100L, status = L2Status.IDLE),
            )
        }
        compose.waitForIdle()
        assertEquals(
            "alpha 改成运行、zeta 改成空闲后，收藏页次序仍必须是加入序",
            listOf("sess-alpha", "sess-zeta"),
            visualFavOrder(listOf("sess-alpha", "sess-zeta")),
        )
    }

    @Test
    fun clickRowNOpensDisplayedSessionIdOnSessionAndFavoritePages() {
        val newerIdle = favRow(ref = "sess-alpha", name = "alpha", addedAt = 200L, status = L2Status.IDLE)
        val olderBusy = favRow(ref = "sess-zeta", name = "zeta", addedAt = 100L, status = L2Status.WORKING)
        val favRows = listOf(newerIdle, olderBusy)
        val openedFav = ArrayList<String>()
        val openedSession = ArrayList<String>()
        val rawSessions = listOf(
            entry(ref = "sess-alpha", name = "alpha", status = "idle"),
            entry(ref = "sess-zeta", name = "zeta", status = "working"),
        )
        val displayed = sortSessions(rawSessions.map { it.toSessionItem(starred = false) })
        assertEquals(
            "会话页合法动态重排：运行中 zeta 应排到空闲 alpha 前面",
            listOf("sess-zeta", "sess-alpha"),
            displayed.map { it.id },
        )
        var page by mutableIntStateOf(0)
        compose.setContent {
            if (page == 0) {
                FavoriteList(
                    rows = favRows,
                    onOpenSession = { ref, _ -> openedFav.add(ref) },
                    onUnfavorite = {},
                )
            } else {
                AppTheme {
                    SessionListScreen(
                        workspaceName = "ws",
                        workspacePath = "/ws",
                        sessions = displayed,
                        onBack = {},
                        onSessionClick = { item -> openedSession.add(item.id) },
                        onToggleStar = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        val favVisual = visualFavOrder(listOf("sess-alpha", "sess-zeta"))
        assertTrue("收藏页至少两行才能验点击绑定", favVisual.size == 2)
        for (id in favVisual) {
            compose.onNodeWithTag("fav-row-$id").performClick()
            compose.waitForIdle()
            assertEquals(
                "收藏页点击行展示 id=$id，打开的必须是同一身份，opened=${openedFav.lastOrNull()}",
                id,
                openedFav.lastOrNull(),
            )
        }

        compose.runOnIdle { page = 1 }
        compose.waitForIdle()
        val sessionVisual = visualSessionOrder(displayed.map { it.id })
        assertEquals("会话页视觉序必须等于 sortSessions 结果", displayed.map { it.id }, sessionVisual)
        for (id in sessionVisual) {
            compose.onNodeWithTag("l2-row-$id").performClick()
            compose.waitForIdle()
            assertEquals(
                "会话页点击行展示 id=$id，打开的必须是同一身份，opened=${openedSession.lastOrNull()}",
                id,
                openedSession.lastOrNull(),
            )
        }
        assertEquals(displayed.map { it.id }, openedSession)
    }

    private fun visualFavOrder(ids: List<String>): List<String> =
        ids.sortedBy { compose.onNodeWithTag("fav-row-$it").getUnclippedBoundsInRoot().top.value }

    private fun visualSessionOrder(ids: List<String>): List<String> =
        ids.sortedBy { compose.onNodeWithTag("l2-row-$it").getUnclippedBoundsInRoot().top.value }

    private fun favRow(ref: String, name: String, addedAt: Long, status: L2Status): FavoriteRow =
        FavoriteRow(
            sessionName = name,
            windowIndex = "1",
            windowName = name,
            addedAt = addedAt,
            isOnline = true,
            ref = ref,
            cwd = "/ws/$name",
            status = status,
        )

    private fun entry(ref: String, name: String, status: String): L2Entry =
        Session(
            ref = ref,
            name = name,
            cwd = "/ws/$name",
            rows = 24,
            cols = 80,
            title = "title-not-identity",
            status = status,
            sessionName = name,
            windowIndex = "1",
            windowName = name,
        ).toL2Entry()
}
