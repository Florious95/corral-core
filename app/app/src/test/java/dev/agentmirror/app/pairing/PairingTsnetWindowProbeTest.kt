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

import dev.agentmirror.app.conn.ConnectionConfig
import dev.agentmirror.app.conn.ConnectionManager
import dev.agentmirror.app.conn.FakeClock
import dev.agentmirror.app.conn.FakeWebSocketTransport
import dev.agentmirror.app.conn.ReconnectPolicy
import dev.agentmirror.app.conn.TransportFactory
import dev.agentmirror.app.tsnet.TsnetProxy
import dev.agentmirror.app.tsnet.TsnetState
import dev.agentmirror.app.tsnet.TsnetWire
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executor

/**
 * 红测：缺陷⑤ 配对页锁死 —— tsnet 起网窗口内首次拨号失败被 RECONNECTING 误判为不可达。
 *
 * 用户真机日志（2026-08-14）：
 *   15:11:31.059 [tsnet] → Up(port=37629)      ← tsnet 起网耗时 ~5.6s
 *   15:11:32.377 SocketTimeoutException（蜂窝直连失败）← 首次拨号失败 → RECONNECTING
 *   15:11:36.155 [socks] dial fail              ← 继续失败
 *   15:11:36.534 [socks] dial ok host=100.75.207.88 port=9900  ← NO_PROXY 生效，打到真服务器
 *   15:11:36.621 [ws] AUTHENTICATING → READY     ← 连接成功！但配对页已在 Failed 锁死
 *
 * 根因：PairingViewModel.L300 RECONNECTING 分支无条件 `advanceAttempt(UNREACHABLE)`——
 * 没有区分「tsnet 刚 Up、netstack 尚未就绪的窗口内失败」与「真正不可达」。tsnet 起网
 * 5~6s 期间首次拨号失败 → 立即 Failed；随后 READY 到达时 `pairingStatus is Pairing` 已
 * 不成立，L287 Success 分支被挡 → 永不解锁（红框报失败、进不去）。
 *
 * 命中条件（当前 HEAD）：tsnet Up 后首拨失败 → 进 RECONNECTING → 状态 Failed（红）。
 * 修复后：RECONNECTING 应等待（不立即判死）→ 后续 READY 解锁为 Success（绿）。
 */
class PairingTsnetWindowProbeTest {

    @Before
    fun setUp() {
        // 独立卫生项（非红测① 主根因）：复位进程级单例，防与 Robolectric 测试类同跑时串扰
        // （TsnetWire 是进程级全局，ColdStartReconnectTest 等会设 environment/backendFactory/
        // executorForTest）。红测① 本身不设这些，但跨类跑时可能被前序测试污染。
        TsnetWire.resetForTest()
    }

    @After
    fun tearDown() {
        // 复位进程级单例（防与 Robolectric 测试类同跑时串扰）。
        TsnetWire.resetForTest()
    }

    /** 记录型假存储（与 PairingViewModelTest 同款）。 */
    private class FakeStore : PairingConfigStore {
        var saved: PairingConfig? = null
        override fun load(): PairingConfig? = saved
        override fun save(config: PairingConfig) {
            saved = config
        }

        override fun clear() {
            saved = null
        }
    }

    private class Harness {
        val store = FakeStore()
        val clock = FakeClock()
        val transports = mutableListOf<FakeWebSocketTransport>()
        /** 每次拨号脚本（按 transport 创建顺序消费；false=fail，true=ok）。默认全成功。 */
        val dialScripts = mutableListOf<List<Boolean>>()
        private val identityVerifier = object : HostIdentityVerifier {
            override fun whoami(endpoint: HostEndpoint) =
                HostCandidate("host-1234", "test", listOf(endpoint))

            override fun identify(
                endpoint: HostEndpoint,
                hostId: String?,
                token: String,
                legacyUrl: String?,
            ) = HostIdentifyResult.Proven(
                HostIdentity(hostId ?: "legacy-host", "test", endpoint, endpoint.authority),
            )
        }

