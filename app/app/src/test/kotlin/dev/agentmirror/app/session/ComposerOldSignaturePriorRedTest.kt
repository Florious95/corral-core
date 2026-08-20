package dev.agentmirror.app.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.input.TextFieldValue
import dev.agentmirror.app.ui.model.SessionStatus
import dev.agentmirror.app.ui.screens.SessionShellScreen
import dev.agentmirror.app.ui.theme.AppTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * E4 功能先验红：只用 SessionShellScreen **旧签名**（无 composerExpanded 等新参数）。
 *
 * 断言「高度 == 40.dp」在改前会绿（锁死收起高度），不能当先验红，故不写那条。
 * 本条改前必红：旧 UI 没有 `session-composer`，发送在 DraftField 外面。
 * 改后 tag 存在且 send 中心落在表面内。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerOldSignaturePriorRedTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun sendCentersInsideComposerSurface() {
        compose.setContent {
            AppTheme {
                SessionShellScreen(
                    sessionDisplayName = "s",
                    status = SessionStatus.Idle,
                    draft = TextFieldValue(""),
                    onDraftChange = {},
                    onSend = {},
                    onBack = {},
                    onOpenSwitcher = {},
                    onKeyPress = {},
                    onAttach = {},
                ) { Box(Modifier.fillMaxSize()) }
            }
        }
        val composer = compose.onNodeWithTag("session-composer").getUnclippedBoundsInRoot()
        val send = compose.onNodeWithTag("session-send").getUnclippedBoundsInRoot()
        val sendCx = ((send.left + send.right) / 2).value
        assertTrue(
            "send center $sendCx composer ${composer.left}..${composer.right}",
            sendCx <= composer.right.value && sendCx >= composer.left.value,
        )
    }
}
