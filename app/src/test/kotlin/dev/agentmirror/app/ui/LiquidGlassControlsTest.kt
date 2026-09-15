package dev.agentmirror.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.agentmirror.app.ui.components.LiquidToggle
import dev.agentmirror.app.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiquidGlassControlsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun liquidToggleExposesSwitchSemanticsAndChangesState() {
        var checked by mutableStateOf(false)
        compose.setContent {
            AppTheme {
                LiquidToggle(
                    checked = checked,
                    onCheckedChange = { checked = it },
                    modifier = Modifier.testTag("test-liquid-toggle"),
                )
            }
        }
        compose.onNodeWithTag("test-liquid-toggle").assertIsOff().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("test-liquid-toggle").assertIsOn()
    }
}
