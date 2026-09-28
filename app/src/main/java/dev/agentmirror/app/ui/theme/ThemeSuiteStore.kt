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

/**
 * 设置页「界面风格」持久化：独立偏好文件，⛔ 不与「外观」（app_appearance）或终端主题槽共用。
 * 读到未知 / 缺失值回退 [ThemeRegistry.default]，不改写存储的原值。
 */
class SharedPreferencesThemeSuiteStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): ThemeId = ThemeRegistry.idOrDefault(prefs.getString(KEY_THEME_ID, null))

    fun save(id: ThemeId) {
        prefs.edit { putString(KEY_THEME_ID, id.value) }
    }

    companion object {
        const val PREFS_NAME = "app_theme_suite"
        const val KEY_THEME_ID = "theme_id"
    }
}
