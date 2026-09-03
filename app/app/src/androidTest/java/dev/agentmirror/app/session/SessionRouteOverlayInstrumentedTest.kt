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

import android.accessibilityservice.AccessibilityService
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.percentOffset
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import dev.agentmirror.app.conn.ConnectionConfig
import dev.agentmirror.app.service.MirrorForegroundService
import dev.agentmirror.app.service.NoopTransportFactory
import dev.agentmirror.app.service.ServiceWire
import dev.agentmirror.app.workspace.FavoriteRow
import dev.agentmirror.app.workspace.L2Entry
import dev.agentmirror.app.workspace.L2Status
import dev.agentmirror.app.workspace.ProductionOverlayFixture
import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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

    @Test
    fun productionSessionRouteViewOverlayScrollsToLastRowAndSelects() {
        val selected = mutableListOf<String>()
        val overlay = productionOverlayEntries(20)
        runProductionRoute(
            onOpenOverlaySession = { ref, _ -> selected += ref },
            overlaySessions = overlay,
            onBack = {},
        ) {
            openView()
            compose.onNodeWithTag("session-overlay-list").assertIsDisplayed()
            compose.onNodeWithTag("session-overlay-list").performScrollToNode(
                hasText("查看会话-20"),
            )
            compose.onNodeWithText("查看会话-20").assertIsDisplayed()
            compose.onNodeWithText("查看会话-20").performClick()
            compose.waitForIdle()
            assertEquals(listOf(overlay.last().ref), selected)
            compose.onNodeWithTag("session-overlay").assertDoesNotExist()
        }
    }

    @Test
    fun productionSessionRouteBackFromHotkeysReturnsDefaultBeforeHost() {
        var hostBackCount = 0
        runProductionRoute(onBack = { hostBackCount++ }) {
            openMenu()
            compose.onNodeWithTag("dock-open-hotkeys").performClick()
            compose.onNodeWithText("Esc").assertIsDisplayed()
            assertTrue(performGlobalBack())
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithText("Esc").fetchSemanticsNodes().isEmpty() &&
                    compose.onAllNodesWithTag("favorite-session-list").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(0, hostBackCount)
            compose.onNodeWithText("Esc").assertDoesNotExist()
            assertSessionStillResumed()
            assertTrue(performGlobalBack())
            compose.waitUntil(timeoutMillis = 5_000) { hostBackCount == 1 }
            assertSessionStillResumed()
        }
    }

    @Test
    fun productionSessionRouteBackFromSessionsReturnsDefaultBeforeHost() {
        var hostBackCount = 0
        runProductionRoute(onBack = { hostBackCount++ }) {
            openMenu()
            compose.onNodeWithTag("dock-open-favorites").performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithTag("dock-open-favorites").fetchSemanticsNodes().isEmpty() &&
                    compose.onAllNodesWithTag("dock-open-hotkeys").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithTag("favorite-session-list").assertIsDisplayed()
            assertTrue(performGlobalBack())
            compose.waitUntil(timeoutMillis = 5_000) { hostBackCount == 1 }
            assertSessionStillResumed()
        }
    }

    @Test
    fun productionSessionRouteBackFromViewOverlayReturnsDefaultBeforeHost() {
        var hostBackCount = 0
        runProductionRoute(onBack = { hostBackCount++ }) {
            openView()
            compose.onNodeWithTag("session-overlay").assertIsDisplayed()
            assertTrue(performGlobalBack())
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithTag("session-overlay").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithTag("session-command-input").assertIsDisplayed()
            assertEquals(0, hostBackCount)
            assertTrue(performGlobalBack())
            compose.waitUntil(timeoutMillis = 5_000) { hostBackCount == 1 }
            assertSessionStillResumed()
        }
    }

    @Test
    fun productionSessionRouteRestoresFavoriteAnchorAfterViewOverlay() {
        val favoriteRows = productionFavoriteRows(24)
        runProductionRoute(
            onBack = {},
            favoriteRows = favoriteRows,
        ) {
            compose.onNodeWithTag("favorite-session-list").performScrollToNode(
                hasText("收藏-18"),
            )
            compose.waitForIdle()
            val before = firstVisibleFavoriteAnchor(favoriteRows.size)
            val beforeScroll = horizontalScrollValue()

            openView()
            compose.onNodeWithTag("session-overlay").assertIsDisplayed()
            assertTrue(performGlobalBack())
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithTag("session-overlay").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithTag("session-command-input").assertIsDisplayed()

            val after = firstVisibleFavoriteAnchor(favoriteRows.size)
            assertEquals(before.first, after.first)
            assertEquals(before.second, after.second, 0.5f)
            assertEquals(beforeScroll, horizontalScrollValue(), 0.5f)
            compose.onNodeWithTag("favorite-session-list").performScrollToNode(
                hasText("收藏-24"),
            )
            compose.onNodeWithText("收藏-24").assertIsDisplayed()
        }
    }

    @Test
    fun productionSessionRouteFocusedBackCollapsesThenDefaultBackReachesHost() {
        var hostBackCount = 0
        runProductionRoute(onBack = { hostBackCount++ }) {
            val originalCallback = compose.activity.window.callback
            compose.onNodeWithTag("session-command-editor").performTouchInput { click() }
            compose.waitUntil(timeoutMillis = 5_000) { inputCapsuleHeight() >= 85.5f }
            assertSame(originalCallback, compose.activity.window.callback)

            assertTrue(performGlobalBack())
            compose.waitUntil(timeoutMillis = 5_000) { inputCapsuleHeight() <= 46.5f }
            assertEquals(0, hostBackCount)
            assertSame(originalCallback, compose.activity.window.callback)
            assertSessionStillResumed()

            assertTrue(performGlobalBack())
            compose.waitUntil(timeoutMillis = 5_000) { hostBackCount == 1 }
            assertSessionStillResumed()
        }
    }

    @Test
    fun productionSessionRouteRapidDispatcherBackPopsHostExactlyOnce() {
        var hostBackCount = 0
        runProductionRoute(onBack = { hostBackCount++ }) {
            openMenu()
            compose.onNodeWithTag("dock-open-hotkeys").performClick()
            compose.onNodeWithText("Esc").assertIsDisplayed()
            compose.runOnUiThread {
                compose.activity.onBackPressedDispatcher.onBackPressed()
                compose.activity.onBackPressedDispatcher.onBackPressed()
            }
            compose.waitUntil(timeoutMillis = 5_000) { hostBackCount == 1 }
            assertSessionStillResumed()
            assertEquals(1, hostBackCount)
        }
    }

    private fun inputCapsuleHeight(): Float {
        val bounds = compose.onNodeWithTag("session-command-input").getUnclippedBoundsInRoot()
        return bounds.bottom.value - bounds.top.value
    }

    private fun assertSessionStillResumed() {
        assertTrue(
            "session activity must remain resumed",
            compose.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
        )
        assertTrue("session activity must not finish", !compose.activity.isFinishing)
        assertTrue("session activity must not be destroyed", !compose.activity.isDestroyed)
    }

    private fun openMenu() {
        compose.onNodeWithContentDescription("返回菜单", useUnmergedTree = true).performClick()
        compose.waitForIdle()
    }

    private fun performGlobalBack(): Boolean {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val accepted = automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        automation.waitForIdle(100, 5_000)
        return accepted
    }

    private fun runProductionRoute(
        onBack: () -> Unit,
        favoriteRows: List<FavoriteRow> = emptyList(),
        overlaySessions: List<L2Entry> = ProductionOverlayFixture.overlayEntries(),
        onOpenOverlaySession: (String, String) -> Unit = { _, _ -> },
        block: () -> Unit,
    ) {
        val previousFactory = ServiceWire.transportFactory
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
                SessionRoute(
                    ref = "production-current",
                    name = "生产当前会话",
                    onBack = onBack,
                    favoriteRows = favoriteRows,
                    overlaySessions = overlaySessions,
                    onOpenOverlaySession = onOpenOverlaySession,
                )
            }
            compose.waitForIdle()
            block()
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

    private fun firstVisibleFavoriteAnchor(count: Int): Pair<Int, Float> {
        val viewport = compose.onNodeWithTag("favorite-session-list").fetchSemanticsNode().boundsInRoot
        return (0 until count).mapNotNull { index ->
            val node = compose.onAllNodesWithTag(
                "session-chip-primary-favorite-$index",
                useUnmergedTree = true,
            ).fetchSemanticsNodes().firstOrNull() ?: return@mapNotNull null
            val bounds = node.boundsInRoot
            if (bounds.right > viewport.left && bounds.left < viewport.right) {
                index to bounds.left
            } else {
                null
            }
        }.minByOrNull { it.second } ?: error("no visible favorite anchor")
    }

    private fun horizontalScrollValue(): Float {
        val node = compose.onNodeWithTag("favorite-session-list").fetchSemanticsNode()
        return node.config[SemanticsProperties.HorizontalScrollAxisRange].value()
    }

    private fun productionFavoriteRows(count: Int): List<FavoriteRow> =
        (0 until count).map { index ->
            val id = "primary-favorite-$index"
            FavoriteRow(
                sessionName = id,
                windowIndex = "0",
                windowName = "收藏-${index + 1}",
                addedAt = index.toLong(),
                isOnline = true,
                ref = id,
                cwd = ProductionOverlayFixture.WORKSPACE,
                title = "收藏-${index + 1}",
                status = if (index % 2 == 0) L2Status.WORKING else L2Status.IDLE,
            )
        }

    private fun productionOverlayEntries(count: Int): List<L2Entry> =
        (0 until count).map { index ->
            val number = index + 1
            L2Entry(
                ref = "/tmp/tmux-1000/audit\\u001f%$number",
                name = "查看会话-$number",
                title = "查看会话-$number",
                rows = 24,
                cols = 80,
                status = if (index % 2 == 0) L2Status.WORKING else L2Status.IDLE,
                cwd = ProductionOverlayFixture.WORKSPACE,
                sessionName = "audit",
                windowIndex = number.toString(),
                windowName = "查看会话-$number",
            )
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
