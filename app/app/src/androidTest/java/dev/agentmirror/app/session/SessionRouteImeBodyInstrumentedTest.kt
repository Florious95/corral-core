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
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import dev.agentmirror.app.conn.ConnectionConfig
import dev.agentmirror.app.service.MirrorForegroundService
import dev.agentmirror.app.service.NoopTransportFactory
import dev.agentmirror.app.service.ServiceWire
import dev.agentmirror.app.termview.SharedPreferencesFontSizeStore
import dev.agentmirror.app.termview.TermSurfaceView
import dev.agentmirror.app.workspace.FavoriteRow
import dev.agentmirror.app.workspace.L2Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class SessionRouteImeBodyInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun productionSessionRouteKeepsSameTerminalBodyThroughImeFocusAndBack() {
        val previousFactory = ServiceWire.transportFactory
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        appContext.getSharedPreferences("term_font_size", 0).edit().clear().commit()
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
                    ref = "audit-a-current",
                    name = "A 当前会话",
                    onBack = {},
                    favoriteRows = listOf(
                        FavoriteRow(
                            sessionName = "audit-a-peer",
                            windowIndex = "0",
                            windowName = "A 收藏同目录",
                            addedAt = 1L,
                            isOnline = true,
                            ref = "audit-a-peer",
                            cwd = "/audit/workspace-A",
                            title = "A 收藏同目录",
                            status = L2Status.IDLE,
                        ),
                        FavoriteRow(
                            sessionName = "audit-b-global",
                            windowIndex = "0",
                            windowName = "B 全局收藏",
                            addedAt = 2L,
                            isOnline = true,
                            ref = "audit-b-global",
                            cwd = "/audit/workspace-B",
                            title = "B 全局收藏",
                            status = L2Status.IDLE,
                        ),
                    ),
                )
            }
            compose.waitForIdle()
            lateinit var surface: TermSurfaceView
            compose.runOnUiThread { surface = termSurface() }
            val beforeId = System.identityHashCode(surface)
            compose.runOnUiThread {
                val vm = ServiceWire.uiConnector as SessionViewModel
                vm.emulator.feed(
                    "Agent CLI visual audit\r\nproduction SessionRoute · PR #69\r\nready $ ",
                )
                surface.invalidate()
            }
            compose.waitUntil(timeoutMillis = 5_000) { bodyIsVisible() }
            assertSourceFont(surface)
            val restingCapsule = inputCapsuleHeight()

            compose.onNodeWithTag("session-command-editor").performTouchInput { click() }
            compose.waitUntil(timeoutMillis = 5_000) { inputCapsuleHeight() >= 85.5f }
            assertEquals(86f, inputCapsuleHeight(), 0.5f)
            lateinit var focused: TermSurfaceView
            compose.runOnUiThread { focused = termSurface() }
            assertEquals("IME focus must keep the same TermSurfaceView instance", beforeId, System.identityHashCode(focused))
            assertTrue("terminal body must remain visible while IME is up", bodyIsVisible())
            assertTrue("IME squeeze must leave a real measured canvas", focused.width > 0 && focused.height > 0)
            assertSourceFont(focused)

            assertTrue(
                InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
                    AccessibilityService.GLOBAL_ACTION_BACK,
                ),
            )
            compose.waitUntil(timeoutMillis = 5_000) {
                abs(inputCapsuleHeight() - restingCapsule) < 0.5f
            }
            lateinit var after: TermSurfaceView
            compose.runOnUiThread { after = termSurface() }
            assertEquals("Back must keep the same TermSurfaceView instance", beforeId, System.identityHashCode(after))
            assertTrue("terminal body must remain visible after Back", bodyIsVisible())
            assertEquals(46f, inputCapsuleHeight(), 0.5f)
            assertSourceFont(after)
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

    private fun inputCapsuleHeight(): Float {
        val bounds = compose.onNodeWithTag("session-command-input").getUnclippedBoundsInRoot()
        return bounds.bottom.value - bounds.top.value
    }

    private fun termSurface(): TermSurfaceView {
        val found = ArrayList<TermSurfaceView>()
        fun walk(view: View) {
            if (view is TermSurfaceView) found += view
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
        }
        walk(compose.activity.window.decorView)
        assertEquals("production SessionRoute must host exactly one TermSurfaceView", 1, found.size)
        return found[0]
    }

    private fun bodyIsVisible(): Boolean {
        var visible = false
        compose.runOnUiThread {
            val view = termSurface()
            val presenter = view.presenter
            visible = presenter != null &&
                view.width > 0 &&
                view.height > 0 &&
                presenter.window.any { row ->
                    presenter.lineCells(row).any { it.text.isNotBlank() }
                }
        }
        return visible
    }

    private fun assertSourceFont(view: TermSurfaceView) {
        assertEquals(12.5f, view.fontSizeSp, 0.0f)
        val sizePx = view.fontSizeSp * view.resources.displayMetrics.scaledDensity
        val expectedCellH = (sizePx * SharedPreferencesFontSizeStore.SOURCE_LINE_HEIGHT_MULTIPLIER)
            .roundToInt()
            .coerceAtLeast(1)
        assertEquals(expectedCellH, view.presenter!!.cellHeight)
        val cellHDp = view.presenter!!.cellHeight / view.resources.displayMetrics.density
        assertEquals(21.875f, cellHDp, 0.2f)
    }
}
