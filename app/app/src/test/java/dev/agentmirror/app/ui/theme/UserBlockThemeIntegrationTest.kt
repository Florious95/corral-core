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

package dev.agentmirror.app.ui.theme

import android.app.Application
import android.content.Context
import androidx.compose.ui.graphics.toArgb
import dev.agentmirror.terminal.TerminalColor
import dev.agentmirror.terminal.TerminalEmulator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real preferences and real terminal parser. Requires the Android Gradle/Robolectric runtime. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class UserBlockThemeIntegrationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication<Application>()

    @Before fun setUp() {
        TermPalette.resetBindingForTest()
        clearPreferences()
    }

    @After fun tearDown() {
        TermPalette.resetBindingForTest()
        clearPreferences()
    }

    @Test fun persistedChoicesRebuildIndexedAndRgbCachesAndSurviveStoreRecreation() {
        val store = SharedPreferencesTermThemeStore(context)
        store.saveLight("dracula")
        store.saveDark("dracula")
        TermPalette.bind(store)
        val indexed = TerminalColor.Indexed(254)
        val rgb = TerminalColor.Rgb(228, 228, 228)
        val lightBefore = TermPalette.colorFor(indexed, true, false)
        val darkBefore = TermPalette.colorFor(rgb, true, true)
        store.saveLight("solarized")
        store.saveDark("vesper")
        val lightAfter = TermPalette.colorFor(indexed, true, false)
        val darkAfter = TermPalette.colorFor(rgb, true, true)
        assertNotEquals(lightBefore, lightAfter)
        assertNotEquals(darkBefore, darkAfter)
        TermPalette.resetBindingForTest()
        TermPalette.bind(SharedPreferencesTermThemeStore(context))
        assertEquals(lightAfter, TermPalette.colorFor(rgb, true, false))
        assertEquals(darkAfter, TermPalette.colorFor(indexed, true, true))
        assertEquals(lightAfter, TermPalette.asTerminalPalette(false).userBlockBackground.toArgb())
        assertEquals(darkAfter, TermPalette.asTerminalPalette(true).userBlockBackground.toArgb())
    }

    @Test fun existingSgrCellsRecolorWithoutFeedingOrRecreatingTheTerminal() {
        val terminal = TerminalEmulator(20, 4)
        terminal.feed("\u001B[48;5;254m \u001B[0m \u001B[48;2;228;228;228m \u001B[0m \u001B[42m ")
        val indexed = terminal.cellAt(0, 0).style.bg
        val rgb = terminal.cellAt(2, 0).style.bg
        val green = terminal.cellAt(4, 0).style.bg
        assertEquals(TerminalColor.Indexed(254), indexed)
        assertEquals(TerminalColor.Rgb(228, 228, 228), rgb)
        assertEquals(TerminalColor.Indexed(2), green)
        TermPalette.bindSelectionForTest("dracula", "dracula")
        val before = TermPalette.colorFor(indexed, true, true)
        assertEquals(before, TermPalette.colorFor(rgb, true, true))
        TermPalette.bindSelectionForTest("vesper", "vesper")
        val after = TermPalette.colorFor(terminal.cellAt(0, 0).style.bg, true, true)
        assertNotEquals(before, after)
        assertEquals(after, TermPalette.colorFor(terminal.cellAt(2, 0).style.bg, true, true))
        assertEquals(TermPalette.of(true).ansi16[2], TermPalette.colorFor(green, true, true))
        assertEquals(indexed, terminal.cellAt(0, 0).style.bg)
        assertEquals(rgb, terminal.cellAt(2, 0).style.bg)
        assertEquals(green, terminal.cellAt(4, 0).style.bg)
    }

    private fun clearPreferences() {
        context.getSharedPreferences(TermThemeStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }
}
