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

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.agentmirror.app.conn.Level2Frame
import dev.agentmirror.app.conn.Session
import dev.agentmirror.app.ui.theme.AgentMirrorTheme
import dev.agentmirror.app.workspace.MemoryFavoriteStore
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
 * 会话界面与收藏页同一投影：账本仍留失联行，界面只展示 live ref 命中的行。
 * idle / waiting / unknown 只要在线就必须仍在。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SessionOnlineProjectionTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun sessionDockHidesOfflineFavoriteKeepsOnlineIdleWaitingUnknownAndRecovers() {
        val wvm = WorkspaceViewModel(
            requestList = {},
            subscribeLevel2 = {},
            unsubscribeLevel2 = {},
            favoriteStore = MemoryFavoriteStore(),
        )
        val current = sample(REF_CURRENT, "current", "idle", "idle", "1")
        val idle = sample(REF_IDLE, "idle", "idle", "idle", "2")
        val waiting = sample(REF_WAITING, "waiting", "waiting", "waiting", "3")
        val unknown = sample(REF_UNKNOWN, "unknown", "unknown", "unknown", "4", health = "unknown")
        wvm.enterLevel2(CWD)
        wvm.onFrame(frame(1, current, idle, waiting, unknown))
        wvm.level2.value.sessions.forEach { wvm.toggleFavorite(it) }

        val h = OverlayTestHarness(REF_CURRENT)
        compose.setContent {
            AgentMirrorTheme {
                val liveGen by wvm.favoriteLiveGen.collectAsState()
                SessionScreen(
                    viewModel = h.vm,
                    name = "current",
                    onBack = {},
                    favoriteRows = remember(liveGen) { wvm.favoriteRows() },
                    overlaySessions = remember(liveGen) { wvm.viewMenuSource(REF_CURRENT).sessions },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("session-chip-$REF_IDLE").assertIsDisplayed()
        compose.onNodeWithTag("session-chip-$REF_WAITING").assertIsDisplayed()
        compose.onNodeWithTag("session-chip-$REF_UNKNOWN").assertIsDisplayed()
        compose.onNodeWithTag("session-chip-$REF_CURRENT").assertDoesNotExist()

        wvm.onFrame(frame(2, current, idle, waiting))
        compose.waitForIdle()
        compose.onNodeWithTag("session-chip-$REF_IDLE").assertIsDisplayed()
        compose.onNodeWithTag("session-chip-$REF_WAITING").assertIsDisplayed()
        compose.onNodeWithTag("session-chip-$REF_UNKNOWN").assertDoesNotExist()
        assertEquals(4, wvm.favorites.value.size)
        assertEquals(false, wvm.favoriteRows().single { it.ref == REF_UNKNOWN }.isOnline)

        wvm.onFrame(frame(3, current, idle, waiting, unknown))
        compose.waitForIdle()
        compose.onNodeWithTag("session-chip-$REF_UNKNOWN").assertIsDisplayed()
        assertTrue(wvm.favoriteRows().all { it.isOnline })
    }

    @Test
    fun overlayHidesDroppedSessionWhenLiveSnapshotStillHasPeers() {
        val wvm = seededCurrentAndPeer()
        assertEquals(listOf(REF_CURRENT, REF_IDLE), wvm.viewMenuSource(REF_CURRENT).sessions.map { it.ref })

        wvm.onFrame(frame(2, sample(REF_CURRENT, "current", "idle", "idle", "1")))
        assertEquals(listOf(REF_CURRENT), wvm.viewMenuSource(REF_CURRENT).sessions.map { it.ref })

        val h = OverlayTestHarness(REF_CURRENT)
        compose.setContent {
            AgentMirrorTheme {
                val liveGen by wvm.favoriteLiveGen.collectAsState()
                SessionScreen(
                    viewModel = h.vm,
                    name = "current",
                    onBack = {},
                    overlaySessions = remember(liveGen) { wvm.viewMenuSource(REF_CURRENT).sessions },
                )
            }
        }
        openView()
        compose.onNodeWithTag("session-overlay").assertIsDisplayed()
        compose.onNodeWithTag("l2-row-$REF_CURRENT").assertIsDisplayed()
        compose.onNodeWithTag("l2-row-$REF_IDLE").assertDoesNotExist()
    }

    @Test
    fun overlayHidesSessionsWhenLiveSnapshotIsEmpty() {
        val wvm = seededCurrentAndPeer()
        assertEquals(2, wvm.viewMenuSource(REF_CURRENT).sessions.size)

        wvm.onFrame(Level2Frame(workspace = CWD, seq = 2, sessions = emptyList()))
        assertTrue(
            "empty live snapshot must not keep stale overlay rows from cache",
            wvm.viewMenuSource(REF_CURRENT).sessions.isEmpty(),
        )
        assertEquals(2, wvm.favorites.value.size)
        assertTrue(wvm.favoriteRows().none { it.isOnline })
    }

    private fun openView() {
        compose.onNodeWithContentDescription("返回菜单").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("session-overlay-open").performClick()
        compose.waitForIdle()
    }

    private fun seededCurrentAndPeer(): WorkspaceViewModel {
        val wvm = WorkspaceViewModel(
            requestList = {},
            subscribeLevel2 = {},
            unsubscribeLevel2 = {},
            favoriteStore = MemoryFavoriteStore(),
        )
        val current = sample(REF_CURRENT, "current", "idle", "idle", "1")
        val idle = sample(REF_IDLE, "idle", "idle", "idle", "2")
        wvm.enterLevel2(CWD)
        wvm.onFrame(frame(1, current, idle))
        wvm.level2.value.sessions.forEach { wvm.toggleFavorite(it) }
        return wvm
    }

    private fun frame(seq: Long, vararg sessions: Session) = Level2Frame(
        workspace = CWD,
        seq = seq,
        sessions = sessions.toList(),
    )

    private fun sample(
        ref: String,
        name: String,
        activity: String,
        status: String,
        windowIndex: String,
        health: String = "normal",
    ) = Session(
        ref = ref,
        name = name,
        cwd = CWD,
        rows = 24,
        cols = 80,
        title = name,
        activity = activity,
        status = status,
        health = health,
        sessionName = name,
        windowIndex = windowIndex,
        windowName = name,
    )
}

private const val CWD = "/proj/a"
private const val REF_CURRENT = "ref-current"
private const val REF_IDLE = "ref-idle"
private const val REF_WAITING = "ref-waiting"
private const val REF_UNKNOWN = "ref-unknown"
