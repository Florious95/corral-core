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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import dev.agentmirror.app.conn.ConnectionState
import dev.agentmirror.app.conn.Level2Frame
import dev.agentmirror.app.conn.Session
import dev.agentmirror.app.ui.screens.FavoritesScreen
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
 * Production ordinary route (WorkspaceScreen → SessionListScreen) and
 * FavoritesScreen must share one 66dp title+path SessionRow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ListRouteParityTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun workspaceScreenAndFavoritesScreenShareRowBoundsTextSlotsAndRightAlignment() {
        val cwd = "/ws/parity"
        val ref = "sock\u001f%1"
        val live = Session(
            ref = ref,
            name = "advisor",
            cwd = cwd,
            rows = 24,
            cols = 80,
            title = "parity-title",
            provider = "codex",
            activity = "idle",
            status = "idle",
            health = "normal",
            sessionName = "team",
            windowIndex = "0",
            windowName = "advisor",
        ).toL2Entry()
        val vm = WorkspaceViewModel(
            requestList = {},
            subscribeLevel2 = {},
            unsubscribeLevel2 = {},
            nowMs = { 11L },
            favoriteStore = MemoryFavoriteStore(),
        )
        vm.onConnectionStateChanged(ConnectionState.READY)
        vm.enterLevel2(cwd)
        vm.onFrame(
            Level2Frame(
                workspace = cwd,
                seq = 1,
                sessions = listOf(
                    Session(
                        ref = ref,
                        name = live.name,
                        cwd = cwd,
                        rows = 24,
                        cols = 80,
                        title = live.title,
                        provider = "codex",
                        activity = "idle",
                        status = "idle",
                        health = "normal",
                        sessionName = live.sessionName,
                        windowIndex = live.windowIndex,
                        windowName = live.windowName,
                    ),
                ),
            ),
        )
        vm.toggleFavorite(live)
        val favItems = vm.favoriteRows(listOf(live)).map { it.toSessionItem() }

        compose.setContent {
            AgentMirrorTheme {
                Column {
                    Box(Modifier.fillMaxWidth().height(420.dp)) {
                        WorkspaceScreen(
                            viewModel = vm,
                            selectedWorkspaceCwd = cwd,
                            onSelectWorkspace = {},
                            onBackToList = {},
                            onOpenSettings = {},
                        )
                    }
                    Box(Modifier.fillMaxWidth().height(280.dp)) {
                        FavoritesScreen(
                            favorites = favItems,
                            onSessionClick = {},
                            onToggleStar = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        val l2Row = compose.onNodeWithTag("l2-row-$ref").getUnclippedBoundsInRoot()
        val favRow = compose.onNodeWithTag("fav-row-$ref").getUnclippedBoundsInRoot()
        val l2Id = compose.onNodeWithTag("l2-id-$ref", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val favId = compose.onNodeWithTag("fav-id-$ref", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val l2Path = compose.onNodeWithTag("l2-path-$ref", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val favPath = compose.onNodeWithTag("fav-path-$ref", useUnmergedTree = true).getUnclippedBoundsInRoot()

        fun h(r: androidx.compose.ui.unit.DpRect) = r.bottom.value - r.top.value
        assertEquals(66.0, h(l2Row).toDouble(), 1.0)
        assertEquals(66.0, h(favRow).toDouble(), 1.0)
        assertEquals(h(l2Row), h(favRow), 0.5f)
        assertTrue("title slots present", h(l2Id) > 0f && h(favId) > 0f)
        assertTrue("path slots present", h(l2Path) > 0f && h(favPath) > 0f)
        assertEquals(h(l2Id), h(favId), 1f)
        assertEquals(h(l2Path), h(favPath), 1f)
        val l2Mark = compose.onNodeWithTag("l2-provider-$ref", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val favMark = compose.onNodeWithTag("fav-provider-$ref", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("provider mark right of title l2", l2Mark.left > l2Id.right)
        assertTrue("provider mark right of title fav", favMark.left > favId.right)
        assertTrue(
            "provider marks share size",
            kotlin.math.abs((l2Mark.right.value - l2Mark.left.value) - (favMark.right.value - favMark.left.value)) < 1f,
        )
        val l2Right = l2Row.right.value
        val favRight = favRow.right.value
        assertTrue(
            "row right edges align within 2dp l2=$l2Right fav=$favRight",
            kotlin.math.abs(l2Right - favRight) < 2f,
        )
    }
}