        val vm = PairingViewModel(
            configStore = store,
            identifyClient = identityVerifier,
            discoveryExecutor = Executor { it.run() },
            connectionFactory = { cfg ->
                ConnectionManager(
                    config = cfg,
                    // 每次拨号（attemptConnect）经 transportFactory.create 新建 transport，
                    // dialScript 从队列消费——精确控制每次拨号结果（首拨 + 重连逐次）。
                    transportFactory = TransportFactory {
                        val t = FakeWebSocketTransport()
                        if (dialScripts.isNotEmpty()) t.dialScript = dialScripts.removeAt(0)
                        transports.add(t)
                        t
                    },
                    clock = clock,
                    // 确定性退避：random 恒 0.5 ⇒ 抖动偏移 0.2*(0.5*2-1)=0 ⇒ delay 恒 1s/2s/4s。
                    // 为什么必须注入（根因，2026-08-14 w-tsnet-dev）：本测试的 pumpPastReconnectDelay
                    // 固定推进 4s 驱动 conn 重连；而 ReconnectPolicy 默认 random 是真随机，
                    // attempt2 退避 = 4s±20% = 3200~4800ms，抖动 >4000ms 那一半时第三次推进
                    // (now < pendingReconnectAt) 不触发重连 ⇒ 第 4 次拨号不发生 ⇒ 断言随机失败
                    // 约 1/3。注入 0.5 让 delay 完全确定，三次推进必然触发三次重连。若某次重构
                    // "顺手"改回真随机，本测试会回到约 1/3 概率红——这个 0.5 不是魔数。
                    policy = ReconnectPolicy(random = { 0.5 }),
                )
            },
            nowMs = { clock.nowMs() },
        )

        fun lastTransport(): FakeWebSocketTransport = transports.last()

        /** 喂 auth_ack ok:true → READY（配对成功解锁）。 */
        fun authOk() {
            lastTransport().deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":true}}""")
        }

