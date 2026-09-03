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
    fun extractedHtmlIconsHaveFrozenHashesAndMissingStayEmpty() {
        assertEquals(
            R.raw.provider_icon_claude_code,
            CanonicalProviderMarks.drawableRes("claude_code"),
        )
        assertEquals(R.raw.provider_icon_codex, CanonicalProviderMarks.drawableRes("codex"))
        assertEquals(R.raw.provider_icon_grok, CanonicalProviderMarks.drawableRes("grok"))
        assertEquals(R.raw.provider_icon_cursor, CanonicalProviderMarks.drawableRes("cursor"))
        assertNull(CanonicalProviderMarks.drawableRes("copilot"))
        assertNull(CanonicalProviderMarks.drawableRes("pi"))
        assertNull(CanonicalProviderMarks.drawableRes("unknown"))

        val ctx = RuntimeEnvironment.getApplication()
        val expected = mapOf(
            R.raw.provider_icon_claude_code to "5d2a03146e55387d8d58cfc44cc1c0fb90c47b559648dbe52f0420f0d9757626",
            R.raw.provider_icon_codex to "9cb405c0c50125d8562fd7a2c9fa4220376d2fc8ecae5ac99f4032e40cf85f7c",
            R.raw.provider_icon_grok to "2425946f7e10d26d978e9fd3cd642cfb6d6db3033d2b4a0819b2b989503540b2",
            R.raw.provider_icon_cursor to "66d07e0c2cc8f227d55fedafbdfcb98825905cbb8b1d071b9a8b68abeb901684",
        )
        expected.forEach { (res, sha) ->
            val bytes = ctx.resources.openRawResource(res).use { it.readBytes() }
            assertEquals(sha, sha256(bytes))
            val text = bytes.decodeToString()
            assertTrue(text.contains(ExtractedProviderIcon.BLOB_PATH))
            assertTrue(!text.contains("unpkg.com"))
            assertTrue(!text.contains("lobehub"))
        }
        val claude = ctx.resources.openRawResource(R.raw.provider_icon_claude_code)
            .use { it.readBytes().decodeToString() }
        assertTrue(claude.contains(ExtractedProviderIcon.CLAUDE_TEXT))
        val grok = ctx.resources.openRawResource(R.raw.provider_icon_grok)
            .use { it.readBytes().decodeToString() }
        assertTrue(grok.contains(ExtractedProviderIcon.GROK_INNER))
        val cursor = ctx.resources.openRawResource(R.raw.provider_icon_cursor)
            .use { it.readBytes().decodeToString() }
        assertTrue(cursor.contains(ExtractedProviderIcon.CURSOR_INNER))
        val codex = ctx.resources.openRawResource(R.raw.provider_icon_codex)
            .use { it.readBytes().decodeToString() }
        assertTrue(codex.contains("stroke-dasharray=\"3.4 1.5\""))
        assertTrue(codex.contains("r=\"3.6\""))

        val assets = ctx.assets.list("provider-icons")
        assertTrue(assets == null || assets.isEmpty())
        assertNull(javaClass.classLoader.getResource("com/caverock/androidsvg/SVG.class"))
        assertNotNull(CanonicalProviderMarks.drawableRes("claude_code"))
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
