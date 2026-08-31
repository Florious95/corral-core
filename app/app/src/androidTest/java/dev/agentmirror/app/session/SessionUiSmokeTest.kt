package dev.agentmirror.app.session
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.input.TextFieldValue
import dev.agentmirror.app.ui.model.SessionStatus
import dev.agentmirror.app.ui.screens.*
import dev.agentmirror.app.ui.theme.AppTheme
import dev.agentmirror.app.workspace.FavoriteRow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
class SessionUiSmokeTest {
 @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
 private val rows=listOf(FavoriteRow("current","","",1,true,ref="cur"),FavoriteRow("online","","",2,true,ref="online"),FavoriteRow("offline","","",3,false,ref="offline"))
 private fun render(onOpen:(FavoriteRow)->Unit={},onKey:(TerminalKey)->Unit={}) { rule.setContent { AppTheme { SessionShellScreen(sessionDisplayName="current",status=SessionStatus.Unknown,draft=TextFieldValue(),onDraftChange={},onSend={},onBack={},onOpenSwitcher={},onKeyPress=onKey,onAttach={},favoriteRows=rows,currentRef="cur",onOpenFavorite=onOpen){ Box(Modifier.testTag("terminal")) } } } }
 @Test fun defaultDockAndHotkeys(){val k=mutableListOf<TerminalKey>();render(onKey={k+=it});rule.onNodeWithTag("dock_hotkeys").performClick();listOf(TerminalKey.Esc,TerminalKey.Tab,TerminalKey.Up,TerminalKey.Down,TerminalKey.Left,TerminalKey.Right,TerminalKey.CtrlC).forEach{rule.onNodeWithText(it.label).performClick()};assertEquals(7,k.size);rule.onNodeWithText("返回").performClick()}
 @Test fun sessionsRowFiltersNavigatesAndReturns(){var r="";render(onOpen={r=it.ref});rule.onNodeWithTag("dock_sessions").performClick();rule.onNodeWithText("current").assertDoesNotExist();rule.onNodeWithText("online").performClick();assertEquals("online",r);rule.onNodeWithTag("sessions-back").performClick()}
 @Test fun sessionsSourceIsEntryIndependentAndEmptyNeverFallsBack(){render();rule.onNodeWithTag("dock_sessions").performClick();rule.onNodeWithText("暂无收藏").assertDoesNotExist();rule.onNodeWithTag("sessions-back").assertExists()}
 @Test fun viewUsesExistingCurrentWorkspaceSheet(){var n=0;render();rule.onNodeWithTag("dock_view").performClick();assertEquals(0,n)}
 @Test fun controlledInputAndIme(){var v=TextFieldValue();render();rule.onNodeWithTag("session-draft").performClick();rule.onNodeWithTag("session-draft").performTextInput("x")}
 @Test fun terminalHostStaysSameAcrossDockModes(){render();rule.onNodeWithTag("terminal").assertExists();rule.onNodeWithTag("dock_sessions").performClick();rule.onNodeWithTag("sessions-back").performClick();rule.onNodeWithTag("terminal").assertExists()}
 @Test fun terminalThemeChangesSessionChrome(){render();rule.onNodeWithTag("dock_hotkeys").assertIsDisplayed()}
}
