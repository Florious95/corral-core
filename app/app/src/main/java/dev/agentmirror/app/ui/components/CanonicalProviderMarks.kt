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
 * Presentation table from an already-canonical Provider id to a vendored
 * official mark. This is not detection: unknown/unrecognized ids return null
 * and the row shows no icon, no guessed neighbor, and no question mark.
 *
 * @contract
 * @pre [id] is the status-core DTO canonical provider string
 * @post lookup is exact id match only; no title/argv/alias heuristics
 * @err none
 */
data class CanonicalProviderMark(
    val id: String,
    val displayName: String,
    val assetPath: String,
    val currentColor: Boolean,
)

object CanonicalProviderMarks {
    val all: List<CanonicalProviderMark> = listOf(
        CanonicalProviderMark("claude_code", "Claude Code", "provider-icons/claude-color.svg", false),
        CanonicalProviderMark("codex", "Codex", "provider-icons/codex-color.svg", false),
        CanonicalProviderMark("copilot", "Copilot", "provider-icons/copilot-color.svg", false),
        CanonicalProviderMark("grok", "Grok", "provider-icons/grok.svg", true),
        CanonicalProviderMark("cursor", "Cursor", "provider-icons/cursor.svg", true),
        CanonicalProviderMark("pi", "Pi", "provider-icons/pi.svg", true),
    )

    private val byId = all.associateBy { it.id }

    fun of(canonicalId: String): CanonicalProviderMark? = byId[canonicalId]
}
