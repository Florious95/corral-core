package dev.agentmirror.app.conn

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * E12：close_session / close_session_ack 必须被 App 编解码认出。
 * 先验红：decode 抛 UNSUPPORTED_TYPE。
 */
class ConnCloseSessionCodecTest {
    @Test
    fun decodeCloseSession() {
        val frame = FrameCodec.decode(
            """{"v":1,"type":"close_session","payload":{"req_id":3,"ref":"s1"}}""",
        )
        assertEquals("close_session", frame.frameType)
    }

    @Test
    fun decodeCloseSessionAckOk() {
        val frame = FrameCodec.decode(
            """{"v":1,"type":"close_session_ack","payload":{"req_id":3,"ok":true}}""",
        )
        assertEquals("close_session_ack", frame.frameType)
    }
}
