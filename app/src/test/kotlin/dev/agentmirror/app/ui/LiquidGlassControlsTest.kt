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
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.highlight.HighlightStyle
import dev.agentmirror.app.ui.components.AppBottomNav
import dev.agentmirror.app.ui.components.HairlineHighlight
import dev.agentmirror.app.ui.components.HairlineInnerShadow
import dev.agentmirror.app.ui.components.LiquidToggle
import dev.agentmirror.app.ui.model.NavTab
import dev.agentmirror.app.ui.theme.AppTheme
import dev.agentmirror.app.ui.theme.DarkPalette
import dev.agentmirror.app.ui.theme.LightPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun hairlineHighlightAndInnerShadowParametersMatchDesign() {
        assertEquals(0.65.dp, HairlineHighlight.width)
        assertEquals(0.65f, HairlineHighlight.alpha, 0.001f)
        val style = HairlineHighlight.style as HighlightStyle.Default
        assertEquals(2.5f, style.falloff, 0.001f)
        assertEquals(45f, style.angle, 0.001f)

        assertEquals(1.5.dp, HairlineInnerShadow.radius)
        assertEquals(0.12f, HairlineInnerShadow.alpha, 0.001f)
        assertEquals(DpOffset(0.dp, 0.75.dp), HairlineInnerShadow.offset)
    }

    @Test
    fun navBackgroundPaletteAlphasMatchDesign() {
        assertEquals(0.30f, LightPalette.navBackground.alpha, 0.01f)
        assertEquals(0.38f, DarkPalette.navBackground.alpha, 0.01f)
    }

    @Test
    fun appBottomNavRendersAndSelectsTab() {
        var selectedTab by mutableStateOf(NavTab.Favorites)
        compose.setContent {
            AppTheme {
                AppBottomNav(
                    selected = selectedTab,
                    onSelect = { selectedTab = it },
                )
            }
        }
        compose.onNodeWithTag("bottom-tab-sessions").performClick()
        compose.waitForIdle()
        assertEquals(NavTab.Sessions, selectedTab)
    }
}
