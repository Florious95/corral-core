/*
 * Copyright 2026 AgentMirror Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.agentmirror.app.session

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import dev.agentmirror.app.ui.components.SessionSwitchSheet
import dev.agentmirror.app.ui.model.SessionItem
import dev.agentmirror.app.ui.model.SessionStatus
import dev.agentmirror.app.ui.theme.AgentMirrorTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 契约 088 E7：sheet 超一屏可滚，且未尽时有「下面还有」渐隐。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SessionSwitchSheetScrollHintTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun twentyItems_listScrolls_fadeUntilBottom() {
        compose.setContent {
            AgentMirrorTheme {
                SessionSwitchSheet(
                    visible = true,
                    workspaceName = "proj",
                    sessions = twentySessions(),
                    currentSessionId = "id-0",
                    onDismiss = {},
                    onSelect = {},
                    onToggleStar = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("session-overlay").assertExists()
        compose.onNodeWithTag("session-overlay-list").assert(hasScrollAction())
        compose.onNodeWithTag("session-overlay-more-fade").assertExists()

        compose.onNodeWithTag("session-overlay-list").performScrollToIndex(19)
        compose.waitForIdle()
        compose.onNodeWithTag("session-overlay-more-fade").assertDoesNotExist()
    }

    private fun twentySessions(): List<SessionItem> = (0 until 20).map { i ->
        SessionItem(
            id = "id-$i",
            displayName = "agent-$i",
            path = "/proj",
            status = if (i % 2 == 0) SessionStatus.Busy else SessionStatus.Idle,
            starred = false,
        )
    }
}
