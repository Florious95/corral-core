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

package dev.agentmirror.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.agentmirror.app.ui.model.SessionItem
import dev.agentmirror.app.ui.model.SessionStatus
import dev.agentmirror.app.ui.screens.FavoritesScreen
import dev.agentmirror.app.ui.screens.SessionListScreen
import dev.agentmirror.app.ui.theme.AppTheme

/**
 * Debug-only fixture that hosts both unified lists without pairing.
 * Not present in the release merged manifest.
 */
class ExternalSessionListAcceptanceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                var sessions by remember { mutableStateOf(seedSessions()) }
                var favorites by remember { mutableStateOf(seedFavorites()) }
                var opened by remember { mutableStateOf(listOf<String>()) }
                Column(Modifier.fillMaxSize()) {
                    SessionListScreen(
                        workspaceName = "fixture",
                        workspacePath = "/ws/fixture",
                        sessions = sessions,
                        onBack = {},
                        onSessionClick = { opened = opened + it.id },
                        onToggleStar = { item ->
                            sessions = sessions.map {
                                if (it.id == item.id) it.copy(starred = !it.starred) else it
                            }
                        },
                        modifier = Modifier.height(420.dp),
                    )
                    FavoritesScreen(
                        favorites = favorites,
                        onSessionClick = { opened = opened + "fav:${it.id}" },
                        onToggleStar = { item ->
                            favorites = favorites.filterNot { it.id == item.id }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

internal fun seedSessions(): List<SessionItem> = listOf(
    item("claude-w", "Claude Working", SessionStatus.Busy, "claude_code", "normal"),
    item("codex-i", "Codex Idle", SessionStatus.Idle, "codex", "normal"),
    item("copilot-u", "Copilot UnknownAct", SessionStatus.Unknown, "copilot", "unknown"),
    item("grok-a", "Grok Abnormal", SessionStatus.Busy, "grok", "abnormal"),
    item("cursor-n", "Cursor Idle", SessionStatus.Idle, "cursor", "normal"),
    item("pi-w", "Pi Working", SessionStatus.Busy, "pi", "normal"),
    item("unk-p", "Unknown Provider", SessionStatus.Idle, "unknown", "normal"),
)

internal fun seedFavorites(): List<SessionItem> = listOf(
    item("fav-on", "Online Fav", SessionStatus.Idle, "codex", "normal", starred = true, online = true),
    item("fav-off", "Offline Fav", SessionStatus.Unknown, "unknown", "unknown", starred = true, online = false),
)

private fun item(
    id: String,
    name: String,
    status: SessionStatus,
    provider: String,
    health: String,
    starred: Boolean = false,
    online: Boolean = true,
) = SessionItem(
    id = id,
    displayName = name,
    path = "/ws/$id",
    status = status,
    starred = starred,
    isOnline = online,
    provider = provider,
    health = health,
)
