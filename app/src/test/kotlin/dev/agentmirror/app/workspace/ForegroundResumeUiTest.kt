/*
 * Copyright 2026 AgentMirror Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.agentmirror.app.workspace

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dev.agentmirror.app.conn.ConnectionState
import dev.agentmirror.app.conn.Level2Frame
import dev.agentmirror.app.ui.theme.AgentMirrorTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Production workspace must not render the internal quiet-age diagnostic banner. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ForegroundResumeUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun quietAgeDiagnosticIsNotRenderedInLevel2Screen() {
        val vm = WorkspaceViewModel(
            requestList = {},
            subscribeLevel2 = {},
            unsubscribeLevel2 = {},
            nowMs = { 21_000L },
        )
        vm.onConnectionStateChanged(ConnectionState.READY)
        vm.enterLevel2("/proj")
        vm.onFrame(Level2Frame(workspace = "/proj", seq = 1, sessions = emptyList()))
        vm.checkLevel2Quiet(now = 41_001L, quietTimeoutMs = 20_000L)

        compose.setContent {
            AgentMirrorTheme {
                WorkspaceScreen(
                    viewModel = vm,
                    selectedWorkspaceCwd = "/proj",
                    onSelectWorkspace = {},
                    onBackToList = {},
                    onOpenSettings = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("l2-stale-banner").assertDoesNotExist()
    }
}
