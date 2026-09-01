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

package dev.agentmirror.app.session

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.agentmirror.app.conn.Level2Frame
import dev.agentmirror.app.conn.Session
import dev.agentmirror.app.ui.theme.AgentMirrorTheme
import dev.agentmirror.app.workspace.MemoryFavoriteStore
import dev.agentmirror.app.workspace.WorkspaceViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 076 §1 A-vw-src：「查看」必须读当前会话的工作区，不得读被最后一次收藏覆盖的单例。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ViewMenuSourceTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun viewMenuSourceUsesCurrentSessionWorkspaceNotLastFavorite() {
        val wvm = seededAbFavoritesLastB()
        val singletonCwd = wvm.level2.value.sessions.map { it.cwd }.distinct()
        assertEquals(
            "A-vw-src 修前必红：单例被最后收藏写成 B current_ws=A last_published=$singletonCwd",
            listOf(CWD_B),
            singletonCwd,
        )

        val src = wvm.viewMenuSource(REF_A)
        assertEquals("current session workspace", CWD_A, src.currentWorkspace)
        assertEquals("current session socket", SOCK_A, src.currentSocket)
        assertEquals(
            "overlay workspace must be current session, not last favorite",
            CWD_A,
            src.overlayWorkspace,
        )
        assertEquals("overlay socket must follow current session", SOCK_A, src.overlaySocket)
        assertEquals("singleton still sits on last favorite B", CWD_B, src.lastPublishedWorkspace)
        assertNotEquals(
            "区分「没刷新」(singleton 仍是 A) vs 「读错源」(singleton 已是 B 而 overlay 跟着它)",
            src.lastPublishedWorkspace,
            src.currentWorkspace,
        )
        assertEquals(listOf(CWD_A), src.sessions.map { it.cwd }.distinct())
        assertEquals(listOf("sess-a"), src.sessions.map { it.sessionName })
        assertTrue(src.sessions.none { it.cwd == CWD_B || it.sessionName == "sess-b" })
    }

    @Test
    fun viewMenuSourceOverlayFromAShowsANotLastFavoriteB() {
        val wvm = seededAbFavoritesLastB()
        val src = wvm.viewMenuSource(REF_A)
        val h = OverlayTestHarness()
        compose.setContent {
            AgentMirrorTheme {
                SessionScreen(
                    viewModel = h.vm,
                    name = "sess-a",
                    onBack = {},
                    overlaySessions = src.sessions,
                )
            }
        }
        compose.onNodeWithContentDescription("返回菜单").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("session-overlay-open").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("session-overlay").assertIsDisplayed()
        compose.onNodeWithTag("l2-row-$REF_A").assertExists()
        compose.onNodeWithTag("l2-row-$REF_B").assertDoesNotExist()
        compose.onNodeWithText("sess-b").assertDoesNotExist()
        compose.onAllNodesWithText("sess-a").assertCountEquals(1)
        compose.onNodeWithText("多agent协作", substring = true).assertIsDisplayed()
        compose.onNodeWithText("远程Agent安卓").assertDoesNotExist()
    }

    private fun seededAbFavoritesLastB(): WorkspaceViewModel {
        val wvm = WorkspaceViewModel(
            requestList = {},
            subscribeLevel2 = {},
            unsubscribeLevel2 = {},
            favoriteStore = MemoryFavoriteStore(),
        )
        wvm.enterLevel2(CWD_A)
        wvm.onFrame(level2(CWD_A, REF_A, "sess-a", SOCK_A))
        wvm.toggleFavorite(wvm.level2.value.sessions.single())
        wvm.enterLevel2(CWD_B)
        wvm.onFrame(level2(CWD_B, REF_B, "sess-b", SOCK_B))
        wvm.toggleFavorite(wvm.level2.value.sessions.single())
        return wvm
    }

    private fun level2(cwd: String, ref: String, sessionName: String, socket: String) = Level2Frame(
        workspace = cwd,
        seq = 1,
        sessions = listOf(
            Session(
                ref = ref,
                name = sessionName,
                title = "t",
                rows = 24,
                cols = 80,
                status = "idle",
                cwd = cwd,
                sessionName = sessionName,
                windowIndex = "1",
                windowName = sessionName,
            ),
        ),
    ).also { require(ref.startsWith(socket)) }
}

private const val CWD_A = "/Volumes/nvme/Projects/多agent协作"
private const val CWD_B = "/Volumes/nvme/Projects/远程Agent安卓"
private const val SOCK_A = "/tmp/tmux-1000/collab"
private const val SOCK_B = "/tmp/tmux-1000/android"
private const val REF_A = "$SOCK_A\u001f%1"
private const val REF_B = "$SOCK_B\u001f%2"
