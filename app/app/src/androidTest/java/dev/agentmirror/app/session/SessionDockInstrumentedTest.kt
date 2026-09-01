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

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
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
import androidx.compose.ui.text.input.TextFieldValue
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
        var clearInputFocus: () -> Unit = {}
        compose.setContent {
            var activeId by remember { mutableStateOf("0") }
            var mode by remember { mutableStateOf(DockRowMode.Sessions) }
            var value by remember { mutableStateOf(TextFieldValue("")) }
            val listState = rememberLazyListState()
            val focusManager = LocalFocusManager.current
            clearInputFocus = { focusManager.clearFocus() }
            val sessions = (0..8).map {
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
                    onSessionSelect = {
                        selected += it
                        activeId = it
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
        compose.onNodeWithTag("session-command-editor").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { inputHeight() > collapsed * 2f }
        val expanded = inputHeight()

        compose.runOnIdle { clearInputFocus() }
        compose.waitUntil(timeoutMillis = 5_000) { inputHeight() <= collapsed + 0.5f }
        compose.onNodeWithTag("session-command-editor").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { inputHeight() >= expanded - 0.5f }

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
        compose.waitUntil(timeoutMillis = 5_000) { inputHeight() <= collapsed + 0.5f }

        compose.onNodeWithContentDescription("返回菜单").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("常用快捷键").assertCountEquals(1)
        compose.onAllNodesWithText("查看").assertCountEquals(1)
        compose.onAllNodesWithText("收藏会话").assertCountEquals(1)
    }

    private fun scrollValue(): Float {
        val node = compose.onNodeWithTag("favorite-session-list").fetchSemanticsNode()
        return node.config[SemanticsProperties.HorizontalScrollAxisRange].value()
    }

    private fun inputHeight(): Float {
        val bounds = compose.onNodeWithTag("session-command-input-field")
            .getUnclippedBoundsInRoot()
        return bounds.bottom.value - bounds.top.value
    }
}
