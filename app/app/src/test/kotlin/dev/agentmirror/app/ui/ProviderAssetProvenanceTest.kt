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

package dev.agentmirror.app.ui

import dev.agentmirror.app.R
import dev.agentmirror.app.ui.components.CanonicalProviderMarks
import dev.agentmirror.app.ui.components.ExtractedProviderIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderAssetProvenanceTest {
    @Test
    fun extractedHtmlIconsAndPriorAppPngsHaveFrozenHashes() {
        assertEquals(
            R.raw.provider_icon_claude_code,
            CanonicalProviderMarks.drawableRes("claude_code"),
        )
        assertEquals(R.raw.provider_icon_codex, CanonicalProviderMarks.drawableRes("codex"))
        assertEquals(R.drawable.provider_grok, CanonicalProviderMarks.drawableRes("grok"))
        assertEquals(R.raw.provider_icon_cursor, CanonicalProviderMarks.drawableRes("cursor"))
        assertEquals(R.drawable.provider_copilot_color, CanonicalProviderMarks.drawableRes("copilot"))
        assertEquals(R.drawable.provider_pi, CanonicalProviderMarks.drawableRes("pi"))
        assertNull(CanonicalProviderMarks.drawableRes("unknown"))

        val ctx = RuntimeEnvironment.getApplication()
        val expectedSvg = mapOf(
            R.raw.provider_icon_claude_code to ("907175cc3a05492c941144633eedc8def950e345f41705a571ec087b5e6b9183" to ExtractedProviderIcon.CLAUDE_BRAND_PATH),
            R.raw.provider_icon_codex to ("6fe42dc2de268e438b2f3cc32eaa07a4b34f17eafc9320bc3381a5e475c3dfe0" to ExtractedProviderIcon.CODEX_BRAND_PATH),
            R.raw.provider_icon_cursor to ("64c552bfae500e7b1d80cb3a7186553817c188504537fea7faa096c161ca200e" to ExtractedProviderIcon.CURSOR_BRAND_PATH),
        )
        expectedSvg.forEach { (res, expected) ->
            val bytes = ctx.resources.openRawResource(res).use { it.readBytes() }
            assertEquals(expected.first, sha256(bytes))
            val text = bytes.decodeToString()
            assertTrue(text.contains(expected.second))
            assertTrue(!text.contains("unpkg.com"))
            assertTrue(!text.contains("lobehub"))
        }
        val expectedPng = mapOf(
            R.drawable.provider_codex_color to "cdfc4f2eecc16469176a3cdfb0decb43646e7e3ac44e894f0cc94d330d897260",
            R.drawable.provider_pi to "9d59066fac0cb0361fb7cf663e87d0f29beb654e49780baa55aab74aa4757b2f",
            R.drawable.provider_copilot_color to "49faef29cb14fa7aaa73672ef126acee65ff504c2463a6672d9a9364fa75c54a",
            R.drawable.provider_grok to "515fd702a733df33e669a431f7d0b465350c8332c344c59672f2782f5ce3ff10",
        )
        expectedPng.forEach { (res, sha) ->
            val bytes = ctx.resources.openRawResource(res).use { it.readBytes() }
            assertEquals(sha, sha256(bytes))
            assertEquals(0x89.toByte(), bytes[0])
            assertEquals('P'.code.toByte(), bytes[1])
            assertEquals('N'.code.toByte(), bytes[2])
            assertEquals('G'.code.toByte(), bytes[3])
        }

        val assets = ctx.assets.list("provider-icons")
        assertTrue(assets == null || assets.isEmpty())
        assertNull(javaClass.classLoader.getResource("com/caverock/androidsvg/SVG.class"))
        assertNotNull(CanonicalProviderMarks.drawableRes("pi"))
        assertTrue(ExtractedProviderIcon.draws("claude_code"))
        assertTrue(ExtractedProviderIcon.draws("codex"))
        assertTrue(ExtractedProviderIcon.draws("cursor"))
        assertTrue(!ExtractedProviderIcon.draws("pi"))
        assertTrue(!ExtractedProviderIcon.draws("copilot"))
        assertTrue(!ExtractedProviderIcon.draws("grok"))
        val grokBytes = ctx.resources.openRawResource(R.drawable.provider_grok).use { it.readBytes() }
        val grokText = grokBytes.decodeToString()
        assertTrue(!grokText.contains(ExtractedProviderIcon.GROK_ICON_SWITCH_X_FALLBACK))
        assertTrue(!grokText.contains("M9 15.5 15 9"))
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
