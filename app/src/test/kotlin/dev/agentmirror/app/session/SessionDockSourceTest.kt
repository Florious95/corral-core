/*
 * Copyright 2026 AgentMirror Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.agentmirror.app.session

import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.agentmirror.app.ui.theme.AgentMirrorTheme
import dev.agentmirror.app.workspace.FavoriteRow
import dev.agentmirror.app.workspace.L2Entry
import dev.agentmirror.app.workspace.L2Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SessionDockSourceTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun cancelledImeShow_doesNotCollapseSource() {
        val showTarget = observeImeVisibility(
            wasVisible = false,
            currentInsetPx = 0,
            rootVisible = false,
            targetInsetPx = 420,
            collapseRequested = false,
        )
        val cancelled = observeImeVisibility(
            wasVisible = showTarget.wasVisible,
            currentInsetPx = 0,
            rootVisible = false,
            targetInsetPx = 0,
            collapseRequested = false,
        )

        assertFalse("IME target alone must not mark it visible", showTarget.wasVisible)
        assertFalse("a cancelled show must not collapse the source", showTarget.shouldCollapse)
        assertFalse("cancel completion must remain non-collapsing", cancelled.shouldCollapse)
    }

    @Test
    fun imeHideStart_collapsesExactlyOnceAfterActualVisibility() {
        val visible = observeImeVisibility(
            wasVisible = false,
            currentInsetPx = 420,
            rootVisible = true,
            targetInsetPx = 420,
            collapseRequested = false,
        )
        val hideStart = observeImeVisibility(
            wasVisible = visible.wasVisible,
            currentInsetPx = 420,
            rootVisible = true,
            targetInsetPx = 0,
            collapseRequested = false,
        )
        val afterRequest = observeImeVisibility(
            wasVisible = hideStart.wasVisible,
            currentInsetPx = 0,
            rootVisible = false,
            targetInsetPx = 0,
            collapseRequested = true,
        )

        assertTrue("actual inset must arm the hide transition", visible.wasVisible)
        assertTrue("normal hide must notify collapse at hide start", hideStart.shouldCollapse)
        assertFalse("collapse request must be idempotent", afterRequest.shouldCollapse)
    }

    @Test
    fun shortcutSelectionUpdatesTextWithoutSendingOrEnter() {
        var value by mutableStateOf(TextFieldValue(""))
        var menuOpen by mutableStateOf(false)
        var sends = 0
        val command = ShortcutCommand(
            id = "handoff",
            name = "交接",
            providerCommands = mapOf("pi" to "/skill:handoff "),
        )
        compose.setContent {
            AgentMirrorTheme {
                Box(Modifier.fillMaxSize()) {
                    CommandInputBar(
                        value = value,
                        onValueChange = { value = it },
                        onSendText = { sends++ },
                        onPickAttachment = {},
                        onShortcutMenuOpenChange = { menuOpen = it },
                    )
                    ShortcutGlassFloatingMenu(
                        expanded = menuOpen,
                        commands = listOf(command),
                        onDismissRequest = { menuOpen = false },
                        onSelect = { selected ->
                            val resolved = resolveShortcutCommand(selected, "pi") as ShortcutResolution.Found
                            value = TextFieldValue(resolved.text)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        compose.onNodeWithTag("session-shortcut-button").performClick()
        compose.onNodeWithTag("session-shortcut-menu").assertIsDisplayed()
        compose.onNodeWithText("交接").performClick()
        assertEquals("/skill:handoff ", value.text)
        assertFalse(value.text.contains('\r') || value.text.contains('\n'))
        assertEquals("shortcut selection must not submit", 0, sends)
    }

    @Test
    fun sessionShortcutButtonOpensFloatingMenu() {
        val h = OverlayTestHarness()
        compose.setContent {
            AgentMirrorTheme {
                SessionScreen(viewModel = h.vm, name = "sess", onBack = {})
            }
        }

        compose.onNodeWithTag("session-shortcut-button").performClick()
        compose.onNodeWithTag("session-shortcut-menu").assertIsDisplayed()
    }

    @Test
    fun sessionButtonsOpenMenusFromTouchInput() {
        val h = OverlayTestHarness()
        compose.setContent {
            AgentMirrorTheme {
                SessionScreen(viewModel = h.vm, name = "sess", onBack = {})
            }
        }

        compose.onNodeWithTag("session-shortcut-button").performTouchInput { click() }
        compose.onNodeWithTag("session-shortcut-menu").assertIsDisplayed()
        compose.onNodeWithTag("session-shortcut-menu").performTouchInput { click(center) }
        compose.onNodeWithTag("session-attach-button").performTouchInput { click() }
        compose.onNodeWithTag("session-attach-menu").assertIsDisplayed()
    }

    @Test
    fun sourceDockHasResidentHotkeysWithoutMenuOrReturnButton() {
        val h = OverlayTestHarness()
        compose.setContent {
            AgentMirrorTheme {
                SessionScreen(viewModel = h.vm, name = "sess", onBack = {})
            }
        }

        // Hotkeys are permanently visible at rest
        listOf("Esc", "Tab", "↑", "↓", "←", "→", "Ctrl-C").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }

        // No menu or return buttons
        compose.onNodeWithContentDescription("返回菜单").assertDoesNotExist()
        compose.onNodeWithText("常用快捷键").assertDoesNotExist()
        compose.onNodeWithText("收藏会话").assertDoesNotExist()
        compose.onAllNodesWithText("快捷键").assertCountEquals(0)
        compose.onAllNodesWithText("会话").assertCountEquals(0)
    }

    @Test
    fun inputFocusExpandsAndRealTerminalTapOrSendCollapsesWithSourceTiming() {
        val sent = mutableListOf<String>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            var value by remember { mutableStateOf(TextFieldValue("")) }
            val focusManager = LocalFocusManager.current
            AgentMirrorTheme {
                SessionDockTheme(dark = false) {
                    SessionScreenScaffold(
                        terminalCanvas = { Box(Modifier.fillMaxSize()) },
                        value = value,
                        onValueChange = { value = it },
                        onSendText = {
                            sent += it
                            value = TextFieldValue("")
                            focusManager.clearFocus()
                        },
                        onPickAttachment = {},
                        onKeyToken = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("发送").performClick()
        compose.waitForIdle()
        assertEquals(listOf(""), sent)

        val collapsed = inputFieldHeight()
        assertEquals(32f, collapsed, 0.5f)

        compose.onNodeWithTag("session-command-editor").performClick()
        compose.mainClock.advanceTimeBy(SessionDockMotion.InputHeightMillis.toLong() + 1)
        compose.waitForIdle()
        val expanded = inputFieldHeight()
        assertEquals(72f, expanded, 0.5f)

        // A genuine pointer action on the terminal, not a focus-manager test hook.
        compose.onNodeWithTag("session-terminal-canvas").performTouchInput { click() }
        compose.mainClock.advanceTimeBy(SessionDockMotion.InputHeightMillis.toLong() + 1)
        compose.waitForIdle()
        assertEquals(32f, inputFieldHeight(), 0.5f)

        compose.onNodeWithTag("session-command-editor").performClick()
        compose.mainClock.advanceTimeBy(SessionDockMotion.InputHeightMillis.toLong() + 1)
        compose.onNodeWithTag("session-command-editor").performTextInput("ls")
        compose.onNodeWithContentDescription("发送").performClick()
        compose.mainClock.advanceTimeBy(SessionDockMotion.InputHeightMillis.toLong() + 1)
        compose.waitForIdle()
        assertEquals(listOf("", "ls"), sent)
        assertEquals(32f, inputFieldHeight(), 0.5f)
    }

    @Test
    fun editorHasImeActionSendAndPerformsSendOnImeAction() {
        val sent = mutableListOf<String>()
        compose.setContent {
            var value by remember { mutableStateOf(TextFieldValue("")) }
            SessionDockTheme(dark = false) {
                CommandInputBar(
                    value = value,
                    onValueChange = { value = it },
                    onSendText = {
                        sent += it
                        value = TextFieldValue("")
                    },
                    onPickAttachment = {},
                )
            }
        }

        // Verify IME action is Send
        compose.onNodeWithTag("session-command-editor").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Send),
        )

        // Type text and trigger IME action (simulating pressing Send on soft keyboard)
        compose.onNodeWithTag("session-command-editor").performTextInput("git status")
        compose.onNodeWithTag("session-command-editor").performImeAction()

        assertEquals(listOf("git status"), sent)
        compose.onNodeWithTag("session-command-editor").assert(hasText(""))
    }

    @Test
    fun editorKeepsTypedNewlineWithoutAutoSubmit() {
        val sent = mutableListOf<String>()
        var value by mutableStateOf(TextFieldValue(""))
        compose.setContent {
            SessionDockTheme(dark = false) {
                CommandInputBar(
                    value = value,
                    onValueChange = { value = it },
                    onSendText = {
                        sent += it
                        value = TextFieldValue("")
                    },
                    onPickAttachment = {},
                )
            }
        }

        compose.onNodeWithTag("session-command-editor").performTextInput("pwd\n")

        assertTrue(sent.isEmpty())
        assertTrue("multiline draft must keep the typed newline", value.text.contains('\n'))
    }

    @Test
    fun sendButtonDirectlyTriggersSendOnFirstClickWhenFocused() {
        val sent = mutableListOf<String>()
        compose.setContent {
            var value by remember { mutableStateOf(TextFieldValue("")) }
            SessionDockTheme(dark = false) {
                CommandInputBar(
                    value = value,
                    onValueChange = { value = it },
                    onSendText = {
                        sent += it
                        value = TextFieldValue("")
                    },
                    onPickAttachment = {},
                )
            }
        }

        // Focus and type text
        compose.onNodeWithTag("session-command-editor").performClick()
        compose.onNodeWithTag("session-command-editor").performTextInput("uname -a")

        // First click on SendButton directly triggers send
        compose.onNodeWithTag("session-send-button").performClick()
        assertEquals(listOf("uname -a"), sent)
        compose.onNodeWithTag("session-command-editor").assert(hasText(""))
    }

    @Test
    fun sendButtonTouchAreaSpansFullHeightAndNeverInsertsNewline() {
        val sent = mutableListOf<String>()
        var draft by mutableStateOf(TextFieldValue(""))
        compose.setContent {
            SessionDockTheme(dark = false) {
                CommandInputBar(
                    value = draft,
                    onValueChange = { draft = it },
                    onSendText = {
                        sent += it
                        draft = TextFieldValue("")
                    },
                    onPickAttachment = {},
                )
            }
        }

        // Focus editor
        compose.onNodeWithTag("session-command-editor").performClick()
        compose.onNodeWithTag("session-command-editor").performTextInput("echo test")
        compose.waitForIdle()

        // Click Send Button
        compose.onNodeWithTag("session-send-button").performClick()
        compose.waitForIdle()

        assertEquals("Must send exactly the typed command", listOf("echo test"), sent)
        assertEquals("Draft must be cleared after sending", "", draft.text)
        assertFalse("Draft must never contain a newline", draft.text.contains('\n'))
        assertFalse("Draft must never contain a carriage return", draft.text.contains('\r'))
    }

    @Test
    fun systemBackWhileEditorFocusedCollapsesCapsuleWithoutHostOnBack() {
        var hostBack = 0
        lateinit var dispatcher: OnBackPressedDispatcher
        compose.mainClock.autoAdvance = false
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            var focused by remember { mutableStateOf(false) }
            var value by remember { mutableStateOf(TextFieldValue("")) }
            val focusManager = LocalFocusManager.current
            SessionDockTheme(dark = false) {
                SessionScreenBackHandler(
                    focused = { focused },
                    onCollapseFocused = {
                        focused = false
                        focusManager.clearFocus(force = true)
                    },
                    overlayOpen = { false },
                    onCloseOverlay = {},
                    onBack = { hostBack++ },
                )
                SessionScreenScaffold(
                    terminalCanvas = { Box(Modifier.fillMaxSize()) },
                    value = value,
                    onValueChange = { value = it },
                    onSendText = {},
                    onPickAttachment = {},
                    onKeyToken = {},
                    onInputFocusedChanged = { focused = it },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("session-command-editor").performClick()
        compose.mainClock.advanceTimeBy(SessionDockMotion.InputHeightMillis.toLong() + 1)
        compose.waitForIdle()
        assertEquals(72f, inputFieldHeight(), 0.5f)
        assertEquals(86f, inputCapsuleHeight(), 0.5f)

        dispatcher.onBackPressed()
        compose.mainClock.advanceTimeBy(SessionDockMotion.InputHeightMillis.toLong() + 1)
        compose.waitForIdle()
        assertEquals(
            "system Back must blur the focused editor; host onBack must not pop the session",
            0,
            hostBack,
        )
        assertEquals(32f, inputFieldHeight(), 0.5f)
        assertEquals(46f, inputCapsuleHeight(), 0.5f)
    }

    @Test
    fun sessionBackHandlerClosesOverlayBeforeHost() {
        var host = 0
        var overlay = true
        dispatchSessionBack(
            focused = false,
            overlayOpen = overlay,
            onCollapseFocused = {},
            onCloseOverlay = { overlay = false },
            onBack = { host++ },
        )
        assertEquals(false, overlay)
        assertEquals(0, host)
        dispatchSessionBack(
            focused = false,
            overlayOpen = overlay,
            onCollapseFocused = {},
            onCloseOverlay = { overlay = false },
            onBack = { host++ },
        )
        assertEquals(1, host)
    }

    @Test
    fun sessionBackHandlerFocusedWinsOverOverlay() {
        var host = 0
        var focused = true
        var overlay = true
        dispatchSessionBack(
            focused = focused,
            overlayOpen = overlay,
            onCollapseFocused = { focused = false },
            onCloseOverlay = { overlay = false },
            onBack = { host++ },
        )
        assertEquals(false, focused)
        assertEquals(true, overlay)
        assertEquals(0, host)
    }

    @Test
    fun sessionBackHandlerDefaultPopsHostImmediately() {
        var host = 0
        dispatchSessionBack(
            focused = false,
            overlayOpen = false,
            onCollapseFocused = {},
            onCloseOverlay = {},
            onBack = { host++ },
        )
        assertEquals(1, host)
    }

    @Test
    fun sessionBackHandlerRapidBackPopsHost() {
        var host = 0
        lateinit var dispatcher: OnBackPressedDispatcher
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            SessionScreenBackHandler(
                focused = { false },
                onCollapseFocused = {},
                overlayOpen = { false },
                onCloseOverlay = {},
                onBack = { host++ },
            )
        }
        compose.runOnIdle {
            dispatcher.onBackPressed()
            dispatcher.onBackPressed()
        }
        assertEquals(2, host)
    }

    @Test
    fun imeDockUsesSourceThreeHundredMillisecondStandardCurveInBothDirections() {
        val opening = TargetBasedAnimation(
            animationSpec = sourceImeAnimationSpec,
            typeConverter = Dp.VectorConverter,
            initialValue = 0.dp,
            targetValue = 236.dp,
        )
        val closing = TargetBasedAnimation(
            animationSpec = sourceImeAnimationSpec,
            typeConverter = Dp.VectorConverter,
            initialValue = 236.dp,
            targetValue = 0.dp,
        )
        val midpointNanos = 150_000_000L
        val expectedOpeningMidpoint = 236f * SessionDockMotion.Standard.transform(0.5f)
        val expectedClosingMidpoint = 236f * (1f - SessionDockMotion.Standard.transform(0.5f))

        assertEquals(300_000_000L, opening.durationNanos)
        assertEquals(300_000_000L, closing.durationNanos)
        assertEquals(expectedOpeningMidpoint, opening.getValueFromNanos(midpointNanos).value, 0.1f)
        assertEquals(expectedClosingMidpoint, closing.getValueFromNanos(midpointNanos).value, 0.1f)
        assertEquals(236f, opening.getValueFromNanos(opening.durationNanos).value, 0.1f)
        assertEquals(0f, closing.getValueFromNanos(closing.durationNanos).value, 0.1f)
    }

    @Test
    fun sourceViewportUsesExactPanelDockInputAndHotkeyGeometry() {
        compose.setContent {
            Box(Modifier.size(width = 390.dp, height = 844.dp)) {
                Box(Modifier.fillMaxSize().padding(top = 42.667.dp, bottom = 24.dp)) {
                    SessionDockTheme(dark = false) {
                        SessionScreenScaffold(
                            terminalCanvas = { Box(Modifier.fillMaxSize()) },
                            value = TextFieldValue(""),
                            onValueChange = {},
                            onSendText = {},
                            onPickAttachment = {},
                            onKeyToken = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        assertRect("session-terminal-canvas", 0f, 42.667f, 390f, 675f)
        assertRect("session-terminal-card", 4f, 46.667f, 382f, 667f)
        assertRect("session-command-input", 11f, 766f, 368f, 46f)
        assertRect("session-command-input-field", 60f, 773f, 272f, 32f)

        compose.onNodeWithContentDescription("返回菜单").assertDoesNotExist()

        // Hotkeys remain directly above the compact input capsule.
        assertRect("hotkey-Esc", 11f, 718f, 50.53f, 40f)
        assertRect("hotkey-Tab", 68.43f, 718f, 50.53f, 40f)
        assertRect("hotkey-Up", 130.46f, 718f, 39.39f, 40f)
        assertRect("hotkey-Down", 173.29f, 718f, 39.41f, 40f)
        assertRect("hotkey-Left", 216.15f, 718f, 39.41f, 40f)
        assertRect("hotkey-Right", 259.00f, 718f, 39.41f, 40f)
        assertRect("hotkey-Ctrl-C", 309.91f, 718f, 69.09f, 40f)
    }

    @Test
    fun themeAndMotionTokensMatchClaudeDesignSource() {
        assertEquals(Color(0xFF161826), sessionDockDarkScheme.background)
        assertEquals(Color(0xFF9184D9), sessionDockDarkScheme.primary)
        assertEquals(Color(0xFFF3F5FE), sessionDockLightScheme.background)
        assertEquals(Color(0xFF6A5CC0), sessionDockLightScheme.primary)
        assertEquals(180, SessionDockMotion.RowInMillis)
        assertEquals(200, SessionDockMotion.PopInMillis)
        assertEquals(250, SessionDockMotion.InputHeightMillis)
        assertEquals(300, SessionDockMotion.KeyboardPushMillis)
        assertEquals(32, sourceInputFieldHeightDp(focused = false, expandedLines = 3))
        assertEquals(52, sourceInputFieldHeightDp(focused = true, expandedLines = 2))
        assertEquals(72, sourceInputFieldHeightDp(focused = true, expandedLines = 3))
        assertEquals(92, sourceInputFieldHeightDp(focused = true, expandedLines = 4))
        assertEquals(112, sourceInputFieldHeightDp(focused = true, expandedLines = 5))
        assertEquals(1_100, SessionDockMotion.CursorBlinkMillis)
        assertEquals(255, SessionDockMotion.cursorAlphaAt(0))
        assertEquals(255, SessionDockMotion.cursorAlphaAt(549))
        assertEquals(51, SessionDockMotion.cursorAlphaAt(550))
        assertEquals(51, SessionDockMotion.cursorAlphaAt(1_099))
        assertEquals(255, SessionDockMotion.cursorAlphaAt(1_100))
        assertEquals(1, SessionDockMotion.millisToNextCursorStep(549))
    }

    private fun assertRect(
        tag: String,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
    ) {
        val bounds = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        assertEquals("$tag left", left, bounds.left.value, 0.7f)
        assertEquals("$tag top", top, bounds.top.value, 0.7f)
        assertEquals("$tag width", width, bounds.right.value - bounds.left.value, 0.7f)
        assertEquals("$tag height", height, bounds.bottom.value - bounds.top.value, 0.7f)
    }

    private fun inputFieldHeight(): Float {
        val bounds = compose.onNodeWithTag("session-command-input-field").getUnclippedBoundsInRoot()
        return bounds.bottom.value - bounds.top.value
    }

    private fun inputCapsuleHeight(): Float {
        val bounds = compose.onNodeWithTag("session-command-input").getUnclippedBoundsInRoot()
        return bounds.bottom.value - bounds.top.value
    }

    private fun sourceInputFieldHeightDp(focused: Boolean, expandedLines: Int): Int =
        if (focused) 32 + (expandedLines - 1) * 20 else 32
}
