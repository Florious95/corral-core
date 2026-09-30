/*
 * Copyright 2026 AgentMirror Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.agentmirror.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.agentmirror.app.ui.components.AppBottomNav
import dev.agentmirror.app.ui.model.NavTab
import dev.agentmirror.app.ui.screens.SettingsScreen
import dev.agentmirror.app.ui.theme.AppTheme
import dev.agentmirror.app.ui.theme.Appearance
import dev.agentmirror.app.ui.theme.DarkPalette
import dev.agentmirror.app.ui.theme.Dims
import dev.agentmirror.app.ui.theme.LightPalette
import dev.agentmirror.app.ui.theme.LocalAppearance
import dev.agentmirror.app.ui.theme.LocalAppPalette
import dev.agentmirror.app.ui.theme.Motion
import dev.agentmirror.app.ui.theme.TerminalPaletteDark
import dev.agentmirror.app.ui.theme.TerminalPaletteLight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * t.set 落位：设置页四项含「外观」、底栏 3b token、外观接到 AppTheme。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LandSettingsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun landSettingsTokensMatchDesignHandoff() {
        assertEquals(60, Dims.navBarHeight.value.toInt())
        assertEquals(44, Dims.navRailWidth.value.toInt())
        assertEquals(2, Dims.navRailThickness.value.toInt())
        assertEquals(5, Dims.navIconLabelGap.value.toInt())
        assertEquals(12, Dims.cardGap.value.toInt())
        assertEquals(300, Motion.pushEnter)
        assertEquals(260, Motion.popEnter)
        assertEquals(300, Motion.fadeThrough)
        assertEquals(320, Motion.navRail)
        assertEquals(0.28f, Motion.pushOffsetFraction)
        assertEquals(0.22f, Motion.popOffsetFraction)
        assertEquals(Color(0xFFF4F5F8), LightPalette.screenBackground)
        assertEquals(Color(0xFF070B14), DarkPalette.screenBackground)
        assertEquals(Color(0xFF0B57D0), LightPalette.navRail)
        assertEquals(Color(0xFF0B57D0), LightPalette.navActive)
        assertNotEquals(LightPalette.screenBackground, DarkPalette.screenBackground)
        assertNotEquals(TerminalPaletteLight.background, TerminalPaletteDark.background)
        assertEquals(
            setOf(Appearance.Light, Appearance.Dark, Appearance.System),
            Appearance.entries.toSet(),
        )
    }

    @Test
    fun landSettingsRendersFourSectionsIncludingAppearance() {
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
        compose.onNodeWithText("主机配对").assertExists()
        compose.onNodeWithText("字体大小").assertExists()
        compose.onNodeWithText("诊断日志").assertExists()
        compose.onNodeWithText("外观").assertExists()
        compose.onNodeWithText("浅色").assertExists()
        compose.onNodeWithText("深色").assertExists()
        compose.onAllNodesWithText("跟随系统").assertCountEquals(2)
        compose.onNodeWithText("PAIRED").assertExists()
        compose.onNodeWithTag("settings-scroll").assertExists()
    }

    @Test
    fun landSettingsAppearanceSwitchChangesPageAndTerminalPalette() {
        var appearance by mutableStateOf(Appearance.Light)
        var pageBg: Color? = null
        var termBg: Color? = null
        compose.setContent {
            AppTheme(appearance = appearance) {
                pageBg = LocalAppPalette.current.screenBackground
                termBg = if (LocalAppPalette.current === DarkPalette) {
                    TerminalPaletteDark.background
                } else {
                    TerminalPaletteLight.background
                }
                SettingsScreen(
                    paired = false,
                    terminalFontSize = 14,
                    appearance = appearance,
                    buildLabel = "0.1.0",
                    onRepair = {},
                    onFontSizeChange = {},
                    onAppearanceChange = { appearance = it },
                    onExportLogs = {},
                    onViewLogs = {},
                )
            }
        }
        compose.waitForIdle()
        val lightPage = pageBg
        val lightTerm = termBg
        assertEquals(LightPalette.screenBackground, lightPage)
        assertEquals(TerminalPaletteLight.background, lightTerm)
        compose.onNodeWithText("深色").performClick()
        compose.waitForIdle()
        assertEquals(Appearance.Dark, appearance)
        assertEquals(DarkPalette.screenBackground, pageBg)
        assertEquals(TerminalPaletteDark.background, termBg)
        assertNotEquals(lightPage, pageBg)
        assertNotEquals(lightTerm, termBg)
    }

    @Test
    fun landSettingsNestedAppThemeInheritsForcedAppearance() {
        var inner: Appearance? = null
        compose.setContent {
            AppTheme(appearance = Appearance.Dark) {
                AppTheme {
                    inner = LocalAppearance.current
                }
            }
        }
        compose.waitForIdle()
        assertEquals(Appearance.Dark, inner)
    }

    @Test
    fun landSettingsBottomNavIsThreeBRailTabs() {
        compose.setContent {
            AppTheme(appearance = Appearance.Light) {
                AppBottomNav(selected = NavTab.Settings, onSelect = {})
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("bottom-tabs").assertExists()
        compose.onNodeWithTag("bottom-tab-favorites").assertExists()
        compose.onNodeWithTag("bottom-tab-sessions").assertExists()
        compose.onNodeWithTag("bottom-tab-settings").assertExists()
        compose.onNodeWithText("收藏").assertExists()
        compose.onNodeWithText("会话").assertExists()
        compose.onNodeWithText("设置").assertExists()
    }
}
