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

import android.content.Context
import androidx.core.content.edit

/** 持久化“退出会话时保留 pane 尺寸”设置。 */
interface RetainPaneSizeStore {
    /** 未设置过时返回 [SharedPreferencesRetainPaneSizeStore.DEFAULT_RETAIN_PANE_SIZE]。 */
    fun load(): Boolean

    /** 保存开关状态。 */
    fun save(enabled: Boolean)
}

/** SharedPreferences 实现；设置跨进程重启保留。 */
class SharedPreferencesRetainPaneSizeStore(context: Context) : RetainPaneSizeStore {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): Boolean = prefs.getBoolean(KEY_RETAIN_PANE_SIZE, DEFAULT_RETAIN_PANE_SIZE)

    override fun save(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_RETAIN_PANE_SIZE, enabled) }
    }

    companion object {
        const val PREFS_NAME = "retain_pane_size"
        const val KEY_RETAIN_PANE_SIZE = "retain_pane_size_enabled"
        const val DEFAULT_RETAIN_PANE_SIZE = false
    }
}
