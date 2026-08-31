package dev.agentmirror.app.session

import android.content.Context
import dev.agentmirror.app.ui.theme.SharedPreferencesTermThemeStore
import dev.agentmirror.app.ui.theme.TermPalette

/** Test-only persistence observer using the production theme store and palette invalidation. */
class SessionThemePersistenceTest(context: Context) {
    private val store = SharedPreferencesTermThemeStore(context)
    fun select(light: String, dark: String): ThemeObservation {
        val before = store.load()
        store.saveLight(light); store.saveDark(dark)
        val after = store.load(); TermPalette.invalidate()
        return ThemeObservation(before.lightFamilyId, after.lightFamilyId, TermPalette.of(false).source)
    }
    data class ThemeObservation(val before: String, val after: String, val source: String)
}
