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

package dev.agentmirror.app.termview

import dev.agentmirror.terminal.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

/**
 * Baseline 4605951e terminal typography: default 14sp, cell height from
 * Paint font metrics — not the HTML CLI placeholder 12.5px / 1.75 line-height.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TermSourceCliMetricsTest {

    @Test
    fun freshInstallKeepsBaselineFontSizeNotHtmlPlaceholder() {
        val emulator = TerminalEmulator(80, 24)
        val presenter = TermViewPresenter(emulator) { _, _ -> }
        val view = TermSurfaceView(RuntimeEnvironment.getApplication()).apply {
            fontSizeSp = SharedPreferencesFontSizeStore.DEFAULT_FONT_SIZE_SP.toFloat()
            this.presenter = presenter
        }
        assertEquals(14, SharedPreferencesFontSizeStore.DEFAULT_FONT_SIZE_SP)
        assertEquals(14f, view.fontSizeSp, 0.0f)
        val htmlPlaceholderCellH = (
            view.fontSizeSp * view.resources.displayMetrics.scaledDensity * 1.75f
            ).roundToInt()
        assertTrue(presenter.cellHeight >= 1)
        assertTrue(
            "cellH must stay font-metrics packing, not HTML placeholder 1.75 line-height",
            presenter.cellHeight != htmlPlaceholderCellH,
        )
    }
}
