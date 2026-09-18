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

package dev.agentmirror.app.ui

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import dev.agentmirror.app.ui.components.SessionActionBottomSheet
import dev.agentmirror.app.ui.components.SessionRow
import dev.agentmirror.app.ui.model.SessionItem
import dev.agentmirror.app.ui.model.SessionStatus
import dev.agentmirror.app.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SessionActionBottomSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private val testSession = SessionItem(
        id = "session-seat-1",
        displayName = "代码审查助手",
        path = "/Users/workspace/project-corral",
        provider = "grok",
        status = SessionStatus.Busy,
        health = "normal",
        isOnline = true,
        starred = false,
    )

    @Test
    fun bottomSheetDisplaysSessionHeaderAndOptions() {
        var favoriteClicked = false
        var closeClicked = false
        var dismissClicked = false

        compose.setContent {
            AppTheme {
                SessionActionBottomSheet(
                    session = testSession,
                    tagPrefix = "l2",
                    allowClose = true,
                    onDismiss = { dismissClicked = true },
                    onToggleFavorite = { favoriteClicked = true },
                    onCloseSession = { closeClicked = true },
                )
            }
        }

        // 1. 头部展示：会话名称与路径信息
        compose.onNodeWithTag("session-action-title").assertExists().assertTextEquals("代码审查助手")
        compose.onNodeWithTag("session-action-path").assertExists()
        compose.onNodeWithText("/Users/workspace/project-corral").assertExists()

        // 2. 选项 1：收藏（星标图标，文案“收藏”）
        compose.onNodeWithTag("l2-favorite-action").assertExists().assertTextEquals("收藏")

        // 3. 选项 2：关闭会话（红色警示调，文案“关闭会话”，附说明“完全退出当前 Agent 并关闭该 pane”）
        compose.onNodeWithTag("l2-close-action", useUnmergedTree = true).assertExists().assertTextEquals("关闭会话")
        compose.onNodeWithText("完全退出当前 Agent 并关闭该 pane").assertExists()

        // 4. 底部独立取消按钮
        compose.onNodeWithTag("session-action-cancel").assertExists().assertTextEquals("取消")

        // 5. 点击触发关闭操作
        compose.onNodeWithTag("session-action-close").performClick()
        assertTrue("关闭会话回调被触发", closeClicked)
    }

    @Test
    fun bottomSheetTogglesStarredStateText() {
        val starredSession = testSession.copy(starred = true)
        var toggled = false

        compose.setContent {
            AppTheme {
                SessionActionBottomSheet(
                    session = starredSession,
                    tagPrefix = "l2",
                    allowClose = true,
                    onDismiss = {},
                    onToggleFavorite = { toggled = true },
                    onCloseSession = {},
                )
            }
        }

        // 已经收藏的会话，文案应为“取消收藏”
        compose.onNodeWithTag("l2-favorite-action").assertExists().assertTextEquals("取消收藏")
        compose.onNodeWithTag("l2-favorite-action").performClick()
        assertTrue("收藏状态切换回调被触发", toggled)
    }

    @Test
    fun sessionRowLongPressTriggersBottomSheet() {
        var closed = false
        compose.setContent {
            AppTheme {
                SessionRow(
                    item = testSession,
                    tagPrefix = "l2",
                    onClick = {},
                    onToggleFavorite = {},
                    unfavoriteOnly = false,
                    onCloseSession = { closed = true },
                )
            }
        }

        // 初始弹层未打开
        compose.onNodeWithTag("session-action-bottom-sheet").assertDoesNotExist()

        // 长按会话行
        compose.onNodeWithTag("l2-row-${testSession.id}").performTouchInput { longClick() }

        // 弹层滑出
        compose.onNodeWithTag("session-action-bottom-sheet").assertExists()
        compose.onNodeWithTag("session-action-title").assertExists().assertTextEquals("代码审查助手")
        compose.onNodeWithTag("l2-close-action", useUnmergedTree = true).assertExists().assertTextEquals("关闭会话")

        // 点击关闭
        compose.onNodeWithTag("session-action-close").performClick()
        assertTrue("通过长按弹层点击关闭成功触发", closed)
    }
}
