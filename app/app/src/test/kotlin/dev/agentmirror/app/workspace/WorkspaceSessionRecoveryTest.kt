/* Copyright 2026 AgentMirror Project Authors. Licensed under Apache-2.0. */
package dev.agentmirror.app.workspace

import dev.agentmirror.app.conn.ConnectionState
import dev.agentmirror.app.conn.Level2Frame
import dev.agentmirror.app.conn.Level2HeartbeatFrame
import dev.agentmirror.app.conn.ListingFrame
import dev.agentmirror.app.conn.Session
import dev.agentmirror.app.conn.Workspace
import org.junit.Assert.*
import org.junit.Test

/** Baseline-compatible tests: identical test bytes can be run against parent and fix. */
class WorkspaceSessionRecoveryTest {
    @Test fun recreatedEmptyModelMustSubscribeDespiteSavedStateSuppression() {
        val h = RecoveryHarness()
        h.vm.onFrame(ListingFrame(reqId = 1, seq = 1,
            workspaces = listOf(Workspace(cwd = "/a", sessionCount = 1))))
        h.vm.suppressNextEnterRefresh()
        h.vm.enterLevel2("/a")
        assertEquals(listOf("/a"), h.subscriptions)
        assertTrue(h.vm.refreshing.value)
        h.deliver("/a", "a")
        assertEquals("a", h.vm.level2.value.sessions.single().ref)
        assertFalse(h.vm.refreshing.value)
    }

    @Test fun oldFavoritesCleanupCannotCancelNewDirectoryBeforeFirstFrame() {
        val h = RecoveryHarness()
        h.favorite("/b", "b")
        h.vm.enterFavorites()
        h.vm.enterLevel2("/a")
        h.vm.leaveFavorites() // outgoing composition disposes AFTER new directory acquired
        assertEquals("/a", h.remoteWorkspace)
        h.deliver("/a", "a")
        assertEquals("a", h.vm.level2.value.sessions.single().ref)
    }

    @Test fun lateFavoriteFrameCannotAdvanceQueueAndStealNewDirectory() {
        val h = RecoveryHarness()
        h.favorite("/b", "b")
        h.favorite("/c", "c")
        h.vm.enterFavorites()
        h.vm.enterLevel2("/a")
        h.deliver("/b", "b") // already queued response arrives after navigation
        assertEquals("/a", h.remoteWorkspace)
        assertFalse(h.subscriptions.contains("/c"))
    }

    @Test fun finishedFavoriteFetchRestoresRetainedDirectory() {
        val h = RecoveryHarness()
        h.vm.enterLevel2("/a")
        h.deliver("/a", "a")
        h.favorite("/b", "b")
        h.vm.enterFavorites()
        h.deliver("/b", "b")
        assertEquals("/a", h.remoteWorkspace)
        assertEquals("a", h.vm.level2.value.sessions.single().ref)
    }

    @Test fun timedOutFavoriteFetchRestoresRetainedDirectory() {
        val h = RecoveryHarness()
        h.vm.enterLevel2("/a")
        h.deliver("/a", "a")
        h.favorite("/b", "b")
        h.vm.enterFavorites()
        h.now += 8_000
        h.vm.checkFavoriteFetch()
        assertEquals("/a", h.remoteWorkspace)
    }

    @Test fun emptyAuthoritativeSnapshotMustNotResurrectRemovedSessions() {
        val h = RecoveryHarness()
        h.vm.enterLevel2("/a")
        h.deliver("/a", "a")
        h.deliver("/a")
        assertTrue(h.vm.level2.value.sessions.isEmpty())
        h.vm.leaveLevel2()
        h.vm.enterLevel2("/a")
        assertTrue(h.vm.level2.value.sessions.isEmpty())
    }

    @Test fun missingFirstSnapshotRetriesOnceEvenWhenHeartbeatsKeepArriving() {
        val h = RecoveryHarness()
        h.vm.enterLevel2("/a")
        repeat(5) {
            h.now += 8_000
            h.vm.onFrame(Level2HeartbeatFrame(workspace = "/a", seq = it + 1L))
            h.vm.checkLevel2Quiet()
        }
        assertEquals(2, h.subscriptions.size)
        repeat(100) { h.now += 1_000; h.vm.checkLevel2Quiet() }
        assertEquals("bounded recovery, not a refresh loop", 2, h.subscriptions.size)
        assertFalse(h.vm.refreshing.value)
        assertEquals("会话列表更新失败，请下拉重试", h.vm.level2.value.banner)
    }

    @Test fun aRetainedSnapshotStillSuppressesRedundantRotationRefresh() {
        val h = RecoveryHarness()
        h.vm.enterLevel2("/a")
        h.deliver("/a", "a")
        h.vm.suppressNextEnterRefresh()
        h.vm.enterLevel2("/a")
        assertEquals(1, h.subscriptions.size)
        assertEquals("a", h.vm.level2.value.sessions.single().ref)
    }

    @Test fun healthySnapshotScrollAndQuietChecksDoNotPoll() {
        val h = RecoveryHarness()
        h.vm.enterLevel2("/a")
        h.deliver("/a", "a")
        repeat(100) {
            h.now += 2_000
            h.vm.onListScroll()
            h.vm.checkLevel2Quiet()
        }
        assertEquals(1, h.subscriptions.size)
        assertEquals("a", h.vm.level2.value.sessions.single().ref)
        assertEquals(0, h.lists)
    }

    @Test fun leavingDirectoryMustNotCancelFavoritesFetchingTheSameWorkspace() {
        val h = RecoveryHarness()
        h.vm.enterLevel2("/a")
        h.favorite("/a", "a")
        h.vm.enterFavorites()
        h.vm.leaveLevel2()
        assertEquals("/a", h.remoteWorkspace)
        h.deliver("/a", "a")
        assertEquals(1, h.vm.favoriteFetchStats().fetchedWorkspaceCount)
        assertNull(h.remoteWorkspace)
    }
}

/** Faithful server slot semantics: every unsubscribe closes the slot, regardless of cwd. */
internal class RecoveryHarness {
    var now = 1_000L
    var lists = 0
    var remoteWorkspace: String? = null
    val subscriptions = mutableListOf<String>()
    val unsubscriptions = mutableListOf<String>()
    private var seq = 0L
    val vm = WorkspaceViewModel(
        initialConnection = ConnectionUi.READY,
        nowMs = { now },
        requestList = { lists++ },
        subscribeLevel2 = { subscriptions.add(it); remoteWorkspace = it },
        unsubscribeLevel2 = { unsubscriptions.add(it); remoteWorkspace = null },
    )
    fun deliver(cwd: String, vararg refs: String) {
        vm.onFrame(Level2Frame(workspace = cwd, seq = ++seq, sessions = refs.map {
            Session(ref = it, name = it, cwd = cwd, rows = 24, cols = 80,
                status = "idle", windowName = it, windowIndex = "0")
        }))
    }
    fun favorite(cwd: String, ref: String) {
        vm.toggleFavorite(L2Entry(ref = ref, name = ref, title = "", rows = 24, cols = 80,
            status = L2Status.IDLE, cwd = cwd, sessionName = ref,
            windowName = ref, windowIndex = "0"))
    }
}
