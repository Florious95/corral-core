/*
 * Tester-owned acceptance probe for PR6.  This file is intentionally kept in
 * the tester clone; it is not a product change and is not copied to developer.
 */
package dev.agentmirror.app.termview

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import dev.agentmirror.app.ui.theme.TermPalette
import dev.agentmirror.terminal.TerminalColor
import dev.agentmirror.terminal.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.abs

/** Executes the production geometry and production TermSurfaceView Canvas route. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoundedGlyphAcceptanceTest {

    private data class PathDraw(
        val path: Path,
        val color: Int,
        val strokeWidth: Float,
        val antiAlias: Boolean,
        val style: Paint.Style,
    )

    private class RecordingCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        val paths = mutableListOf<PathDraw>()
        val rects = mutableListOf<Int>()

        override fun drawPath(path: Path, paint: Paint) {
            // TermSurfaceView reuses one Path instance; snapshot it before the next glyph rewinds it.
            paths += PathDraw(Path(path), paint.color, paint.strokeWidth, paint.isAntiAlias, paint.style)
            super.drawPath(path, paint)
        }

        override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
            rects += paint.color
            super.drawRect(left, top, right, bottom, paint)
        }
    }

    @Test
    fun roundedCornerProductionGeometryKeepsDirectionsEndpointsAndSizeAcrossCells() {
        val expected = mapOf(
            0x256D to (true to true),   // ╭ right + down
            0x256E to (false to true),  // ╮ left + down
            0x256F to (false to false), // ╯ left + up
            0x2570 to (true to false),  // ╰ right + up
        )
        // Two representative cells cover the normal and small-cell direct contract without
        // turning this focused PR test into a size/theme matrix.
        for ((cellW, cellH) in listOf(19 to 22, 8 to 12)) {
            for ((cp, direction) in expected) {
                val originX = 113
                val originY = 227
                val corner = BoxBlockGeometry.roundedCorner(cp, originX, originY, cellW, cellH)
                assertTrue("U+${cp.toString(16)} returned null at ${cellW}x$cellH", corner != null)
                assertEquals(direction.first, corner!!.right)
                assertEquals(direction.second, corner.down)
                assertEquals(originX + if (corner.right) cellW.toFloat() else 0f, corner.horizontalEndX, 0.001f)
                assertEquals(originY + if (corner.down) cellH.toFloat() else 0f, corner.verticalEndY, 0.001f)
                assertTrue("negative radius U+${cp.toString(16)} ${cellW}x$cellH", corner.radius >= 0f)
                assertTrue("invalid stroke U+${cp.toString(16)} ${cellW}x$cellH", corner.strokeWidth >= 1f)
                assertTrue("normal/small cell must retain a visible arc", corner.radius > 0f)
            }
        }
    }

    @Test
    fun nonRoundedGeometryRetainsIntegerFillRoute() {
        val originX = 7
        val originY = 11
        val horizontal = BoxBlockGeometry.fills(0x2500, originX, originY, 19, 22)
        assertEquals(1, horizontal.size)
        assertEquals(BoxBlockGeometry.IRect(7, 21, 26, 23), horizontal.single().rect)
        assertEquals(BoxBlockGeometry.IRect(7, 11, 26, 33), BoxBlockGeometry.fills(0x2588, originX, originY, 19, 22).single().rect)
        for (cp in 0x256D..0x2570) assertTrue(BoxBlockGeometry.fills(cp, originX, originY, 19, 22).isEmpty())
        assertFalse(BoxBlockGeometry.roundedCorner(0x2500, originX, originY, 19, 22) != null)
    }

    @Test
    fun productionCanvasUsesFourCurvedPathsWithFixtureColorAndEndpoints() {
        val emulator = TerminalEmulator(8, 2)
        emulator.feed("\u001b[38;2;255;165;0m╭╮╯╰")
        val view = TermSurfaceView(RuntimeEnvironment.getApplication())
        view.nightOverride = false
        view.presenter = TermViewPresenter(emulator) { _, _ -> }
        // Robolectric's legacy font metrics collapse cellH to 1px; seed the production
        // View's measured cell dimensions so this native Canvas probe executes a real arc.
        setPrivateInt(view, "cellW", 19)
        setPrivateInt(view, "cellH", 22)
        view.layout(0, 0, 480, 160)
        val bitmap = Bitmap.createBitmap(480, 160, Bitmap.Config.ARGB_8888)
        val canvas = RecordingCanvas(bitmap)
        view.draw(canvas)

        assertEquals("one production path per rounded fixture glyph", 4, canvas.paths.size)
        val expectedColor = TermPalette.colorFor(
            TerminalColor.Rgb(255, 165, 0),
            background = false,
            dark = false,
        )
        assertTrue("rounded path must not be a fill rectangle", canvas.paths.isNotEmpty())
        assertEquals(
            "rounded fixture foreground must not be emitted as a rectangle",
            0,
            canvas.rects.count { it == expectedColor },
        )
        for (path in canvas.paths) {
            assertEquals(expectedColor, path.color)
            assertTrue("rounded paint must be antialiased", path.antiAlias)
            assertEquals(Paint.Style.STROKE, path.style)
            assertTrue("rounded stroke width must be positive", path.strokeWidth > 0f)
            val points = path.path.approximate(0.25f)
            assertTrue("normal cell must execute a curved path", points.size >= 9)
            val firstX = points[1]
            val firstY = points[2]
            val lastX = points[points.size - 2]
            val lastY = points[points.size - 1]
            assertTrue("path must have distinct endpoints", abs(firstX - lastX) > 0.5f || abs(firstY - lastY) > 0.5f)
        }
        // The source fixture is in directional order; endpoint monotonicity distinguishes
        // all four corner orientations without relying on a font or bitmap oracle.
        val endpointDirections = canvas.paths.map { draw ->
            val p = draw.path.approximate(0.25f)
            val firstX = p[1]
            val firstY = p[2]
            val lastX = p[p.size - 2]
            val lastY = p[p.size - 1]
            (lastX < firstX) to (lastY > firstY)
        }
        assertEquals(
            listOf(true to true, false to true, false to false, true to false),
            endpointDirections,
        )
        bitmap.recycle()
    }

    private fun setPrivateInt(view: TermSurfaceView, name: String, value: Int) {
        TermSurfaceView::class.java.getDeclaredField(name).apply {
            isAccessible = true
            setInt(view, value)
        }
    }
}
