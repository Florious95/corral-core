/* Copyright 2026 AgentMirror Project Authors. Licensed under Apache-2.0. */
package dev.agentmirror.app.termview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Choreographer
import dev.agentmirror.terminal.TerminalEmulator
import java.io.File
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TermForegroundEconomyTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val views = mutableListOf<TermSurfaceView>()

    private fun view(): TermSurfaceView = TermSurfaceView(context).also { views.add(it) }
    private fun presenter() = TermViewPresenter(TerminalEmulator(80, 24)) { _, _ -> }
    private fun field(view: TermSurfaceView, name: String): Any? =
        TermSurfaceView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(view)
    @Suppress("UNCHECKED_CAST")
    private fun advances(view: TermSurfaceView) = field(view, "glyphAdvanceCache") as MutableMap<Int, Float>

    @After
    fun cleanup() {
        for (view in views) {
            val callback = field(view, "frameCallback") as Choreographer.FrameCallback
            Choreographer.getInstance().removeFrameCallback(callback)
            view.presenter = null
        }
        File(context.filesDir, TermDrawControlFiles.OPT).delete()
        File(context.filesDir, TermDrawControlFiles.BURST).delete()
        context.getSharedPreferences("term_viewport_geom", Context.MODE_PRIVATE).edit().clear().commit()
        TermDrawMeter.resetForTest()
    }

    @Test
    fun samePresenterDoesNotRebindMeasureOrRequestFrame() {
        val v = view(); val p = presenter()
        v.presenter = p
        val hook = p.onFrameRequested
        advances(v)[123456] = 17f
        TermSurfaceView::class.java.getDeclaredField("framePending").apply {
            isAccessible = true; setBoolean(v, false)
        }
        repeat(100) { v.presenter = p }
        check(p.onFrameRequested === hook)
        check(advances(v)[123456] == 17f) // applyFontMetrics would have cleared this.
        check(field(v, "framePending") == false)
    }

    @Test
    fun sameFontKeepsCacheButChangedFontStillMeasures() {
        val v = view(); v.presenter = presenter()
        val paint = field(v, "fgPaint") as Paint
        val oldPx = paint.textSize
        advances(v)[123456] = 17f
        repeat(100) { v.fontSizeSp = v.fontSizeSp }
        check(advances(v)[123456] == 17f && paint.textSize == oldPx)
        v.fontSizeSp += 2f
        check(advances(v).isEmpty() && paint.textSize > oldPx)
    }

    @Test
    fun replacementPresenterStillSeedsAndReceivesFrames() {
        val v = view(); val p1 = presenter(); val p2 = presenter()
        v.presenter = p1
        v.presenter = p2
        check(p1.onFrameRequested == null)
        check(p2.cellMetricsSeeded && p2.cellWidth > 0 && p2.cellHeight > 0)
        TermSurfaceView::class.java.getDeclaredField("framePending").apply {
            isAccessible = true; setBoolean(v, false)
        }
        p2.onFrameRequested!!.invoke()
        check(field(v, "framePending") == true)
    }

    @Test
    fun oldViewCannotUnbindNewOwner() {
        val a = view(); val b = view(); val p = presenter()
        a.presenter = p
        b.presenter = p
        val newOwner = p.onFrameRequested
        a.presenter = null
        check(newOwner != null && p.onFrameRequested === newOwner)
    }

    @Test
    fun diagnosticFilesAreNotConsumedByDoFrameOrDraw() {
        val v = view(); v.presenter = presenter()
        v.layout(0, 0, 640, 480)
        val opt = File(context.filesDir, TermDrawControlFiles.OPT)
        val burst = File(context.filesDir, TermDrawControlFiles.BURST)
        opt.writeText("0"); burst.writeText("120")
        TermDrawMeter.optEnabled = true
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val callback = field(v, "frameCallback") as Choreographer.FrameCallback
            repeat(100) { callback.doFrame(it.toLong()); v.draw(canvas) }
            check(TermDrawMeter.optEnabled && burst.readText() == "120")
        } finally { bitmap.recycle() }
    }

    @Test
    fun sharedGeometryRestoresAAfterAnotherViewWroteB() {
        val a = view(); a.presenter = presenter(); a.layout(0, 0, 640, 480)
        val store = SharedPreferencesViewportGeomStore(context)
        val first = store.load()
        val b = view(); b.presenter = presenter(); b.layout(0, 0, 480, 640)
        check(store.load() != first)
        TermSurfaceView::class.java.getDeclaredMethod("persistViewportGeom").apply {
            isAccessible = true; invoke(a)
        }
        check(first != null && store.load() == first)
    }

    @Test
    fun cachedAndUncachedGeometryHaveIdenticalPixels() {
        val v = view()
        val draw = TermSurfaceView::class.java.getDeclaredMethod(
            "drawBoxBlock", Canvas::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val cps = (0x2500..0x259F).filter(BoxBlockGeometry::handles)
        for ((w, h) in listOf(1 to 1, 7 to 8, 19 to 37, 23 to 41)) {
            for (color in listOf(0xffaaff77.toInt(), 0x80334455.toInt())) {
                val a = Bitmap.createBitmap(20 * (w + 4), 8 * (h + 4), Bitmap.Config.ARGB_8888)
                val b = Bitmap.createBitmap(a.width, a.height, Bitmap.Config.ARGB_8888)
                try {
                    a.eraseColor(0xff202020.toInt()); b.eraseColor(0xff202020.toInt())
                    for ((bitmap, fast) in listOf(a to false, b to true)) {
                        TermDrawMeter.optEnabled = fast
                        val canvas = Canvas(bitmap)
                        cps.forEachIndexed { i, cp ->
                            draw.invoke(v, canvas, cp, (i % 20) * (w + 4) + 2, (i / 20) * (h + 4) + 2, w, h, color)
                        }
                    }
                    check(a.sameAs(b)) { "pixel mismatch size=$w,$h color=$color" }
                } finally { a.recycle(); b.recycle() }
            }
        }
    }
}
