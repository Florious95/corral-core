package dev.agentmirror.app.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InputReboundIntentTest {
    @get:Rule val compose = createComposeRule()

    private class Harness {
        var current by mutableIntStateOf(420)
        var target by mutableIntStateOf(420)
        var visible by mutableStateOf(true)
        var collapseRequested by mutableStateOf(false)
        var collapseRequest by mutableIntStateOf(0)
        val expansionRequest = mutableIntStateOf(0)
        val collapses = mutableListOf<String>()
        lateinit var focusManager: FocusManager
        val focusRequester = FocusRequester()
        val keyboard = object : SoftwareKeyboardController {
            override fun show() = Unit
            override fun hide() = Unit
        }
        fun expand() {
            expansionRequest.intValue++
            collapseRequested = false
            collapseRequest = 0
        }
        fun collapse(source: String) {
            if (collapseRequested) return
            collapses += source
            collapseRequested = true
            collapseRequest++
            keyboard.hide()
            focusManager.clearFocus(force = true)
        }
    }

    private fun mount(current: Int = 420, target: Int = 420, visible: Boolean = true): Harness {
        val h = Harness().apply { this.current = current; this.target = target; this.visible = visible }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            h.focusManager = LocalFocusManager.current
            CompositionLocalProvider(LocalSoftwareKeyboardController provides h.keyboard) {
                SessionDockTheme(dark = false) {
                    ObserveImeHide(h.current, h.target, h.visible, h.collapseRequested, h.expansionRequest) {
                        h.collapse("ime-hide")
                    }
                    CommandInputBar(
                        TextFieldValue(""), {}, {}, {},
                        modifier = Modifier.focusRequester(h.focusRequester),
                        collapseRequest = h.collapseRequest,
                        onExpandRequested = h::expand,
                        onCollapseRequested = { h.collapse("editor-focus-loss") },
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("session-command-editor").performClick()
        settle()
        assertHeight(72f)
        return h
    }

    @Test
    fun explicitReopenDuringOldNativeHideKeepsActualFocusAndExpandedHeight() {
        val h = mount()
        // Explicit terminal/back/send collapse: observer sees the hide while collapseRequested=true,
        // so hideNotified remains false. Native pixels are still from the old keyboard generation.
        compose.runOnIdle {
            h.collapse("terminal-down")
            h.target = 0
            h.current = 210
        }
        settle()
        assertHeight(32f)
        assertEquals(listOf("terminal-down"), h.collapses)

        compose.onNodeWithTag("session-command-editor").performClick()
        settle()
        assertEquals("old target=0 must not clear the newly focused field", listOf("terminal-down"), h.collapses)
        compose.onNodeWithTag("session-command-editor").assertIsFocused()
        assertHeight(72f)

        // The old physical hide may finish before the new show reports its first inset.
        compose.runOnIdle { h.current = 0; h.visible = false }
        settle()
        assertHeight(72f)
        compose.onNodeWithTag("session-command-editor").assertIsFocused()
    }

    @Test
    fun hideAndReopenInOneUiTurnCannotReplayPreviousGeneration() {
        val h = mount()
        compose.runOnIdle {
            h.collapse("terminal-down")
            h.target = 0
            h.current = 210
            // Reacquire actual BasicTextField focus before Compose samples the old hide.
            h.focusRequester.requestFocus()
        }
        settle()
        assertEquals(listOf("terminal-down"), h.collapses)
        compose.onNodeWithTag("session-command-editor").assertIsFocused()
        assertHeight(72f)
    }

    @Test
    fun newSuccessfulShowStillAllowsOneGenuineSystemHide() {
        val h = mount(current = 0, target = 420, visible = true)
        // Metadata/target alone is not a completed visible show.
        compose.runOnIdle { h.target = 0; h.visible = false }
        settle()
        assertTrue(h.collapses.isEmpty())
        assertHeight(72f)
        compose.runOnIdle { h.target = 420; h.current = 420; h.visible = true }
        settle()
        compose.runOnIdle { h.target = 0; h.current = 210 }
        settle()
        assertEquals(listOf("ime-hide"), h.collapses)
        assertHeight(32f)
        compose.runOnIdle { h.current = 0; h.visible = false }
        settle()
        assertEquals(listOf("ime-hide"), h.collapses)
    }

    @Test
    fun floatingKeyboardVisibilityStillCollapsesOnRealHide() {
        val h = mount(current = 0, target = 0, visible = true)
        assertTrue(h.collapses.isEmpty())
        compose.runOnIdle { h.visible = false }
        settle()
        assertEquals(listOf("ime-hide"), h.collapses)
        assertHeight(32f)
    }

    @Test
    fun editorGestureStaysOnEditorWhileItsMeasuredHeightChanges() {
        val sources = mutableListOf<String>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            SessionDockTheme(dark = false) {
                SessionScreenScaffold(
                    terminalCanvas = { Box(Modifier.fillMaxSize()) },
                    value = TextFieldValue(""), onValueChange = {}, onSendText = {},
                    onPickAttachment = {}, onKeyToken = {}, onDockCollapse = { sources += it },
                )
            }
        }
        compose.onNodeWithTag("session-command-editor").performTouchInput { down(center) }
        settle()
        compose.onNodeWithTag("session-command-editor").performTouchInput { up() }
        settle()
        assertTrue("remeasure must not deliver an editor up as a terminal down: $sources", sources.isEmpty())
        compose.onNodeWithTag("session-command-editor").assertIsFocused()
        assertHeight(72f)
    }

    private fun settle() {
        // Await effect-triggered focus/layout work as well as its animation. Native inset samples
        // remain explicitly frozen by Harness; advancing Compose cannot finish the old IME hide.
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun assertHeight(expected: Float) {
        val bounds = compose.onNodeWithTag("session-command-input-field").getUnclippedBoundsInRoot()
        assertEquals(expected, bounds.bottom.value - bounds.top.value, 0.5f)
    }
}
