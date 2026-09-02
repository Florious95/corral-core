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

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.percentOffset
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import dev.agentmirror.app.conn.ConnectionConfig
import dev.agentmirror.app.service.MirrorForegroundService
import dev.agentmirror.app.service.NoopTransportFactory
import dev.agentmirror.app.service.ServiceWire
import dev.agentmirror.app.workspace.FavoriteRow
import dev.agentmirror.app.workspace.L2Status
import dev.agentmirror.app.workspace.ProductionOverlayFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SessionRouteOverlayInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun productionSessionRouteViewMenuOpensSelectsAndDismissesProtocolList() {
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
            val overlay = ProductionOverlayFixture.overlayEntries()
            compose.setContent {
                SessionRoute(
                    ref = ProductionOverlayFixture.CURRENT_REF,
                    name = "A 当前会话",
                    onBack = {},
                    favoriteRows = listOf(
                        FavoriteRow(
                            sessionName = "audit",
                            windowIndex = "2",
                            windowName = "A 同目录会话",
                            addedAt = 1L,
                            isOnline = true,
                            ref = ProductionOverlayFixture.PEER_REF,
                            cwd = ProductionOverlayFixture.WORKSPACE,
                            title = "A 同目录会话",
                            status = L2Status.IDLE,
                        ),
                    ),
                    overlaySessions = overlay,
                    onOpenOverlaySession = { ref, _ -> selected += ref },
                )
            }
            compose.waitForIdle()
            compose.onNodeWithTag("session-command-input").assertIsDisplayed()
            compose.onNodeWithTag("favorite-session-list").assertIsDisplayed()
            openView()
            compose.onNodeWithTag("session-overlay").assertIsDisplayed()
            compose.onNodeWithText("切换会话").assertIsDisplayed()
            compose.onNodeWithText("A 当前会话").assertIsDisplayed()
            compose.onNodeWithText("A 同目录会话").assertIsDisplayed()
            compose.onNodeWithText("A 第三会话").assertIsDisplayed()
            compose.onNodeWithText("查看弹出菜单（原生实现，此处仅占位）").assertDoesNotExist()
            compose.onNodeWithTag("session-switch-workspace").assertIsDisplayed()

            compose.onNodeWithText("A 第三会话").performClick()
            compose.waitForIdle()
            assertEquals(listOf(ProductionOverlayFixture.THIRD_REF), selected)
            compose.onNodeWithTag("session-overlay").assertDoesNotExist()

            openView()
            compose.onNodeWithTag("session-overlay-scrim").performTouchInput {
                click(percentOffset(0.5f, 0.08f))
            }
            compose.waitForIdle()
            compose.onNodeWithTag("session-overlay").assertDoesNotExist()
            assertEquals(listOf(ProductionOverlayFixture.THIRD_REF), selected)
            assertTrue(
                overlay.map { it.ref }.containsAll(
                    listOf(
                        ProductionOverlayFixture.CURRENT_REF,
                        ProductionOverlayFixture.PEER_REF,
                        ProductionOverlayFixture.THIRD_REF,
                    ),
                ),
            )
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

    private fun openView() {
        val alreadyMenu = compose.onAllNodesWithTag("session-overlay-open")
            .fetchSemanticsNodes()
            .isNotEmpty()
        if (!alreadyMenu) {
            compose.onNodeWithContentDescription("返回菜单", useUnmergedTree = true).performClick()
            compose.waitForIdle()
        }
        compose.onNodeWithTag("session-overlay-open").performClick()
        compose.waitForIdle()
    }
}
