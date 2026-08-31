package dev.agentmirror.app.session

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.input.TextFieldValue
import dev.agentmirror.app.ui.model.SessionStatus
import dev.agentmirror.app.ui.screens.SessionShellScreen
import dev.agentmirror.app.ui.screens.otherFavoriteRows
import dev.agentmirror.app.ui.theme.AppTheme
import dev.agentmirror.app.ui.theme.SessionChromeColors
import dev.agentmirror.app.ui.theme.TermPalette
import dev.agentmirror.app.workspace.FavoriteRow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SessionUiSmokeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val rows = listOf(
        FavoriteRow("current", "", "", 1, true, ref = "cur"),
        FavoriteRow("online", "", "", 2, true, ref = "online"),
        FavoriteRow("offline", "", "", 3, false, ref = "offline"),
    )

    @Test fun realComposeDockAndFavoritesSemantics() {
        var opens = 0
        var keys = 0
        rule.setContent {
            AppTheme { SessionShellScreen("current", SessionStatus.Unknown, draft = TextFieldValue(""), onDraftChange = {}, onSend = {}, onBack = {}, onOpenSwitcher = {}, onKeyPress = { keys++ }, onAttach = {}, favoriteRows = rows, currentRef = "cur", onOpenFavorite = { opens++ }) { Box(Modifier.testTag("terminal-host")) } }
        }
        rule.onNodeWithTag("dock_hotkeys").assertExists()
        rule.onNodeWithTag("dock_view").assertExists()
        rule.onNodeWithTag("dock_sessions").assertExists()
        assertEquals(3, rule.onAllNodesWithTag("dock_hotkeys").fetchSemanticsNodes().size + rule.onAllNodesWithTag("dock_view").fetchSemanticsNodes().size + rule.onAllNodesWithTag("dock_sessions").fetchSemanticsNodes().size)
        rule.onNodeWithTag("dock_sessions").performClick()
        rule.onNodeWithText("online").assertExists()
        rule.onNodeWithText("offline").assertExists().assertIsNotEnabled()
        rule.onNodeWithText("current").assertDoesNotExist()
        rule.onNodeWithText("online").performClick()
        assertEquals(1, opens)
        rule.onNodeWithTag("sessions-back").performClick()
        rule.onNodeWithTag("dock_sessions").assertExists()
        rule.onNodeWithTag("dock_hotkeys").performClick()
        listOf("Esc", "Tab", "↑", "↓", "←", "→", "Ctrl-C").forEach { rule.onNodeWithText(it).performClick() }
        assertEquals(7, keys)
    }

    @Test fun favoriteSourceExcludesCurrentForBothEntryFixturesAndEmptyState() {
        assertEquals(listOf("online", "offline"), otherFavoriteRows(rows, "cur").map { it.ref })
        assertEquals(listOf("online", "offline"), otherFavoriteRows(rows, "cur").map { it.ref })
        assertTrue(otherFavoriteRows(listOf(rows.first()), "cur").isEmpty())
        val light = SessionChromeColors.from(TermPalette.Light)
        val dark = SessionChromeColors.from(TermPalette.Dark)
        assertNotEquals(light.page, dark.page)
        assertNotEquals(light.success, dark.success)
    }
}
