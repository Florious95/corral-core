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

package dev.agentmirror.app.termview

import android.content.Context
import androidx.core.content.edit

/**
 * 字号持久化（feat-font-size-setting-drop-pinch 契约③：捏合后大小未延续）。
 *
 * 单位 sp（Settings 页选择、[TermSurfaceView.fontSizeSp] 消费）。非敏感设置，明文
 * SharedPreferences 足够（对照 `pairing/PairingConfigStore.kt` 的敏感凭据加密，字号不涉密）。
 */
interface FontSizeStore {
    /** 读取已保存字号；从未设置过返回 null（调用方回落 [SharedPreferencesFontSizeStore.DEFAULT_FONT_SIZE_SP]）。 */
    fun load(): Int?

    /** 保存字号（sp）。 */
    fun save(sp: Int)
}

/** SharedPreferences 持久化实现。 */
class SharedPreferencesFontSizeStore(context: Context) : FontSizeStore {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): Int? =
        if (prefs.contains(KEY_FONT_SIZE_SP)) prefs.getInt(KEY_FONT_SIZE_SP, DEFAULT_FONT_SIZE_SP) else null

    override fun save(sp: Int) {
        // KTX edit {}（同 PairingConfigStore 写法）：默认 apply（异步落盘）足够，字号无同步语义要求。
        prefs.edit { putInt(KEY_FONT_SIZE_SP, sp) }
    }

    companion object {
        private const val PREFS_NAME = "term_font_size"
        private const val KEY_FONT_SIZE_SP = "font_size_sp"

        /**
         * Settings 页可选预设字号（sp）——fix-font-size-scale-unit：用户裁定不改 sp→物理像素
         * 映射，只向下扩展档位（4/6/8/10），原 12/14/16/18/20 保留。
         */
        val PRESET_SIZES_SP: List<Int> = listOf(4, 6, 8, 10, 12, 14, 16, 18, 20)

        /** 从未设置过时的默认字号（sp）。 */
        const val DEFAULT_FONT_SIZE_SP = 14
    }
}
