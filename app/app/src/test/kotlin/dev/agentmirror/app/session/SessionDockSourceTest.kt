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
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.agentmirror.app.ui.theme.AgentMirrorTheme
import dev.agentmirror.app.workspace.FavoriteRow
import dev.agentmirror.app.workspace.L2Entry
import dev.agentmirror.app.workspace.L2Status
import org.junit.Assert.assertEquals
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
    fun sourceMenuHasExactlyThreeActionsAndDelegatesViewOverlay() {
        val h = OverlayTestHarness()
        compose.setContent {
            AgentMirrorTheme {
                SessionScreen(viewModel = h.vm, name = "sess", onBack = {})
            }
        }

        compose.onNodeWithTag("favorite-session-list").assertIsDisplayed()
        compose.onAllNodesWithText("快捷键").assertCountEquals(0)
        compose.onNodeWithContentDescription("返回菜单").performClick()
        compose.waitForIdle()

        compose.onAllNodesWithText("快捷键").assertCountEquals(1)
        compose.onAllNodesWithText("查看").assertCountEquals(1)
        compose.onAllNodesWithText("会话").assertCountEquals(1)
        compose.onNodeWithText("常用快捷键").assertDoesNotExist()
        compose.onNodeWithText("收藏会话").assertDoesNotExist()

        compose.onNodeWithTag("dock-open-hotkeys").performClick()
        compose.waitForIdle()
        listOf("Esc", "Tab", "↑", "↓", "←", "→", "Ctrl-C").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        compose.onNodeWithContentDescription("返回菜单").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("session-overlay-open").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("session-overlay").assertIsDisplayed()
    }

    @Test
    fun inputFocusExpandsAndRealTerminalTapOrSendCollapsesWithSourceTiming() {
        var sent = ""
        compose.mainClock.autoAdvance = false
        compose.setContent {
            var value by remember { mutableStateOf(TextFieldValue("")) }
            val focusManager = LocalFocusManager.current
            val listState = rememberLazyListState()
            AgentMirrorTheme {
                SessionDockTheme(dark = false) {
                    SessionScreenScaffold(
                        terminalCanvas = { Box(Modifier.fillMaxSize()) },
                        dockMode = DockRowMode.Sessions,
                        onDockModeChange = {},
                        sessions = emptyList(),
                        sessionListState = listState,
                        onSessionSelect = {},
                        value = value,
                        onValueChange = { value = it },
                        onSendText = {
                            sent = it
                            value = TextFieldValue("")
                            focusManager.clearFocus()
                        },
                        onPickAttachment = {},
                        onKeyToken = {},
                        onOpenViewMenu = {},
                    )
                }
            }
        }
        compose.waitForIdle()
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
        assertEquals("ls", sent)
        assertEquals(32f, inputFieldHeight(), 0.5f)
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
                    dockMode = { DockRowMode.Sessions },
                    onCloseOverlay = {},
                    onDockModeChange = {},
                    onBack = { hostBack++ },
                )
                SessionScreenScaffold(
                    terminalCanvas = { Box(Modifier.fillMaxSize()) },
                    dockMode = DockRowMode.Sessions,
                    onDockModeChange = {},
                    sessions = emptyList(),
                    sessionListState = rememberLazyListState(),
                    onSessionSelect = {},
                    value = value,
                    onValueChange = { value = it },
                    onSendText = {},
                    onPickAttachment = {},
                    onKeyToken = {},
                    onInputFocusedChanged = { focused = it },
                    onOpenViewMenu = {},
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
    fun sessionBackHandlerClosesOverlayAndReturnsDefaultBeforeHost() {
        var host = 0
        var overlay = true
        var dock = DockRowMode.Menu
        dispatchSessionBack(
            focused = false,
            overlayOpen = overlay,
            dockMode = dock,
            onCollapseFocused = {},
            onCloseOverlay = { overlay = false },
            onDockModeChange = { dock = it },
            onBack = { host++ },
        )
        assertEquals(false, overlay)
        assertEquals(DockRowMode.Sessions, dock)
        assertEquals(0, host)
        dispatchSessionBack(
            focused = false,
            overlayOpen = overlay,
            dockMode = dock,
            onCollapseFocused = {},
            onCloseOverlay = { overlay = false },
            onDockModeChange = { dock = it },
            onBack = { host++ },
        )
        assertEquals(1, host)
    }

    @Test
    fun sessionBackHandlerReturnsHotkeysThenSessionsHostFallthrough() {
        var host = 0
        var dock = DockRowMode.Hotkeys
        dispatchSessionBack(
            focused = false,
            overlayOpen = false,
            dockMode = dock,
            onCollapseFocused = {},
            onCloseOverlay = {},
            onDockModeChange = { dock = it },
            onBack = { host++ },
        )
        assertEquals(DockRowMode.Sessions, dock)
        assertEquals(0, host)
        dispatchSessionBack(
            focused = false,
            overlayOpen = false,
            dockMode = dock,
            onCollapseFocused = {},
            onCloseOverlay = {},
            onDockModeChange = { dock = it },
            onBack = { host++ },
        )
        assertEquals(1, host)
    }

    @Test
    fun sessionBackHandlerFocusedWinsOverOverlayAndDock() {
        var host = 0
        var focused = true
        var overlay = true
        var dock = DockRowMode.Hotkeys
        dispatchSessionBack(
            focused = focused,
            overlayOpen = overlay,
            dockMode = dock,
            onCollapseFocused = { focused = false },
            onCloseOverlay = { overlay = false },
            onDockModeChange = { dock = it },
            onBack = { host++ },
        )
        assertEquals(false, focused)
        assertEquals(true, overlay)
        assertEquals(DockRowMode.Hotkeys, dock)
        assertEquals(0, host)
    }

    @Test
    fun sessionBackHandlerDefaultSessionsPopsHostImmediately() {
        var host = 0
        dispatchSessionBack(
            focused = false,
            overlayOpen = false,
            dockMode = DockRowMode.Sessions,
            onCollapseFocused = {},
            onCloseOverlay = {},
            onDockModeChange = {},
            onBack = { host++ },
        )
        assertEquals(1, host)
    }

    @Test
    fun sessionBackHandlerRapidDockBackPopsHostExactlyOnce() {
        var host = 0
        var dock = DockRowMode.Hotkeys
        lateinit var dispatcher: OnBackPressedDispatcher
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            SessionScreenBackHandler(
                focused = { false },
                onCollapseFocused = {},
                overlayOpen = { false },
                dockMode = { dock },
                onCloseOverlay = {},
                onDockModeChange = { dock = it },
                onBack = { host++ },
            )
        }
        compose.runOnIdle {
            dispatcher.onBackPressed()
            dispatcher.onBackPressed()
        }
        assertEquals(DockRowMode.Sessions, dock)
        assertEquals(1, host)
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
    fun lateFavoriteSwitchKeepsScrollModeAndExpandedInputThenSwitchesAgain() {
        val entries = (0..8).map(::favoriteEntry)
        val favorites = entries.map { it.toFavoriteRow() }
        val harnesses = listOf(0, 6, 7).associate { index ->
            entries[index].ref to OverlayTestHarness(entries[index].ref)
        }
        var active by mutableStateOf(harnesses.getValue(entries[0].ref).vm)
        val selected = mutableListOf<String>()

        compose.setContent {
            AgentMirrorTheme {
                SessionScreen(
                    viewModel = active,
                    name = "favorite",
                    onBack = {},
                    favoriteRows = favorites,
                    onOpenOverlaySession = { ref, _ ->
                        selected += ref
                        active = harnesses.getValue(ref).vm
                    },
                )
            }
        }
        compose.onNodeWithTag("session-command-editor").performClick()
        compose.waitForIdle()
        val expandedBefore = inputFieldHeight()

        val favoriteList = compose.onNodeWithTag("favorite-session-list")
        favoriteList.performScrollToNode(hasText("收藏-6"))
        compose.waitForIdle()
        val scrollBefore = horizontalScrollValue()
        assertTrue("test must reach the late list segment", scrollBefore > 0f)

        compose.onNodeWithText("收藏-6").performClick()
        compose.waitForIdle()
        val scrollAfterFirst = horizontalScrollValue()
        val expandedAfterFirst = inputFieldHeight()
        compose.onNodeWithTag("favorite-session-list").assertIsDisplayed()
        compose.onNodeWithText("快捷键").assertDoesNotExist()
        assertTrue(abs(scrollAfterFirst - scrollBefore) < 0.5f)
        assertTrue(abs(expandedAfterFirst - expandedBefore) < 0.5f)

        compose.onNodeWithText("收藏-7").performClick()
        compose.waitForIdle()
        assertEquals(listOf(entries[6].ref, entries[7].ref), selected)
        assertTrue(abs(horizontalScrollValue() - scrollBefore) < 0.5f)
        assertTrue(abs(inputFieldHeight() - expandedBefore) < 0.5f)
    }

    @Test
    fun sourceViewportUsesExactPanelDockInputAndHotkeyGeometry() {
        compose.setContent {
            Box(Modifier.size(width = 390.dp, height = 844.dp)) {
                Box(Modifier.fillMaxSize().padding(top = 42.667.dp, bottom = 24.dp)) {
                    var mode by remember { mutableStateOf(DockRowMode.Sessions) }
                    SessionDockTheme(dark = false) {
                        SessionScreenScaffold(
                            terminalCanvas = { Box(Modifier.fillMaxSize()) },
                            dockMode = mode,
                            onDockModeChange = { mode = it },
                            sessions = emptyList(),
                            sessionListState = rememberLazyListState(),
                            onSessionSelect = {},
                            value = TextFieldValue(""),
                            onValueChange = {},
                            onSendText = {},
                            onPickAttachment = {},
                            onKeyToken = {},
                            onOpenViewMenu = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        assertRect("session-terminal-canvas", 0f, 42.667f, 390f, 675.333f)
        assertRect("session-terminal-card", 4f, 46.667f, 382f, 667.333f)
        assertRect("favorite-session-list", 11f, 718f, 320f, 40f)
        assertRect("session-command-input", 11f, 766f, 368f, 46f)
        assertRect("session-command-input-field", 60f, 773f, 272f, 32f)

        val returnBounds = compose.onNodeWithContentDescription("返回菜单").getUnclippedBoundsInRoot()
        assertEquals(339f, returnBounds.left.value, 0.7f)
        assertEquals(718f, returnBounds.top.value, 0.7f)
        assertEquals(40f, returnBounds.right.value - returnBounds.left.value, 0.7f)
        assertEquals(40f, returnBounds.bottom.value - returnBounds.top.value, 0.7f)

        compose.onNodeWithContentDescription("返回菜单").performClick()
        compose.waitForIdle()
        assertRect("dock-open-hotkeys", 11f, 718f, 117.333f, 40f)
        assertRect("session-overlay-open", 136.333f, 718f, 117.333f, 40f)
        assertRect("dock-open-favorites", 261.667f, 718f, 117.333f, 40f)

        compose.onNodeWithTag("dock-open-hotkeys").performClick()
        compose.waitForIdle()
        assertRect("hotkey-Esc", 11f, 718f, 43.94f, 40f)
        assertRect("hotkey-Tab", 60.94f, 718f, 43.94f, 40f)
        assertRect("hotkey-Up", 114.88f, 718f, 34.25f, 40f)
        assertRect("hotkey-Down", 152.13f, 718f, 34.27f, 40f)
        assertRect("hotkey-Left", 189.39f, 718f, 34.27f, 40f)
        assertRect("hotkey-Right", 226.66f, 718f, 34.27f, 40f)
        assertRect("hotkey-Ctrl-C", 270.92f, 718f, 60.08f, 40f)
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

    private fun horizontalScrollValue(): Float {
        val node = compose.onNodeWithTag("favorite-session-list").fetchSemanticsNode()
        return node.config[SemanticsProperties.HorizontalScrollAxisRange].value()
    }

    private fun inputFieldHeight(): Float {
        val bounds = compose.onNodeWithTag("session-command-input-field").getUnclippedBoundsInRoot()
        return bounds.bottom.value - bounds.top.value
    }

    private fun inputCapsuleHeight(): Float {
        val bounds = compose.onNodeWithTag("session-command-input").getUnclippedBoundsInRoot()
        return bounds.bottom.value - bounds.top.value
    }

    private fun L2Entry.toFavoriteRow() = FavoriteRow(
        sessionName = sessionName,
        windowIndex = windowIndex,
        windowName = windowName,
        addedAt = 0L,
        isOnline = true,
        ref = ref,
        cwd = cwd,
        title = title,
        status = status,
    )

    private fun favoriteEntry(index: Int): L2Entry = L2Entry(
        ref = "/tmp/tmux-1000/favorites\u001f%$index",
        name = "收藏-$index",
        title = "收藏-$index",
        rows = 24,
        cols = 80,
        status = if (index % 2 == 0) L2Status.WORKING else L2Status.IDLE,
        cwd = "/workspace",
        sessionName = "favorites",
        windowIndex = index.toString(),
        windowName = "收藏-$index",
    )
}
