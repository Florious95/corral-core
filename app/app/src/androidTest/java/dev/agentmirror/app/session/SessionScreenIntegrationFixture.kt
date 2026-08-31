package dev.agentmirror.app.session

import androidx.compose.runtime.Composable
import dev.agentmirror.app.workspace.WorkspaceViewModel

/** Test fixture wiring production SessionScreen and WorkspaceViewModel source. */
class SessionScreenIntegrationFixture(private val workspace: WorkspaceViewModel) {
    fun source(ref: String) = workspace.viewMenuSource(ref)
    @Composable fun render(viewModel: SessionViewModel, ref: String, onBack: () -> Unit, onOpen: (String, String) -> Unit) =
        SessionScreen(viewModel = viewModel, name = ref, onBack = onBack, overlaySessions = source(ref).sessions, onOpenOverlaySession = onOpen)
}
