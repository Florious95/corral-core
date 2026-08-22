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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 契约 085 §2：主题目录钉具体色值常量，不是非空。
 * 金样来自方案已核的 Vesper / Afterglow Background。
 */
class TermSchemeCatalogTest {

    @Test
    fun upstreamShaIsPinned() {
        assertEquals("4cbae6273354e5e91a7641d72c69daa3de6a867f", TermSchemeCatalog.UPSTREAM_SHA)
    }

    @Test
    fun thirtyFamiliesWithStableIds() {
        assertEquals(30, TermSchemeCatalog.families.size)
        val ids = TermSchemeCatalog.families.map { it.id }
        assertEquals(ids, ids.distinct())
        assertEquals(
            listOf(
                "follow-system", "vesper", "apple-system-colors", "dracula", "solarized",
                "catppuccin", "tokyo-night", "gruvbox", "nord", "monokai-pro",
                "rose-pine", "ayu", "one-half", "kanagawa", "everforest",
                "github", "night-owl", "iceberg", "flexoki", "selenized",
                "modus", "tomorrow", "melange", "zenbones", "atom-one-dark",
                "snazzy", "oceanic-next", "poimandres", "horizon", "zenburn",
            ),
            ids,
        )
    }

    @Test
    fun uniqueSourceFilesMatchFetchedCatalog() {
        val sources = TermSchemeCatalog.families.flatMap { listOf(it.lightSource, it.darkSource) }.toSet()
        assertEquals(sources, TermSchemeCatalog.colorsBySourceFile.keys)
        assertEquals(52, TermSchemeCatalog.colorsBySourceFile.size)
        sources.forEach { name ->
            assertTrue("$name must keep upstream suffix", name.endsWith(".itermcolors"))
        }
    }

    @Test
    fun vesperBackgroundIsGoldSample() {
        val c = TermSchemeCatalog.colors("Vesper.itermcolors")
        assertEquals("Vesper.itermcolors", c.sourceFile)
        assertEquals(0xFF101010.toInt(), c.background)
        assertEquals(16, c.ansi.size)
    }

    @Test
    fun afterglowBackgroundIsGoldSample() {
        val c = TermSchemeCatalog.colors("Afterglow.itermcolors")
        assertEquals("Afterglow.itermcolors", c.sourceFile)
        assertEquals(0xFF212121.toInt(), c.background)
        assertNotEquals(c.background, TermSchemeCatalog.colors("Vesper.itermcolors").background)
    }

    @Test
    fun everySchemeHasOpaque16AnsiAndFgBgCursor() {
        TermSchemeCatalog.colorsBySourceFile.values.forEach { c ->
            assertEquals("${c.sourceFile} ansi", 16, c.ansi.size)
            assertTrue("${c.sourceFile} bg alpha", c.background ushr 24 == 0xFF)
            assertTrue("${c.sourceFile} fg alpha", c.foreground ushr 24 == 0xFF)
            assertTrue("${c.sourceFile} cursor alpha", c.cursor ushr 24 == 0xFF)
            c.ansi.forEachIndexed { i, color ->
                assertTrue("${c.sourceFile} ansi[$i] alpha", color ushr 24 == 0xFF)
            }
        }
    }

    @Test
    fun followSystemMapsAlabasterLightAndAfterglowDark() {
        val f = TermSchemeCatalog.families.first { it.id == "follow-system" }
        assertEquals("默认（Alabaster / Afterglow）", f.title)
        assertEquals("Alabaster.itermcolors", f.lightSource)
        assertEquals("Afterglow.itermcolors", f.darkSource)
        assertEquals(0xFF212121.toInt(), TermSchemeCatalog.colors(f.darkSource).background)
    }

    @Test
    fun oneDarkUsesAtomOneDarkFile() {
        val f = TermSchemeCatalog.families.first { it.id == "atom-one-dark" }
        assertEquals("One Dark", f.title)
        assertEquals("Atom One Dark.itermcolors", f.lightSource)
        assertEquals("Atom One Dark.itermcolors", f.darkSource)
        TermSchemeCatalog.colors("Atom One Dark.itermcolors")
    }

    @Test
    fun solarizedUsesIterm2PrefixedFiles() {
        val f = TermSchemeCatalog.families.first { it.id == "solarized" }
        assertEquals("iTerm2 Solarized Light.itermcolors", f.lightSource)
        assertEquals("iTerm2 Solarized Dark.itermcolors", f.darkSource)
        TermSchemeCatalog.colors(f.lightSource)
        TermSchemeCatalog.colors(f.darkSource)
    }

    @Test
    fun familySlotsResolveIntoCatalog() {
        TermSchemeCatalog.families.forEach { f ->
            val light = TermSchemeCatalog.colors(f.lightSource)
            val dark = TermSchemeCatalog.colors(f.darkSource)
            assertEquals(f.lightSource, light.sourceFile)
            assertEquals(f.darkSource, dark.sourceFile)
        }
    }

    @Test
    fun unknownSourceFileFails() {
        try {
            TermSchemeCatalog.colors("missing-scheme.itermcolors")
            fail("expected error")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("missing-scheme.itermcolors"))
        }
    }
}
