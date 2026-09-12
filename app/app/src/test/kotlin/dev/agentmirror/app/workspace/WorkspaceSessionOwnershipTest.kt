/* Copyright 2026 AgentMirror Project Authors. Licensed under Apache-2.0. */
package dev.agentmirror.app.workspace

import dev.agentmirror.app.conn.ConnectionState
import dev.agentmirror.app.conn.ListingFrame
import dev.agentmirror.app.conn.Level2HeartbeatFrame
import org.junit.Assert.*
import org.junit.Test

class WorkspaceSessionOwnershipTest {
    @Test fun oldDirectoryLeaseCannotReleaseNewDirectory() {
        val h = RecoveryHarness()
        val old = h.vm.enterLevel2("/a")
        val current = h.vm.enterLevel2("/b")
        h.vm.leaveLevel2(old)
        assertEquals("/b", h.remoteWorkspace)
        h.vm.leaveLevel2(current)
        assertNull(h.remoteWorkspace)
    }
    @Test fun oldLeaseCannotReleaseReenteredSameDirectory() {
        val h = RecoveryHarness()
        val old = h.vm.enterLevel2("/a")
        val current = h.vm.enterLevel2("/a")
        h.vm.leaveLevel2(old)
        assertEquals("/a", h.remoteWorkspace)
        h.vm.leaveLevel2(current)
        assertNull(h.remoteWorkspace)
    }
    @Test fun oldFavoritesLeaseCannotReleaseNewFavoritesFetch() {
        val h = RecoveryHarness()
        h.favorite("/a", "a")
        val old = h.vm.enterFavorites()
        val current = h.vm.enterFavorites()
        h.vm.leaveFavorites(old)
        assertEquals("/a", h.remoteWorkspace)
        h.vm.leaveFavorites(current)
        assertNull(h.remoteWorkspace)
    }
    @Test fun sessionHandoffInvalidatesDirectoryLeaseWithoutExtraScan() {
        val h = RecoveryHarness()
        val old = h.vm.enterLevel2("/a")
        h.deliver("/a", "a")
        h.vm.enterSessionLive("a", "/a")
        h.vm.leaveLevel2(old)
        assertEquals("/a", h.remoteWorkspace)
        assertEquals(1, h.subscriptions.size)
    }
    @Test fun manualRefreshWaitsForLevel2NotListingOrHeartbeat() {
        val h = RecoveryHarness()
        h.vm.enterLevel2("/a")
        h.deliver("/a", "a")
        h.vm.refreshLevel2()
        assertEquals(0, h.lists)
        assertEquals(2, h.subscriptions.size)
        h.vm.onFrame(ListingFrame(reqId = 1, seq = 1, workspaces = emptyList()))
        h.vm.onFrame(Level2HeartbeatFrame(workspace = "/a", seq = 2))
        assertTrue(h.vm.refreshing.value)
        assertEquals("a", h.vm.level2.value.sessions.single().ref)
        h.deliver("/b", "b")
        assertTrue(h.vm.refreshing.value)
        h.deliver("/a", "a", "new")
        assertFalse(h.vm.refreshing.value)
        assertEquals(2, h.vm.level2.value.sessions.size)
    }
    @Test fun retryBudgetResetsOnlyOnAnExplicitNewRefresh() {
        val h = RecoveryHarness()
        h.vm.enterLevel2("/a")
        repeat(2) { h.now += 40_000; h.vm.checkLevel2Quiet() }
        assertEquals(2, h.subscriptions.size)
        h.vm.refreshLevel2()
        assertTrue(h.vm.refreshing.value)
        h.now += 40_000; h.vm.checkLevel2Quiet()
        assertEquals(4, h.subscriptions.size)
        h.deliver("/a", "a")
        assertFalse(h.vm.refreshing.value)
    }
    @Test fun disconnectKeepsCacheAndReadyRecoversSelectedDirectory() {
        val h = RecoveryHarness()
        h.vm.enterLevel2("/a")
        h.deliver("/a", "a")
        h.vm.refreshLevel2()
        h.vm.onConnectionStateChanged(ConnectionState.RECONNECTING)
        assertFalse(h.vm.refreshing.value)
        h.now += 80_000; h.vm.checkLevel2Quiet()
        assertEquals(2, h.subscriptions.size)
        assertEquals("a", h.vm.level2.value.sessions.single().ref)
        h.vm.onConnectionStateChanged(ConnectionState.READY)
        assertEquals("/a", h.subscriptions.last())
        h.deliver("/a", "a", "new")
        assertEquals(2, h.vm.level2.value.sessions.size)
    }
    @Test fun failedCachedRefreshStaysVisibleUntilAnActualSnapshotArrives() {
        val h = RecoveryHarness()
        h.vm.enterLevel2("/a")
        h.deliver("/a", "a")
        h.vm.refreshLevel2()
        repeat(2) { h.now += 40_000; h.vm.checkLevel2Quiet() }
        h.vm.onFrame(Level2HeartbeatFrame(workspace = "/a", seq = 10))
        h.now += 1_000; h.vm.checkLevel2Quiet()
        assertEquals("会话列表更新失败，请下拉重试", h.vm.level2.value.banner)
        assertEquals("a", h.vm.level2.value.sessions.single().ref)
        h.deliver("/a", "a")
        assertNull(h.vm.level2.value.banner)
    }
    @Test fun readyDuringFavoritesFetchDoesNotStealItsSlot() {
        val h = RecoveryHarness()
        h.vm.enterLevel2("/a")
        h.favorite("/b", "b")
        h.vm.enterFavorites()
        h.vm.onConnectionStateChanged(ConnectionState.RECONNECTING)
        h.vm.onConnectionStateChanged(ConnectionState.READY)
        assertEquals("/b", h.remoteWorkspace)
        h.deliver("/b", "b")
        assertEquals("/a", h.remoteWorkspace)
    }
}
