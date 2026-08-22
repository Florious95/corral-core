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

package dev.agentmirror.app.session

import dev.agentmirror.app.conn.ConnectionConfig
import dev.agentmirror.app.service.NoopTransportFactory
import dev.agentmirror.app.service.ServiceWire
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 上传基地址统一收口锁定测试（fix-reconnect-stale-config 同根并案，纯 JVM 无网络）。
 *
 * 缺陷现场（图 30）：某会话内传图报「未配置上传地址」——SessionRoute.createSessionViewModel
 * 硬编码传 `null` 给 SessionViewModel 的 baseUrl，绕过 startPersistentConnection 统一装配
 * 入口（该入口已正确注入 ServiceWire.uploadBaseUrl）。修复：统一改读 ServiceWire.uploadBaseUrl。
 *
 * 本类锁定：
 * - [createSessionViewModel_readsUploadBaseFromServiceWire]：ServiceWire.uploadBaseUrl 已注入
 *   （配对成功/冷启动统一装配），createSessionViewModel 构造的 VM 必须以该地址为上传基地址——
 *   修前硬编码 null → 断言红。
 * - [createSessionViewModel_uploadUnset_reportsUnconfigured]：保真。未注入上传地址（连接配置
 *   未落地）时 VM 的 baseUrl 为 null——上传明确报错「未配置上传地址」语义保留（halt 纪律）。
 *
 * 纯 JVM：ServiceWire 是进程级单例（无 Context 依赖），transportFactory 用 Noop（不真联网）。
 */
class SessionUploadBaseWiringTest {

    @Before
    fun resetServiceWire() {
        ServiceWire.uiConnector = null
        ServiceWire.uploadBaseUrl = null
        ServiceWire.transportFactory = NoopTransportFactory
        ServiceWire.releaseManager()
        ServiceWire.resetConfigForTest()
    }

    @After
    fun teardown() {
        resetServiceWire()
    }

    @Test
    fun createSessionViewModel_readsUploadBaseFromServiceWire() {
        // 配对成功/冷启动统一装配入口（startPersistentConnection）已注入上传基地址。
        ServiceWire.setConfig(ConnectionConfig("ws://192.168.31.116:9900/ws", "tok"))
        ServiceWire.transportFactory = NoopTransportFactory
        ServiceWire.uploadBaseUrl = "http://192.168.31.116:9900"

        val vm = createSessionViewModel("s1")

        assertNotNull("连接配置已注入时 createSessionViewModel 必须构造出 VM", vm)
        assertEquals(
            "上传基地址必须统一读 ServiceWire.uploadBaseUrl（修前硬编码 null → 真机「未配置上传地址」）",
            "http://192.168.31.116:9900",
            vm!!.baseUrl,
        )
    }

    @Test
    fun createSessionViewModel_uploadUnset_reportsUnconfigured() {
        // 连接配置未落地（uploadBaseUrl 未注入）：baseUrl 保持 null——上传报「未配置上传地址」。
        ServiceWire.setConfig(ConnectionConfig("ws://192.168.31.116:9900/ws", "tok"))

        val vm = createSessionViewModel("s1")

        assertNotNull(vm)
        assertNull("上传地址未注入时 baseUrl 必须为 null（明确报错语义保留）", vm!!.baseUrl)
    }
}
