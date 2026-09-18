package dev.agentmirror.app.termview

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.View
import android.widget.FrameLayout
import dev.agentmirror.terminal.TerminalEmulator
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Real View attach/visibility dispatch; no invented SurfaceHolder callbacks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class TermRenderLifecycleTest {
    private lateinit var controller: ActivityController<Activity>
    private lateinit var parent: FrameLayout
    private lateinit var view: TermSurfaceView

    @Before
    fun attach() {
        controller = Robolectric.buildActivity(Activity::class.java).create()
        parent = FrameLayout(controller.get())
        controller.get().setContentView(parent)
        view = TermSurfaceView(controller.get())
        parent.addView(view, FrameLayout.LayoutParams(640, 480))
        controller.start().resume().visible().windowFocusChanged(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(64))
        assertTrue(view.isAttachedToWindow)
        // This SDK 34 fixture still reports windowVisibility=GONE after visible();
        // exercise the public visibility dispatch explicitly, as the window does.
        view.dispatchWindowVisibilityChanged(View.VISIBLE)
    }

    @After
    fun cleanup() {
        parent.removeAllViews()
        Choreographer.getInstance().removeFrameCallback(field("frameCallback") as Choreographer.FrameCallback)
        (field("mainHandler") as Handler).removeCallbacks(field("wakeRunnable") as Runnable)
        controller.pause().stop().destroy()
    }

    private fun field(name: String): Any? = TermSurfaceView::class.java.getDeclaredField(name).let {
        it.isAccessible = true
        it.get(view)
    }

    @Suppress("UNCHECKED_CAST")
    private fun request() = (field("frameRequestCallback") as () -> Unit).invoke()

    private fun requestFromWorker() {
        Thread { request() }.apply { start(); join() }
    }

    private fun assertNoPendingWork() {
        assertEquals("hidden/detached View must not retain a vsync claim", false, field("framePending"))
        assertFalse("hidden/detached View must not retain a Handler wake claim", (field("wakeQueued") as AtomicBoolean).get())
    }

    @Test
    fun hiddenCancelsBothQueuedWakeAndVsync() {
        request()
        requestFromWorker()
        assertTrue(field("framePending") as Boolean)
        assertTrue((field("wakeQueued") as AtomicBoolean).get())
        view.dispatchWindowVisibilityChanged(View.INVISIBLE)
        assertNoPendingWork()
    }

    @Test
    fun delayedCaptureCompletionCannotRearmHiddenView() {
        view.dispatchWindowVisibilityChanged(View.INVISIBLE)
        requestFromWorker() // Capture completes after the visibility callback, not before it.
        assertNoPendingWork()
        request() // The UI-thread route must obey the same visibility gate.
        assertNoPendingWork()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        assertNoPendingWork()
    }

    @Test
    fun detachCancelsRequestsAndReattachForcesFrameWithoutNewDamage() {
        request()
        requestFromWorker()
        parent.removeView(view)
        assertFalse(view.isAttachedToWindow)
        assertNoPendingWork()
        requestFromWorker()
        assertNoPendingWork()
        val before = field("lastFrameTimeNanos") as Long
        parent.addView(view)
        view.dispatchWindowVisibilityChanged(View.VISIBLE)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(64))
        assertTrue("reattach must run a new frame without emulator damage", (field("lastFrameTimeNanos") as Long) > before)
        assertNoPendingWork()
    }

    @Test
    fun foregroundAppliesLatestScreenWithoutAnotherInputEvent() {
        val presenter = TermViewPresenter(TerminalEmulator(80, 24)) { _, _ -> }
        val source = TerminalEmulator(80, 24)
        view.presenter = presenter
        fun renderedRow() = presenter.lineCells(0).joinToString("") { it.text }.trimEnd()
        source.feed("BEFORE_BACKGROUND")
        presenter.setDisplaySnapshot(source.snapshot())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(64))
        assertEquals("BEFORE_BACKGROUND", renderedRow())

        view.dispatchWindowVisibilityChanged(View.INVISIBLE)
        source.feed("\u001b[H\u001b[2JAFTER_BACKGROUND")
        presenter.setDisplaySnapshot(source.snapshot())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        assertEquals("hidden View must not consume a new render frame", "BEFORE_BACKGROUND", renderedRow())
        assertNoPendingWork()

        view.dispatchWindowVisibilityChanged(View.VISIBLE)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(64))
        assertEquals("resume must apply already available content without another touch/delta", "AFTER_BACKGROUND", renderedRow())
        view.presenter = null
    }

    @Test
    fun visibleReplacesLostCallbacksRatherThanTrustingStaleClaims() {
        request()
        requestFromWorker()
        // Model callbacks lost while the app/window was suspended. The flags
        // deliberately stay true: checking only postFrame's dedup bit cannot heal.
        Choreographer.getInstance().removeFrameCallback(field("frameCallback") as Choreographer.FrameCallback)
        (field("mainHandler") as Handler).removeCallbacks(field("wakeRunnable") as Runnable)
        val before = field("lastFrameTimeNanos") as Long
        view.dispatchWindowVisibilityChanged(View.VISIBLE)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(64))
        assertTrue("VISIBLE must force an actual new Choreographer callback", (field("lastFrameTimeNanos") as Long) > before)
        assertNoPendingWork()
        val resumed = field("lastFrameTimeNanos") as Long
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        assertEquals("recovery must not create a permanent frame loop", resumed, field("lastFrameTimeNanos"))
    }
}
