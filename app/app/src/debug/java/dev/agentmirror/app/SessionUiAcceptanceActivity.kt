package dev.agentmirror.app

import androidx.activity.ComponentActivity
import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.activity.compose.setContent
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.agentmirror.app.ui.model.SessionStatus
import dev.agentmirror.app.ui.screens.SessionShellScreen
import dev.agentmirror.app.ui.theme.AppTheme
import dev.agentmirror.app.workspace.FavoriteRow

/** Deterministic debug-only semantic acceptance fixture; excluded from release. */
class SessionUiAcceptanceActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent {
            var currentRef by mutableStateOf("current")
            var callbackCount by mutableStateOf(0)
            AppTheme {
                SessionShellScreen(
                    sessionDisplayName = "acceptance-current",
                    status = SessionStatus.Unknown,
                    draft = TextFieldValue(), onDraftChange = { callbackCount++ }, onSend = { callbackCount++ }, onBack = { finish() },
                    onOpenSwitcher = { callbackCount++ }, onKeyPress = { callbackCount++ }, onAttach = { callbackCount++ }, currentRef = currentRef,
                    favoriteRows = listOf(
                        FavoriteRow("current", "", "", 1, true, ref = "current"),
                        FavoriteRow("online", "", "", 2, true, ref = "online"),
                        FavoriteRow("offline", "", "", 3, false, ref = "offline"),
                    ),
                    onOpenFavorite = { currentRef = it.ref; callbackCount++ },
                ) { Box(Modifier.semantics { contentDescription = "acceptance-callbacks=$callbackCount current-ref=$currentRef" }) }
            }
        }
    }
}
