package dev.agentmirror.app.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.agentmirror.app.ui.theme.SessionChromeColors
import dev.agentmirror.app.ui.screens.otherFavoriteRows
import dev.agentmirror.app.workspace.FavoriteRow
import dev.agentmirror.app.ui.theme.TermPalette
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionUiSmokeTest {
    @Test fun emulatorAndChromeContract() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(context.packageName == "dev.agentmirror.app")
        val light = SessionChromeColors.from(TermPalette.Light)
        val dark = SessionChromeColors.from(TermPalette.Dark)
        assertNotEquals(light.page, dark.page)
        assertNotEquals(light.surface, dark.surface)
        val rows = listOf(FavoriteRow("current", "", "", 1, true, ref = "cur"), FavoriteRow("other", "", "", 2, true, ref = "other"))
        assertTrue(otherFavoriteRows(rows, "cur").map { it.ref } == listOf("other"))
        assertTrue(otherFavoriteRows(rows, "other").map { it.ref } == listOf("cur"))
        assertTrue(otherFavoriteRows(listOf(rows.first()), "cur").isEmpty())
    }
}
