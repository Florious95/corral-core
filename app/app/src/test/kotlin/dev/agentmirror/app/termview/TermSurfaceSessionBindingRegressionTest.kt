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

import android.content.Context
import dev.agentmirror.terminal.TerminalEmulator
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Guards the termview -> session resize edge when a session presenter is rebound to a View. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TermSurfaceSessionBindingRegressionTest {

    @Test
    fun bindingPresenterDoesNotResizeSessionFromItsStaleViewport() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("cell_size", Context.MODE_PRIVATE)
            .edit()
            .putInt("cell_width", 14)
            .putInt("cell_height", 28)
            .commit()

        val resizeCalls = mutableListOf<Pair<Int, Int>>()
        val presenter = TermViewPresenter(TerminalEmulator(cols = 80, rows = 24)) { rows, cols ->
            resizeCalls += rows to cols
        }
        // A retained SessionViewModel presenter still carries the previous View's viewport.
        presenter.onViewportSizeChanged(widthPx = 800, heightPx = 480)
        resizeCalls.clear()

        TermSurfaceView(context).presenter = presenter

        // Binding happens before the replacement View has a viewport, so it must not push a
        // resize into the session using the retained presenter's stale 800x480 geometry.
        assertTrue("binding emitted session resize(s): $resizeCalls", resizeCalls.isEmpty())
    }
}
