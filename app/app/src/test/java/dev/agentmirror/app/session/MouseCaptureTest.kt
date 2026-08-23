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
import dev.agentmirror.app.conn.ConnectionManager
import dev.agentmirror.app.conn.FakeClock
import dev.agentmirror.app.conn.FakeWebSocketTransport
import dev.agentmirror.app.conn.FrameCodec
import dev.agentmirror.app.conn.InputFrame
import dev.agentmirror.app.conn.InputKey
import dev.agentmirror.app.conn.ScrollWheelFrame
import dev.agentmirror.app.conn.TransportFactory
import dev.agentmirror.app.termview.TermMouseCapture
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 输入透传第 4 步：壳采集鼠标/触摸 → 核层 encodeMouse → InputFrame.bytes。
 */
class MouseCaptureTest {

    private class Harness(ref: String = "s1") {
        val transport = FakeWebSocketTransport()
        val manager = ConnectionManager(
            config = ConnectionConfig(url = "ws://host:0/ws", token = "tok"),
            transportFactory = TransportFactory { transport },
            clock = FakeClock(),
        )
        val vm: SessionViewModel

        init {
            manager.start()
            transport.deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":true}}""")
            vm = SessionViewModel(
                manager = manager,
                uploader = NoOpUploader,
                baseUrl = "http://host:0",
                ref = ref,
                initialRows = 24,
                initialCols = 80,
            )
            manager.setListener(vm)
        }

        fun inputFrames(): List<InputFrame> =
            transport.sentText.mapNotNull {
                runCatching { FrameCodec.decode(it) as? InputFrame }.getOrNull()
            }

        fun bytesFrames(): List<InputFrame> =
            inputFrames().filter { it.bytes != null }

        fun enableMouse() {
            vm.emulator.feed("${E}[?1002h${E}[?1006h")
        }
    }

    private object NoOpUploader : AttachmentUploader {
        override fun upload(baseUrl: String, attachment: Attachment): UploadOutcome =
            UploadOutcome.Success("/noop")
    }

    // R1: 第 3 行第 5 列左键按下，送出的 bytes 等于核层编码，不是壳自拼。
    @Test
    fun testMousePressAtRow3Col5SendsCoreEncodedBytes() {
        val h = Harness()
        h.enableMouse()
        val want = h.vm.emulator.encodeMouse(
            button = 0,
            column = 5,
            row = 3,
            press = true,
        )
        assertNotNull(want)
        val sent = h.vm.onTermMouse(column = 5, row = 3, press = true)
        assertTrue("tracking on must send", sent)
        val frames = h.bytesFrames()
        assertEquals(1, frames.size)
        assertArrayEquals(want, frames[0].bytes)
    }

    // R2: 没开鼠标模式时不发帧（不是发空 bytes）。
    @Test
    fun testMouseNoneWhenTrackingOffDoesNotSendFrame() {
        val h = Harness()
        val before = h.inputFrames().size
        val sent = h.vm.onTermMouse(column = 5, row = 3, press = true)
        assertFalse("没开模式必须不发", sent)
        assertEquals(before, h.inputFrames().size)
        assertTrue("不得发空 bytes 帧", h.bytesFrames().isEmpty())
    }

    // R3: Ctrl 修饰键带到核层编码入口。
    @Test
    fun testMouseCtrlModifierReachesEncodeMouse() {
        val h = Harness()
        h.enableMouse()
        val withCtrl = h.vm.emulator.encodeMouse(
            button = 0,
            column = 5,
            row = 3,
            press = true,
            ctrl = true,
        )
        val without = h.vm.emulator.encodeMouse(
            button = 0,
            column = 5,
            row = 3,
            press = true,
            ctrl = false,
        )
        assertNotNull(withCtrl)
        assertFalse(withCtrl!!.contentEquals(without))
        val sent = h.vm.onTermMouse(column = 5, row = 3, press = true, ctrl = true)
        assertTrue(sent)
        assertArrayEquals(withCtrl, h.bytesFrames().last().bytes)
    }

    // R4 不退：既有键盘路径与 ScrollWheelFrame 仍绿。
    @Test
    fun testKeyboardAndScrollWheelPathsStillWork() {
        val h = Harness()
        h.vm.sendKey(InputKey.ESC)
        val keyFrames = h.inputFrames().filter { it.keys.isNotEmpty() }
        assertTrue("键盘路径必须仍发 keys", keyFrames.any { it.keys == listOf(InputKey.ESC) })

        h.vm.onScrollWheel(3)
        val wheels = h.transport.sentText.mapNotNull {
            runCatching { FrameCodec.decode(it) as? ScrollWheelFrame }.getOrNull()
        }
        assertEquals(1, wheels.size)
        assertEquals(-3, wheels[0].delta)
    }

    @Test
    fun testPixelToCellUsesCharSizeNotRawPixels() {
        val cap = TermMouseCapture()
        assertTrue(cap.hit(xPx = 40f, yPx = 40f, cellW = 10, cellH = 20, cols = 80, rows = 24))
        assertEquals(5, cap.col)
        assertEquals(3, cap.row)
        assertFalse("同格不报", cap.crossedCell())
        assertTrue(cap.hit(xPx = 50f, yPx = 40f, cellW = 10, cellH = 20, cols = 80, rows = 24))
        assertEquals(6, cap.col)
        assertTrue("跨格才报", cap.crossedCell())
    }

    private companion object {
        const val E = "\u001b"
    }
}
