package dev.agentmirror.app.session

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.agentmirror.app.SessionUiAcceptanceActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Seven stateful paths through the debug Activity's real SessionScreen/VM/terminal. */
class SessionUiSmokeTest {
    @get:Rule
    val rule = createAndroidComposeRule<SessionUiAcceptanceActivity>()

    @Test
    fun defaultDockAndHotkeys() {
        reset("full", "favorites")
        val before = snapshot()
        assertDefaultDock()
        listOf("session-topbar", "sessions-back", "favorite-online").forEach {
            rule.onNodeWithTag(it).assertDoesNotExist()
        }
        listOf("Provider", "关闭会话", "创建会话").forEach { rule.onNodeWithText(it).assertDoesNotExist() }

        rule.onNodeWithTag("dock_hotkeys").performClick()
        val keys = listOf("Esc", "Tab", "Up", "Down", "Left", "Right", "CtrlC")
        keys.forEachIndexed { index, key ->
            rule.onNodeWithTag("hotkey-$key").performClick()
            rule.waitUntil(2_000) { snapshot().keyValues.size == index + 1 }
        }
        rule.onNodeWithTag("hotkeys-back").performClick()
        assertDefaultDock()
        val after = snapshot()
        assertEquals(listOf("esc", "tab", "up", "down", "left", "right", "ctrl_c"), after.keyValues)
        assertEquals(before.currentRef, after.currentRef)
        assertEquals(before.favoriteSelections, after.favoriteSelections)
        assertEquals(before.viewSelections, after.viewSelections)
    }

    @Test
    fun sessionsRowFiltersNavigatesAndReturns() {
        reset("full", "favorites")
        assertTrue(snapshot().favoriteSelections.isEmpty())
        rule.onNodeWithTag("dock_sessions").performClick()
        rule.onNodeWithTag("session-dock-menu").assertDoesNotExist()
        rule.onNodeWithTag("session-overlay").assertDoesNotExist()
        rule.onNodeWithTag("favorite-current").assertDoesNotExist()
        val expected = listOf("online", "offline", "long-1", "long-2")
        expected.forEach { rule.onNodeWithTag("favorite-$it").assertExists() }
        rule.onNodeWithTag("favorite-offline").assertIsNotEnabled()
        rule.onNodeWithText("不在线").assertExists()
        assertTrue(snapshot().favoriteSelections.isEmpty())

        rule.onNodeWithTag("favorite-long-2").performScrollTo().assertIsDisplayed()
        val chip = rule.onNodeWithTag("favorite-long-2").getUnclippedBoundsInRoot()
        val back = rule.onNodeWithTag("sessions-back").getUnclippedBoundsInRoot()
        assertFalse(chip.left < back.right && back.left < chip.right && chip.top < back.bottom && back.top < chip.bottom)
        rule.onNodeWithTag("favorite-online").performScrollTo().performClick()
        rule.waitUntil(2_000) { snapshot().favoriteSelections.size == 1 }
        val selected = snapshot()
        assertEquals(listOf("online" to "online-favorite"), selected.favoriteSelections)
        assertEquals("online", selected.currentRef)
        assertEquals("online-favorite", selected.currentName)

        rule.onNodeWithTag("sessions-back").performClick()
        assertDefaultDock()
        assertEquals(expected, snapshot().favoriteRefs.filterNot { it == "current" })
    }

    @Test
    fun sessionsSourceIsEntryIndependentAndEmptyNeverFallsBack() {
        reset("full", "favorites")
        rule.onNodeWithTag("dock_sessions").performClick()
        val fromFavorites = renderedFavoriteRefs()

        reset("full", "ordinary")
        rule.onNodeWithTag("dock_sessions").performClick()
        val fromOrdinary = renderedFavoriteRefs()
        assertEquals(fromFavorites, fromOrdinary)
        assertEquals(listOf("online", "offline", "long-1", "long-2"), fromOrdinary)
        assertFalse(fromOrdinary.contains("current"))

        reset("empty", "ordinary")
        assertTrue(snapshot().viewRefs.contains("must-not-render"))
        rule.onNodeWithTag("dock_sessions").performClick()
        rule.onNodeWithText("暂无收藏").assertIsDisplayed()
        rule.onNodeWithTag("sessions-back").assertIsEnabled()
        rule.onNodeWithTag("favorite-must-not-render").assertDoesNotExist()
        rule.onNodeWithText("workspace-view-b").assertDoesNotExist()
        assertTrue(renderedFavoriteRefs().isEmpty())
        rule.onNodeWithTag("sessions-back").performClick()
        assertDefaultDock()
    }

    @Test
    fun viewUsesExistingCurrentWorkspaceSheet() {
        reset("full", "ordinary")
        val before = snapshot()
        assertFalse(before.overlayOpen)
        rule.onNodeWithTag("dock_view").performClick()
        rule.waitUntil(2_000) { snapshot().overlayOpen }
        rule.onNodeWithTag("session-overlay").assertIsDisplayed()
        rule.onNodeWithTag("l2-row-view-a").assertExists()
        rule.onNodeWithTag("l2-row-view-b").assertExists()
        rule.onNodeWithTag("l2-row-online").assertDoesNotExist()

        rule.onNodeWithTag("session-overlay-scrim").performClick()
        rule.waitUntil(2_000) { !snapshot().overlayOpen }
        assertTrue(snapshot().viewSelections.isEmpty())
        assertTrue(snapshot().favoriteSelections.isEmpty())

        rule.onNodeWithTag("dock_view").performClick()
        rule.waitUntil(2_000) { snapshot().overlayOpen }
        rule.onNodeWithTag("l2-row-view-b").performClick()
        rule.waitUntil(2_000) { !snapshot().overlayOpen }
        assertEquals(listOf("view-b" to "workspace-view-b"), snapshot().viewSelections)
        assertTrue(snapshot().favoriteSelections.isEmpty())

        reset("full", "ordinary")
        rule.onNodeWithTag("dock_sessions").performClick()
        assertFalse(snapshot().overlayOpen)
        rule.onNodeWithTag("session-overlay").assertDoesNotExist()
    }

