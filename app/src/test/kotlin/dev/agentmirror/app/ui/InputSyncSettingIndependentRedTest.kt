package dev.agentmirror.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.agentmirror.app.ui.screens.SettingsScreen
import dev.agentmirror.app.ui.theme.AppTheme
import dev.agentmirror.app.ui.theme.Appearance
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Independent user-visible oracle for the input-sync setting.
 *
 * The setting must be visible in the existing settings surface and explain both
 * modes. This intentionally asserts rendered semantics, not implementation names.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InputSyncSettingIndependentRedTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun settingsExposeInputSyncSwitchAndModeDescription() {
        compose.setContent {
            AppTheme(appearance = Appearance.Light) {
                SettingsScreen(
                    paired = true,
                    terminalFontSize = 14,
                    appearance = Appearance.System,
                    buildLabel = "0.1.0",
                    onRepair = {},
                    onFontSizeChange = {},
                    onAppearanceChange = {},
                    onExportLogs = {},
                    onViewLogs = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("输入框实时同步", substring = true).assertExists()
        compose.onNodeWithText("开启时打字实时显示在 CLI 终端", substring = true).assertExists()
        compose.onNodeWithText("关闭时仅在点击发送后一次性投递", substring = true).assertExists()
    }
}
