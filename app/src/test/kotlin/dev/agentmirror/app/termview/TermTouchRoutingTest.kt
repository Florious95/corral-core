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

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import dev.agentmirror.terminal.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Tap-only mouse routing: drags stay exclusively on the viewport scroll path. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TermTouchRoutingTest {

    private data class MouseReport(
        val column: Int,
        val row: Int,
        val press: Boolean,
        val motion: Boolean,
    )

    private fun buildView(): Pair<TermSurfaceView, TermViewPresenter> {
        val emulator = TerminalEmulator(cols = 20, rows = 50)
        emulator.feed((0 until 50).joinToString("\r\n") { "line$it" })
        val presenter = TermViewPresenter(emulator) { _, _ -> }
        val view = TermSurfaceView(ApplicationProvider.getApplicationContext()).apply {
            this.presenter = presenter
            layout(0, 0, 300, 400)
        }
        // Robolectric's font metrics are empty; make the gesture threshold deterministic.
        val lineHeight = TermSurfaceView::class.java.getDeclaredField("lineHeightPx")
        lineHeight.isAccessible = true
        lineHeight.setInt(view, 40)
        return view to presenter
    }

    @Test
    fun tapSendsOneSgrPressAndReleaseAtAlignedCell() {
        val (view, presenter) = buildView()
        val reports = mutableListOf<MouseReport>()
        view.onTermMouse = { col, row, press, motion, _, _, _ ->
            reports += MouseReport(col, row, press, motion)
            true
        }
        val x = view.contentLeftPx() + presenter.cellWidth * 3 + 0.5f
        val y = presenter.cellHeight * 2 + 0.5f
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(0L, 80L, MotionEvent.ACTION_UP, x, y, 0)
        try {
            view.onTouchEvent(down)
            view.onTouchEvent(up)
        } finally {
            down.recycle()
            up.recycle()
        }

        assertEquals(
            listOf(
                MouseReport(column = 4, row = 3, press = true, motion = false),
                MouseReport(column = 4, row = 3, press = false, motion = false),
            ),
            reports,
        )
    }

    @Test
    fun edgeDragUsesTerminalMouseAndNeverScrollsViewport() {
        val (view, _) = buildView()
        val reports = mutableListOf<MouseReport>()
        val scrolls = mutableListOf<Int>()
        view.onTermMouse = { col, row, press, motion, _, _, _ ->
            reports += MouseReport(col, row, press, motion)
            true
        }
        view.onRemoteScrollBy = { scrolls += it }
        val x = view.width - 1f
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, x, 20f, 0)
        val move = MotionEvent.obtain(0L, 100L, MotionEvent.ACTION_MOVE, x, 180f, 0)
        val up = MotionEvent.obtain(0L, 120L, MotionEvent.ACTION_UP, x, 180f, 0)
        try {
            view.onTouchEvent(down)
            view.onTouchEvent(move)
            view.onTouchEvent(up)
        } finally {
            down.recycle()
            move.recycle()
            up.recycle()
        }

        assertTrue("edge drag must emit mouse reports", reports.isNotEmpty())
        assertTrue("edge drag must emit a press", reports.first().press && !reports.first().motion)
        assertTrue("edge drag must report a motion event to Pi", reports.any { it.motion })
        assertTrue("edge drag must emit a release", !reports.last().press && !reports.last().motion)
        assertTrue("edge mouse drag must not enter viewport scrolling", scrolls.isEmpty())
    }

    @Test
    fun dragNeverSendsMouseMotionAndStillScrollsViewport() {
        val (view, _) = buildView()
        val reports = mutableListOf<MouseReport>()
        val scrolls = mutableListOf<Int>()
        view.onTermMouse = { col, row, press, motion, _, _, _ ->
            reports += MouseReport(col, row, press, motion)
            true
        }
        view.onRemoteScrollBy = { scrolls += it }
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 100f, 20f, 0)
        val move = MotionEvent.obtain(0L, 100L, MotionEvent.ACTION_MOVE, 100f, 180f, 0)
        val up = MotionEvent.obtain(0L, 120L, MotionEvent.ACTION_UP, 100f, 180f, 0)
        try {
            view.onTouchEvent(down)
            view.onTouchEvent(move)
            view.onTouchEvent(up)
        } finally {
            down.recycle()
            move.recycle()
            up.recycle()
        }

        assertTrue("drag must never emit a mouse click or button-32 motion", reports.isEmpty())
        assertTrue("drag must remain on the viewport scroll path", scrolls.isNotEmpty())
    }
}
