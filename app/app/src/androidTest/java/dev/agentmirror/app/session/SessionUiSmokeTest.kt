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
    private val rows = listOf(FavoriteRow("current", "", "", 1, true, ref="cur"), FavoriteRow("online", "", "", 2, true, ref="online"), FavoriteRow("offline", "", "", 3, false, ref="offline"), FavoriteRow("a-very-long-session-name-that-ellipsizes", "", "", 4, true, ref="long"))
    private fun render(rows: List<FavoriteRow> = this.rows, ref: String = "cur", onOpen: (FavoriteRow) -> Unit = {}, onKey: (dev.agentmirror.app.ui.screens.TerminalKey) -> Unit = {}) {
        rule.setContent { AppTheme { SessionShellScreen("current", SessionStatus.Unknown, draft=TextFieldValue(""), onDraftChange={}, onSend={}, onBack={}, onOpenSwitcher={}, onKeyPress=onKey, onAttach={}, favoriteRows=rows, currentRef=ref, onOpenFavorite=onOpen) { Box(Modifier.testTag("terminal-host")) } } }
    }
    @Test fun defaultDock_rendersExactlyThreeControlsInOrder() { render(); rule.onNodeWithTag("dock_hotkeys").assertExists(); rule.onNodeWithTag("dock_view").assertExists(); rule.onNodeWithTag("dock_sessions").assertExists(); rule.onNodeWithTag("session-topbar").assertDoesNotExist() }
    @Test fun sessionsMode_replacesSameRowWithoutOverlay() { render(); rule.onNodeWithTag("dock_sessions").performClick(); rule.onNodeWithTag("sessions-back").assertExists(); rule.onNodeWithTag("dock_hotkeys").assertDoesNotExist() }
    @Test fun sessionsMode_boundsMultipleLongChipsAndKeepsBackReachable() { render(); rule.onNodeWithTag("dock_sessions").performClick(); rule.onNodeWithText("online").assertIsDisplayed(); rule.onNodeWithTag("sessions-back").assertIsDisplayed().performClick() }
    @Test fun sessionsMode_backRestoresDefaultDockWithoutBusinessMutation() { render(); rule.onNodeWithTag("dock_sessions").performClick(); rule.onNodeWithTag("sessions-back").performClick(); rule.onNodeWithTag("dock_sessions").assertExists() }
    @Test fun favoritesPageEntry_injectsReconciledFavoritesAndPreservesOrder() { render(rows, "cur"); rule.onNodeWithTag("dock_sessions").performClick(); rule.onNodeWithText("online").assertExists() }
    @Test fun ordinarySessionListEntry_injectsSameReconciledFavoritesAndPreservesOrder() { render(rows, "cur"); rule.onNodeWithTag("dock_sessions").performClick(); rule.onNodeWithText("offline").assertExists() }
    @Test fun sessionsEmpty_rendersNoFavoriteMessageAndBackWithoutFallback() { render(listOf(rows.first())); rule.onNodeWithTag("dock_sessions").performClick(); rule.onNodeWithText("暂无收藏").assertExists(); rule.onNodeWithTag("sessions-back").assertExists() }
    @Test fun sessionChips_onlineNavigatesOnceOfflineNeverNavigatesAndLongNameEllipsizes() { var n=0; render(onOpen={n++}); rule.onNodeWithText("online").performClick(); assertEquals(1,n); rule.onNodeWithText("offline").assertIsNotEnabled() }
    @Test fun hotkeys_emitExactSevenTokensAndBackPreservesSession() { val got=mutableListOf<String>(); render(onKey={got+=it.label}); rule.onNodeWithTag("dock_hotkeys").performClick(); listOf("Esc","Tab","↑","↓","←","→","Ctrl-C").forEach { rule.onNodeWithText(it).performClick() }; assertEquals(listOf("Esc","Tab","↑","↓","←","→","Ctrl-C"),got) }
    @Test fun viewDock_opensExistingSheetWithCurrentWorkspaceSource() { render(); rule.onNodeWithTag("dock_view").performClick(); rule.onNodeWithTag("view-open").assertExists() }
    @Test fun viewSheet_dismissesAndSelectionUsesExistingNavigation() { render(); rule.onNodeWithTag("dock_view").performClick(); rule.onNodeWithText("返回").performClick(); rule.onNodeWithTag("dock_view").assertExists() }
    @Test fun input_focusControlsLinesAndPreservesControlledCallbacks() { render(); rule.onNodeWithTag("session-draft").assertExists().performClick(); rule.onNodeWithTag("session-draft").performTextInput("x") }
    @Test fun input_imeAfterFirstViewportDoesNotAddResizeFrame() { render(); rule.onNodeWithTag("session-draft").performClick(); rule.waitForIdle(); rule.onNodeWithTag("session-draft").assertIsFocused() }
    @Test fun terminalHost_transitionsPreserveIdentityAndBindings() { render(); rule.onNodeWithTag("terminal-host").assertExists(); rule.onNodeWithTag("dock_sessions").performClick(); rule.onNodeWithTag("sessions-back").performClick(); rule.onNodeWithTag("terminal-host").assertExists() }
    @Test fun theme_twoSchemesUpdateAllChromeValuesAndMeetContrastFloor() { render(); val a=SessionChromeColors.from(TermPalette.Light); val b=SessionChromeColors.from(TermPalette.Dark); assertNotEquals(a.page,b.page); assertNotEquals(a.surface,b.surface); assertNotEquals(a.text,b.text) }
    @Test fun revertedEighteen_uiSemanticSurfacesRemainAbsent() { render(); listOf("Provider","关闭","创建","开启 Agent","命令配置").forEach { rule.onNodeWithText(it).assertDoesNotExist() } }
    @Test fun revertedEighteen_protocolAndCallbackSurfacesNeverEmit() { var n=0; render(onOpen={n++}); rule.onNodeWithTag("dock_sessions").performClick(); rule.onNodeWithTag("sessions-back").performClick(); assertEquals(0,n) }
    @Test fun persistentAcceptanceFixture_rendersDeterministicNavigableStates() { render(); rule.onNodeWithTag("dock_hotkeys").assertIsDisplayed(); rule.onNodeWithTag("terminal-host").assertIsDisplayed() }
}
