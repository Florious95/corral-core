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

/**
 * 输入框实时同步设置持久化。
 *
 * 控制 App 会话输入框与远端 CLI 终端的输入同步模式：
 * - 开启（默认 true）：App 输入框打字时实时同步（passthrough）到 CLI 输入框；
 * - 关闭（false）：打字时仅在 App 本地保存草稿，不向远端发送按键/文本；点击发送时一次性投递整串文本并提交。
 */
interface InputSyncStore {
    /** 读取设置；未设置过返回默认值 [SharedPreferencesInputSyncStore.DEFAULT_INPUT_SYNC_ENABLED]。 */
    fun load(): Boolean

    /** 保存设置。 */
    fun save(enabled: Boolean)
}

/** SharedPreferences 持久化实现。 */
class SharedPreferencesInputSyncStore(context: Context) : InputSyncStore {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): Boolean = prefs.getBoolean(KEY_INPUT_SYNC_ENABLED, DEFAULT_INPUT_SYNC_ENABLED)

    override fun save(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_INPUT_SYNC_ENABLED, enabled) }
    }

    companion object {
        const val PREFS_NAME = "input_sync"
        const val KEY_INPUT_SYNC_ENABLED = "input_sync_enabled"
        const val DEFAULT_INPUT_SYNC_ENABLED = true
    }
}
