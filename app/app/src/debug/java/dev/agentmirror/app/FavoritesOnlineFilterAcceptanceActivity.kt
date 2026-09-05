/*
 * Test-only visual harness for the favorites online projection.
 * This file is kept on an acceptance branch and is not a product change.
 */
package dev.agentmirror.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.agentmirror.app.conn.Level2Frame
import dev.agentmirror.app.conn.Session
import dev.agentmirror.app.ui.theme.AgentMirrorTheme
import dev.agentmirror.app.workspace.ConnectionUi
import dev.agentmirror.app.workspace.FavoriteRecord
import dev.agentmirror.app.workspace.MemoryFavoriteStore
import dev.agentmirror.app.workspace.WorkspaceViewModel

/** Synthetic-only UI harness; it never opens the production connection. */
class FavoritesOnlineFilterAcceptanceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialOnline = intent.getStringExtra("mode") == "online"
        setContent {
            FavoritesOnlineFilterHarness(initialOnline = initialOnline)
        }
    }
}

@Composable
private fun FavoritesOnlineFilterHarness(initialOnline: Boolean) {
    val store = remember { MemoryFavoriteStore(storedFavorites()) }
    val viewModel = remember {
        WorkspaceViewModel(
            initialConnection = ConnectionUi.READY,
            requestList = {},
            subscribeLevel2 = {},
            unsubscribeLevel2 = {},
            favoriteStore = store,
        ).also { vm ->
            vm.enterLevel2(WORKSPACE)
            if (initialOnline) vm.onFrame(liveFrame())
        }
    }
    val navState = remember {
        MainNavState(initialShowPairing = false).also { it.homePane = ThreePane.Favorites }
    }
    var online by remember { mutableStateOf(initialOnline) }

    AgentMirrorTheme {
        Column(Modifier.fillMaxSize().testTag("favorites-acceptance-root")) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("synthetic favorites acceptance")
                Text("stored=${viewModel.favorites.value.size} live=${viewModel.favoriteRows().count { it.isOnline }}")
                Button(
                    onClick = {
                        online = !online
                        viewModel.onFrame(if (online) liveFrame() else offlineFrame())
                    },
                    modifier = Modifier.testTag("toggle-favorites-online"),
                ) {
                    Text(if (online) "断开在线" else "恢复在线")
                }
            }
            Box(Modifier.weight(1f)) {
                ThreePaneHome(navState = navState, workspaceViewModel = viewModel)
            }
        }
    }
}

private const val WORKSPACE = "/synthetic/favorites-online-filter"

private fun storedFavorites(): List<FavoriteRecord> = listOf(
    FavoriteRecord(ref = "fav-idle", sessionName = "idle", windowName = "idle", cwd = WORKSPACE, addedAt = 1L),
    FavoriteRecord(ref = "fav-waiting", sessionName = "waiting", windowName = "waiting", cwd = WORKSPACE, addedAt = 2L),
    FavoriteRecord(ref = "fav-unknown", sessionName = "unknown", windowName = "unknown", cwd = WORKSPACE, addedAt = 3L),
)

private fun liveSessions(): List<Session> = listOf(
    Session(
        ref = "fav-idle",
        name = "idle",
        cwd = WORKSPACE,
        rows = 24,
        cols = 80,
        title = "idle",
        provider = "codex",
        activity = "idle",
        status = "idle",
        health = "normal",
        sessionName = "idle",
        windowIndex = "1",
        windowName = "idle",
    ),
    Session(
        ref = "fav-waiting",
        name = "waiting",
        cwd = WORKSPACE,
        rows = 24,
        cols = 80,
        title = "waiting",
        provider = "grok",
        activity = "waiting",
        status = "waiting",
        health = "normal",
        sessionName = "waiting",
        windowIndex = "2",
        windowName = "waiting",
    ),
    Session(
        ref = "fav-unknown",
        name = "unknown",
        cwd = WORKSPACE,
        rows = 24,
        cols = 80,
        title = "unknown",
        provider = "copilot",
        activity = "unknown",
        status = "unknown",
        health = "unknown",
        sessionName = "unknown",
        windowIndex = "3",
        windowName = "unknown",
    ),
)

private fun liveFrame(): Level2Frame = Level2Frame(
    workspace = WORKSPACE,
    seq = 2L,
    sessions = liveSessions(),
)

private fun offlineFrame(): Level2Frame = Level2Frame(
    workspace = WORKSPACE,
    seq = 3L,
    sessions = emptyList(),
)
