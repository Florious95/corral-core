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

package dev.agentmirror.app.service

import androidx.compose.runtime.derivedStateOf
import dev.agentmirror.app.conn.BinaryFrame
import dev.agentmirror.app.conn.ConnectionConfig
import dev.agentmirror.app.conn.ConnectionManager
import dev.agentmirror.app.conn.ConnectionState
import dev.agentmirror.app.conn.FakeWebSocketTransport
import dev.agentmirror.app.conn.FrameError
import dev.agentmirror.app.conn.FramePayload
import dev.agentmirror.app.conn.TransportFactory
import dev.agentmirror.app.conn.WebSocketTransport
import dev.agentmirror.app.tsnet.ConnectionPath
import dev.agentmirror.app.tsnet.TsnetBackend
import dev.agentmirror.app.tsnet.TsnetProxy
import dev.agentmirror.app.tsnet.TsnetWire
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executor

/**
 * 重连链路 stale config 红测（fix-reconnect-stale-config P0 根因①/②，纯 JVM）。
 *
 * 真实现场（leader 四次真机实证）：用户先扫错地址 ws://10.20.55.20:9900/ws（不可达），
 * 重扫/手填对地址 ws://192.168.31.116:9900/ws 连接成功进列表；锁屏断连后无限「重连中」，
 * daemon 侧全程零连接到达——重连请求根本没发到正确地址。
 *
 * 根因①：ServiceWire.manager 单例幂等复用（fix-cold-start-reconnect 引入的双层守卫）——
 * manager 在旧地址时代创建后，setConfig 只更新 ServiceWire 层 config 字段，已存活 manager
 * 的拨号地址（ConnectionConfig 是构造期 val）永不更新 → 重连永远拨旧址。
 *
 * 本类三条测试：
 * - [configChange_rebuildsManager_dialsNewUrl]：红测。配置变更后 manager 必须重建并以新地址
 *   拨号（锁定 goal「改配置后重连拨新址」）。修前 manager 复用持旧地址 → 断言红。
 * - [sameConfig_keepsManager_avoidFlap]：保真。相同配置重复注入（如重复扫同码）不得重建——
 *   防过度重建闪断既有会话，manager 单例幂等语义保留。
 * - [networkAvailable_duringReconnect_retriesImmediately]：红测。RECONNECTING 中网络恢复
 *   （ConnectivityManager 回调 → ServiceWire.onNetworkAvailable 转发）必须打断退避立即重拨
 *   （根因② E2 缺口：NetworkCallback 从未注册）。修前 ServiceWire 无转发方法 → 编译红。
 */
class ServiceWireStaleConfigTest {

    /** 记录拨号地址的传输工厂：断言"拨向哪个地址"。拨号结果按脚本出队（空则默认成功）。 */
    private class RecordingTransportFactory : TransportFactory {
        val dialedUrls = mutableListOf<String>()
        val dialScripts = ArrayDeque<Boolean>()

        override fun create(url: String): WebSocketTransport {
            dialedUrls.add(url)
            val t = FakeWebSocketTransport()
            t.dialScript = listOf(dialScripts.removeFirstOrNull() ?: true)
            return t
        }
    }

    /** ServiceWire.manager 需要的空壳监听：本类断言不依赖回调扇出。 */
    private object NoopListener : ConnectionManager.Listener {
        override fun onStateChanged(state: ConnectionState) = Unit
        override fun onFrame(frame: FramePayload) = Unit
        override fun onBinary(frame: BinaryFrame) = Unit
        override fun onLocalDecodeError(code: FrameError, message: String) = Unit
        override fun onInputResult(reqId: Long, ok: Boolean, reason: String?) = Unit
        override fun onReconnect(attempt: Int, delayMs: Long) = Unit
    }

