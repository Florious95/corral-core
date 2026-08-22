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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 红测（feat-font-size-setting-drop-pinch，taskbook acceptance 第 3 条）：
 * 字号变更后重启 App 仍是所选字号——用户列举的问题③「捏合后大小未延续」的持久化版本。
 *
 * ## 契约声明（测试席对开发席的接口约定）
 *
 * 本文件假设新增一个持久化抽象，命名与位置比照既有 [dev.agentmirror.app.pairing.PairingConfigStore]
 * 的做法（同包内、纯 JVM 可注入假 + SharedPreferences 生产实现）：
 *
 * ```
 * package dev.agentmirror.app.termview
 *
 * interface FontSizeStore {
 *     fun load(): Int?               // 已保存的字号（sp），未保存过则 null
 *     fun save(fontSizeSp: Int)
 * }
 *
 * class SharedPreferencesFontSizeStore(context: Context) : FontSizeStore
 * ```
 *
 * 若开发席选择了不同的类名/包名/方法签名，请与测试席同步改这个文件——不得为了让测试
 * 变绿而悄悄放宽断言语义（判据纪律）。类/方法不存在时下面的反射探测直接 fail（真红）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TermFontSizeSettingPersistenceTest {

    private val storeClassName = "dev.agentmirror.app.termview.SharedPreferencesFontSizeStore"

    private fun newStoreOrFail(context: Context): Any {
        val clazz = try {
            Class.forName(storeClassName)
        } catch (e: ClassNotFoundException) {
            throw AssertionError(
                "[契约缺失] $storeClassName 不存在——字号持久化存储尚未实现（用户问题③：" +
                    "捏合后大小未延续）。红测红在正确的地方：请开发席实现该类（或与测试席同步改名）。",
                e,
            )
        }
        val ctor = clazz.getConstructor(Context::class.java)
        ctor.isAccessible = true
        return ctor.newInstance(context)
    }

    private fun save(store: Any, sizeSp: Int) {
        val method = store.javaClass.methods.firstOrNull { it.name == "save" }
            ?: throw AssertionError("[契约缺失] FontSizeStore.save(Int) 不存在")
        method.isAccessible = true
        method.invoke(store, sizeSp)
    }

    private fun load(store: Any): Int? {
        val method = store.javaClass.methods.firstOrNull { it.name == "load" }
            ?: throw AssertionError("[契约缺失] FontSizeStore.load(): Int? 不存在")
        method.isAccessible = true
        return method.invoke(store) as Int?
    }

    @Test
    fun savedFontSize_survivesAcrossFreshStoreInstances_simulatingAppRestart() {
        val context = RuntimeEnvironment.getApplication()
        val firstInstance = newStoreOrFail(context)
        save(firstInstance, 18)

        // 重启 App 的等价物：不复用同一 Store 对象，重新构造一个（生产代码里 App 进程重启
        // 后同理是新对象），底层落盘（SharedPreferences）必须已经生效。
        val secondInstance = newStoreOrFail(context)
        assertEquals(
            "[③] 字号变更后重启 App（新 Store 实例）仍应读到所选字号 18sp",
            18, load(secondInstance),
        )
    }

    @Test
    fun neverSaved_loadReturnsNull_notASilentDefault() {
        // 用独立 SharedPreferences 文件名隔离（不同 Context 也共享同一磁盘 prefs 文件，
        // Robolectric 每个测试方法间 SharedPreferences 不清空的风险由本测试独立验证覆盖：
        // 未保存过时 load() 必须显式返回 null，而不是静默吐出某个硬编码默认字号——
        // 首次进入设置页时"当前字号"应由调用方另行给默认，不该塞进 Store 语义里）。
        val context = RuntimeEnvironment.getApplication()
        val store = newStoreOrFail(context)
        assertNull(
            "[③] 从未保存过字号时 load() 必须返回 null，不得静默返回某个硬编码默认值",
            load(store),
        )
    }
}
