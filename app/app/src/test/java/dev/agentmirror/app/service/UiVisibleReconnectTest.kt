/*
 * Copyright 2026 AgentMirror Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.agentmirror.app.service

import android.content.Context
import dev.agentmirror.app.MainActivity
import dev.agentmirror.app.conn.ConnectionConfig
import dev.agentmirror.app.conn.ConnectionState
import dev.agentmirror.app.conn.FakeWebSocketTransport
import dev.agentmirror.app.conn.TransportFactory
import dev.agentmirror.app.conn.WebSocketTransport
import dev.agentmirror.app.session.createSessionViewModel
import dev.agentmirror.app.workspace.WorkspaceViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * 契约 090：回前台 / 收藏页重连（E17 / E18）。
 *
 * 用户 2026-08-21 实测是**概率性**不刷：不是每一次都不重连，所以单次绿是假绿。
 * 本文件每条路径**重复** [TRIALS] 次，全部必须真的发起一次新拨号（transport.create
 * 次数 +1），一次都不许漏。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UiVisibleReconnectTest {

    companion object {
        const val TRIALS = 20
    }

    private class RecordingTransportFactory : TransportFactory {
        val created = mutableListOf<FakeWebSocketTransport>()
        override fun create(url: String): WebSocketTransport {
            val t = FakeWebSocketTransport()
            created.add(t)
            return t
        }
    }

    @Before
    fun reset() {
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("pairing_config", Context.MODE_PRIVATE)
            .edit().clear().commit()
        ServiceWire.uiConnector = null
        ServiceWire.listConnector = null
        ServiceWire.uploadBaseUrl = null
        ServiceWire.transportFactory = NoopTransportFactory
        ServiceWire.releaseManager()
        ServiceWire.resetConfigForTest()
        ServiceWire.servicePumpActive = false
    }

    @After
    fun teardown() {
        ServiceWire.uiConnector = null
        ServiceWire.listConnector = null
        ServiceWire.uploadBaseUrl = null
        ServiceWire.transportFactory = NoopTransportFactory
        ServiceWire.releaseManager()
        ServiceWire.resetConfigForTest()
        ServiceWire.servicePumpActive = false
    }

    private fun seedPrefs() {
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("pairing_config", Context.MODE_PRIVATE)
            .edit()
            .putString("url", "ws://10.0.2.2:9900/ws")
            .putString("token", "tok-recon")
            .commit()
    }

    private fun ready(t: FakeWebSocketTransport) {
        t.deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":true}}""")
    }

    /** A-recon-fg：会话页在屏 → 后台 → 回前台，连接已断，必须发起一次新拨号。重复 20 次。 */
    @Test
    fun aReconFg_sessionBackgroundForeground_alwaysDialsAgain() {
        val misses = mutableListOf<Int>()
        repeat(TRIALS) { i ->
            reset()
            seedPrefs()
            val factory = RecordingTransportFactory()
            ServiceWire.transportFactory = factory
            val controller = Robolectric.buildActivity(MainActivity::class.java)
            controller.create()
            val first = factory.created.single()
            ready(first)
            first.peerClose(1006, "dropped-fg-$i")
            assertEquals(ConnectionState.RECONNECTING, ServiceWire.managerOrNull()?.state())
            val before = factory.created.size
            // 不点进会话、不 pump：只走 Activity ON_START（回前台）。
            controller.start()
            val dialed = factory.created.size > before
            if (!dialed) misses.add(i)
            controller.destroy()
        }
        assertTrue(
            "A-recon-fg 概率性失败：N=$TRIALS 次里有 ${misses.size} 次回前台未发起新拨号 trials=$misses",
            misses.isEmpty(),
        )
    }

    /**
     * A-recon-fav：停在收藏页 + 连接已断，收藏页自己进入重连，⛔ 不调用 createSessionViewModel。
     * 重复 20 次。偶发形态用两种断法交替：peerClose（RECONNECTING）与 releaseManager（manager 空）。
     */
    @Test
    fun aReconFav_favoritesVisible_alwaysDialsWithoutOpeningSession() {
        val misses = mutableListOf<Int>()
        repeat(TRIALS) { i ->
            reset()
            val factory = RecordingTransportFactory()
            ServiceWire.transportFactory = factory
            ServiceWire.setConfig(ConnectionConfig("ws://10.0.2.2:9900/ws", "tok-fav"))
            val m = ServiceWire.manager(object : dev.agentmirror.app.conn.ConnectionManager.Listener {
                override fun onStateChanged(state: ConnectionState) = Unit
                override fun onFrame(frame: dev.agentmirror.app.conn.FramePayload) = Unit
                override fun onBinary(frame: dev.agentmirror.app.conn.BinaryFrame) = Unit
                override fun onLocalDecodeError(code: dev.agentmirror.app.conn.FrameError, message: String) = Unit
                override fun onInputResult(reqId: Long, ok: Boolean, reason: String?) = Unit
                override fun onReconnect(attempt: Int, delayMs: Long) = Unit
            })
            m.start()
            ready(factory.created.single())
            if (i % 2 == 0) {
                factory.created.last().peerClose(1006, "dropped-fav-$i")
            } else {
                ServiceWire.releaseManager()
            }
            val before = factory.created.size
            // 收藏页可见：只 enterFavorites。明示不走进会话装配。
            val vm = WorkspaceViewModel()
            vm.enterFavorites()
            val touchedSession = false
            if (touchedSession) createSessionViewModel("must-not-run")
            val dialed = factory.created.size > before
            if (!dialed) misses.add(i)
        }
        assertTrue(
            "A-recon-fav 概率性失败：N=$TRIALS 次里有 ${misses.size} 次收藏页未发起新拨号 trials=$misses",
            misses.isEmpty(),
        )
    }

    /** A-recon-once：两条路径走同一个入口函数，防再给收藏页单独补一份。查代码内容。 */
    @Test
    fun aReconOnce_bothPathsCallTheSameUiVisibleEntry() {
        val main = source("src/main/java/dev/agentmirror/app/MainActivity.kt")
        val workspace = source("src/main/java/dev/agentmirror/app/workspace/WorkspaceViewModel.kt")
        val wire = source("src/main/java/dev/agentmirror/app/service/ServiceWire.kt")
        assertTrue("共同入口必须落在 ServiceWire.onUiVisible：$wire", wire.contains("fun onUiVisible("))
        assertTrue(
            "A-recon-fg 路径（回前台）必须调 ServiceWire.onUiVisible，不得另写一套：$main",
            main.contains("ServiceWire.onUiVisible("),
        )
        assertTrue(
            "A-recon-fav 路径（收藏页可见）必须调同一个 ServiceWire.onUiVisible，不得单独补订阅：$workspace",
            workspace.contains("ServiceWire.onUiVisible("),
        )
        val favOwnStart = Regex("""fun enterFavorites\s*\([^)]*\)\s*\{.*?\}""", RegexOption.DOT_MATCHES_ALL)
            .find(workspace)?.value.orEmpty()
        assertTrue("enterFavorites 必须存在", favOwnStart.isNotEmpty())
        assertTrue(
            "enterFavorites 不得自己 manager.start()（那是给收藏页单独补的形状）：$favOwnStart",
            !favOwnStart.contains("manager.start(") && !favOwnStart.contains(".start()"),
        )
    }

    private fun source(rel: String): String {
        val f = File(rel)
        assertTrue("源文件不存在 ${f.absolutePath}", f.isFile)
        return f.readText()
    }
}
