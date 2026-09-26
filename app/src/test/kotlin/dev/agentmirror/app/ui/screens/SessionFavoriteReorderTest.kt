package dev.agentmirror.app.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dev.agentmirror.app.ui.model.SessionItem
import dev.agentmirror.app.ui.model.SessionStatus
import dev.agentmirror.app.ui.theme.AppTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SessionFavoriteReorderTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun favoritedRowMovesBeforeUnfavoritedRows() {
        var sessions by mutableStateOf(
            listOf(
                item("a"),
                item("b"),
                item("c"),
            ),
        )

        compose.setContent {
            AppTheme {
                SessionListRows(
                    sessions = sessions,
                    onSessionClick = {},
                    onToggleStar = {},
                )
            }
        }
        compose.waitForIdle()

        compose.runOnIdle { sessions = sessions.map { it.copy(starred = it.id == "b") } }
        compose.waitForIdle()

        val starredTop = compose.onNodeWithTag("l2-row-b").getUnclippedBoundsInRoot().top
        val unstarredTop = compose.onNodeWithTag("l2-row-a").getUnclippedBoundsInRoot().top
        assertTrue("收藏项必须位于未收藏项之前", starredTop < unstarredTop)
    }

    private fun item(id: String) = SessionItem(
        id = id,
        displayName = id,
        path = "/workspace/$id",
        status = SessionStatus.Idle,
        starred = false,
    )
}
