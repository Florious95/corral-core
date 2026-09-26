package dev.agentmirror.app.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinnedWorkspaceStoreTest {

    @Test
    fun togglePersistsAcrossViewModelInstances() {
        val store = MemoryPinnedWorkspaceStore()
        val first = WorkspaceViewModel(
            initialConnection = ConnectionUi.READY,
            pinnedWorkspaceStore = store,
        )

        assertTrue(first.toggleWorkspacePin("/repo/pinned"))
        assertEquals(setOf("/repo/pinned"), store.load())

        val second = WorkspaceViewModel(
            initialConnection = ConnectionUi.READY,
            pinnedWorkspaceStore = store,
        )
        assertEquals(setOf("/repo/pinned"), second.pinnedWorkspaces.value)
        assertEquals(false, second.toggleWorkspacePin("/repo/pinned"))
        assertEquals(emptySet<String>(), store.load())
    }
}