    @Test
    fun controlledInputAndIme() {
        reset("full", "ordinary")
        val field = rule.onNodeWithTag("session-draft")
        val beforeBounds = field.getUnclippedBoundsInRoot()
        val beforeHeight = beforeBounds.bottom - beforeBounds.top
        field.performClick().assertIsFocused()
        field.performTextInput("first\nsecond")
        field.assertTextEquals("first\nsecond")
        field.performTextReplacement("first\nsecond-edited")
        field.assertTextEquals("first\nsecond-edited")
        val expandedBounds = field.getUnclippedBoundsInRoot()
        val expandedHeight = expandedBounds.bottom - expandedBounds.top
        assertTrue("multiline field did not expand", expandedHeight > beforeHeight)
        assertTrue("field exceeded production max", expandedHeight <= 141.dp)

        rule.onNodeWithTag("session-attach").performClick()
        rule.onNodeWithText("拍照").assertIsDisplayed()
        rule.onNodeWithText("从相册选择").assertIsDisplayed()
        rule.onNodeWithTag("session-send").performClick()
        rule.waitUntil(2_000) { snapshot().inputTexts.lastOrNull() == "" }
        rule.onNodeWithTag("session-draft").assertTextEquals("")
        assertEquals(listOf("first\nsecond", "-edited", ""), snapshot().inputTexts)
    }

    @Test
    fun terminalHostStaysSameAcrossDockModes() {
        reset("full", "ordinary")
        val first = snapshot()
        assertEquals(1, first.terminalCount)
        assertNotEquals(0, first.terminalIdentity)
        assertNotEquals(0, first.presenterIdentity)
        assertEquals("current", first.terminalRef)
        val samples = mutableListOf(first)

        rule.onNodeWithTag("dock_hotkeys").performClick()
        samples += snapshot()
        rule.onNodeWithTag("hotkeys-back").performClick()
        rule.onNodeWithTag("dock_sessions").performClick()
        samples += snapshot()
        rule.onNodeWithTag("sessions-back").performClick()
        rule.onNodeWithTag("dock_view").performClick()
        rule.waitUntil(2_000) { snapshot().overlayOpen }
        samples += snapshot()
        rule.onNodeWithTag("session-overlay-scrim").performClick()
        rule.waitUntil(2_000) { !snapshot().overlayOpen }
        samples += snapshot()

        assertEquals(setOf(1), samples.map { it.terminalCount }.toSet())
        assertEquals(setOf(first.terminalIdentity), samples.map { it.terminalIdentity }.toSet())
        assertEquals(setOf(first.presenterIdentity), samples.map { it.presenterIdentity }.toSet())
        assertEquals(setOf("current"), samples.map { it.terminalRef }.toSet())
    }

    @Test
    fun terminalThemeChangesSessionChrome() {
        reset("full", "ordinary")
        rule.runOnUiThread { rule.activity.selectThemeForTest("vesper") }
        rule.waitForIdle()
        val before = snapshot()
        val terminalBefore = before.terminalIdentity
        rule.onNodeWithTag("session-shell").assertContentDescriptionContains(before.schemeId)

        rule.runOnUiThread { rule.activity.selectThemeForTest("dracula") }
        rule.waitUntil(2_000) { snapshot().themeId == "dracula" && snapshot().chrome != before.chrome }
        val after = snapshot()
        assertNotEquals(before.schemeId, after.schemeId)
        assertNotEquals(before.chrome, after.chrome)
        assertEquals(6, after.chrome.split(',').size)
        assertEquals(terminalBefore, after.terminalIdentity)
        rule.onNodeWithTag("session-shell").assertContentDescriptionContains(after.schemeId)
        assertDefaultDock()
    }

    private fun reset(fixture: String, entry: String) {
        rule.runOnUiThread { rule.activity.resetFixture(fixture, entry, SOURCE_SHA) }
        rule.waitForIdle()
        rule.waitUntil(2_000) { snapshot().sourceValid && snapshot().terminalCount == 1 }
        assertEquals(fixture, snapshot().fixture)
        assertEquals(entry, snapshot().entry)
    }

    private fun snapshot(): SessionUiAcceptanceActivity.DebugSessionSnapshot {
        lateinit var value: SessionUiAcceptanceActivity.DebugSessionSnapshot
        rule.runOnIdle { value = rule.activity.snapshot() }
        return value
    }

    private fun assertDefaultDock() {
        val tags = listOf("dock_hotkeys", "dock_view", "dock_sessions")
        val bounds = tags.map { rule.onNodeWithTag(it).assertIsDisplayed().getUnclippedBoundsInRoot() }
        assertTrue(bounds[0].left < bounds[1].left && bounds[1].left < bounds[2].left)
        rule.onNodeWithTag("session-dock-menu").assertIsDisplayed()
        rule.onNodeWithTag("sessions-back").assertDoesNotExist()
        rule.onNodeWithTag("session-dock-hotkeys").assertDoesNotExist()
    }

    private fun renderedFavoriteRefs(): List<String> =
        listOf("online", "offline", "long-1", "long-2", "current", "must-not-render")
            .filter { rule.onAllNodesWithTag("favorite-$it").fetchSemanticsNodes().isNotEmpty() }

    private companion object {
        const val SOURCE_SHA = "0123456789abcdef0123456789abcdef01234567"
    }
}
