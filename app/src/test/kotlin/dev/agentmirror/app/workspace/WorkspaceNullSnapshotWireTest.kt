package dev.agentmirror.app.workspace

import dev.agentmirror.app.conn.FrameCodec
import dev.agentmirror.app.conn.FrameDecodeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Regression for the real f7 daemon's final-session deletion frame. */
class WorkspaceNullSnapshotWireTest {
    private val populated = """{"v":1,"type":"level2_frame","payload":{"workspace":"/a","seq":1,"sessions":[{"ref":"owned-ref","name":"fixture","cwd":"/a","rows":24,"cols":80}]}}"""

    private fun model() = WorkspaceViewModel(initialConnection = ConnectionUi.READY).also {
        it.enterLevel2("/a")
        it.onFrame(FrameCodec.decode(populated))
        assertEquals("owned-ref", it.level2.value.sessions.single().ref)
    }

    @Test
    fun serverNullSnapshotClearsDisplayedAndCachedLastSession() {
        val vm = model()
        vm.onFrame(FrameCodec.decode("""{"v":1,"type":"level2_frame","payload":{"workspace":"/a","seq":2,"sessions":null}}"""))
        assertTrue("authoritative deletion must remove visible row", vm.level2.value.sessions.isEmpty())
        vm.leaveLevel2()
        vm.enterLevel2("/a")
        assertTrue("deleted row must not return from cache", vm.level2.value.sessions.isEmpty())
    }

    @Test
    fun heartbeatWithoutSessionsPreservesPopulatedList() {
        val vm = model()
        vm.onFrame(FrameCodec.decode("""{"v":1,"type":"level2_heartbeat","payload":{"workspace":"/a","seq":2}}"""))
        assertEquals("owned-ref", vm.level2.value.sessions.single().ref)
    }

    @Test
    fun omittedSnapshotSessionsRemainsInvalidInsteadOfDeletingRows() {
        try {
            FrameCodec.decode("""{"v":1,"type":"level2_frame","payload":{"workspace":"/a","seq":2}}""")
            fail("missing snapshot payload must not become an authoritative empty list")
        } catch (_: FrameDecodeException) {
            // Expected: an omitted sessions field is not an empty snapshot.
        }
    }

    @Test
    fun malformedSnapshotSessionsRemainsInvalid() {
        try {
            FrameCodec.decode("""{"v":1,"type":"level2_frame","payload":{"workspace":"/a","seq":2,"sessions":"broken"}}""")
            fail("malformed snapshot must not become an authoritative empty list")
        } catch (_: FrameDecodeException) {
            // Expected: only JSON null is accepted for an empty snapshot.
        }
    }
}
