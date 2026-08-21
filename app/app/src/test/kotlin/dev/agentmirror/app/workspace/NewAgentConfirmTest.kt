package dev.agentmirror.app.workspace

import dev.agentmirror.app.conn.CreateFailReason
import dev.agentmirror.app.conn.CreateSessionAckFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewAgentConfirmTest {
    @Test
    fun requestDoesNotSend() {
        val sent = mutableListOf<Triple<String, List<String>, String>>()
        val vm = WorkspaceViewModel(sendCreateSession = { cwd, argv, provider ->
            sent.add(Triple(cwd, argv, provider))
            1L
        })
        vm.onFrame(
            dev.agentmirror.app.conn.ListingFrame(
                reqId = 1,
                seq = 1,
                workspaces = listOf(dev.agentmirror.app.conn.Workspace(cwd = "/ws", sessionCount = 1)),
            ),
        )
        vm.requestNewAgent()
        assertEquals("/ws", vm.newAgent.value?.cwd)
        assertEquals(0, sent.size)
        vm.cancelNewAgent()
        assertNull(vm.newAgent.value)
    }

    @Test
    fun ackOkOpensSession() {
        var sent = 0
        val vm = WorkspaceViewModel(sendCreateSession = { _, _, _ ->
            sent += 1
            9L
        })
        vm.onFrame(
            dev.agentmirror.app.conn.ListingFrame(
                reqId = 1,
                seq = 1,
                workspaces = listOf(dev.agentmirror.app.conn.Workspace(cwd = "/proj", sessionCount = 2)),
            ),
        )
        vm.requestNewAgent()
        vm.selectNewAgentProvider("grok")
        vm.setNewAgentBypass(true)
        vm.confirmNewAgent()
        assertEquals(1, sent)
        vm.onFrame(CreateSessionAckFrame(reqId = 9, ok = true, ref = "r-new"))
        assertNull(vm.newAgent.value)
        assertEquals("r-new" to "Grok", vm.openedSession.value)
    }

    @Test
    fun ackFailKeepsDialog() {
        val vm = WorkspaceViewModel(sendCreateSession = { _, _, _ -> 4L })
        vm.onFrame(
            dev.agentmirror.app.conn.ListingFrame(
                reqId = 1,
                seq = 1,
                workspaces = listOf(dev.agentmirror.app.conn.Workspace(cwd = "/ws", sessionCount = 1)),
            ),
        )
        vm.requestNewAgent()
        vm.confirmNewAgent()
        vm.onFrame(
            CreateSessionAckFrame(
                reqId = 4,
                ok = false,
                reason = CreateFailReason.NO_TMUX_ANCHOR,
            ),
        )
        assertEquals("no_tmux_anchor", vm.newAgent.value?.error)
        assertTrue(vm.newAgent.value?.inFlight == false)
    }
}
