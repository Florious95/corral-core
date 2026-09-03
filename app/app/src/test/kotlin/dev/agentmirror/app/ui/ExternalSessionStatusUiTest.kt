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

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import dev.agentmirror.app.ui.components.CanonicalProviderMarks
import dev.agentmirror.app.ui.components.SessionRow
import dev.agentmirror.app.ui.model.SessionItem
import dev.agentmirror.app.ui.model.SessionRowMotion
import dev.agentmirror.app.ui.model.SessionStatus
import dev.agentmirror.app.ui.model.sessionRowMotion
import dev.agentmirror.app.ui.screens.FavoritesScreen
import dev.agentmirror.app.ui.screens.SessionListScreen
import dev.agentmirror.app.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExternalSessionStatusUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun fourAxisProjectionNeverTreatsUnknownAsIdleOrWorking() {
        assertEquals(
            SessionRowMotion.Working,
            sessionRowMotion(SessionStatus.Busy, "normal", true),
        )
        assertEquals(
            SessionRowMotion.Idle,
            sessionRowMotion(SessionStatus.Idle, "normal", true),
        )
        val none = listOf(
            Triple(SessionStatus.Busy, "abnormal", true),
            Triple(SessionStatus.Idle, "abnormal", true),
            Triple(SessionStatus.Unknown, "normal", true),
            Triple(SessionStatus.Busy, "unknown", true),
            Triple(SessionStatus.Idle, "unknown", true),
            Triple(SessionStatus.Unknown, "unknown", true),
            Triple(SessionStatus.Busy, "normal", false),
            Triple(SessionStatus.Idle, "garbage", true),
        )
        none.forEach { (activity, health, online) ->
            assertEquals(
                "activity=$activity health=$health online=$online",
                SessionRowMotion.None,
                sessionRowMotion(activity, health, online),
            )
        }
    }

    @Test
    fun sixCanonicalProvidersAndUnknownHaveExactTableHits() {
        val ids = CanonicalProviderMarks.all.map { it.id }
        assertEquals(
            listOf("claude_code", "codex", "copilot", "grok", "cursor", "pi"),
            ids,
        )
        ids.forEach { assertTrue(CanonicalProviderMarks.of(it) != null) }
        assertNull(CanonicalProviderMarks.of("unknown"))
        assertNull(CanonicalProviderMarks.of("claude"))
        assertNull(CanonicalProviderMarks.of("cursor-agent"))
        assertNull(CanonicalProviderMarks.of("Grok Code"))
    }

    @Test
    fun virtualClockAdvancesWorkingLampAndLeavesIdleStatic() {
        val working = item("w", SessionStatus.Busy, "claude_code", "normal")
        val idle = item("i", SessionStatus.Idle, "codex", "normal")
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AppTheme {
                SessionRow(working, "l2", false, {}, {}, false)
                SessionRow(idle, "l2", false, {}, {}, false)
            }
        }
        compose.mainClock.advanceTimeByFrame()
        val working0 = desc("l2-motion-w")
        val idle0 = desc("l2-motion-i")
        assertTrue("working starts as working:*", working0.startsWith("working:"))
        assertEquals("idle:static", idle0)
        compose.mainClock.advanceTimeBy(950)
        compose.mainClock.advanceTimeByFrame()
        val working1 = desc("l2-motion-w")
        val idle1 = desc("l2-motion-i")
        assertNotEquals("working lamp must change frames", working0, working1)
        assertEquals("idle lamp must stay static", idle0, idle1)
        assertTrue(working1.startsWith("working:"))
    }

    @Test
    fun listRendersSixProvidersWithoutProviderTextOrQuestionOrStar() {
        val rows = listOf(
            item("claude-w", SessionStatus.Busy, "claude_code", "normal", "Claude Working"),
            item("codex-i", SessionStatus.Idle, "codex", "normal", "Codex Idle"),
            item("copilot-u", SessionStatus.Unknown, "copilot", "unknown", "Copilot Row"),
            item("grok-a", SessionStatus.Busy, "grok", "abnormal", "Grok Row"),
            item("cursor-n", SessionStatus.Idle, "cursor", "normal", "Cursor Row"),
            item("pi-w", SessionStatus.Busy, "pi", "normal", "Pi Row"),
            item("unk-p", SessionStatus.Idle, "unknown", "normal", "Unknown Provider Row"),
        )
        compose.setContent {
            AppTheme {
                SessionListScreen(
                    workspaceName = "ws",
                    workspacePath = "/ws",
                    sessions = rows,
                    onBack = {},
                    onSessionClick = {},
                    onToggleStar = {},
                )
            }
        }
        compose.waitForIdle()
        CanonicalProviderMarks.all.forEach { mark ->
            compose.onNodeWithText(mark.displayName, useUnmergedTree = true).assertDoesNotExist()
        }
        compose.onNodeWithText("未知").assertDoesNotExist()
        compose.onNodeWithText("进行中").assertDoesNotExist()
        compose.onNodeWithText("空闲").assertDoesNotExist()
        compose.onNodeWithText("☆").assertDoesNotExist()
        compose.onNodeWithText("★").assertDoesNotExist()
        compose.onNodeWithText("关闭").assertDoesNotExist()
        compose.onNodeWithText("创建").assertDoesNotExist()
        compose.onNodeWithText("配置").assertDoesNotExist()
        compose.onNodeWithTag("l2-star-claude-w").assertDoesNotExist()
        compose.onNodeWithTag("l2-provider-claude-w", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("l2-provider-codex-i", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("l2-provider-copilot-u", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("l2-provider-grok-a", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("l2-provider-cursor-n", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("l2-provider-pi-w", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("l2-provider-unk-p", useUnmergedTree = true).assertDoesNotExist()
        assertTrue(desc("l2-motion-claude-w").startsWith("working:"))
        assertEquals("idle:static", desc("l2-motion-codex-i"))
        assertTrue(desc("l2-motion-copilot-u").isEmpty())
        assertTrue(desc("l2-motion-grok-a").isEmpty())
        assertTrue(desc("l2-motion-pi-w").startsWith("working:"))
    }

    @Test
    fun ordinaryListShortClickOpensOnceLongPressFavoriteExactlyOnce() {
        val row = item("s1", SessionStatus.Idle, "pi", "normal", "Seat")
        var opens = 0
        var toggles = 0
        var starred = false
        compose.setContent {
            AppTheme {
                val item = row.copy(starred = starred)
                SessionListScreen(
                    workspaceName = "ws",
                    workspacePath = "/ws",
                    sessions = listOf(item),
                    onBack = {},
                    onSessionClick = { opens += 1 },
                    onToggleStar = {
                        toggles += 1
                        starred = !starred
                    },
                )
            }
        }
        compose.onNodeWithTag("l2-row-s1").performClick()
        compose.runOnIdle { assertEquals(1, opens); assertEquals(0, toggles) }
        compose.onNodeWithTag("l2-row-s1").performTouchInput { longClick() }
        compose.waitUntil(2_000) {
            compose.onAllNodesWithTag("l2-favorite-action").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("l2-favorite-action").assertTextEquals("收藏")
        compose.onNodeWithText("关闭").assertDoesNotExist()
        compose.onNodeWithTag("l2-favorite-action").performClick()
        compose.runOnIdle {
            assertEquals(1, opens)
            assertEquals(1, toggles)
        }
    }

    @Test
    fun favoriteListOfflineShortClickDoesNotOpenLongPressUnfavoriteOnce() {
        val online = item("fav-on", SessionStatus.Idle, "codex", "normal", "On", starred = true)
        val offline = item(
            "fav-off",
            SessionStatus.Unknown,
            "unknown",
            "unknown",
            "Off",
            starred = true,
            online = false,
        )
        var opens = 0
        var removed = 0
        compose.setContent {
            AppTheme {
                FavoritesScreen(
                    favorites = listOf(online, offline),
                    onSessionClick = { opens += 1 },
                    onToggleStar = { removed += 1 },
                )
            }
        }
        compose.onNodeWithTag("fav-row-fav-off").performClick()
        compose.runOnIdle { assertEquals(0, opens) }
        compose.onNodeWithText("不在线").assertExists()
        compose.onNodeWithTag("fav-star-fav-off").assertDoesNotExist()
        compose.onNodeWithTag("fav-row-fav-off").performTouchInput { longClick() }
        compose.waitUntil(2_000) {
            compose.onAllNodesWithTag("fav-favorite-action").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("fav-favorite-action").assertTextEquals("取消收藏")
        compose.onNodeWithText("关闭").assertDoesNotExist()
        compose.onNodeWithTag("fav-favorite-action").performClick()
        compose.runOnIdle {
            assertEquals(0, opens)
            assertEquals(1, removed)
        }
        compose.onNodeWithTag("fav-row-fav-on").performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }

    private fun desc(tag: String): String {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        return node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
            .joinToString()
    }

    private fun item(
        id: String,
        status: SessionStatus,
        provider: String,
        health: String,
        name: String = id,
        starred: Boolean = false,
        online: Boolean = true,
    ) = SessionItem(
        id = id,
        displayName = name,
        path = "/ws/$id",
        status = status,
        starred = starred,
        isOnline = online,
        provider = provider,
        health = health,
    )
}
