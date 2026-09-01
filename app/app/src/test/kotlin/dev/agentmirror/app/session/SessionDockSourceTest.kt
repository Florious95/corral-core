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

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
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
import androidx.compose.ui.text.input.TextFieldValue
import dev.agentmirror.app.ui.theme.AgentMirrorTheme
import dev.agentmirror.app.workspace.FavoriteKey
import dev.agentmirror.app.workspace.L2Entry
import dev.agentmirror.app.workspace.L2Status
import dev.agentmirror.app.workspace.favoriteKey
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
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
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
        compose.onAllNodesWithText("常用快捷键").assertCountEquals(0)
        compose.onNodeWithContentDescription("返回菜单").performClick()
        compose.waitForIdle()

        compose.onAllNodesWithText("常用快捷键").assertCountEquals(1)
        compose.onAllNodesWithText("查看").assertCountEquals(1)
        compose.onAllNodesWithText("收藏会话").assertCountEquals(1)
        compose.onNodeWithText("快捷键").assertDoesNotExist()
        compose.onNodeWithText("会话").assertDoesNotExist()

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
    fun inputFocusExpandsAndBlurOrSendCollapsesWithSourceTiming() {
        var sent = ""
        var clearInputFocus: () -> Unit = {}
        compose.mainClock.autoAdvance = false
        compose.setContent {
            var value by remember { mutableStateOf(TextFieldValue("")) }
            val focusManager = LocalFocusManager.current
            clearInputFocus = { focusManager.clearFocus() }
            AgentMirrorTheme {
                SessionDockTheme(dark = false) {
                    CommandInputBar(
                        value = value,
                        onValueChange = { value = it },
                        onSendText = {
                            sent = it
                            value = TextFieldValue("")
                            focusManager.clearFocus()
                        },
                        onPickAttachment = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        val collapsed = inputFieldHeight()

        compose.onNodeWithTag("session-command-editor").performClick()
        compose.mainClock.advanceTimeBy(SessionDockMotion.InputHeightMillis.toLong() + 1)
        compose.waitForIdle()
        val expanded = inputFieldHeight()
        assertTrue("focused input must expand: collapsed=$collapsed expanded=$expanded", expanded > collapsed * 2f)

        compose.runOnIdle { clearInputFocus() }
        compose.mainClock.advanceTimeBy(SessionDockMotion.InputHeightMillis.toLong() + 1)
        compose.waitForIdle()
        assertTrue(abs(inputFieldHeight() - collapsed) < 0.5f)

        compose.onNodeWithTag("session-command-editor").performClick()
        compose.mainClock.advanceTimeBy(SessionDockMotion.InputHeightMillis.toLong() + 1)
        compose.onNodeWithTag("session-command-editor").performTextInput("ls")
        compose.onNodeWithContentDescription("发送").performClick()
        compose.mainClock.advanceTimeBy(SessionDockMotion.InputHeightMillis.toLong() + 1)
        compose.waitForIdle()
        val sentCollapsed = inputFieldHeight()
        assertEquals("ls", sent)
        assertTrue(abs(sentCollapsed - collapsed) < 0.5f)
    }

    @Test
    fun lateFavoriteSwitchKeepsScrollModeAndExpandedInputThenSwitchesAgain() {
        val entries = (0..8).map(::favoriteEntry)
        val favorites: Set<FavoriteKey> = entries.mapTo(linkedSetOf()) { it.favoriteKey() }
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
                    overlaySessions = entries,
                    overlayFavorited = favorites,
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
        compose.onNodeWithText("常用快捷键").assertDoesNotExist()
        assertTrue(abs(scrollAfterFirst - scrollBefore) < 0.5f)
        assertTrue(abs(expandedAfterFirst - expandedBefore) < 0.5f)

        compose.onNodeWithText("收藏-7").performClick()
        compose.waitForIdle()
        assertEquals(listOf(entries[6].ref, entries[7].ref), selected)
        assertTrue(abs(horizontalScrollValue() - scrollBefore) < 0.5f)
        assertTrue(abs(inputFieldHeight() - expandedBefore) < 0.5f)
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
        assertEquals(1_100, SessionDockMotion.CursorBlinkMillis)
        assertEquals(255, SessionDockMotion.cursorAlphaAt(0))
        assertEquals(255, SessionDockMotion.cursorAlphaAt(549))
        assertEquals(51, SessionDockMotion.cursorAlphaAt(550))
        assertEquals(51, SessionDockMotion.cursorAlphaAt(1_099))
        assertEquals(255, SessionDockMotion.cursorAlphaAt(1_100))
        assertEquals(1, SessionDockMotion.millisToNextCursorStep(549))
    }

    private fun horizontalScrollValue(): Float {
        val node = compose.onNodeWithTag("favorite-session-list").fetchSemanticsNode()
        return node.config[SemanticsProperties.HorizontalScrollAxisRange].value()
    }

    private fun inputFieldHeight(): Float {
        val bounds = compose.onNodeWithTag("session-command-input-field").getUnclippedBoundsInRoot()
        return bounds.bottom.value - bounds.top.value
    }

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
