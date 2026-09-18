package dev.agentmirror.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.TextFieldValue
import dev.agentmirror.app.tsnet.ConnectionPath
import dev.agentmirror.app.ui.model.SessionStatus
import dev.agentmirror.app.ui.screens.SessionShellScreen
import dev.agentmirror.app.ui.theme.AppTheme
import dev.agentmirror.app.ui.theme.Appearance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Regression contract for the session draft IME action.
 *
 * The source contract is intentionally independent of the implementation's callback
 * plumbing: the IME must advertise Send, and the Compose action must call onSend.
 * The UI test then invokes that action (not the visible send button) and verifies the
 * command is sent once and the controlled draft is cleared without a newline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SessionImeActionSendTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun draftFieldDeclaresSendImeActionAndRoutesOnSend() {
        val source = sourceFile().readText()
        val draftField = source.substringAfter("private fun DraftField").substringBefore("private fun SendButton")
        assertTrue(
            "DraftField must explicitly advertise ImeAction.Send",
            Regex("""keyboardOptions\s*=.*ImeAction\.Send""").containsMatchIn(draftField),
        )
        assertTrue(
            "DraftField must route the IME action through onSend",
            Regex("""keyboardActions\s*=.*KeyboardActions\s*\(.*onSend\s*=\s*\{[^}]*onSend\(\)""", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(draftField),
        )
        assertTrue(
            "DraftField must remain single-line so Enter cannot insert a newline",
            Regex("""singleLine\s*=\s*true""").containsMatchIn(draftField),
        )
    }

    @Test
    fun imeSendInvokesSendAndClearsDraftWithoutNewline() {
        var draft by mutableStateOf(TextFieldValue(""))
        var sendCount = 0
        compose.setContent {
            AppTheme(appearance = Appearance.Light) {
                SessionShellScreen(
                    sessionDisplayName = "ime-send",
                    status = SessionStatus.Idle,
                    connectionPath = ConnectionPath.LAN,
                    draft = draft,
                    onDraftChange = { draft = it },
                    onSend = {
                        sendCount++
                        draft = TextFieldValue("")
                    },
                    onBack = {},
                    onOpenSwitcher = {},
                    onKeyPress = {},
                    onAttach = {},
                ) {}
            }
        }
        val editor = compose.onNodeWithTag("session-draft")
        editor.performTextInput("echo ime-action")
        compose.waitForIdle()
        editor.performImeAction()
        compose.waitForIdle()

        compose.runOnIdle {
            assertEquals("IME Send must invoke the send callback exactly once", 1, sendCount)
            assertEquals("send callback must clear the controlled draft", "", draft.text)
            assertFalse("IME Send must never insert a newline", draft.text.contains('\n'))
            assertFalse("IME Send must never insert a carriage return", draft.text.contains('\r'))
        }
    }

    private fun sourceFile(): File {
        val relative = "src/main/java/dev/agentmirror/app/ui/screens/SessionShellScreen.kt"
        return sequenceOf(File(relative), File("../app/$relative"))
            .firstOrNull { it.isFile }
            ?: error("cannot locate SessionShellScreen.kt from ${File(".").absolutePath}")
    }
}
