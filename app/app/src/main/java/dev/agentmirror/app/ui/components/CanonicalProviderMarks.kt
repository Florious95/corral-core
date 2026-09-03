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

package dev.agentmirror.app.ui.components

/**
 * Closed canonical Provider ids the UI may name. This is not detection and
 * is not an icon atlas: a drawable exists only when the user-frozen visual
 * source contains one unique glyph for that id.
 *
 * @contract
 * @pre [id] is the status-core DTO canonical provider string
 * @post lookup is exact id match only; no title/argv/alias heuristics
 * @err none
 */
data class CanonicalProviderMark(
    val id: String,
    val displayName: String,
)

object CanonicalProviderMarks {
    val all: List<CanonicalProviderMark> = listOf(
        CanonicalProviderMark("claude_code", "Claude Code"),
        CanonicalProviderMark("codex", "Codex"),
        CanonicalProviderMark("copilot", "Copilot"),
        CanonicalProviderMark("grok", "Grok"),
        CanonicalProviderMark("cursor", "Cursor"),
        CanonicalProviderMark("pi", "Pi"),
    )

    private val byId = all.associateBy { it.id }

    fun of(canonicalId: String): CanonicalProviderMark? = byId[canonicalId]

    /** No glyph from the frozen JPEG source; never invent a substitute. */
    fun drawableRes(canonicalId: String): Int? = null
}
