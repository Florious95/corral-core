package dev.agentmirror.app.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceConnectingStateTest {

    @Test
    fun connectingStateKeepsLoadingModelAndBannerText() {
        val state = WorkspaceUiState(connection = ConnectionUi.CONNECTING)

        assertTrue(state.isLoading)
        assertEquals("连接中…", connectionBannerText(ConnectionUi.CONNECTING))
    }

    @Test
    fun reconnectingStateKeepsCachedWorkspacesAndBannerText() {
        val state = WorkspaceUiState(
            connection = ConnectionUi.RECONNECTING,
            workspaces = listOf(WorkspaceUi("/repo", 2)),
        )

        assertTrue(state.isDisconnected)
        assertEquals("重连中…", connectionBannerText(ConnectionUi.RECONNECTING))
        assertEquals(1, state.workspaces.size)
    }
}
