package dev.agentmirror.app

import androidx.activity.ComponentActivity
import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.activity.compose.setContent
import androidx.compose.ui.text.input.TextFieldValue
import dev.agentmirror.app.ui.model.SessionStatus
import dev.agentmirror.app.ui.screens.SessionShellScreen
import dev.agentmirror.app.ui.theme.AppTheme
import dev.agentmirror.app.workspace.FavoriteRow

/** Deterministic debug-only semantic acceptance fixture; excluded from release. */
class SessionUiAcceptanceActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent {
            AppTheme {
                SessionShellScreen(
                    sessionDisplayName = "acceptance-current",
                    status = SessionStatus.Unknown,
                    draft = TextFieldValue(), onDraftChange = {}, onSend = {}, onBack = { finish() },
                    onOpenSwitcher = {}, onKeyPress = {}, onAttach = {}, currentRef = "current",
                    favoriteRows = listOf(
                        FavoriteRow("current", "", "", 1, true, ref = "current"),
                        FavoriteRow("online", "", "", 2, true, ref = "online"),
                        FavoriteRow("offline", "", "", 3, false, ref = "offline"),
                    ),
                    onOpenFavorite = {},
                ) { Box(Modifier) }
            }
        }
    }
}
