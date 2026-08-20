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

/** 浅槽 / 深槽各自记住的族 id。缺键与未知族都回退 Vesper。 */
data class TermThemeSelection(
    val lightFamilyId: String,
    val darkFamilyId: String,
) {
    companion object {
        val DEFAULT = TermThemeSelection(
            lightFamilyId = TermThemeStore.DEFAULT_FAMILY_ID,
            darkFamilyId = TermThemeStore.DEFAULT_FAMILY_ID,
        )
    }
}

/** 终端主题浅/深两槽的偏好读写。 */
interface TermThemeStore {
    fun load(): TermThemeSelection
    fun saveLight(familyId: String)
    fun saveDark(familyId: String)

    companion object {
        /** 出厂默认族：Vesper（Vesper.itermcolors）。 */
        const val DEFAULT_FAMILY_ID = "vesper"
        const val PREFS_NAME = "app_term_theme"
        const val KEY_LIGHT = "terminal-theme-light"
        const val KEY_DARK = "terminal-theme-dark"
    }
}

/** 终端主题偏好。不写入 app_appearance，避免外观旧探针被脏数据打红。 */
class SharedPreferencesTermThemeStore(context: Context) : TermThemeStore {

    private val prefs = context.applicationContext.getSharedPreferences(
        TermThemeStore.PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    override fun load(): TermThemeSelection = TermThemeSelection(
        lightFamilyId = prefs.getString(TermThemeStore.KEY_LIGHT, null)
            ?: TermThemeStore.DEFAULT_FAMILY_ID,
        darkFamilyId = prefs.getString(TermThemeStore.KEY_DARK, null)
            ?: TermThemeStore.DEFAULT_FAMILY_ID,
    )

    override fun saveLight(familyId: String) {
        prefs.edit { putString(TermThemeStore.KEY_LIGHT, familyId) }
        TermPalette.invalidate()
    }

    override fun saveDark(familyId: String) {
        prefs.edit { putString(TermThemeStore.KEY_DARK, familyId) }
        TermPalette.invalidate()
    }
}