    @Before
    fun resetServiceWire() {
        // ServiceWire 是进程级单例：逐用例重置，避免上一个用例的 manager/配置残留。
        ServiceWire.uiConnector = null
        ServiceWire.uploadBaseUrl = null
        ServiceWire.transportFactory = NoopTransportFactory // 安全默认：不真联网
        ServiceWire.releaseManager()
        ServiceWire.resetConfigForTest()
    }

    @After
    fun teardown() {
        resetServiceWire()
    }

    // ---- 红测一：配置变更必须重建 manager 并以新地址拨号（根因①锁定）----

    @Test
    fun configChange_rebuildsManager_dialsNewUrl() {
        // 用户真实序列：先注入旧地址并建 manager（拨旧址）→ 再改对地址。
        val factory = RecordingTransportFactory()
        ServiceWire.transportFactory = factory

        ServiceWire.setConfig(ConnectionConfig("ws://10.20.55.20:9900/ws", "tok-old"))
        val first = ServiceWire.manager(NoopListener)
        first.start()
        assertEquals("首次配置拨旧址", listOf("ws://10.20.55.20:9900/ws"), factory.dialedUrls)

        // 重扫/手填成功：setConfig 注入新地址。
        ServiceWire.setConfig(ConnectionConfig("ws://192.168.31.116:9900/ws", "tok-new"))

        // manager() 再次取：修前单例复用（同一实例、拨号地址不变）→ 断言红；
        // 修后重建为新 manager 并拨新址。
        val second = ServiceWire.manager(NoopListener)
        second.start()

        assertEquals(
            "配置变更后 manager 必须重建（新实例，非复用旧单例）",
            false,
            first === second,
        )
        assertEquals(
            "配置变更后重连必须拨新地址（锁定测试：改配置后重连拨新址）",
            listOf("ws://10.20.55.20:9900/ws", "ws://192.168.31.116:9900/ws"),
            factory.dialedUrls,
        )
    }

    // ---- 保真：相同配置重复注入不得重建（防闪断）----

    @Test
    fun sameConfig_keepsManager_avoidFlap() {
        val factory = RecordingTransportFactory()
        ServiceWire.transportFactory = factory

        ServiceWire.setConfig(ConnectionConfig("ws://192.168.31.116:9900/ws", "tok"))
        val first = ServiceWire.manager(NoopListener)
        first.start()
        assertEquals(1, factory.dialedUrls.size)

        // 重复注入同一配置（重复扫同码 / 冷启动同一 storedConfig）：不得断连重建。
        ServiceWire.setConfig(ConnectionConfig("ws://192.168.31.116:9900/ws", "tok"))

        val second = ServiceWire.manager(NoopListener)
        assertSame("相同配置必须保持 manager 单例（幂等，防闪断既有会话）", first, second)
        // 不得二次拨号。
        assertEquals("相同配置不得触发第二次拨号", 1, factory.dialedUrls.size)
    }

    // ---- 018 标准5：重连中必须可见当前拨号地址 + 已试次数（失败可见）----

    @Test
    fun dialUrl_exposesManagerDialTarget() {
        // 重连中 UI/通知展示"当前拨号地址"的数据源是 ConnectionManager.dialUrl()——
        // 它必须返回管理器**实际拨向**的地址（构造期配置），而非 ServiceWire 层配置字段。
        // stale-config 缺陷时期：用户改对了 ServiceWire 配置，但 manager 仍持旧地址拨号，
        // dialUrl() 能直接暴露"重连正拨旧址"（改配置后仍拨旧地址）。
        val factory = RecordingTransportFactory()
        ServiceWire.transportFactory = factory
        ServiceWire.setConfig(ConnectionConfig("ws://192.168.31.116:9900/ws", "tok"))

        val m = ServiceWire.manager(NoopListener)
        m.start()

        assertEquals(
            "dialUrl 必须返回管理器实际拨向地址（重连中展示，018 标准5 失败可见）",
            "ws://192.168.31.116:9900/ws",
            m.dialUrl(),
        )
    }

