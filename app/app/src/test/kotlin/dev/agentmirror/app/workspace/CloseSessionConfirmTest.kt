package dev.agentmirror.app.workspace

import dev.agentmirror.app.conn.CloseFailReason
import dev.agentmirror.app.conn.CloseSessionAckFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E12 客户端原子收尾：未确认不发帧；ack.ok 才取消收藏；失败留对话框。
 */
class CloseSessionConfirmTest {

    @Test
    fun requestCloseDoesNotSend() {
        val sent = mutableListOf<String>()
        val vm = WorkspaceViewModel(sendCloseSession = { ref ->
            sent.add(ref)
            1L
        })
        vm.requestClose("r1", "demo")
        assertEquals("r1", vm.closeConfirm.value?.ref)
        assertEquals(0, sent.size)
        vm.cancelClose()
        assertNull(vm.closeConfirm.value)
        assertEquals(0, sent.size)
    }

    @Test
    fun ackOkDropsFavorite() {
        val store = MemoryFavoriteStore()
        val vm = WorkspaceViewModel(
            favoriteStore = store,
            sendCloseSession = { 9L },
        )
        vm.toggleFavorite(
            L2Entry(
                ref = "r1",
                name = "n",
                title = "",
                rows = 24,
                cols = 80,
                status = L2Status.IDLE,
                cwd = "/ws",
                sessionName = "s",
                windowIndex = "1",
                windowName = "demo",
            ),
        )
        assertTrue(vm.isFavorited("r1"))
        vm.requestClose("r1", "demo")
        vm.confirmClose()
        assertTrue(vm.closeConfirm.value?.inFlight == true)
        vm.onFrame(CloseSessionAckFrame(reqId = 9, ok = true))
        assertFalse(vm.isFavorited("r1"))
        assertNull(vm.closeConfirm.value)
        assertEquals("r1", vm.closedRef.value)
    }

    @Test
    fun ackFailKeepsDialogAndFavorite() {
        val vm = WorkspaceViewModel(sendCloseSession = { 4L })
        vm.toggleFavorite(
            L2Entry(
                ref = "r2",
                name = "n",
                title = "",
                rows = 24,
                cols = 80,
                status = L2Status.WORKING,
                cwd = "/ws",
                windowName = "busy",
            ),
        )
        vm.requestClose("r2", "busy")
        vm.confirmClose()
        vm.onFrame(
            CloseSessionAckFrame(
                reqId = 4,
                ok = false,
                reason = CloseFailReason.CLOSE_FAILED,
            ),
        )
        assertTrue(vm.isFavorited("r2"))
        assertEquals("close_failed", vm.closeConfirm.value?.error)
        assertFalse(vm.closeConfirm.value?.inFlight == true)
    }
}
