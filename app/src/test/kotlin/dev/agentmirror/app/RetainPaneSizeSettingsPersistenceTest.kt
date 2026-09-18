package dev.agentmirror.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.agentmirror.app.session.SharedPreferencesRetainPaneSizeStore
import dev.agentmirror.app.ui.theme.AppTheme
import dev.agentmirror.app.ui.theme.Appearance
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 尺寸驻留开关在真实设置路由重建后仍保持用户选择。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RetainPaneSizeSettingsPersistenceTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun togglingRetainPaneSizeSurvivesSettingsRouteRecreation() {
        val context = RuntimeEnvironment.getApplication()
        SharedPreferencesRetainPaneSizeStore(context).save(false)

        @Composable
        fun content() {
            AppTheme(appearance = Appearance.Light) {
                SettingsScreen(
                    onBack = {},
                    onRePair = {},
                    enableBackHandler = false,
                    appearance = Appearance.Light,
                    onAppearanceChange = {},
                )
            }
        }

        var routeKey by mutableIntStateOf(0)
        compose.setContent { key(routeKey) { content() } }
        compose.waitForIdle()
        val toggle = compose.onNodeWithTag("retain-pane-size-switch")
        toggle.assertIsOff()
        toggle.performClick()
        compose.waitForIdle()
        toggle.assertIsOn()

        routeKey++
        compose.waitForIdle()
        compose.onNodeWithTag("retain-pane-size-switch").assertIsOn()
    }
}
