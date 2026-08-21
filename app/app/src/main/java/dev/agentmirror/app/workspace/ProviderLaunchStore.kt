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

package dev.agentmirror.app.workspace

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Provider 启动命令落盘（契约 088 E14）。prefs 名 `provider_launch`。
 *
 * @contract
 * @pre 无
 * @post load 永远返回六个白名单 id，缺的用出厂默认补；Pi 的 bypassFlag 恒为空
 * @err 损坏 JSON 回落默认，不抛
 * @inv 不发明第七个 providerId
 */
interface ProviderLaunchStore {
    fun load(): List<ProviderLaunch>
    fun save(launches: List<ProviderLaunch>)
}

/** 内存实现：单测 / 默认构造。 */
class MemoryProviderLaunchStore(
    initial: List<ProviderLaunch> = ProviderLaunchDefaults.all,
) : ProviderLaunchStore {
    private val lock = Any()
    private var items: List<ProviderLaunch> = mergeWithDefaults(initial)

    override fun load(): List<ProviderLaunch> = synchronized(lock) { items }

    override fun save(launches: List<ProviderLaunch>) {
        synchronized(lock) { items = mergeWithDefaults(launches) }
    }
}

/** SharedPreferences 持久化，prefs 名 `provider_launch`。 */
class SharedPreferencesProviderLaunchStore(context: Context) : ProviderLaunchStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): List<ProviderLaunch> {
        val raw = prefs.getString(KEY_LAUNCHES, null) ?: return ProviderLaunchDefaults.all
        val parsed = runCatching {
            json.decodeFromString(ListSerializer(ProviderLaunch.serializer()), raw)
        }.getOrElse { emptyList() }
        return mergeWithDefaults(parsed)
    }

    override fun save(launches: List<ProviderLaunch>) {
        val merged = mergeWithDefaults(launches)
        prefs.edit {
            putString(KEY_LAUNCHES, json.encodeToString(ListSerializer(ProviderLaunch.serializer()), merged))
        }
    }

    private companion object {
        const val PREFS_NAME = "provider_launch"
        const val KEY_LAUNCHES = "launches"
        val json = Json { ignoreUnknownKeys = true }
    }
}

internal fun mergeWithDefaults(stored: List<ProviderLaunch>): List<ProviderLaunch> {
    val byId = stored.associateBy { it.providerId }
    return ProviderLaunchDefaults.all.map { def ->
        val hit = byId[def.providerId]
        if (hit == null) def
        else {
            val flag = if (def.providerId == "pi") "" else hit.bypassFlag
            def.copy(
                command = hit.command.ifBlank { def.command },
                bypassFlag = flag,
            )
        }
    }
}
