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

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import dev.agentmirror.app.MainActivity
import dev.agentmirror.app.conn.ConnectionConfig
import dev.agentmirror.app.conn.ConnectionManager
import dev.agentmirror.app.conn.ConnectionState
import dev.agentmirror.app.conn.FakeWebSocketTransport
import dev.agentmirror.app.conn.TransportFactory
import dev.agentmirror.app.conn.WebSocketTransport
import dev.agentmirror.app.session.createSessionViewModel
import dev.agentmirror.app.workspace.WorkspaceViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 前台服务接线红测（feat-fg-service-wiring 验收 `--tests "*ForegroundService*"`）。
 *
 * 缺陷现场（docs/round-findings-20260811.md P-1）：MirrorForegroundService 在 manifest
 * 声明了，但全仓库没有任何 startService/startForegroundService/stopService 调用点——
 * 它从未被启动过（死件家族第六例）。真实接线是 ConnectionManager 由 startPersistentConnection
 * 创建，时钟泵由在屏组合的 LaunchedEffect 驱动。
 *
 * 三条红测（顺序照角色文件）：
 * 1. 服务启动/停止状态机——有配置冷启动必须启动前台服务；stopService 必须停服务并释放连接；
 * 2. 被杀后冷启动恢复（004 架构底线守门）——杀服务 + 进程回收后，冷启动 → 重连 → 首屏
 *    快照仍完整恢复，且恢复后状态与被杀前一致（状态唯一来源是 SharedPreferences，不在服务里）；
 * 3. 连接归属——连接由服务承接（服务持有进程级单例 manager），在屏组合不再各自持有：
 *    会话页进入必须复用同一 manager（不新建连接），且时钟泵由服务驱动（泵单拍推进重连重拨）。
 *
 * 红测先行：当前代码无服务启动调用点 → 红测一/二在"服务必须被启动"断言红；红测三在
 * "服务泵必须驱动重连"断言红（pumpOnce/managerOrNull/serviceListener 尚未存在 → 编译红）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ForegroundServiceWiringTest {

    /** 记录拨号地址/次数的传输工厂（与 ColdStartReconnectTest 同款夹具）。 */
    private class RecordingTransportFactory : TransportFactory {
        val created = mutableListOf<FakeWebSocketTransport>()
        override fun create(url: String): WebSocketTransport {
            val t = FakeWebSocketTransport()
            created.add(t)
            return t
        }
    }

    /** 脚本化拨号结果工厂：供"服务泵驱动重连重拨"的确定性断言。 */
    private class ScriptedTransportFactory : TransportFactory {
        val created = mutableListOf<FakeWebSocketTransport>()
        val dialScripts = ArrayDeque<Boolean>()
        override fun create(url: String): WebSocketTransport {
            val t = FakeWebSocketTransport()
            t.dialScript = listOf(dialScripts.removeFirstOrNull() ?: true)
            created.add(t)
            return t
        }
    }

    /** ServiceWire.manager 需要的空壳监听（服务经 serviceListener 槽收事件，不经此参数）。 */
    private object NoopListener : ConnectionManager.Listener {
        override fun onStateChanged(state: ConnectionState) = Unit
        override fun onFrame(frame: dev.agentmirror.app.conn.FramePayload) = Unit
        override fun onBinary(frame: dev.agentmirror.app.conn.BinaryFrame) = Unit
        override fun onLocalDecodeError(code: dev.agentmirror.app.conn.FrameError, message: String) = Unit
        override fun onInputResult(reqId: Long, ok: Boolean, reason: String?) = Unit
        override fun onReconnect(attempt: Int, delayMs: Long) = Unit
    }

    private lateinit var factory: RecordingTransportFactory
    private val createdActivities = mutableListOf<org.robolectric.android.controller.ActivityController<MainActivity>>()

    @Before
    fun resetServiceWireAndPrefs() {
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("pairing_config", Context.MODE_PRIVATE)
            .edit().clear().commit()
        factory = RecordingTransportFactory()
        ServiceWire.uiConnector = null
        ServiceWire.uploadBaseUrl = null
        ServiceWire.serviceListener = null
        ServiceWire.transportFactory = factory
        ServiceWire.releaseManager()
        ServiceWire.resetConfigForTest()
    }

    @After
    fun teardown() {
        // 反向销毁 Activity + 停掉可能残留的前台服务（stopService → Robolectric 销毁服务、
        // onDestroy 停泵并释放连接）。进程级单例状态不得跨用例泄漏（实证纪律，同 ColdStartReconnectTest）。
        createdActivities.forEach { it.destroy() }
        createdActivities.clear()
        try {
            MirrorForegroundService.stop(RuntimeEnvironment.getApplication())
        } catch (_: Exception) {
            // 服务可能未被启动过：stopService 是幂等 no-op，这里防御性兜住。
        }
        ServiceWire.uiConnector = null
        ServiceWire.uploadBaseUrl = null
        ServiceWire.serviceListener = null
        ServiceWire.transportFactory = NoopTransportFactory
        ServiceWire.releaseManager()
        ServiceWire.resetConfigForTest()
    }

    private fun seedConfig(url: String, token: String) {
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("pairing_config", Context.MODE_PRIVATE)
            .edit().putString("url", url).putString("token", token).commit()
    }

    /** 构建 MainActivity（.create() 只跑 onCreate：force-stop→重开冷启动的真实路径，见 ColdStartReconnectTest KDoc）。 */
    private fun buildMainActivity(): MainActivity {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        createdActivities.add(controller)
        return controller.create().get()
    }

    /** 驱动连接 READY + 全量 listing（工作区列表数据源就绪，与 ColdStartReconnectTest 同款驱动）。 */
    private fun driveReadyWithListing(transport: FakeWebSocketTransport, seq: Long) {
        transport.deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":true}}""")
        transport.deliverText(
            """{"v":1,"type":"listing","payload":{"req_id":1,"seq":$seq,"workspaces":[
                {"cwd":"/proj/a","session_count":1,"aggregate_state":"working","sessions":[
                    {"ref":"s1","name":"claude","cwd":"/proj/a","state":"working","rows":24,"cols":80}]}]}}""",
        )
    }

    private val nm: NotificationManager
        get() = RuntimeEnvironment.getApplication()
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // ---- 红测一：服务启动/停止状态机（启动/停止条件可断言）----

    @Test
    fun coldStartWithConfig_startsForegroundService_andConnection() {
        // 红测：修复前全仓库无 startForegroundService 调用点，服务从未被启动 →
        // ShadowApplication 的 startedServices 恒空 → 首断言失败。
        seedConfig("ws://10.0.2.2:9900/ws", "tok-wiring-1")
        buildMainActivity()

        val started = shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService()
        assertEquals(
            "有配置冷启动必须启动前台服务（startForegroundService 真实调用点）",
            MirrorForegroundService::class.java.name,
            started?.component?.className,
        )
        // 连接由服务路径承接：冷启动同步建立连接并拨号（004 冷启动 1s 恢复的正确性核心，
        // 不依赖服务启动的时序）。
        assertEquals("冷启动必须创建并拨号连接", 1, factory.created.size)
        assertEquals("连接必须发起拨号", 1, factory.created.single().dialIndex)
    }

    @Test
    fun coldStartWithoutConfig_doesNotStartService() {
        // 对照：无配置冷启动（首启配对页）不得启动前台服务——服务生命周期绑定"已配对"。
        buildMainActivity()

        val started = shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService()
        assertNull("无配置冷启动不得启动前台服务", started)
        assertTrue("无配置不得启动任何连接", factory.created.isEmpty())
    }

    @Test
    fun serviceLifecycle_startAndStop_drivesManagerAndNotification() {
        // 直接驱动服务状态机：startCommand（启动）→ destroy（停止），断言连接与通知随之启停。
        ServiceWire.setConfig(ConnectionConfig("ws://10.0.2.2:9900/ws", "tok-wiring-2"))
        val controller = Robolectric.buildService(MirrorForegroundService::class.java)
        controller.create().startCommand(0, 1)

        // 启动：服务持有连接管理器（连接由服务承接，红测三的归属前提）+ 常驻通知发布。
        val manager = ServiceWire.managerOrNull()
        assertNotNull("服务启动后必须持有连接管理器（连接归属服务）", manager)
        assertEquals("连接必须已拨号", 1, factory.created.size)
        assertNotNull(
            "服务启动必须发布常驻通知（ID_PERSISTENT）",
            shadowOf(nm).getNotification(NotificationHelper.ID_PERSISTENT),
        )

        // 停止：释放连接 + 解绑服务监听（onDestroy 幂等清理）。
        controller.destroy()
        assertNull("服务停止必须释放连接管理器", ServiceWire.managerOrNull())
        assertNull("服务停止必须解绑 serviceListener", ServiceWire.serviceListener)
    }

    // ---- 红测二：被杀后冷启动恢复（004 架构底线守门测试）----

    @Test
    fun serviceKilled_coldStartRestores_sameWorkspaceState() {
        // 红测（004 底线）：服务被杀 + 进程回收后，冷启动 → 重连 → 首屏快照必须完整恢复，
        // 且恢复后状态与被杀前一致。状态唯一来源是 SharedPreferences（客户端无状态），
        // 服务只是体验增强——若把状态搬进服务（配置/连接只存服务内），此测试必红。
        seedConfig("ws://10.0.2.2:9900/ws", "tok-wiring-3")

        // 首启：冷启动建立连接 + 启动服务，推进 READY + listing → 工作区渲染出数据。
        val a1 = buildMainActivity()
        assertEquals("首启必须创建连接", 1, factory.created.size)
        val vm1: WorkspaceViewModel = a1.workspaceViewModel
        ServiceWire.uiConnector = vm1
        driveReadyWithListing(factory.created.single(), seq = 7)
        assertEquals("被杀前工作区必须已渲染数据", 1, vm1.uiState.value.workspaces.size)
        assertEquals("/proj/a", vm1.uiState.value.workspaces.single().cwd)

        // 杀服务 + 进程回收：停掉前台服务（onDestroy 释放连接）+ 销毁 Activity + ServiceWire
        // 状态归零——等价进程死亡（连接/配置不驻留服务，唯一来源是 SharedPreferences）。
        MirrorForegroundService.stop(RuntimeEnvironment.getApplication())
        createdActivities.forEach { it.destroy() }
        createdActivities.clear()
        ServiceWire.releaseManager()
        ServiceWire.resetConfigForTest()
        ServiceWire.uiConnector = null
        ServiceWire.serviceListener = null

        // 冷启动恢复：新进程从 prefs 重读配置 → 重连 → READY + 全量 listing → 首屏快照恢复原状。
        val a2 = buildMainActivity()
        assertEquals("服务被杀后冷启动必须重建连接", 2, factory.created.size)
        val vm2: WorkspaceViewModel = a2.workspaceViewModel
        ServiceWire.uiConnector = vm2
        driveReadyWithListing(factory.created[1], seq = 7)
        assertEquals("恢复后首屏必须回到被杀前的列表（004 无状态免疫）", 1, vm2.uiState.value.workspaces.size)
        assertEquals("/proj/a", vm2.uiState.value.workspaces.single().cwd)
        assertEquals(1, vm2.uiState.value.workspaces.single().sessionCount)
    }

    // ---- 红测三：连接归属断言（连接由服务承接，不在屏组合各自持有）----

    @Test
    fun sessionEntry_reusesServiceManager_noSecondDial() {
        // 红测：若接线做错（在屏组合各自新建连接），进入会话会二次拨号 → 断言红。
        seedConfig("ws://10.0.2.2:9900/ws", "tok-wiring-4")
        buildMainActivity()
        assertEquals("冷启动后服务持有唯一连接", 1, factory.created.size)

        // 进入会话：createSessionViewModel 必须复用服务持有的同一 manager（共享单例），
        // 不得新建连接（归属断言：连接由服务承接，会话页只是订阅者）。
        val vm = createSessionViewModel("s1")
        assertNotNull("连接配置已注入时必须能构造会话 VM", vm)
        val shared = ServiceWire.managerOrNull()
        assertNotNull("服务必须持有连接管理器", shared)
        assertEquals("进入会话不得新建连接（连接由服务承接）", 1, factory.created.size)
    }

    @Test
    fun servicePump_drivesReconnectBackoff() {
        // 红测：时钟泵归属服务。断连退避到点必须由服务泵单拍推进触发重拨——
        // 修前泵由在屏组合 LaunchedEffect 驱动，服务无 pumpOnce/managerOrNull → 编译红。
        val scripted = ScriptedTransportFactory()
        scripted.dialScripts.addLast(false) // 首次拨号失败 → RECONNECTING（退避调度）
        scripted.dialScripts.addLast(true)  // 泵推进重拨成功
        ServiceWire.transportFactory = scripted
        ServiceWire.setConfig(ConnectionConfig("ws://10.0.2.2:9900/ws", "tok-wiring-5"))

        val controller = Robolectric.buildService(MirrorForegroundService::class.java)
        val svc = controller.create().startCommand(0, 1).get()

        val manager = ServiceWire.managerOrNull()
        assertNotNull(manager)
        assertEquals("首次拨号失败必须进入退避重连", ConnectionState.RECONNECTING, manager!!.state())
        assertEquals(1, scripted.created.size)

        // 服务时钟泵单拍推进（越过退避截止）→ 必须重拨（归属断言：泵由服务驱动）。
        svc.pumpOnce(System.currentTimeMillis() + 60_000)
        assertEquals("服务时钟泵必须驱动重连重拨", 2, scripted.created.size)
        assertEquals("重拨成功进入认证", ConnectionState.AUTHENTICATING, manager.state())

        controller.destroy()
    }
}
