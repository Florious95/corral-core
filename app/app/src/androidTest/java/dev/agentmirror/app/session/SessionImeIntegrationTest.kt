package dev.agentmirror.app.session

import android.view.Window
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Test-only executable IME observation helpers used by the Compose inventory. */
class SessionImeIntegrationTest {
    fun observe(window: Window, root: android.view.View): ImeObservation = ImeObservation(
        softInputMode = window.attributes.softInputMode,
        imeVisible = ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) == true,
        insetBottom = ViewCompat.getRootWindowInsets(root)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0,
    )
    data class ImeObservation(val softInputMode: Int, val imeVisible: Boolean, val insetBottom: Int)
}
