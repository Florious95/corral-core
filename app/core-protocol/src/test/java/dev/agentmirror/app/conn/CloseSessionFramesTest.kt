package dev.agentmirror.app.conn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CloseSessionFramesTest {
    @Test
    fun closeSessionRequestRoundTrips() {
        val frame = CloseSessionFrame(reqId = 17, ref = "/tmp/tmux.sock\u001f%10")
        val decoded = FrameCodec.decode(FrameCodec.encode(frame))
        assertEquals(frame, decoded)
    }

    @Test
    fun closeSessionResultDecodesAndIsServerOnly() {
        val wire = "{\"v\":1,\"type\":\"close_session_result\",\"payload\":{\"req_id\":17,\"ok\":true}}"
        assertEquals(CloseSessionResultFrame(reqId = 17, ok = true), FrameCodec.decode(wire))
        try {
            FrameCodec.encode(CloseSessionResultFrame(reqId = 17, ok = true))
            fail("close_session_result must not be sent upstream")
        } catch (e: FrameEncodeException) {
            assertTrue(e.message.orEmpty().contains("server-to-client"))
        }
    }

    @Test
    fun closeSessionValidationRejectsMissingFields() {
        for (frame in listOf<FramePayload>(
            CloseSessionFrame(reqId = 0, ref = "/tmp/tmux.sock\u001f%10"),
            CloseSessionFrame(reqId = 1, ref = ""),
            CloseSessionResultFrame(reqId = 0, ok = true),
            CloseSessionResultFrame(reqId = 1, ok = false),
            CloseSessionResultFrame(reqId = 1, ok = true, reason = "close_failed"),
        )) {
            try {
                FrameCodec.encode(frame)
                fail("invalid frame unexpectedly encoded: $frame")
            } catch (_: FrameEncodeException) {
                // expected
            }
        }
    }
}