    @Test
    fun okHttpFactory_recordsLanOrTailnetFromActualSocketChoice() {
        TsnetWire.resetForTest()
        try {
            // 节点未 Up：即使目标是普通 LAN，实际选择也明确记录为 LAN。
            OkHttpTransportFactory.create("ws://192.168.31.116:9900/ws")
            assertEquals(ConnectionPath.LAN, ServiceWire.connectionPath())

            // 假节点同步 Up 后，100.64/10 目标确实注入 SOCKS socketFactory，才记录 tailnet。
            TsnetWire.environment = TsnetWire.Environment("/tmp/ts-test", "agentmirror-test")
            TsnetWire.executorForTest = Executor { it.run() }
            TsnetWire.backendFactory = {
                object : TsnetBackend {
                    override fun start(stateDir: String, hostname: String, authKey: String) =
                        TsnetProxy("127.0.0.1", 1080, "fake-cred")

                    override fun close() = Unit
                }
            }
            TsnetWire.ensureStarted("fake-auth-key")
            OkHttpTransportFactory.create("ws://100.101.2.3:9900/ws")
            assertEquals(ConnectionPath.TAILNET, ServiceWire.connectionPath())
        } finally {
            TsnetWire.resetForTest()
        }
    }

    @Test
    fun pairingProbeDoesNotOverwritePersistentConnectionPath() {
        ServiceWire.transportFactory = OkHttpTransportFactory
        ServiceWire.recordConnectionPath(ConnectionPath.TAILNET)

        // 只创建、不 start：验证配对探针选路不会把仍在 READY 的旧持久连接徽标改成 LAN。
        ServiceWire.pairingTransportFactory().create("ws://192.168.31.116:9900/ws")

        assertEquals(ConnectionPath.TAILNET, ServiceWire.connectionPath())
    }

    @Test
    fun connectionPath_isSnapshotObservableWhenRetrySwitchesToTailnet() {
        // AgentMirrorApp reads connectionPath() while composing the workspace/session header.
        // A cold-start tailnet URL first records LAN while tsnet is Starting, then records
        // TAILNET on the successful retry; the observable read must invalidate between them.
        val observed = derivedStateOf { ServiceWire.connectionPath() }

        ServiceWire.recordConnectionPath(ConnectionPath.LAN)
        assertEquals(ConnectionPath.LAN, observed.value)

        ServiceWire.recordConnectionPath(ConnectionPath.TAILNET)
        assertEquals(ConnectionPath.TAILNET, observed.value)
    }

    // ---- 红测二：RECONNECTING 中网络恢复必须立即重拨（根因② E2 缺口锁定）----

    @Test
    fun networkAvailable_duringReconnect_retriesImmediately() {
        val factory = RecordingTransportFactory()
        // 拨号脚本：第一条失败（进入 RECONNECTING），第二条成功。
        factory.dialScripts.addLast(false)
        factory.dialScripts.addLast(true)
        ServiceWire.transportFactory = factory
        ServiceWire.setConfig(ConnectionConfig("ws://192.168.31.116:9900/ws", "tok"))

        val m = ServiceWire.manager(NoopListener)
        m.start()
        // 首条拨号失败 ⇒ RECONNECTING（退避等待中），仅拨过一次。
        assertEquals(ConnectionState.RECONNECTING, m.state())
        assertEquals(1, factory.dialedUrls.size)

        // 网络恢复（Android ConnectivityManager 回调桥接 ServiceWire.onNetworkAvailable）：
        // 必须打断退避立即重拨（修前 ServiceWire 无此转发方法 → 编译红）。
        ServiceWire.onNetworkAvailable()

        assertEquals(
            "网络恢复必须打断退避立即重拨（不等退避到点）",
            ConnectionState.AUTHENTICATING,
            m.state(),
        )
        assertEquals("网络恢复后立即二次拨号", 2, factory.dialedUrls.size)
    }
}
