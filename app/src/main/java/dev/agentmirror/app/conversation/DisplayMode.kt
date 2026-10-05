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

package dev.agentmirror.app.conversation

import android.content.Context
import androidx.core.content.edit

/**
 * How a session opens. TUI is the default and keeps every terminal path byte-identical; GUI opens
 * managed conversation panes natively and launches new Pi agents as managed conversations.
 */
enum class DisplayMode { TUI, GUI }

interface DisplayModeStore {
    fun load(): DisplayMode
    fun save(mode: DisplayMode)
}

class SharedPreferencesDisplayModeStore(context: Context) : DisplayModeStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Unknown or corrupt values fall back to TUI, never to a mode the user did not choose. */
    override fun load(): DisplayMode =
        DisplayMode.entries.firstOrNull { it.name == prefs.getString(KEY, null) } ?: DisplayMode.TUI

    override fun save(mode: DisplayMode) {
        prefs.edit { putString(KEY, mode.name) }
    }

    companion object {
        const val PREFS_NAME = "display_mode"
        const val KEY = "display_mode"
    }
}
