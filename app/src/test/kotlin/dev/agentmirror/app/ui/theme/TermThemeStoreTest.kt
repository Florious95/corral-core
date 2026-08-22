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

import androidx.compose.ui.graphics.toArgb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TermThemeStoreTest {

    @After
    fun tearDown() {
        TermPalette.resetBindingForTest()
    }

    @Test
    fun missingKeysDefaultToVesper() {
        val store = SharedPreferencesTermThemeStore(RuntimeEnvironment.getApplication())
        val sel = store.load()
        assertEquals(TermThemeStore.DEFAULT_FAMILY_ID, sel.lightFamilyId)
        assertEquals(TermThemeStore.DEFAULT_FAMILY_ID, sel.darkFamilyId)
        TermPalette.bind(store)
        assertEquals("Vesper.itermcolors", TermPalette.of(true).source)
        assertEquals("Vesper.itermcolors", TermPalette.of(false).source)
        assertEquals(0xFF101010.toInt(), TermPalette.of(true).defaultBg)
        assertTrue(TermPalette.token(true).startsWith("term-theme-dark"))
        assertTrue(TermPalette.token(true).contains("source=Vesper.itermcolors"))
    }

    @Test
    fun unknownFamilyFallsBackToVesper() {
        val store = SharedPreferencesTermThemeStore(RuntimeEnvironment.getApplication())
        store.saveDark("not-a-family")
        TermPalette.bind(store)
        assertEquals("Vesper.itermcolors", TermPalette.of(true).source)
    }

    @Test
    fun composePaletteUsesThemePaperAndAppUserBlock() {
        TermPalette.bindSelectionForTest("vesper", "vesper")
        val pal = TermPalette.asTerminalPalette(dark = true)
        assertEquals(0xFF101010.toInt(), pal.background.toArgb())
        assertEquals(TerminalPaletteDark.userBlockBackground, pal.userBlockBackground)
        assertEquals(TerminalPaletteDark.userBlockForeground, pal.userBlockForeground)
        assertNotEquals(TerminalPaletteDark.cursor, pal.cursor)
    }
}
