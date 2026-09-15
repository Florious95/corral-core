package dev.agentmirror.app.conn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CreateAgentFramesTest {
    @Test
    fun createAgentRequestRoundTrips() {
        val frame = CreateAgentFrame(
            reqId = 101,
            workspace = "/repo",
            anchorRef = "/tmp/tmux.sock\u001f%0",
            provider = "pi",
            name = "修复测试员",
            bypass = true,
        )
        val decoded = FrameCodec.decode(FrameCodec.encode(frame))
        assertEquals(frame, decoded)
    }

    @Test
    fun authAckCarriesLauncherCapabilities() {
        val frame = AuthAckFrame(
            ok = true,
            agentLaunchers = listOf(
                AgentLauncherFrame("pi", "Pi Coding Agent", supportsBypass = true, naming = "cli"),
            ),
        )
        val wire = FrameCodec.encode(frame)
        assertTrue(wire.contains("agent_launchers"))
        assertEquals(frame, FrameCodec.decode(wire))
    }

    @Test
    fun createAgentResultIsDecodeOnly() {
        val result = CreateAgentResultFrame(reqId = 1, ok = false, reason = "target_not_found")
        val wire = "{\"v\":1,\"type\":\"create_agent_result\",\"payload\":{\"req_id\":1,\"ok\":false,\"reason\":\"target_not_found\"}}"
        assertEquals(result, FrameCodec.decode(wire))
        try {
            FrameCodec.encode(result)
            fail("create_agent_result must not be sent upstream")
        } catch (_: FrameEncodeException) {
            // expected
        }
    }
}
