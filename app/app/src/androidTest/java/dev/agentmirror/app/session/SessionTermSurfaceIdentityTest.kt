package dev.agentmirror.app.session

import android.view.View
import android.view.ViewGroup
import dev.agentmirror.app.termview.TermSurfaceView

/** Test-only hierarchy recorder for the actual AndroidView TermSurfaceView instance. */
class SessionTermSurfaceIdentityTest {
    fun find(root: View): List<TermSurfaceView> = buildList {
        fun walk(view: View) { if (view is TermSurfaceView) add(view); if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i)) }
        walk(root)
    }
}
