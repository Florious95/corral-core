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

import android.accessibilityservice.AccessibilityService
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.platform.app.InstrumentationRegistry
import dev.agentmirror.app.conn.ConnectionConfig
import dev.agentmirror.app.service.MirrorForegroundService
import dev.agentmirror.app.service.NoopTransportFactory
import dev.agentmirror.app.service.ServiceWire
import dev.agentmirror.app.workspace.FavoriteRow
import dev.agentmirror.app.workspace.L2Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class SessionDockInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun favoriteSwitchPreservesScrollModeAndExpandedInputThenSendsAndCollapses() {
        val selected = mutableListOf<String>()
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        compose.setContent {
            var activeId by remember { mutableStateOf("0") }
            var mode by remember { mutableStateOf(DockRowMode.Sessions) }
            var value by remember { mutableStateOf(TextFieldValue("")) }
            val listState = rememberLazyListState()
            val focusManager = LocalFocusManager.current
            val visibleOrder = remember { mutableStateListOf(*(1..8).toList().toTypedArray()) }
            val sessions = visibleOrder.map {
                SessionChipUi(
                    id = it.toString(),
                    name = "收藏-$it",
                    isActive = activeId == it.toString(),
                    isRunning = it % 2 == 0,
                )
            }
            SessionDockTheme(dark = false) {
                SessionScreenScaffold(
                    terminalCanvas = {
                        Box(Modifier.fillMaxSize().background(Color(0xFFE9F2EC)))
                    },
                    dockMode = mode,
                    onDockModeChange = { mode = it },
                    sessions = sessions,
                    sessionListState = listState,
                    onSessionSelect = { next ->
                        selected += next
                        val slot = visibleOrder.indexOf(next.toInt())
                        if (slot >= 0) visibleOrder[slot] = activeId.toInt()
                        activeId = next
                    },
                    value = value,
                    onValueChange = { value = it },
                    onSendText = {
                        value = TextFieldValue("")
                        focusManager.clearFocus()
                    },
                    onPickAttachment = {},
                    onKeyToken = {},
                    onBack = {},
                    onOpenViewMenu = {},
                )
            }
        }

        val collapsed = inputHeight()
        val restingInputBottom = inputBottom()
        assertEquals(32f, collapsed, 0.5f)
        assertEquals(46f, inputCapsuleHeight(), 0.5f)
        compose.onNodeWithTag("session-command-editor").performTouchInput { click() }
        compose.waitUntil(timeoutMillis = 5_000) {
            inputHeight() >= 71.5f && inputBottom() < restingInputBottom - 100f
        }
        val expanded = inputHeight()
        val imeRaisedInputBottom = inputBottom()
        assertEquals(72f, expanded, 0.5f)
        assertEquals(86f, inputCapsuleHeight(), 0.5f)

        // A real system Back first hides the IME; IME-hidden reconciliation must clear focus.
        assertTrue(
            InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
                AccessibilityService.GLOBAL_ACTION_BACK,
            ),
        )
        compose.waitUntil(timeoutMillis = 5_000) {
            inputHeight() <= 32.5f && abs(inputBottom() - restingInputBottom) < 0.5f
        }
        assertEquals(32f, inputHeight(), 0.5f)
        assertEquals(46f, inputCapsuleHeight(), 0.5f)
        compose.onNodeWithTag("session-command-editor").performTouchInput { click() }
        compose.waitUntil(timeoutMillis = 5_000) {
            inputHeight() >= 71.5f && inputBottom() < restingInputBottom - 100f
        }

        // Real outside pointer action must blur, collapse, and return the dock from IME raise.
        compose.onNodeWithTag("session-terminal-canvas").performTouchInput { click() }
        compose.waitUntil(timeoutMillis = 5_000) {
            inputHeight() <= 32.5f && abs(inputBottom() - restingInputBottom) < 0.5f
        }
        assertEquals(32f, inputHeight(), 0.5f)
        assertEquals(46f, inputCapsuleHeight(), 0.5f)
        compose.onNodeWithTag("session-command-editor").performTouchInput { click() }
        compose.waitUntil(timeoutMillis = 5_000) {
            inputHeight() >= 71.5f && abs(inputBottom() - imeRaisedInputBottom) < 0.5f
        }

        compose.onNodeWithTag("favorite-session-list")
            .performScrollToNode(hasText("收藏-6"))
        compose.waitUntil(timeoutMillis = 5_000) { scrollValue() > 0f }
        val scroll = scrollValue()

        compose.onNodeWithText("收藏-6").performClick()
        compose.waitForIdle()
        assertTrue(abs(scrollValue() - scroll) < 0.5f)
        assertTrue(abs(inputHeight() - expanded) < 0.5f)
        compose.onNodeWithTag("favorite-session-list").assertIsDisplayed()

        compose.onNodeWithText("收藏-7").performClick()
        compose.waitForIdle()
        assertEquals(listOf("6", "7"), selected)
        assertTrue(abs(scrollValue() - scroll) < 0.5f)
        assertTrue(abs(inputHeight() - expanded) < 0.5f)

        compose.onNodeWithTag("session-command-editor").performTextInput("ls")
        compose.onNodeWithContentDescription("发送").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            inputHeight() <= 32.5f && abs(inputBottom() - restingInputBottom) < 0.5f
        }
        assertEquals(32f, inputHeight(), 0.5f)

        compose.onNodeWithContentDescription("返回菜单").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("快捷键").assertCountEquals(1)
        compose.onAllNodesWithText("查看").assertCountEquals(1)
        compose.onAllNodesWithText("会话").assertCountEquals(1)
    }

    @Test
    fun productionSessionRouteKeepsGlobalFavoriteViewportDockAndInputAcrossTwoSwitches() {
        val previousFactory = ServiceWire.transportFactory
        val selected = mutableListOf<String>()
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            ServiceWire.releaseManager()
            ServiceWire.resetConfigForTest()
            ServiceWire.transportFactory = NoopTransportFactory
            ServiceWire.setConfig(ConnectionConfig("ws://127.0.0.1:9/ws", "instrumentation-test"))
        }
        try {
            compose.setContent {
                var activeRef by remember { mutableStateOf("favorite-0") }
                val favorites = (0..8).map { index ->
                    FavoriteRow(
                        sessionName = "favorite-$index",
                        windowIndex = index.toString(),
                        windowName = "收藏-$index",
                        addedAt = index.toLong(),
                        isOnline = true,
                        ref = "favorite-$index",
                        cwd = "/workspace/$index",
                        title = "收藏-$index",
                        status = if (index % 2 == 0) L2Status.WORKING else L2Status.IDLE,
                    )
                }
                SessionRoute(
                    ref = activeRef,
                    name = activeRef,
                    onBack = {},
                    favoriteRows = favorites,
                    onOpenOverlaySession = { ref, _ ->
                        selected += ref
                        activeRef = ref
                    },
                )
            }
            compose.onNodeWithText("收藏-0").assertDoesNotExist()
            compose.onNodeWithTag("session-command-editor").performTouchInput { click() }
            compose.waitUntil(timeoutMillis = 5_000) { inputHeight() >= 71.5f }
            val expanded = inputHeight()

            compose.onNodeWithTag("favorite-session-list").performScrollToNode(hasText("收藏-6"))
            compose.waitUntil(timeoutMillis = 5_000) { scrollValue() > 0f }
            val scroll = scrollValue()
            compose.onNodeWithText("收藏-6").performClick()
            compose.waitForIdle()

            assertEquals(listOf("favorite-6"), selected)
            compose.onNodeWithText("收藏-6").assertDoesNotExist()
            compose.onNodeWithTag("favorite-session-list").assertIsDisplayed()
            assertTrue(abs(scrollValue() - scroll) < 0.5f)
            assertTrue(abs(inputHeight() - expanded) < 0.5f)

            compose.onNodeWithText("收藏-7").performClick()
            compose.waitForIdle()
            assertEquals(listOf("favorite-6", "favorite-7"), selected)
            compose.onNodeWithText("收藏-7").assertDoesNotExist()
            compose.onNodeWithTag("favorite-session-list").assertIsDisplayed()
            compose.onAllNodesWithText("快捷键").assertCountEquals(0)
            assertTrue(abs(scrollValue() - scroll) < 0.5f)
            assertTrue(abs(inputHeight() - expanded) < 0.5f)
        } finally {
            compose.runOnUiThread {
                MirrorForegroundService.stop(compose.activity)
                ServiceWire.uiConnector = null
                ServiceWire.releaseManager()
                ServiceWire.resetConfigForTest()
                ServiceWire.transportFactory = previousFactory
            }
        }
    }

    private fun scrollValue(): Float {
        val node = compose.onNodeWithTag("favorite-session-list").fetchSemanticsNode()
        return node.config[SemanticsProperties.HorizontalScrollAxisRange].value()
    }

    private fun inputBottom(): Float = compose.onNodeWithTag("session-command-input-field")
        .getUnclippedBoundsInRoot().bottom.value

    private fun inputCapsuleHeight(): Float {
        val bounds = compose.onNodeWithTag("session-command-input").getUnclippedBoundsInRoot()
        return bounds.bottom.value - bounds.top.value
    }

    private fun inputHeight(): Float {
        val bounds = compose.onNodeWithTag("session-command-input-field")
            .getUnclippedBoundsInRoot()
        return bounds.bottom.value - bounds.top.value
    }
}
