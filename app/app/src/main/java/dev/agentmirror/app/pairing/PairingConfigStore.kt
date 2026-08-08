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

package dev.agentmirror.app.pairing

import android.content.Context
import android.content.SharedPreferences

/**
 * 配对配置持久化抽象（纯 JVM 可注入假；生产用 [SharedPreferencesPairingConfigStore]）。
 */
interface PairingConfigStore {
    /** 读取已配对配置；无则 null（首启判定：null → 配对页）。 */
    fun load(): PairingConfig?

    /** 保存配对配置。 */
    fun save(config: PairingConfig)

    /** 清除（重新配对时可选）。 */
    fun clear()
}

/**
 * SharedPreferences 持久化实现。
 *
 * ⚠ token 以明文存 SharedPreferences：这是已知风险，后续改进用 Keystore
 * （EncryptedSharedPreferences / 独立 Keystore 条目）加密，见 TODO。任何情况下
 * 不得把 token 打进日志（协议 §9 红线）。
 */
class SharedPreferencesPairingConfigStore(context: Context) : PairingConfigStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): PairingConfig? {
        val url = prefs.getString(KEY_URL, null) ?: return null
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        return PairingConfig(url, token)
    }

    override fun save(config: PairingConfig) {
        prefs.edit()
            .putString(KEY_URL, config.url)
            .putString(KEY_TOKEN, config.token)
            .apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_URL).remove(KEY_TOKEN).apply()
    }

    private companion object {
        const val PREFS_NAME = "pairing_config"
        const val KEY_URL = "url"
        const val KEY_TOKEN = "token"
    }
}
