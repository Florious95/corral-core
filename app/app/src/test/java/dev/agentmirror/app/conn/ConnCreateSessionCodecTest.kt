package dev.agentmirror.app.conn

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnCreateSessionCodecTest {
    @Test
    fun decodeCreateSession() {
        val frame = FrameCodec.decode(
            """{"v":1,"type":"create_session","payload":{"req_id":3,"cwd":"/ws","argv":["sleep","30"]}}""",
        )
        assertEquals("create_session", frame.frameType)
    }

    @Test
    fun decodeCreateSessionAckOk() {
        val frame = FrameCodec.decode(
            """{"v":1,"type":"create_session_ack","payload":{"req_id":3,"ok":true,"ref":"s1"}}""",
        )
        assertEquals("create_session_ack", frame.frameType)
    }
}
