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

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.percentOffset
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import dev.agentmirror.app.conn.OverlaySubscribeFrame
import dev.agentmirror.app.ui.theme.AgentMirrorTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Claude Design source contract for the session “查看” placeholder card. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayMenuTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun viewOpensOnlySourceCardWithoutStartingAnotherSubscription() {
        val h = OverlayTestHarness()
        compose.setContent {
            AgentMirrorTheme {
                SessionScreen(viewModel = h.vm, name = "current", onBack = {})
            }
        }
        openView()

        compose.onNodeWithTag("session-overlay").assertIsDisplayed()
        compose.onNodeWithText("查看弹出菜单（原生实现，此处仅占位）").assertIsDisplayed()
        compose.onNodeWithText("点任意处关闭").assertIsDisplayed()
        compose.onNodeWithText("切换会话").assertDoesNotExist()
        assertTrue(h.vm.overlayOpen)
        assertTrue(h.sent().none { it is OverlaySubscribeFrame })

        val card = compose.onNodeWithTag("session-overlay").getUnclippedBoundsInRoot()
        assertEquals(145f, card.left.value, 0.7f)
        assertEquals(230f, card.right.value - card.left.value, 0.7f)
        assertEquals(94.1f, card.bottom.value - card.top.value, 1.0f)
        assertEquals(618.91f, card.top.value, 0.7f)
        assertEquals(713f, card.bottom.value, 0.7f)
    }

    @Test
    fun tappingCardDismissesBecauseSourceSaysAnyPoint() {
        val h = OverlayTestHarness()
        compose.setContent {
            AgentMirrorTheme {
                SessionScreen(viewModel = h.vm, name = "current", onBack = {})
            }
        }
        openView()
        compose.onNodeWithTag("session-overlay").performClick()
        compose.waitForIdle()

        assertFalse(h.vm.overlayOpen)
        compose.onNodeWithTag("session-overlay").assertDoesNotExist()
    }

    @Test
    fun tappingScrimDismissesSourceCard() {
        val h = OverlayTestHarness()
        compose.setContent {
            AgentMirrorTheme {
                SessionScreen(viewModel = h.vm, name = "current", onBack = {})
            }
        }
        openView()
        compose.onNodeWithTag("session-overlay-scrim").performTouchInput {
            click(percentOffset(0.5f, 0.08f))
        }
        compose.waitForIdle()

        assertFalse(h.vm.overlayOpen)
        compose.onNodeWithTag("session-overlay").assertDoesNotExist()
    }

    private fun openView() {
        compose.onNodeWithContentDescription("返回菜单").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("session-overlay-open").performClick()
        compose.waitForIdle()
    }
}
