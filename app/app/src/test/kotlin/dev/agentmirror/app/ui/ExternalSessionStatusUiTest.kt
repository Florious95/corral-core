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
import dev.agentmirror.app.conn.Session
import dev.agentmirror.app.ui.components.CanonicalProviderMarks
import dev.agentmirror.app.ui.components.SessionRow
import dev.agentmirror.app.ui.theme.SessionRowMarker
import dev.agentmirror.app.ui.model.SessionItem
import dev.agentmirror.app.ui.model.SessionRowMotion
import dev.agentmirror.app.ui.model.SessionStatus
import dev.agentmirror.app.ui.model.sessionRowMotion
import dev.agentmirror.app.ui.screens.FavoritesScreen
import dev.agentmirror.app.ui.screens.SessionListScreen
import dev.agentmirror.app.workspace.toL2Entry
import dev.agentmirror.app.workspace.toSessionItem
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
    fun fourAxisProjectionUsesOnlyOnlineAndActivity() {
        val healths = listOf("normal", "unknown", "abnormal")
        val activities = listOf(SessionStatus.Busy, SessionStatus.Idle, SessionStatus.Unknown)
        listOf(true, false).forEach { online ->
            activities.forEach { activity ->
                healths.forEach { health ->
                    val row = item(
                        id = "matrix-$activity-$health-$online",
                        status = activity,
                        provider = "matrix",
                        health = health,
                        online = online,
                    )
                    val expected = if (!online) {
                        SessionRowMotion.None
                    } else {
                        when (activity) {
                            SessionStatus.Busy -> SessionRowMotion.Working
                            SessionStatus.Idle -> SessionRowMotion.Idle
                            SessionStatus.Unknown -> SessionRowMotion.None
                        }
                    }
                    assertEquals(
                        "activity=$activity health=$health online=$online",
                        expected,
                        sessionRowMotion(row.status, row.health, row.isOnline),
                    )
                    assertEquals(health, row.health)
                }
            }
        }
    }

    @Test
    fun dtoToL2EntryToSessionItemNormalizesMalformedHealthWithoutChangingActivity() {
        val session = Session(
            ref = "dto-malformed-health",
            name = "session",
            cwd = "/workspace",
            rows = 24,
            cols = 80,
            activity = "working",
            status = "working",
            health = "broken",
        )
        val item = session.toL2Entry().toSessionItem(starred = false)

        assertEquals("dto-malformed-health", item.id)
        assertEquals(SessionStatus.Busy, item.status)
        assertEquals("unknown", item.health)
        assertEquals(
            SessionRowMotion.Working,
            sessionRowMotion(item.status, item.health, item.isOnline),
        )
    }

    @Test
    fun dtoPipelineRendersAbnormalHealthWorkingRowWithSameRefAndAdvances() {
        val session = Session(
            ref = "dto-abnormal-health",
            name = "session",
            cwd = "/workspace",
            rows = 24,
            cols = 80,
            activity = "working",
            status = "working",
            health = "abnormal",
        )
        val item = session.toL2Entry().toSessionItem(starred = false)
        assertEquals("dto-abnormal-health", item.id)
        assertEquals(SessionStatus.Busy, item.status)
        assertEquals("abnormal", item.health)

        compose.mainClock.autoAdvance = false
        compose.setContent {
            AppTheme { SessionRow(item, "l2", {}, {}, false) }
        }
        compose.mainClock.advanceTimeByFrame()
        val before = desc("l2-motion-dto-abnormal-health")
        assertTrue(before.startsWith("working:"))
        compose.mainClock.advanceTimeBy(950)
        compose.mainClock.advanceTimeByFrame()
        val after = desc("l2-motion-dto-abnormal-health")
        assertTrue(after.startsWith("working:"))
        assertNotEquals(before, after)
    }

    @Test
    fun workingMarkerMatchesCodexNativeConversationSpinnerFramesAndCadence() {
        assertEquals(20f, SessionRowMarker.size.value, 0f)
        assertEquals(2.2f, SessionRowMarker.dotRadius.value, 0f)
        assertEquals(5f, SessionRowMarker.dotColumnInset.value, 0f)
        assertEquals(4f, SessionRowMarker.dotTopInset.value, 0f)
        assertEquals(6f, SessionRowMarker.dotRowStep.value, 0f)
        assertEquals("0.149.0", SessionRowMarker.sourceCliVersion)
        assertEquals(100, SessionRowMarker.frameIntervalMillis)
        assertEquals(1000, SessionRowMarker.periodMillis)
        assertEquals(
            listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"),
            SessionRowMarker.spinnerFrames,
        )
        assertEquals(
            listOf(0x0B, 0x19, 0x39, 0x38, 0x3C, 0x34, 0x36, 0x27, 0x07, 0x0F),
            SessionRowMarker.spinnerMasks,
        )
        val frames = (0..900 step SessionRowMarker.frameIntervalMillis)
            .map { SessionRowMarker.frameAt(it.toLong()) }
        assertEquals((0..900 step 100).map { it.toLong() }, frames.map { it.sourceMillis })
        assertEquals((0..9).toList(), frames.map { it.position })
        assertEquals(SessionRowMarker.spinnerFrames, frames.map { it.glyph })
        val end = SessionRowMarker.frameAt(1000)
        assertEquals(0, end.sourceMillis)
        assertEquals(0, end.position)
        assertEquals("⠋", end.glyph)
    }

    @Test
    fun sixCanonicalProvidersAndUnknownHaveExactTableHits() {
        val ids = CanonicalProviderMarks.all.map { it.id }
        assertEquals(
            listOf("claude_code", "codex", "copilot", "grok", "cursor", "pi"),
            ids,
        )
        ids.forEach {
            assertTrue(CanonicalProviderMarks.of(it) != null)
        }
        assertTrue(CanonicalProviderMarks.drawableRes("claude_code") != null)
        assertTrue(CanonicalProviderMarks.drawableRes("codex") != null)
        assertTrue(CanonicalProviderMarks.drawableRes("grok") != null)
        assertTrue(CanonicalProviderMarks.drawableRes("cursor") != null)
        assertTrue(CanonicalProviderMarks.drawableRes("copilot") != null)
        assertTrue(CanonicalProviderMarks.drawableRes("pi") != null)
        assertNull(CanonicalProviderMarks.of("unknown"))
        assertNull(CanonicalProviderMarks.of("claude"))
        assertNull(CanonicalProviderMarks.of("cursor-agent"))
        assertNull(CanonicalProviderMarks.of("Grok Code"))
    }

    @Test
    fun piWorkingDtoProjectsToWorkingMotionIndependentOfHealthAndProvider() {
        assertEquals(
            SessionRowMotion.Working,
            sessionRowMotion(SessionStatus.Busy, "normal", true),
        )
        val piWorking = item("pi-w", SessionStatus.Busy, "pi", "normal")
        val piIdle = item("pi-i", SessionStatus.Idle, "pi", "normal")
        val piUnknown = item("pi-u", SessionStatus.Unknown, "pi", "unknown")
        val piAbnormal = item("pi-a", SessionStatus.Busy, "pi", "abnormal")
        assertEquals(
            SessionRowMotion.Working,
            sessionRowMotion(piWorking.status, piWorking.health, piWorking.isOnline),
        )
        assertEquals(
            SessionRowMotion.Idle,
            sessionRowMotion(piIdle.status, piIdle.health, piIdle.isOnline),
        )
        assertEquals(
            SessionRowMotion.None,
            sessionRowMotion(piUnknown.status, piUnknown.health, piUnknown.isOnline),
        )
        assertEquals(
            SessionRowMotion.Working,
            sessionRowMotion(piAbnormal.status, piAbnormal.health, piAbnormal.isOnline),
        )
        assertEquals("pi", piWorking.provider)
    }

    @Test
    fun virtualClockAdvancesWorkingLampAndLeavesIdleStatic() {
        val working = item("w", SessionStatus.Busy, "claude_code", "normal")
        val idle = item("i", SessionStatus.Idle, "codex", "normal")
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AppTheme {
                SessionRow(working, "l2", {}, {}, false)
                SessionRow(idle, "l2", {}, {}, false)
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
    fun grokWorkingUnknownHealthAdvancesLampFrames() {
        assertKnownActivityUnknownHealthAnimates("grok")
    }

    @Test
    fun codexWorkingUnknownHealthAdvancesLampFrames() {
        assertKnownActivityUnknownHealthAnimates("codex")
    }

    private fun assertKnownActivityUnknownHealthAnimates(provider: String) {
        val row = item("$provider-working-unknown-health", SessionStatus.Busy, provider, "unknown")
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AppTheme { SessionRow(row, "l2", {}, {}, false) }
        }
        compose.mainClock.advanceTimeByFrame()
        val before = desc("l2-motion-${row.id}")
        assertTrue("$provider authoritative working must animate", before.startsWith("working:"))
        compose.mainClock.advanceTimeBy(950)
        compose.mainClock.advanceTimeByFrame()
        val after = desc("l2-motion-${row.id}")
        assertTrue(after.startsWith("working:"))
        assertNotEquals("$provider working lamp must advance frames", before, after)
    }

    @Test
    fun workingLampUsesSpinnerIdleStaticAndUnknownOnlyQuiet() {
        val working = item("pi-w", SessionStatus.Busy, "pi", "normal")
        val idle = item("pi-i", SessionStatus.Idle, "pi", "normal")
        val unknown = item("pi-u", SessionStatus.Unknown, "pi", "unknown")
        val abnormal = item("pi-a", SessionStatus.Busy, "pi", "abnormal")
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AppTheme {
                SessionRow(working, "l2", {}, {}, false)
                SessionRow(idle, "l2", {}, {}, false)
                SessionRow(unknown, "l2", {}, {}, false)
                SessionRow(abnormal, "l2", {}, {}, false)
            }
        }
        compose.mainClock.advanceTimeByFrame()
        val w0 = desc("l2-motion-pi-w")
        val a0 = desc("l2-motion-pi-a")
        assertTrue("pi working starts as working:* got=$w0", w0.startsWith("working:"))
        assertEquals("working:glyph=⠋:elapsed=0:position=0:mask=11", w0)
        assertEquals("idle:static", desc("l2-motion-pi-i"))
        assertTrue(desc("l2-motion-pi-u").isEmpty())
        assertTrue(a0.startsWith("working:"))
        compose.mainClock.advanceTimeBy(950)
        compose.mainClock.advanceTimeByFrame()
        val w1 = desc("l2-motion-pi-w")
        val a1 = desc("l2-motion-pi-a")
        assertNotEquals("working lamp must change spinner frames", w0, w1)
        assertTrue(w1.startsWith("working:"))
        assertEquals("idle:static", desc("l2-motion-pi-i"))
        assertTrue(desc("l2-motion-pi-u").isEmpty())
        assertTrue(a1.startsWith("working:"))
        assertNotEquals("abnormal-health working lamp must change frames", a0, a1)
    }

    @Test
    fun everyBrailleMaskPaintsSixDotsInsideTheTwentyDpSlot() {
        val maxX = SessionRowMarker.size.value
        val maxY = SessionRowMarker.size.value
        val radius = SessionRowMarker.dotRadius.value
        val xs = listOf(SessionRowMarker.dotColumnInset.value, maxX - SessionRowMarker.dotColumnInset.value)
        val ys = (0..2).map { SessionRowMarker.dotTopInset.value + it * SessionRowMarker.dotRowStep.value }
        xs.forEach { x -> assertTrue(x - radius >= 0f && x + radius <= maxX) }
        ys.forEach { y -> assertTrue(y - radius >= 0f && y + radius <= maxY) }
        assertEquals(10, SessionRowMarker.spinnerMasks.size)
        SessionRowMarker.spinnerMasks.forEach { mask ->
            assertTrue(mask in 1..0x3F)
        }
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
        compose.onNodeWithTag("l2-path-claude-w", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("l2-provider-claude-w", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("l2-provider-codex-i", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("l2-provider-grok-a", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("l2-provider-cursor-n", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("l2-provider-copilot-u", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("l2-provider-pi-w", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("l2-provider-unk-p", useUnmergedTree = true).assertDoesNotExist()
        assertTrue(desc("l2-motion-claude-w").startsWith("working:"))
        assertEquals("idle:static", desc("l2-motion-codex-i"))
        assertTrue(desc("l2-motion-copilot-u").isEmpty())
        assertTrue(desc("l2-motion-grok-a").startsWith("working:"))
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
