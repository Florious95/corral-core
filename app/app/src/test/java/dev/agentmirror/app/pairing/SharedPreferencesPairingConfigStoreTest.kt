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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * SharedPreferencesPairingConfigStore 接缝零测（test-app-android-seams 交付物之一）。
 *
 * 覆盖知识基底 §0 第三类：真持久化 round-trip（存→取→删→取 null），用 Robolectric 真
 * SharedPreferences（ShadowSharedPreferences 真实落盘语义，非手写假件）。
 *
 * 语义要点：存储只认 url+token 两键成对（load 缺任一即 null——半写残留不可当已配对）；
 * 清除后全量归 null。token 恒以明文落盘（已知风险，见生产代码注释），本测试只验存取不验加密。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharedPreferencesPairingConfigStoreTest {

    private lateinit var store: SharedPreferencesPairingConfigStore

    @Before
    fun setUp() {
        // 每用例独立 Robolectric app 实例，SharedPreferences 天然隔离；仍显式清一次
        // 防用例间耦合（MainActivityNavTest 模板同款防御性保留）。
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("pairing_config", Context.MODE_PRIVATE)
            .edit().clear().commit()
        store = SharedPreferencesPairingConfigStore(RuntimeEnvironment.getApplication())
    }

    @Test
    fun saveThenLoad_returnsSameConfig() {
        // 存→取 round-trip：url 与 token 原样读出。
        store.save(PairingConfig(url = "http://10.0.0.1:8080", token = "tok-123"))

        val loaded = store.load()
        assertEquals(PairingConfig("http://10.0.0.1:8080", "tok-123"), loaded)
    }

    @Test
    fun saveTwice_overwritesPrevious() {
        // 覆盖写：后存覆盖前存，load 只读到最新值。
        store.save(PairingConfig("http://old", "old-token"))
        store.save(PairingConfig("http://new", "new-token"))

        assertEquals(PairingConfig("http://new", "new-token"), store.load())
    }

    @Test
    fun clear_thenLoad_isNull() {
        // 存→删→取 null：clear 后不再有任何配对配置。
        store.save(PairingConfig("http://x", "t"))
        store.clear()

        assertNull(store.load())
    }

    @Test
    fun load_whenNeverSaved_isNull() {
        // 从未保存 → null（首启判定：null → 配对页）。
        assertNull(store.load())
    }

    @Test
    fun load_whenOnlyUrlWritten_isNull() {
        // 半写残留（只有 url 没 token）不可当已配对：load 必须成对返回，缺任一即 null。
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("pairing_config", Context.MODE_PRIVATE)
            .edit().putString("url", "http://half").apply()

        assertNull(store.load())
    }

    @Test
    fun persistedAcrossNewStoreInstance() {
        // 真持久化：新 store 实例（同 app 进程同 prefs 文件）仍能读到——不是实例内存态。
        store.save(PairingConfig("http://persist", "p-tok"))

        val another = SharedPreferencesPairingConfigStore(RuntimeEnvironment.getApplication())
        assertEquals(PairingConfig("http://persist", "p-tok"), another.load())
    }
}
