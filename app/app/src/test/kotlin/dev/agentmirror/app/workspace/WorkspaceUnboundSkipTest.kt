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

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import dev.agentmirror.app.ui.theme.AgentMirrorTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Issue 83: 未绑定 skip 必须进安静空工作区，不能停在「连接中… / 正在连接主机…」。
 * 有 pairing 的 CONNECTING 仍显示加载；READY 空引导不得被 skip 误用。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkspaceUnboundSkipTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun unboundSkip_isQuietEmpty_withoutConnectingCopy() {
        val vm = WorkspaceViewModel(
            initialConnection = ConnectionUi.UNBOUND,
            requestList = {},
        )
        compose.setContent {
            AgentMirrorTheme {
                WorkspaceScreen(
                    viewModel = vm,
                    selectedWorkspaceCwd = null,
                    onSelectWorkspace = {},
                    onBackToList = {},
                    onOpenSettings = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("工作区").assertIsDisplayed()
        compose.onNodeWithTag("workspace-unbound-empty").assertIsDisplayed()
        compose.onNodeWithText("连接中…").assertDoesNotExist()
        compose.onNodeWithText("正在连接主机…").assertDoesNotExist()
        compose.onNodeWithText("暂无工作区").assertDoesNotExist()
        compose.onNodeWithText("正在重连…").assertDoesNotExist()
        compose.onNodeWithText("连接已关闭").assertDoesNotExist()
        assertEquals(0, countIndeterminateProgress())
    }

    @Test
    fun connectingWithNoWorkspaces_stillShowsHostLoading() {
        val vm = WorkspaceViewModel(requestList = {})
        compose.setContent {
            AgentMirrorTheme {
                WorkspaceScreen(
                    viewModel = vm,
                    selectedWorkspaceCwd = null,
                    onSelectWorkspace = {},
                    onBackToList = {},
                    onOpenSettings = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("正在连接主机…").assertIsDisplayed()
        compose.onNodeWithText("连接中…").assertIsDisplayed()
        compose.onNodeWithTag("workspace-unbound-empty").assertDoesNotExist()
    }

    private fun countIndeterminateProgress(): Int =
        compose.onAllNodes(
            SemanticsMatcher("indeterminate-progress") { node ->
                node.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) ==
                    ProgressBarRangeInfo.Indeterminate
            },
            useUnmergedTree = true,
        ).fetchSemanticsNodes().size
}
