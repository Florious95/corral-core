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

package dev.agentmirror.app

import android.content.Context
import dev.agentmirror.app.conn.BinaryFrame
import dev.agentmirror.app.conn.ConnectionManager
import dev.agentmirror.app.conn.ConnectionState
import dev.agentmirror.app.conn.FakeWebSocketTransport
import dev.agentmirror.app.conn.FrameError
import dev.agentmirror.app.conn.FramePayload
import dev.agentmirror.app.conn.TransportFactory
import dev.agentmirror.app.conn.WebSocketTransport
import dev.agentmirror.app.pairing.PairingConfig
import dev.agentmirror.app.pairing.startPersistentConnection
import dev.agentmirror.app.service.NoopTransportFactory
import dev.agentmirror.app.service.ServiceWire
import dev.agentmirror.app.tsnet.TsnetBackend
import dev.agentmirror.app.tsnet.TsnetProxy
import dev.agentmirror.app.tsnet.TsnetState
import dev.agentmirror.app.tsnet.TsnetWire
import dev.agentmirror.app.workspace.ConnectionUi
import dev.agentmirror.app.workspace.WorkspaceViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

/**
 * 冷启动自动重连 Robolectric 测试（fix-cold-start-reconnect P0，验收 `--tests "*ColdStart*"`）。
 *
 * 红测先行：修复前 ServiceWire.manager(...).start() 全仓唯一调用点在 PairingRoute.onPaired
 * （配对成功一刻），冷启动（有配对配置，showPairing=false 直进工作区）无任何路径启动连接 →
 * 顶栏永远「连接中…」、列表空白。本案三条红测据此设计：
 * - 有配置冷启动：断言连接被创建并拨号 + 上传基地址注入（onPaired 序列同构）；
 * - 有配置冷启动 + 工作区接线：断言连接推进 READY 时接线 VM 收到状态推进（列表数据源就绪）；
 * - 双 start（冷启动序列 + 配对成功序列先后触发）：断言只建一条连接（幂等守卫锁定，
 *   D10 多订阅替换语义的坑）；对照：无配置冷启动不得启动连接、停配对页（首启语义）。
 *
 * 断言面沿用 MainActivityNavTest 的 Robolectric 直接断言 Activity 状态（不依赖 Compose
 * 渲染断言）。关键差异：本类用 [Robolectric.buildActivity(...).create()] 而非 `.setup()`。
 * 实证：`.setup()`（attach+resume+visible）会让有配置的冷启动渲染工作区顶栏 CONNECTING 的
 * CircularProgressIndicator——Material3 的无限动画在 Robolectric 下让 Recomposer 无限请求
 * 帧，GC 抖动直至 OOM 杀掉 Recomposer 线程（本类早前 156s/测试实证），测试线程才得以继续。
 * `.create()` 只跑 [MainActivity.onCreate]——恰是 force-stop→重开（冷启动）的真实路径，且
 * 未 attach 窗口故 Compose 不组合、不触发无限动画，确定性且快。本类断言的是 onCreate 侧
 * 连接启动副作用 + 工作区 VM 收到连接推进（ServiceWire.uiConnector 扇出），都不需要 Compose
 * 渲染，故 `.create()` 足够且更忠实。工作区接线（uiConnector）在测试里显式挂载 VM——与
 * WorkspaceWiringTest 的实证一致，它正是 Compose 工作区分支 DisposableEffect 的等价动作。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ColdStartReconnectTest {

    @Before
    fun resetServiceWireAndPrefs() {
        // 清空配对配置残留：保证 navState 初值（showPairing）与真实首启一致（同 MainActivityNavTest）。
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("pairing_config", Context.MODE_PRIVATE)
            .edit().clear().commit()
        // ServiceWire 是进程级单例：逐用例重置，避免上一个用例的 manager/配置/接线残留。
        // （Robolectric 同用例类内共享静态，跨方法不自动隔离。）
        ServiceWire.uiConnector = null
        ServiceWire.uploadBaseUrl = null
        ServiceWire.transportFactory = NoopTransportFactory // 安全默认：不真联网（Noop 拨号即败）
        ServiceWire.releaseManager() // stop + 置空 manager
        ServiceWire.resetConfigForTest() // 清 config：防泄漏污染后续用例（SessionRoute 据此建 VM）
        TsnetWire.resetForTest()
    }

    @After
    fun teardown() {
        // 反向销毁本用例创建的 Activity + 复位 ServiceWire 单例：进程级全局状态不得跨用例类
        // 泄漏（实证：漏掉 config 会让后续 WorkspaceWiringTest.leavingWorkspace 的离屏复位断言
        // 被 SessionRoute 挂上的 SessionViewModel 污染而红——fix-cold-start-reconnect 全量跑实证）。
        createdActivities.forEach { it.destroy() } // Robolectric 生命周期控制器销毁
        createdActivities.clear()
        ServiceWire.uiConnector = null
        ServiceWire.uploadBaseUrl = null
        ServiceWire.transportFactory = NoopTransportFactory
        ServiceWire.releaseManager()
        ServiceWire.resetConfigForTest()
        TsnetWire.resetForTest()
    }

    /** 本用例创建的 Activity 控制器（@After 统一 destroy，防止跨用例类泄漏）。 */
    private val createdActivities = mutableListOf<org.robolectric.android.controller.ActivityController<MainActivity>>()

    /** 构建 MainActivity 并登记（@After 统一 destroy）。 */
    private fun buildMainActivity(): MainActivity {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        createdActivities.add(controller)
        return controller.create().get()
    }

    /** 记录型传输工厂：断言"连接被创建并拨号"的次数（与 ConnManagerTest 同款夹具）。 */
    private class RecordingTransportFactory : TransportFactory {
        val created = mutableListOf<FakeWebSocketTransport>()
        override fun create(url: String): WebSocketTransport {
            val t = FakeWebSocketTransport()
            created.add(t)
            return t
        }
    }

    /** 手动执行器：让测试把 tsnet 保持在 Starting，点名推进到 Up。 */
    private class ManualExecutor : Executor {
        private val queued = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            queued.addLast(command)
        }

        fun runAll() {
            while (queued.isNotEmpty()) queued.removeFirst().run()
        }
    }

    /** 预置配对配置（键与 SharedPreferencesPairingConfigStore 对齐）。 */
    private fun seedConfig(url: String, token: String) {
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("pairing_config", Context.MODE_PRIVATE)
            .edit().putString("url", url).putString("token", token).commit()
    }

    // ---- 红测一：有配置冷启动必须启动连接（缺陷：无任何路径 start）----

    @Test
    fun coldStart_withExistingConfig_startsPersistentConnection() {
        // 红测：修复前冷启动不 start（唯一 start 在 onPaired），factory.created 恒空 → 断言失败。
        seedConfig("ws://10.0.2.2:9900/ws", "tok-cold-1")
        val factory = RecordingTransportFactory()
        ServiceWire.transportFactory = factory

        val activity = buildMainActivity()

        assertEquals("冷启动已有配对配置必须创建并启动连接", 1, factory.created.size)
        val transport = factory.created.single()
        assertEquals("连接必须发起拨号（transport.start 被调用）", 1, transport.dialIndex)
        // 序列与 onPaired 同构：上传基地址必须一并注入（勿只抄 start()）。
        assertEquals("http://10.0.2.2:9900", ServiceWire.uploadBaseUrl)
        // 有配置直进工作区（不落配对页）。
        assertFalse("有配置冷启动不得停配对页", activity.navState.showPairing)
    }

    @Test
    fun coldStart_tailnetWaitsForEmbeddedNodeBeforeFirstDial() {
        val executor = ManualExecutor()
        val statesAtDial = mutableListOf<TsnetState>()
        val created = mutableListOf<FakeWebSocketTransport>()
        TsnetWire.environment = TsnetWire.Environment("/tmp/ts-cold-start", "agentmirror-test")
        TsnetWire.executorForTest = executor
        TsnetWire.backendFactory = {
            object : TsnetBackend {
                override fun start(stateDir: String, hostname: String, authKey: String) =
                    TsnetProxy("127.0.0.1", 1080, "fake-cred")

                override fun close() = Unit
            }
        }
        ServiceWire.transportFactory = TransportFactory {
            statesAtDial += TsnetWire.state
            FakeWebSocketTransport().also(created::add)
        }

        startPersistentConnection(
            PairingConfig("ws://100.101.2.3:9900/ws", "tok-cold-tailnet", "fake-auth-key"),
        )

        assertTrue("tailnet 冷启动在 tsnet Up 前不得先直拨", created.isEmpty())
        executor.runAll()
        assertEquals(1, created.size)
        assertTrue("首拨必须发生在 tsnet Up 后，实际=$statesAtDial", statesAtDial.single() is TsnetState.Up)
    }

    @Test
    fun newerLanConfigSupersedesPendingTailnetStart() {
        val executor = ManualExecutor()
        TsnetWire.environment = TsnetWire.Environment("/tmp/ts-cold-start", "agentmirror-test")
        TsnetWire.executorForTest = executor
        TsnetWire.backendFactory = {
            object : TsnetBackend {
                override fun start(stateDir: String, hostname: String, authKey: String) =
                    TsnetProxy("127.0.0.1", 1080, "fake-cred")

                override fun close() = Unit
            }
        }
        val dialedUrls = mutableListOf<String>()
        ServiceWire.transportFactory = TransportFactory { url ->
            dialedUrls += url
            FakeWebSocketTransport()
        }
        val tailnet = PairingConfig("ws://100.101.2.3:9900/ws", "tok-old", "fake-auth-key")
        val lan = PairingConfig("ws://192.168.1.8:9900/ws", "tok-new")

        startPersistentConnection(tailnet)
        startPersistentConnection(lan)
        assertEquals(listOf(lan.url), dialedUrls)

        executor.runAll()

        assertEquals("迟到的 tsnet Up 不得恢复已被新 LAN 配置取代的拨号", listOf(lan.url), dialedUrls)
        assertEquals("http://192.168.1.8:9900", ServiceWire.uploadBaseUrl)
    }

    // ---- 红测二：连接推进时工作区接线收到状态推进（列表数据源就绪）----

    @Test
    fun coldStart_connectionAdvances_wiredWorkspaceReceivesReady() {
        // 红测：修复前无连接启动，transport 空 → 首个断言失败（接线无从收到推进）。
        seedConfig("ws://10.0.2.2:9900/ws", "tok-cold-2")
        val factory = RecordingTransportFactory()
        ServiceWire.transportFactory = factory

        val activity = buildMainActivity()
        assertEquals(1, factory.created.size)

        // 工作区 VM 由 MainActivity 持有（fix-workspace-wiring 提升）；Compose 工作区分支的
        // DisposableEffect 把它挂到 ServiceWire.uiConnector——此处显式接线（等价动作），
        // 使断言不依赖 Robolectric 下 Compose 重组帧是否推进（WorkspaceWiringTest 实测不稳）。
        val vm: WorkspaceViewModel = activity.workspaceViewModel
        ServiceWire.uiConnector = vm

        // 驱动连接推进到 READY：fake 传输已同步 onOpen（auth 已发，state=AUTHENTICATING），
        // 投递 auth_ack(ok) 即完成握手（与 ConnManagerTest.ready 同款驱动）。
        factory.created.single().deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":true}}""")

        assertEquals(
            "工作区接线必须收到连接状态推进（冷启动重连后列表数据源就绪）",
            ConnectionUi.READY,
            vm.uiState.value.connection,
        )
    }

    // ---- 红测三：晚挂载工作区 VM 必须补播全量 listing（冷启动数据缺失竞态）----
    // 冷启动连接在 onCreate 即启动（早于 Compose 挂载工作区 VM），连接可能在 VM 接线前就
    // 已 READY + 收到全量 listing。uiConnector setter 只补播连接态不补播数据 → VM 错过 listing，
    // 列表只渲染晚到的零散 delta（层2 实证：force-stop 重开只显示一个真实舰队工作区，隔离会话
    // 缺失）。本条锁定该竞态：先 READY+listing，再挂 VM，断言 VM 收到全量列表。

    @Test
    fun lateWiring_workspaceReceivesListingReplay() {
        // 红测：修复前 setter 只补播连接态，不补播 listing → 断言失败（列表空白）。
        seedConfig("ws://10.0.2.2:9900/ws", "tok-cold-3b")
        val factory = RecordingTransportFactory()
        ServiceWire.transportFactory = factory

        val activity = buildMainActivity()
        assertEquals(1, factory.created.size)
        val transport = factory.created.single()

        // 连接推进到 READY + 收到全量 listing（模拟 listing 先于 VM 接线到达）：
        transport.deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":true}}""")
        transport.deliverText(
            """{"v":1,"type":"listing","payload":{"req_id":1,"seq":42,"workspaces":[
                {"cwd":"/proj/a","session_count":1,"aggregate_state":"working","sessions":[
                    {"ref":"s1","name":"claude","cwd":"/proj/a","state":"working","rows":24,"cols":80}]}]}}""",
        )

        // 此时（VM 未接线）listing 已错过；随后挂载工作区 VM（等价 Compose DisposableEffect）。
        val vm: WorkspaceViewModel = activity.workspaceViewModel
        ServiceWire.uiConnector = vm

        assertEquals(
            "晚挂载的工作区 VM 必须补播全量 listing（冷启动重连后列表恢复）",
            ConnectionUi.READY,
            vm.uiState.value.connection,
        )
        assertEquals("晚挂载 VM 必须收到全量 listing 数据", 1, vm.uiState.value.workspaces.size)
        assertEquals("/proj/a", vm.uiState.value.workspaces.single().cwd)
        assertEquals(1, vm.uiState.value.workspaces.single().sessions.size)
    }

    // ---- 红测四：双 start 幂等守卫（D10 多订阅替换语义的坑）----

    @Test
    fun doubleStart_coldStartThenRepair_createsOnlyOneConnection() {
        // 红测：修复前冷启动不建连接，首断言失败；修复后冷启动建一条，配对成功序列再触发
        // 必须复用既有 manager（非 STOPPED 不重复拨号）——只一条连接。
        seedConfig("ws://10.0.2.2:9900/ws", "tok-cold-3")
        val factory = RecordingTransportFactory()
        ServiceWire.transportFactory = factory

        // 冷启动：start #1（本次修复的入口）。
        buildMainActivity()
        assertEquals(1, factory.created.size)

        // 模拟配对成功切屏后 onPaired 的 start 再触发（同构序列）：manager() 复用既有单例
        // + ConnectionManager.start() 非 STOPPED 幂等 → 不得二次拨号（防双连接）。
        ServiceWire.manager(object : ConnectionManager.Listener {
            override fun onStateChanged(state: ConnectionState) = Unit
            override fun onFrame(frame: FramePayload) = Unit
            override fun onBinary(frame: BinaryFrame) = Unit
            override fun onLocalDecodeError(code: FrameError, message: String) = Unit
            override fun onInputResult(reqId: Long, ok: Boolean, reason: String?) = Unit
            override fun onReconnect(attempt: Int, delayMs: Long) = Unit
        }).start()

        assertEquals("双 start 场景必须只建一条连接（幂等守卫锁定）", 1, factory.created.size)
    }

    // ---- 对照：无配置冷启动不得启动连接、停配对页（首启语义，修复前后都应保持）----

    @Test
    fun coldStart_withoutConfig_doesNotStart_andStaysOnPairingPage() {
        val factory = RecordingTransportFactory()
        ServiceWire.transportFactory = factory

        val activity = buildMainActivity()

        assertTrue("无配置冷启动必须停配对页（首启语义）", activity.navState.showPairing)
        assertTrue("无配置不得启动任何连接", factory.created.isEmpty())
        assertNull("无配置不得注入上传基地址", ServiceWire.uploadBaseUrl)
    }
}
