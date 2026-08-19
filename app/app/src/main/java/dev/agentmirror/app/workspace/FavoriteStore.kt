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
 * 收藏落盘抽象（纯 JVM 可注入假；生产用 [SharedPreferencesFavoriteStore]）。
 */
interface FavoriteStore {
    fun load(): List<FavoriteRecord>

    fun save(records: List<FavoriteRecord>)
}

/** 内存实现：单测 / 默认构造。 */
class MemoryFavoriteStore(
    initial: List<FavoriteRecord> = emptyList(),
) : FavoriteStore {
    private val lock = Any()
    private var items: List<FavoriteRecord> = initial.toList()

    override fun load(): List<FavoriteRecord> = synchronized(lock) { items }

    override fun save(records: List<FavoriteRecord>) {
        synchronized(lock) { items = records.toList() }
    }
}

/**
 * SharedPreferences 持久化。JSON 数组，字段见 [FavoriteRecord]。
 *
 * @contract
 * @pre Context 可用 applicationContext
 * @post save 后新实例 load 得到同一组结构键与 addedAt
 * @err 损坏 JSON 当空表，不抛
 */
class SharedPreferencesFavoriteStore(context: Context) : FavoriteStore {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): List<FavoriteRecord> {
        val raw = prefs.getString(KEY_RECORDS, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(FavoriteRecord.serializer()), raw)
        }.getOrElse { emptyList() }
    }

    override fun save(records: List<FavoriteRecord>) {
        prefs.edit {
            putString(KEY_RECORDS, json.encodeToString(ListSerializer(FavoriteRecord.serializer()), records))
        }
    }

    private companion object {
        const val PREFS_NAME = "favorites"
        const val KEY_RECORDS = "records"
        val json = Json { ignoreUnknownKeys = true }
    }
}
