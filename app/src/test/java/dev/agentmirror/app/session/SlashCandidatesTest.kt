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

package dev.agentmirror.app.session

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlashCandidatesTest {
    private fun draft(text: String, caret: Int = text.length) = TextFieldValue(text, TextRange(caret))

    @Test
    fun onlyACommandWordWithTheCaretAtItsEndAsks() {
        assertEquals("", slashQuery(draft("/")))
        assertEquals("co", slashQuery(draft("/co")))
        assertNull(slashQuery(draft("/compact now")))
        assertNull(slashQuery(draft("/co\nx")))
        assertNull(slashQuery(draft("hello /co")))
        assertNull(slashQuery(draft("/co", caret = 1)))
        assertNull(slashQuery(TextFieldValue("/co", TextRange(1, 3))))
    }

    @Test
    fun builtinsRankByPrefixAndFillWithoutSending() {
        val list = slashCandidates("co", ProviderSlashBuiltins.getValue("pi"), emptyList(), "pi")
        assertEquals("/compact", list.first().title)
        assertEquals("/compact ", list.first().insert)
        assertEquals(2, list.first().match)
        assertTrue(list.none { it.shortcut })
        assertTrue("every Pi built-in for an empty query", slashCandidates("", ProviderSlashBuiltins.getValue("pi"), emptyList(), "pi").size == ProviderSlashBuiltins.getValue("pi").size)
    }

    @Test
    fun shortcutsComeFromTheCurrentProviderOnlyAndInsertTheirFullText() {
        val shortcuts = listOf(
            ShortcutCommand("a", "收尾检查", mapOf("pi" to "/compact 保留决策\n然后总结", "codex" to "/compact")),
            ShortcutCommand("b", "Codex only", mapOf("codex" to "/review")),
            ShortcutCommand("c", "部署", mapOf("pi" to "run deploy.sh")),
        )
        val pi = slashCandidates("comp", emptyList(), shortcuts, "pi")
        assertEquals(listOf("shortcut:a"), pi.map { it.key })
        assertEquals("/compact 保留决策\n然后总结", pi.single().insert)
        assertEquals("/compact 保留决策", pi.single().detail)
        // An empty query lists every shortcut configured for this provider, none from others.
        assertEquals(listOf("shortcut:a", "shortcut:c"), slashCandidates("", emptyList(), shortcuts, "pi").map { it.key })
        assertTrue(slashCandidates("", emptyList(), shortcuts, "unknown").isEmpty())
    }

    @Test
    fun unknownProvidersAdvertiseNoBuiltins() {
        assertNull(ProviderSlashBuiltins["grok"])
        assertNull(ProviderSlashBuiltins["cursor"])
        assertEquals(listOf("compact", "new", "help"), ManagedConsoleBuiltins.map { it.name })
    }
}
