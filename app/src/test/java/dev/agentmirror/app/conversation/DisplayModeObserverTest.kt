package dev.agentmirror.app.conversation

import android.content.Context
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class DisplayModeObserverTest {
    @Test
    fun savedModeNotifiesRouteAndDisposalStopsNotifications() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(SharedPreferencesDisplayModeStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        val store = SharedPreferencesDisplayModeStore(context)
        val seen = mutableListOf<DisplayMode>()
        val stop = store.observe(seen::add)
        store.save(DisplayMode.GUI)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(DisplayMode.GUI), seen)
        stop()
        store.save(DisplayMode.TUI)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(DisplayMode.GUI), seen)
        assertEquals(DisplayMode.TUI, store.load())
    }

    @Test
    fun unknownPreferenceNotifiesWithSafeDefault() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences(SharedPreferencesDisplayModeStore.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(SharedPreferencesDisplayModeStore.KEY, DisplayMode.GUI.name).commit()
        val store = SharedPreferencesDisplayModeStore(context)
        val seen = mutableListOf<DisplayMode>()
        val stop = store.observe(seen::add)
        prefs.edit().putString(SharedPreferencesDisplayModeStore.KEY, "unknown").commit()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(DisplayMode.TUI), seen)
        stop()
    }
}
