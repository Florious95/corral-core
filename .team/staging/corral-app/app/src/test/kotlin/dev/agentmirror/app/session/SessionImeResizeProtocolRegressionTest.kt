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

import androidx.test.core.app.ApplicationProvider
import dev.agentmirror.app.conn.ConnectionConfig
import dev.agentmirror.app.conn.ConnectionManager
import dev.agentmirror.app.conn.FakeClock
import dev.agentmirror.app.conn.FakeWebSocketTransport
import dev.agentmirror.app.conn.FrameCodec
import dev.agentmirror.app.conn.ResizeFrame
import dev.agentmirror.app.conn.TransportFactory
import dev.agentmirror.app.termview.TermSurfaceView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 场景红测 · raw/019 裁定②：键盘弹出 / 输入框变高**不触发 resize 协议帧**。
 *
 * 缺陷本体（FIELD.md A/B/C 三包实测同数字，从未实现）：
 * IME 弹起 → 终端区 weight 收缩 → TermSurfaceView.onSizeChanged → onViewportSizeChanged
 * → recomputeGeometry 发现 rows 变小 → onResizeRequest → manager.resize → ResizeFrame。
 *
 * 断言对象是「向服务端发出的 resize 帧」，不是 View bounds 变化（bounds 变化是布局
 * 必然，不该消灭）。正确行为：
 * - 首次拿到真实视口：允许发一次 resize（唯一合法帧）；
 * - 此后 IME 弹起 / 输入框一行→两行→三行（视口逐级收缩 63px）：不得再发 resize 帧。
 *
 * 首帧具体 rows/cols 值不再断言固定数字（feat-font-size-setting-drop-pinch：字格尺寸
 * 由 View 实测字形度量得出，不再有 presenter 默认字格 10x20 可依赖）——断言改为「首帧
 * 恰好一次 resize，后续 IME 挤压不追加」，与本测试真正要守的契约一致。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionImeResizeProtocolRegressionTest {

    @Test
    fun inputBarGrowthDoesNotEmitResizeFramesAfterInitialViewport() {
        val transport = FakeWebSocketTransport()
        val manager = ConnectionManager(
            config = ConnectionConfig(url = "ws://host:0/ws", token = "tok"),
            transportFactory = TransportFactory { transport },
            clock = FakeClock(),
        )
        manager.start()
        transport.deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":true}}""")
        val viewModel = SessionViewModel(
            manager = manager,
            uploader = AttachmentUploader { _, _ -> UploadOutcome.Failure("unused") },
            baseUrl = null,
            ref = "s1",
            initialRows = 24,
            initialCols = 80,
        )
        val surface = TermSurfaceView(ApplicationProvider.getApplicationContext()).apply {
            presenter = viewModel.presenter
        }

        // 首次真实视口：唯一允许的一次 resize（具体 rows/cols 由实测字格决定，不再硬编码）。
        surface.layout(0, 0, 1080, 1920)
        val firstResize = resizeFrames(transport)
        assertEquals("首次真实视口应恰好发一次 resize", 1, firstResize.size)

        // IME 弹起后视口逐级收缩（FIELD 实测每级 63px），模拟输入框一行→两行→三行。
        // rows 会逐级变小，但协议不得再产生 resize 帧。
        surface.layout(0, 0, 1080, 1857)
        surface.layout(0, 0, 1080, 1794)
        surface.layout(0, 0, 1080, 1731)

        assertEquals(
            "输入框变高（IME 弹起 / 一行→两行→三行）不得再发 resize 协议帧",
            firstResize,
            resizeFrames(transport),
        )
    }

    private fun resizeFrames(transport: FakeWebSocketTransport): List<Pair<Int, Int>> =
        transport.sentText
            .mapNotNull { runCatching { FrameCodec.decode(it) }.getOrNull() as? ResizeFrame }
            .map { it.rows to it.cols }
}
