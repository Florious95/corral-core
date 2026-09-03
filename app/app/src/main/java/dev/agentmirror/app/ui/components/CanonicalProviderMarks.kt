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

import dev.agentmirror.app.R

/**
 * Closed canonical Provider ids the UI may name. This is not detection and
 * is not an icon atlas: a drawable exists only from a frozen source (HTML
 * extract or prior-app PNG blob).
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

    /**
     * HTML `icon()` extracts for four ids; prior-app PNG blobs for Pi and Copilot
     * (`1b12e92d8efb1c0eec41e14a264f9d80ee833ad9` `R.drawable.provider_*`).
     */
    fun drawableRes(canonicalId: String): Int? = when (canonicalId) {
        "claude_code" -> R.raw.provider_icon_claude_code
        "codex" -> R.raw.provider_icon_codex
        "grok" -> R.raw.provider_icon_grok
        "cursor" -> R.raw.provider_icon_cursor
        "copilot" -> R.drawable.provider_copilot_color
        "pi" -> R.drawable.provider_pi
        else -> null
    }
}
