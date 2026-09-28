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

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.agentmirror.app.conn.ResizeFrame
import dev.agentmirror.app.conn.SubscribeFrame
import dev.agentmirror.app.session.CommandInputBar
import dev.agentmirror.app.session.OverlayTestHarness
import dev.agentmirror.app.session.SessionDockTheme
import dev.agentmirror.app.session.SessionScreen
import dev.agentmirror.app.session.ShortcutCommand
import dev.agentmirror.app.session.ShortcutGlassFloatingMenu
import dev.agentmirror.app.ui.components.SessionRow
import dev.agentmirror.app.ui.model.SessionItem
import dev.agentmirror.app.ui.model.SessionStatus
import dev.agentmirror.app.ui.screens.SettingsScreen
import dev.agentmirror.app.ui.theme.AgentMirrorTheme
import dev.agentmirror.app.ui.theme.AppPalette
import dev.agentmirror.app.ui.theme.AppTheme
import dev.agentmirror.app.ui.theme.Appearance
import dev.agentmirror.app.ui.theme.LightPalette
import dev.agentmirror.app.ui.theme.LocalAppPalette
import dev.agentmirror.app.ui.theme.LocalThemeSuite
import dev.agentmirror.app.ui.theme.ModernistDarkPalette
import dev.agentmirror.app.ui.theme.ModernistLightPalette
import dev.agentmirror.app.ui.theme.SharedPreferencesAppearanceStore
import dev.agentmirror.app.ui.theme.SharedPreferencesThemeSuiteStore
import dev.agentmirror.app.ui.theme.ThemeId
import dev.agentmirror.app.ui.theme.modernistLightScheme
import dev.agentmirror.app.ui.theme.modernistShapes
import dev.agentmirror.app.ui.theme.rootDarkColorScheme
import dev.agentmirror.app.ui.theme.rootLightColorScheme
import dev.agentmirror.app.ui.theme.rootShapes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Theme Kit 接线：独立持久化、根部提供 / 嵌套继承、热切换不丢状态、设置页即时切换，
 * 以及现代主义下四条安卓原生交互红线（长按弹层、多行输入、快捷钮几何、选快捷命令不收键盘）与 dock / PTY 几何不变。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeSuiteUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun themePreferenceIsDedicatedAndIndependentOfAppearance() {
        val themePrefs = context.getSharedPreferences("app_theme_suite", Context.MODE_PRIVATE)
        val appearancePrefs = context.getSharedPreferences("app_appearance", Context.MODE_PRIVATE)
        themePrefs.edit().clear().commit()
        appearancePrefs.edit().clear().commit()

        assertEquals(ThemeId.LiquidGlass, SharedPreferencesThemeSuiteStore(context).load())

        SharedPreferencesThemeSuiteStore(context).save(ThemeId.Modernist)
        assertEquals(ThemeId.Modernist, SharedPreferencesThemeSuiteStore(context).load())
        assertEquals("风格偏好不得写进外观偏好", emptyMap<String, Any?>(), appearancePrefs.all)

        SharedPreferencesAppearanceStore(context).save(Appearance.Dark)
        assertEquals(ThemeId.Modernist, SharedPreferencesThemeSuiteStore(context).load())
        assertEquals(Appearance.Dark, SharedPreferencesAppearanceStore(context).load())
        assertEquals(setOf("theme_id"), themePrefs.all.keys)

        // 未知值安全回退默认，且不改写存储的原值。
        themePrefs.edit().putString("theme_id", "neon").commit()
        assertEquals(ThemeId.LiquidGlass, SharedPreferencesThemeSuiteStore(context).load())
        assertEquals("neon", themePrefs.getString("theme_id", null))
    }

    @Test
    fun appThemeProvidesSuiteAndNestedAppThemeInheritsIt() {
        var outer: AppPalette? = null
        var nested: AppPalette? = null
        var nestedDark: AppPalette? = null
        var nestedId: ThemeId? = null
        var rootScheme: ColorScheme? = null
        var rootShapesSeen: Shapes? = null
        compose.setContent {
            AppTheme(appearance = Appearance.Light, themeId = ThemeId.Modernist) {
                outer = LocalAppPalette.current
                AppTheme {
                    nested = LocalAppPalette.current
                    nestedId = LocalThemeSuite.current.id
                }
                AppTheme(appearance = Appearance.Dark) {
                    nestedDark = LocalAppPalette.current
                }
                AgentMirrorTheme(darkTheme = false) {
                    rootScheme = MaterialTheme.colorScheme
                    rootShapesSeen = MaterialTheme.shapes
                }
            }
        }
        compose.waitForIdle()
        assertSame(ModernistLightPalette, outer)
        assertSame(ModernistLightPalette, nested)
        assertEquals(ThemeId.Modernist, nestedId)
        assertSame("嵌套只改外观时保留所选风格", ModernistDarkPalette, nestedDark)
        assertSame(modernistLightScheme, rootScheme)
        assertSame(modernistShapes, rootShapesSeen)
    }

    @Test
    fun liquidGlassRootThemeKeepsBaselineMaterialProviders() {
        var inside: ColorScheme? = null
        var insideShapes: Shapes? = null
        var standalone: ColorScheme? = null
        compose.setContent {
            AppTheme(appearance = Appearance.Light, themeId = ThemeId.LiquidGlass) {
                AgentMirrorTheme(darkTheme = false) {
                    inside = MaterialTheme.colorScheme
                    insideShapes = MaterialTheme.shapes
                }
            }
            AgentMirrorTheme(darkTheme = true) { standalone = MaterialTheme.colorScheme }
        }
        compose.waitForIdle()
        assertSame(rootLightColorScheme, inside)
        assertSame(rootShapes, insideShapes)
        assertSame(rootDarkColorScheme, standalone)
    }

    @Test
    fun hotSwitchRecolorsWithoutRecreatingCompositionState() {
        var themeId by mutableStateOf(ThemeId.LiquidGlass)
        val tokens = mutableListOf<Any>()
        val palettes = mutableListOf<AppPalette>()
        compose.setContent {
            AppTheme(appearance = Appearance.Light, themeId = themeId) {
                val token = remember { Any() }
                tokens += token
                palettes += LocalAppPalette.current
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { themeId = ThemeId.Modernist }
        compose.waitForIdle()
        compose.runOnIdle { themeId = ThemeId.LiquidGlass }
        compose.waitForIdle()
        assertTrue(tokens.size >= 3)
        assertEquals("换风格不得重建组合状态", 1, tokens.toSet().size)
        assertSame(LightPalette, palettes.first())
        assertTrue(palettes.any { it === ModernistLightPalette })
        assertSame("A→B→A 回到同一份液态玻璃色板", LightPalette, palettes.last())
    }

    @Test
    fun settingsThemePickerListsRegistryOptionsAndSwitchesInstantly() {
        var themeId by mutableStateOf(ThemeId.LiquidGlass)
        var pageBg: Any? = null
        compose.setContent {
            AppTheme(appearance = Appearance.Light, themeId = themeId) {
                pageBg = LocalAppPalette.current.screenBackground
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
                    themeId = themeId,
                    onThemeChange = { themeId = it },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("界面风格").performScrollTo()
        compose.onNodeWithText("液态玻璃").assertExists()
        compose.onNodeWithText("现代主义").assertExists()
        compose.onNodeWithTag("theme-suite-option-liquid_glass").assertIsSelected()
        compose.onNodeWithTag("theme-suite-option-modernist").assertIsNotSelected()
        // 「外观」仍是独立的三段式，风格选项不挤占它。
        compose.onAllNodesWithText("跟随系统").assertCountEquals(2)

        compose.onNodeWithTag("theme-suite-option-modernist").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(ThemeId.Modernist, themeId)
        assertEquals(ModernistLightPalette.screenBackground, pageBg)
        compose.onNodeWithTag("theme-suite-option-modernist").assertIsSelected()
        compose.onAllNodesWithText("跟随系统").assertCountEquals(2)

        compose.onNodeWithTag("theme-suite-option-liquid_glass").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(ThemeId.LiquidGlass, themeId)
        assertEquals(LightPalette.screenBackground, pageBg)
    }

    @Test
    fun modernistSessionRowKeepsTapAndLongPressActionSheet() {
        var opened = 0
        val item = SessionItem(
            id = "s-1",
            displayName = "代码审查助手",
            path = "/Users/workspace/project-corral",
            provider = "grok",
            status = SessionStatus.Busy,
            health = "normal",
            isOnline = true,
            starred = true,
        )
        compose.setContent {
            AppTheme(themeId = ThemeId.Modernist) {
                SessionRow(
                    item = item,
                    tagPrefix = "l2",
                    onClick = { opened++ },
                    onToggleFavorite = {},
                    unfavoriteOnly = false,
                    onCloseSession = {},
                )
            }
        }
        compose.onNodeWithTag("l2-row-s-1").performClick()
        assertEquals(1, opened)
        compose.onNodeWithTag("session-action-bottom-sheet").assertDoesNotExist()
        compose.onNodeWithTag("l2-row-s-1").performTouchInput { longClick() }
        compose.onNodeWithTag("session-action-bottom-sheet").assertExists()
        compose.onNodeWithTag("l2-favorite-action").assertExists()
        assertEquals("长按只开弹层，不触发打开", 1, opened)
    }

    @Test
    fun modernistShortcutKeepsGeometryMultilineEditorAndKeyboardFocus() {
        var themeId by mutableStateOf(ThemeId.Modernist)
        var value by mutableStateOf(TextFieldValue(""))
        var menuOpen by mutableStateOf(false)
        var sends = 0
        val command = ShortcutCommand(
            id = "handoff",
            name = "交接",
            providerCommands = mapOf("pi" to "/skill:handoff "),
        )
        compose.setContent {
            AppTheme(appearance = Appearance.Light, themeId = themeId) {
                SessionDockTheme(dark = false) {
                    Box(Modifier.fillMaxSize()) {
                        CommandInputBar(
                            value = value,
                            onValueChange = { value = it },
                            onSendText = { sends++ },
                            onPickAttachment = {},
                            onShortcutMenuOpenChange = { menuOpen = it },
                        )
                        ShortcutGlassFloatingMenu(
                            expanded = menuOpen,
                            commands = listOf(command),
                            onDismissRequest = { menuOpen = false },
                            onSelect = { value = TextFieldValue("/skill:handoff ") },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("session-command-editor").performClick()
        compose.onNodeWithTag("session-command-editor").assertIsFocused()

        val attach = compose.onNodeWithTag("session-attach-button").getBoundsInRoot()
        val shortcut = compose.onNodeWithTag("session-shortcut-button").getBoundsInRoot()
        assertEquals("快捷钮叠在加号槽上方 36dp", (attach.top - 36.dp).value, shortcut.top.value, 0.5f)
        assertEquals(attach.left.value, shortcut.left.value, 0.5f)
        val expanded = compose.onNodeWithTag("session-command-input-field").getBoundsInRoot()
        assertEquals("聚焦展开高度 = 20 × 3 + 12", 72f, (expanded.bottom - expanded.top).value, 0.5f)

        // 热切回液态玻璃：几何一致（风格只换外壳）。
        compose.runOnIdle { themeId = ThemeId.LiquidGlass }
        compose.waitForIdle()
        assertEquals(shortcut, compose.onNodeWithTag("session-shortcut-button").getBoundsInRoot())
        assertEquals(expanded, compose.onNodeWithTag("session-command-input-field").getBoundsInRoot())
        compose.runOnIdle { themeId = ThemeId.Modernist }
        compose.waitForIdle()

        compose.onNodeWithTag("session-shortcut-button").performClick()
        compose.onNodeWithTag("session-shortcut-menu").assertExists()
        compose.onNodeWithText("交接").performClick()
        compose.waitForIdle()
        assertEquals("/skill:handoff ", value.text)
        assertEquals("选快捷命令不得发送", 0, sends)
        compose.onNodeWithTag("session-shortcut-menu").assertDoesNotExist()
        compose.onNodeWithTag("session-command-editor").assertIsFocused()
    }

    @Test
    fun hotSwitchInSessionKeepsTerminalAndDockGeometryAndSendsNoResize() {
        val h = OverlayTestHarness()
        var themeId by mutableStateOf(ThemeId.LiquidGlass)
        compose.setContent {
            AppTheme(appearance = Appearance.Light, themeId = themeId) {
                SessionScreen(viewModel = h.vm, name = "sess", onBack = {})
            }
        }
        compose.waitForIdle()
        val tags = listOf(
            "session-terminal-card",
            "session-dock-hotkeys",
            "hotkey-Esc",
            "hotkey-Ctrl-C",
            "session-command-input",
            "session-attach-button",
            "session-send-button",
        )
        fun bounds(): List<DpRect> = tags.map { compose.onNodeWithTag(it).getBoundsInRoot() }
        fun geometryFrames() = h.sent().filter { it is ResizeFrame || it is SubscribeFrame }

        val baseline = bounds()
        val baselineFrames = geometryFrames()
        compose.runOnIdle { themeId = ThemeId.Modernist }
        compose.waitForIdle()
        assertEquals("换风格不得移动终端卡或 dock", baseline, bounds())
        assertEquals("换风格不得触发 resize / 重订阅", baselineFrames, geometryFrames())
        compose.runOnIdle { themeId = ThemeId.LiquidGlass }
        compose.waitForIdle()
        assertEquals(baseline, bounds())
        assertEquals(baselineFrames, geometryFrames())
    }
}
