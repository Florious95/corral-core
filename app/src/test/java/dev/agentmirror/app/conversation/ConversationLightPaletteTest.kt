package dev.agentmirror.app.conversation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.agentmirror.app.ui.theme.TermPalette
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationLightPaletteTest {
    @Test
    fun lightAppearanceHasCleanLightSurfacesAndDarkText() {
        val p = ConversationPalette.from(TermPalette.Light, dark = false)
        assertFalse("App Light must not be forced to Dark", p.dark)
        for ((name, color) in listOf("canvas" to p.canvas, "bubble" to p.userBubble,
            "surface" to p.surface, "code" to p.code, "panel" to p.panel,
            "panelEnd" to p.panelEnd, "glass" to p.glass)) {
            assertTrue("$name must stay opaque, light and cool-neutral", color.alpha == 1f &&
                color.red >= 0.9f && color.green >= color.red && color.blue >= color.green)
        }
        assertTrue(p.canvas == Color.White)
        assertTrue(p.ink.red < 0.12f && p.ink.green < 0.12f && p.ink.blue < 0.12f)
        assertTrue(TermPalette.contrast(p.userInk.toArgb(), p.userBubble.toArgb()) >= ConversationPalette.BODY_TARGET)
    }

    @Test
    fun appLightStaysLightEvenWithADarkTerminalScheme() {
        val p = ConversationPalette.from(TermPalette.Dark, dark = false)
        assertFalse(p.dark)
        assertTrue(p.canvas == Color.White && p.panel == Color.White && p.glass == Color.White)
        assertTrue(p.ink.red < 0.12f)
    }

    @Test
    fun oppositeAppearancesOfTheSameSchemeNeverShareMemoizedPalette() {
        val scheme = TermPalette.Dark
        val dark = ConversationPalette.of(scheme, dark = true)
        val light = ConversationPalette.of(scheme, dark = false)
        assertNotSame(dark, light)
        assertTrue(dark.dark && dark.canvas == Color.Black)
        assertFalse(light.dark)
        assertTrue(light.canvas == Color.White)
        assertSame(dark, ConversationPalette.of(scheme, dark = true))
        assertSame(light, ConversationPalette.of(scheme, dark = false))
    }
}
