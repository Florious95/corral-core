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

import android.content.Context
import androidx.core.content.edit

/** 设置页「外观」持久化。非敏感，明文 SharedPreferences。 */
class SharedPreferencesAppearanceStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): Appearance = when (prefs.getString(KEY_APPEARANCE, null)) {
        VALUE_LIGHT -> Appearance.Light
        VALUE_DARK -> Appearance.Dark
        VALUE_SYSTEM -> Appearance.System
        else -> Appearance.System
    }

    fun save(value: Appearance) {
        prefs.edit {
            putString(
                KEY_APPEARANCE,
                when (value) {
                    Appearance.Light -> VALUE_LIGHT
                    Appearance.Dark -> VALUE_DARK
                    Appearance.System -> VALUE_SYSTEM
                },
            )
        }
    }

    companion object {
        private const val PREFS_NAME = "app_appearance"
        private const val KEY_APPEARANCE = "appearance"
        private const val VALUE_LIGHT = "light"
        private const val VALUE_DARK = "dark"
        private const val VALUE_SYSTEM = "system"
    }
}
