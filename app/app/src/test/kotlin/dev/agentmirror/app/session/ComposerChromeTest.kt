package dev.agentmirror.app.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.input.TextFieldValue
import dev.agentmirror.app.ui.model.SessionStatus
import dev.agentmirror.app.ui.screens.SessionShellScreen
import dev.agentmirror.app.ui.theme.AppTheme
import dev.agentmirror.app.ui.theme.Dims
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * E2 膨胀 / E3 焦点 / E4 加号发送收进框内。先验红：高度锁 40.dp、发送在 DraftField 外。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerChromeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun clickDraft_expandsToInputHeightExpanded() {
        var expanded by mutableStateOf(false)
        var draft by mutableStateOf(TextFieldValue(""))
        compose.setContent {
            AppTheme {
                SessionShellScreen(
                    sessionDisplayName = "s",
                    status = SessionStatus.Idle,
                    draft = draft,
                    onDraftChange = { draft = it },
                    onSend = {},
                    onBack = {},
                    onOpenSwitcher = {},
                    onKeyPress = {},
                    onAttach = {},
                    composerExpanded = expanded,
                    onToggleExpand = { expanded = !expanded },
                ) { Box(Modifier.fillMaxSize()) }
            }
        }
        compose.onNodeWithTag("session-draft").performClick()
        compose.waitForIdle()
        val b = compose.onNodeWithTag("session-composer").getUnclippedBoundsInRoot()
        val h = b.bottom - b.top
        assertTrue("expanded height $h vs ${Dims.inputHeightExpanded}", h.value >= Dims.inputHeightExpanded.value * 0.9f)
    }

    @Test
    fun sendCentersInsideComposerSurface() {
        compose.setContent {
            AppTheme {
                SessionShellScreen(
                    sessionDisplayName = "s",
                    status = SessionStatus.Idle,
                    draft = TextFieldValue(""),
                    onDraftChange = {},
                    onSend = {},
                    onBack = {},
                    onOpenSwitcher = {},
                    onKeyPress = {},
                    onAttach = {},
                ) { Box(Modifier.fillMaxSize()) }
            }
        }
        val composer = compose.onNodeWithTag("session-composer").getUnclippedBoundsInRoot()
        val send = compose.onNodeWithTag("session-send").getUnclippedBoundsInRoot()
        val attach = compose.onNodeWithTag("session-attach").getUnclippedBoundsInRoot()
        val sendCx = (send.left + send.right) / 2
        assertTrue("send center $sendCx composer ${composer.left}..${composer.right}", sendCx <= composer.right)
        assertTrue("attach inside", attach.left >= composer.left)
    }

    @Test
    fun keyCapClick_doesNotStealDraftFocus() {
        var draft by mutableStateOf(TextFieldValue("ab"))
        compose.setContent {
            AppTheme {
                SessionShellScreen(
                    sessionDisplayName = "s",
                    status = SessionStatus.Idle,
                    draft = draft,
                    onDraftChange = { draft = it },
                    onSend = {},
                    onBack = {},
                    onOpenSwitcher = {},
                    onKeyPress = {},
                    onAttach = {},
                ) { Box(Modifier.fillMaxSize()) }
            }
        }
        compose.onNodeWithTag("session-draft").requestFocus()
        compose.onNodeWithTag("session-key-esc").performClick()
        compose.onNodeWithTag("session-draft").assertIsFocused()
    }
}