        /**
         * 推进假时钟并触发 onTick，驱动 conn 层 pump 发起下一次重连拨号。
         * 步长 4s：越过退避 delay（1s/2s/4s/…）但**不越过**配对超时预算（单候选 15s），
         * 让 tsnet 窗口内的多次失败由 conn 退避驱动、由超时预算兜底（leader 裁定方案 b）。
         */
        fun pumpPastReconnectDelay() {
            clock.advance(4_000L)
            vm.onTick(clock.nowMs())
        }
    }

    /**
     * tsnet 起网完成 → Up → startProbe 首次拨号。
     * [dialScript] 控制 tsnet Up 后的拨号序列（false=fail，true=ok）。
     */
    private fun Harness.submitTailnet(dialScriptsPerAttempt: List<Boolean>) {
        vm.manualUrl = "ws://100.101.2.3:9900/ws"
        vm.manualToken = "ABC123"
        vm.manualTsAuthKey = "fake-auth-key"
        // 每次拨号一个单元素脚本（conn 每次 attemptConnect 新建 transport，逐次消费）。
        dialScripts.addAll(dialScriptsPerAttempt.map { listOf(it) })
        vm.submitManual()
        // tsnet 起网完成 → Up → startProbe()（pairingStartedAt 起算，首拨按首个脚本）。
        vm.onTsnetState(TsnetState.Up(TsnetProxy("127.0.0.1", 1080, "fake-cred")))
        assertEquals("前置：应已进入拨号（创建 1 个 transport）", 1, transports.size)
    }

    /**
     * 红测①：tsnet 起网窗口内**连失败三次** → 随后 READY 应解锁为 Success。
     *
     * 复刻用户真机日志真实时序（leader 裁定必须三次、且失败类型不同）：
     *   tsnet Up → 拨号1 失败（SocketTimeout）→ 拨号2 失败（SOCKS general failure）
     *   → 拨号3 失败 → 拨号4 成功（dial ok）→ READY。
     *
     * 当前 HEAD：tsnet Up → startProbe 首拨失败（同步）→ L300 RECONNECTING 无条件
     * advanceAttempt(UNREACHABLE) → 立即 Failed。后续 READY 到达时 pairingStatus 已非
     * Pairing，L287 Success 分支被挡 → 永锁 Failed。断言最终 Success ⇒ HEAD 红。
     *
     * 修复后：tsnet 路径 RECONNECTING 不判死（保持 Pairing）→ 第 4 次拨号成功 READY 解锁
     * Success ⇒ 绿。
     */
    @Test
    fun `tsnet 起网窗口内连失败三次 随后 READY 应解锁为 Success`() {
        val h = Harness()
        // tsnet 刚 Up、netstack 未就绪 → 拨号连失败 3 次，第 4 次成功（复刻用户时序）。
        h.submitTailnet(dialScriptsPerAttempt = listOf(false, false, false, true))
        // 前置：首拨失败确已发生（Conn 层进 RECONNECTING）。
        assertTrue(
            "前置：首拨失败后 Conn 层应已调度重连（RECONNECTING）。实际=${h.vm.connectionState}",
            h.vm.connectionState == dev.agentmirror.app.conn.ConnectionState.RECONNECTING,
        )
        // 修复后：窗口内 RECONNECTING 不判死，保持 Pairing（当前 HEAD 这里已 Failed，红）。
        assertTrue(
            "修复期望：tsnet 起网窗口内连失败不判死（保持 Pairing 等后续拨号）。实际=${h.vm.pairingStatus}",
            h.vm.pairingStatus is PairingStatus.Pairing,
        )
        // 驱动 conn 层重连 3 次（失败→失败→失败→成功）。
        repeat(3) { h.pumpPastReconnectDelay() }
        // 诊断：确认第 4 次拨号成功（transport open）后再喂 READY。
        assertEquals("第 4 次拨号应成功（transport open）", true, h.lastTransport().isOpen)
        // 第 4 次拨号成功 → transport open → 喂 auth_ack ok → READY。
        h.authOk()
        assertEquals(
            "配对页不应锁死在 Failed：tsnet 起网窗口内连失败三次后 READY 到达应解锁为 Success。" +
                "实际=${h.vm.pairingStatus}",
            PairingStatus.Success,
            h.vm.pairingStatus,
        )
    }

    /**
     * 红测③（T3，leader 2026-08-14 缺口补）：走 tsnet 路径配对成功后，断言 configStore
     * 持久化配置含非空 tsAuthKey。
     *
     * 链条：配对永远到不了 Success ⇒ tsAuthKey 从不落盘 ⇒ 冷启动时它是空的 ⇒ ensureStarted
     * 不被调用 ⇒ 没有 tsnet ⇒ 拿蜂窝地址直连 tailnet ⇒ 永远超时（用户 15:37 日志：整段无一条
     * [tsnet]/[socks]）。[PairingViewModel.L304] configStore.save(cfg) 是全工程唯一保存点且只
     * 在成功路径。修前到不了 Success ⇒ save 未调用 ⇒ 本断言红；修复后配对成功 ⇒ save 被调用
     * 且 cfg 含 currentTsAuthKey ⇒ 绿。
     */
    @Test
    fun `T3 tsnet 路径配对成功 持久化配置含非空 tsAuthKey`() {
        val h = Harness()
        // tsnet 起网 → Up → 首拨成功（dialScript=[true]）→ READY → Success。
        h.submitTailnet(dialScriptsPerAttempt = listOf(true))
        h.authOk()
        assertEquals("前置：配对应成功", PairingStatus.Success, h.vm.pairingStatus)

        // 断言持久化的配置含非空 tsAuthKey（冷启动拉起 tsnet 的唯一依据）。
        val saved = h.store.saved
        assertTrue("T3 命中（修前）：持久化配置必须含非空 tsAuthKey（冷启动靠它拉起 tsnet）。saved=$saved",
            saved != null && saved.tsAuthKey.isNotEmpty())
    }

    /**
     * 红测②（防过度修复）：真正不可达的地址，tsnet 从未起网（无 authkey）→ 首拨失败仍必须快速失败红框。
     * 修复不得把「tsnet 起网窗口」的保护扩散到「真不可达」——那会变成无限转圈（003 失败可见）。
     */
    @Test
    fun `真正不可达 无 tsnet 起网 首拨失败仍快速失败红框`() {
        val h = Harness()
        // 无 authkey、非 tailnet 段（LAN 地址）→ 不触发 tsnet 起网 → 直接 startProbe。
        h.vm.manualUrl = "ws://192.168.1.5:9900/ws"
        h.vm.manualToken = "ABC123"
        h.vm.manualTsAuthKey = ""
        h.dialScripts.add(listOf(false))
        h.vm.submitManual()

        val st = h.vm.pairingStatus
        assertTrue("真正不可达必须快速失败（红框），不得因修复变成无限转圈。got $st", st is PairingStatus.Failed)
        assertEquals(PairingFailCause.UNREACHABLE, (st as PairingStatus.Failed).cause)
    }
}
