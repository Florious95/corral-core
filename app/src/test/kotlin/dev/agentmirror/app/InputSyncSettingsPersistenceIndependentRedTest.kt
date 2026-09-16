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
import dev.agentmirror.app.ui.theme.AppTheme
import dev.agentmirror.app.ui.theme.Appearance
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** User-visible persistence oracle: toggle the real settings route, recreate it, read it back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InputSyncSettingsPersistenceIndependentRedTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun togglingInputSyncSwitchSurvivesSettingsRouteRecreation() {
        val context = RuntimeEnvironment.getApplication()
        val store = runCatching {
            Class.forName("dev.agentmirror.app.session.SharedPreferencesInputSyncStore")
                .getConstructor(android.content.Context::class.java)
                .newInstance(context)
        }.getOrNull()
        store?.javaClass?.getMethod("save", Boolean::class.javaPrimitiveType)?.invoke(store, true)

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
        compose.setContent {
            key(routeKey) {
                content()
            }
        }
        compose.waitForIdle()
        val toggle = compose.onNodeWithTag("input-sync-switch")
        toggle.assertIsOn()
        toggle.performClick()
        compose.waitForIdle()
        toggle.assertIsOff()

        // Re-entering the real route must read the persisted false value.
        routeKey++
        compose.waitForIdle()
        compose.onNodeWithTag("input-sync-switch").assertIsOff()
    }
}
