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

package dev.agentmirror.app.workspace

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import dev.agentmirror.app.ExternalSessionListAcceptanceActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ExternalSessionListGestureTest {
    @get:Rule
    val rule = createAndroidComposeRule<ExternalSessionListAcceptanceActivity>()

    @Test
    fun listShortClickAndLongPressFavoriteWithoutStarOrForbiddenActions() {
        rule.onNodeWithTag("l2-star-claude-w").assertDoesNotExist()
        rule.onNodeWithText("☆").assertDoesNotExist()
        rule.onNodeWithText("★").assertDoesNotExist()
        rule.onNodeWithText("未知").assertDoesNotExist()
        rule.onNodeWithText("关闭").assertDoesNotExist()
        rule.onNodeWithText("创建").assertDoesNotExist()
        rule.onNodeWithText("配置").assertDoesNotExist()
        rule.onNodeWithTag("l2-path-claude-w", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("l2-path-codex-i", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("l2-provider-claude-w", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("l2-provider-codex-i", useUnmergedTree = true).assertExists()

        rule.onNodeWithTag("l2-row-codex-i").performClick()
        rule.onNodeWithTag("l2-row-codex-i").performTouchInput { longClick() }
        rule.waitUntil(2_000) {
            rule.onAllNodesWithTag("l2-favorite-action").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("l2-favorite-action").assertTextEquals("收藏")
        rule.onNodeWithText("关闭").assertDoesNotExist()
        rule.onNodeWithTag("l2-favorite-action").performClick()
        rule.waitUntil(2_000) {
            rule.onAllNodesWithTag("l2-favorite-action").fetchSemanticsNodes().isEmpty()
        }
        rule.onNodeWithTag("l2-session-list-scroll").performScrollToNode(hasTestTag("l2-row-pi-w"))
        rule.onNodeWithTag("l2-provider-pi-w", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("l2-session-list-scroll").performScrollToNode(hasTestTag("l2-row-copilot-u"))
        rule.onNodeWithTag("l2-provider-copilot-u", useUnmergedTree = true).assertExists()
    }

    @Test
    fun favoriteOfflineDoesNotNavigateAndLongPressOnlyUnfavorites() {
        rule.onNodeWithTag("fav-row-fav-off").performClick()
        rule.onNodeWithText("不在线").assertExists()
        rule.onNodeWithTag("fav-star-fav-off").assertDoesNotExist()
        rule.onNodeWithTag("fav-row-fav-off").performTouchInput { longClick() }
        rule.waitUntil(2_000) {
            rule.onAllNodesWithTag("fav-favorite-action").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("fav-favorite-action").assertTextEquals("取消收藏")
        rule.onNodeWithText("关闭").assertDoesNotExist()
        rule.onNodeWithTag("fav-favorite-action").performClick()
        rule.waitUntil(2_000) {
            rule.onAllNodesWithTag("fav-row-fav-off").fetchSemanticsNodes().isEmpty()
        }
        rule.onNodeWithText("未知").assertDoesNotExist()
    }

    @Test
    fun workingLampAnimatesAndListsShareTitlePathHeight() {
        val working = rule.onNodeWithTag("l2-motion-claude-w", useUnmergedTree = true)
            .fetchSemanticsNode()
            .config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
            .joinToString()
        val idle = rule.onNodeWithTag("l2-motion-codex-i", useUnmergedTree = true)
            .fetchSemanticsNode()
            .config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
            .joinToString()
        assertTrue(working.startsWith("working:"))
        assertEquals("idle:static", idle)
        val l2 = rule.onNodeWithTag("l2-row-codex-i").getUnclippedBoundsInRoot()
        val fav = rule.onNodeWithTag("fav-row-fav-on").getUnclippedBoundsInRoot()
        assertEquals(l2.bottom.value - l2.top.value, fav.bottom.value - fav.top.value, 1f)
        rule.onNodeWithTag("l2-path-codex-i", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("fav-path-fav-on", useUnmergedTree = true).assertExists()
    }
}
