/* Copyright 2026 AgentMirror Project Authors
 * Licensed under the Apache License, Version 2.0.
 * See http://www.apache.org/licenses/LICENSE-2.0 for terms.
 */
package dev.agentmirror.app.input

import dev.agentmirror.app.conn.InputKey
import dev.agentmirror.app.session.Attachment
import dev.agentmirror.app.session.AttachmentUploader
import dev.agentmirror.app.session.InputStatus
import dev.agentmirror.app.session.SessionViewModel
import dev.agentmirror.app.session.UploadOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/** Android/Compose class integration; run by the authoritative Gradle test task. */
class TouchInputViewModelTest {
    private fun vm(h: TouchInputTransportTest.Harness): SessionViewModel {
        val vm = SessionViewModel(
            manager = h.manager,
            uploader = object : AttachmentUploader {
                override fun upload(baseUrl: String, attachment: Attachment) = UploadOutcome.Success("/fixture")
            },
            baseUrl = "http://fixture:0", ref = "pane", initialRows = 44, initialCols = 46,
            touchInputs = h.gate,
        )
        h.manager.setListener(vm)
        h.manager.subscribe("pane", 44, 46)
        vm.emulator.feed("\u001b[?1002h\u001b[?1006h")
        return vm
    }

    @Test fun upWithoutFinalMoveStillDeliversPhysicalEndpointBeforeRelease() {
        val h = TouchInputTransportTest.Harness()
        val vm = vm(h)
        assertTrue(vm.onTermMouse(46, 1, true))
        assertTrue(vm.onTermMouse(46, 10, true, motion = true))
        assertTrue(vm.onTermMouse(46, 42, false)) // no MOVE event at row 42
        assertEquals(1, h.inputs().size)
        h.ack(h.inputs().single().reqId)
        assertEquals(3, h.inputs().size)
        assertArrayEquals("\u001b[<32;46;42M".toByteArray(), h.inputs()[1].bytes)
        assertArrayEquals("\u001b[<0;46;42m".toByteArray(), h.inputs()[2].bytes)
        h.manager.stop()
        vm.dispose()
    }

    @Test fun mouseAckCannotMarkQueuedShortcutSent() {
        val h = TouchInputTransportTest.Harness()
        val vm = vm(h)
        vm.onTermMouse(46, 1, true)
        vm.onTermMouse(46, 10, true, motion = true)
        vm.sendKey(InputKey.ESC)
        assertTrue(vm.inputStatus is InputStatus.Sending)
        h.ack(h.inputs().single().reqId)
        assertEquals(3, h.inputs().size)
        assertTrue(vm.inputStatus is InputStatus.Sending)
        h.ack(h.inputs()[1].reqId)
        assertTrue(vm.inputStatus is InputStatus.Sending)
        h.ack(h.inputs()[2].reqId)
        assertEquals(InputStatus.Sent, vm.inputStatus)
        h.manager.stop()
        vm.dispose()
    }

    @Test fun veryFastAckConfirmsShortcutBeforeTheStaleSdkTimeout() {
        val h = TouchInputTransportTest.Harness(ackDuringWrite = true)
        val vm = vm(h)
        vm.sendKey(InputKey.ESC)
        h.drain()
        assertEquals(InputStatus.Sent, vm.inputStatus)
        h.clock.advance(1001)
        h.manager.resolveExpiredInputs(h.clock.nowMs())
        h.drain()
        assertEquals(InputStatus.Sent, vm.inputStatus)
        h.manager.stop()
        vm.dispose()
    }

    @Test fun leavingScreenPreservesAcceptedUpBeforeNativeUnsubscribe() {
        val h = TouchInputTransportTest.Harness()
        val vm = vm(h)
        vm.onTermMouse(46, 1, true)
        vm.onTermMouse(46, 42, true, motion = true)
        vm.onTermMouse(46, 42, false)
        vm.dispose()
        assertEquals(1, h.inputs().size)
        h.ack(h.inputs().single().reqId)
        assertEquals(3, h.inputs().size)
        assertArrayEquals("\u001b[<0;46;42m".toByteArray(), h.inputs()[2].bytes)
        val lastType = Json.parseToJsonElement(h.wire.sentText.last()).jsonObject["type"]?.jsonPrimitive?.content
        assertEquals("unsubscribe", lastType)
        h.manager.stop()
    }
}
