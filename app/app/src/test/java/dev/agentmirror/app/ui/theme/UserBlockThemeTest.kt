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
import androidx.compose.ui.graphics.toArgb
import dev.agentmirror.terminal.TerminalColor
import kotlin.math.pow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Public-API regressions: these tests also compile against the pre-fix palette. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class UserBlockThemeTest {
    @Before fun setUp() = TermPalette.resetBindingForTest()
    @After fun tearDown() = TermPalette.resetBindingForTest()

    @Test fun draculaUsesItsReadableSelectionInsteadOfShellGreen() {
        select("dracula")
        for (dark in listOf(false, true)) {
            val actual = TermPalette.of(dark).userBlockBg
            assertEquals(0xFF44475A.toInt(), actual)
            assertNotEquals(0xFF10241F.toInt(), actual)
            assertNotEquals(0xFFE6F5F2.toInt(), actual)
        }
    }

    @Test fun changingFamilyWithoutChangingNightModeChangesUserBlock() {
        for (dark in listOf(false, true)) {
            select("dracula")
            val before = TermPalette.of(dark).userBlockBg
            select("vesper")
            assertNotEquals(before, TermPalette.of(dark).userBlockBg)
        }
    }

    @Test fun sameSourceInLightAndDarkSlotsHasTheSameUserBlockAndText() {
        for (family in TermSchemeCatalog.families.filter { it.lightSource == it.darkSource }) {
            select(family.id)
            assertEquals(family.id, TermPalette.of(false).userBlockBg, TermPalette.of(true).userBlockBg)
            assertEquals(
                family.id,
                TermPalette.asTerminalPalette(false).userBlockForeground.toArgb(),
                TermPalette.asTerminalPalette(true).userBlockForeground.toArgb(),
            )
        }
    }

    @Test fun composeExportMatchesResolvedSchemeForEveryCatalogSlot() {
        forEachSlot { label, dark, palette ->
            val exported = TermPalette.asTerminalPalette(dark)
            assertEquals(label, palette.userBlockBg, exported.userBlockBackground.toArgb())
            assertEquals(label, palette.defaultFg, exported.userBlockForeground.toArgb())
            assertEquals(label, palette.defaultBg, exported.background.toArgb())
            assertEquals(label, palette.defaultFg, exported.foreground.toArgb())
            assertEquals(label, palette.cursor, exported.cursor.toArgb())
            palette.selection?.let { assertEquals(label, it, exported.selection.toArgb()) }
        }
    }

    @Test fun everyCatalogSlotPreservesThemeColorsAndReadableDefaultText() {
        forEachSlot { label, _, palette ->
            val source = TermSchemeCatalog.colorsBySourceFile.getValue(palette.source)
            assertEquals(label, source.background, palette.defaultBg)
            assertEquals(label, source.foreground, palette.defaultFg)
            for (index in 0..15) assertEquals(label, source.ansi[index], palette.ansi16[index])
            assertEquals(label, 255, palette.userBlockBg ushr 24)
            val floor = minOf(4.5, contrastRatio(palette.defaultFg, palette.defaultBg))
            assertTrue(label, contrastRatio(palette.defaultFg, palette.userBlockBg) + 1e-9 >= floor)
        }
    }

    @Test fun indexedAndTrueColorUserBlockRoutesAgreeColdWarmAndUncached() {
        forEachSlot { label, dark, palette ->
            val aliases = listOf(
                TerminalColor.Indexed(254), TerminalColor.Indexed(231),
                TerminalColor.Indexed(253), TerminalColor.Indexed(255),
                TerminalColor.Rgb(228, 228, 228), TerminalColor.Rgb(255, 255, 255),
                TerminalColor.Rgb(250, 240, 230),
            )
            for (color in aliases) {
                // null/default background takes the precomputed table or RGB memo;
                // a nondefault background takes the uncached route.
                for (against in listOf(null, palette.defaultBg, palette.defaultBg xor 0x00010101)) {
                    repeat(2) {
                        assertEquals(label, palette.userBlockBg, TermPalette.colorFor(color, true, dark, against))
                    }
                }
            }
            assertEquals(label, palette.userBlockBg, palette.xterm256[254])
        }
    }

    @Test fun ordinaryAnsiAndDefaultBackgroundSemanticsAreUnchanged() {
        forEachSlot { label, dark, palette ->
            for (i in 0..15) {
                assertEquals(label, palette.ansi16[i], TermPalette.colorFor(TerminalColor.Indexed(i), false, dark))
                if (i != 0) assertEquals(label, palette.ansi16[i], TermPalette.colorFor(TerminalColor.Indexed(i), true, dark))
            }
            assertEquals(label, palette.defaultBg, TermPalette.colorFor(TerminalColor.Default, true, dark))
            assertEquals(label, palette.defaultFg, TermPalette.colorFor(TerminalColor.Default, false, dark))
            for (color in listOf(TerminalColor.Indexed(0), TerminalColor.Indexed(16), TerminalColor.Rgb(0, 0, 0))) {
                assertEquals(label, palette.defaultBg, TermPalette.colorFor(color, true, dark))
            }
        }
    }

    @Test fun familyRoundTripRebuildsBothIndexedAndRgbCachesWithoutMutatingOldScheme() {
        val store = MemoryStore("dracula", "dracula")
        TermPalette.bind(store)
        for (dark in listOf(false, true)) {
            val old = TermPalette.of(dark)
            val expectedOld = old.userBlockBg
            val rgb = TerminalColor.Rgb(228, 228, 228)
            val indexed = TerminalColor.Indexed(254)
            assertEquals(expectedOld, TermPalette.colorFor(rgb, true, dark))
            assertEquals(expectedOld, TermPalette.colorFor(indexed, true, dark))
            if (dark) store.saveDark("vesper") else store.saveLight("vesper")
            val next = TermPalette.of(dark).userBlockBg
            assertNotEquals(expectedOld, next)
            assertEquals(next, TermPalette.colorFor(rgb, true, dark))
            assertEquals(next, TermPalette.colorFor(indexed, true, dark))
            assertEquals(expectedOld, old.userBlockBg)
            assertEquals(expectedOld, old.xterm256[254])
            if (dark) store.saveDark("dracula") else store.saveLight("dracula")
            assertEquals(expectedOld, TermPalette.colorFor(rgb, true, dark))
            assertEquals(expectedOld, TermPalette.colorFor(indexed, true, dark))
        }
    }

    @Test fun lightAndDarkChoicesStayIndependentAcrossSaveAndRebind() {
        val store = MemoryStore("dracula", "vesper")
        TermPalette.bind(store)
        val lightBefore = TermPalette.of(false).userBlockBg
        val darkBefore = TermPalette.of(true).userBlockBg
        store.saveLight("solarized")
        assertNotEquals(lightBefore, TermPalette.of(false).userBlockBg)
        assertEquals(darkBefore, TermPalette.of(true).userBlockBg)
        val lightAfter = TermPalette.of(false).userBlockBg
        store.saveDark("catppuccin")
        assertNotEquals(darkBefore, TermPalette.of(true).userBlockBg)
        assertEquals(lightAfter, TermPalette.of(false).userBlockBg)
        val savedDark = TermPalette.of(true).userBlockBg
        TermPalette.resetBindingForTest()
        TermPalette.bind(store)
        assertEquals(lightAfter, TermPalette.of(false).userBlockBg)
        assertEquals(savedDark, TermPalette.of(true).userBlockBg)
    }

    @Test fun unknownAndMissingSelectionsResolveToVesperNotLegacyGreen() {
        select("vesper")
        val expected = TermPalette.of(true).userBlockBg
        select("missing-theme-id")
        for (dark in listOf(false, true)) assertEquals(expected, TermPalette.of(dark).userBlockBg)
        TermPalette.resetBindingForTest()
        for (dark in listOf(false, true)) assertEquals(expected, TermPalette.of(dark).userBlockBg)
    }

    @Test fun emergencyPalettesAlsoDeriveReadableBlocksInsteadOfFixedUserColors() {
        for (palette in listOf(TermPalette.Light, TermPalette.Dark)) {
            val floor = minOf(4.5, contrastRatio(palette.defaultFg, palette.defaultBg))
            assertTrue(contrastRatio(palette.defaultFg, palette.userBlockBg) + 1e-9 >= floor)
            assertNotEquals(0xFF10241F.toInt(), palette.userBlockBg)
            assertNotEquals(0xFFE6F5F2.toInt(), palette.userBlockBg)
        }
    }

    @Test fun unchangedSelectionReusesSchemeAndInvalidationKeepsDeterministicColors() {
        select("dracula")
        val old = TermPalette.of(true)
        repeat(10) { assertTrue(old === TermPalette.of(true)) }
        TermPalette.invalidate()
        val rebuilt = TermPalette.of(true)
        assertTrue(old !== rebuilt)
        assertEquals(old.userBlockBg, rebuilt.userBlockBg)
    }

    private fun select(id: String) = TermPalette.bindSelectionForTest(id, id)

    private fun forEachSlot(assertion: (String, Boolean, TermPalette.Scheme) -> Unit) {
        for (family in TermSchemeCatalog.families) {
            select(family.id)
            for (dark in listOf(false, true)) assertion("${family.id}/dark=$dark", dark, TermPalette.of(dark))
        }
    }

    private class MemoryStore(light: String, dark: String) : TermThemeStore {
        private var selection = TermThemeSelection(light, dark)
        override fun load() = selection
        override fun saveLight(familyId: String) {
            selection = selection.copy(lightFamilyId = familyId)
            TermPalette.invalidate()
        }
        override fun saveDark(familyId: String) {
            selection = selection.copy(darkFamilyId = familyId)
            TermPalette.invalidate()
        }
    }
}

/** Independent WCAG relative-luminance oracle, not the production helper. */
internal fun contrastRatio(first: Int, second: Int): Double {
    fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val encoded = ((color ushr shift) and 255) / 255.0
            return if (encoded <= 0.04045) encoded / 12.92 else ((encoded + 0.055) / 1.055).pow(2.4)
        }
        return channel(16) * 0.2126 + channel(8) * 0.7152 + channel(0) * 0.0722
    }
    val a = luminance(first)
    val b = luminance(second)
    return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
}
