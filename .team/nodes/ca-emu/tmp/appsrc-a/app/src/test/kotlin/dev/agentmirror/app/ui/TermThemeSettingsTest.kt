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
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import dev.agentmirror.app.ui.screens.SettingsScreen
import dev.agentmirror.app.ui.screens.TermThemePickerScreen
import dev.agentmirror.app.ui.theme.AppTheme
import dev.agentmirror.app.ui.theme.Appearance
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TermThemeSettingsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun settingsShowsTerminalThemeCardAndDefaultVesper() {
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
        compose.onNodeWithText("外观").assertExists()
        compose.onNodeWithText("终端主题").assertExists()
        compose.onNodeWithText("浅色时").assertExists()
        compose.onNodeWithText("深色时").assertExists()
        compose.onAllNodesWithText("Vesper").assertCountEquals(2)
        compose.onNodeWithTag("term-theme-light-row").assertExists()
        compose.onNodeWithTag("term-theme-dark-row").assertExists()
        compose.onNodeWithText("终端正文始终保持深色，这里只切换列表、设置和外壳。").assertDoesNotExist()
    }

    @Test
    fun pickerHasSearchGroupsAndSelectingDraculaSaves() {
        var selected by mutableStateOf("vesper")
        compose.setContent {
            AppTheme(appearance = Appearance.Dark) {
                TermThemePickerScreen(
                    darkSlot = true,
                    selectedFamilyId = selected,
                    onSelect = { selected = it },
                    onBack = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("搜索主题").assertExists()
        compose.onNodeWithText("成对深浅").assertExists()
        compose.onNodeWithTag("term-theme-picker-list")
            .performScrollToNode(hasText("Nord"))
        compose.onNodeWithText("Nord").assertExists()
        compose.onNodeWithTag("term-theme-picker-list")
            .performScrollToNode(hasText("Dracula"))
        compose.onNodeWithText("Dracula").performClick()
        compose.waitForIdle()
        assertEquals("dracula", selected)
    }

    @Test
    fun pickerSearchFiltersToTitleContains() {
        compose.setContent {
            AppTheme(appearance = Appearance.Dark) {
                TermThemePickerScreen(
                    darkSlot = true,
                    selectedFamilyId = "vesper",
                    onSelect = {},
                    onBack = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("term-theme-search-input").performTextInput("Nord")
        compose.waitForIdle()
        compose.onNodeWithTag("term-theme-family-nord").assertExists()
        compose.onNodeWithTag("term-theme-family-dracula").assertDoesNotExist()
        compose.onNodeWithTag("term-theme-family-vesper").assertDoesNotExist()
    }

    @Test
    fun pickerMarksSelectedFamily() {
        compose.setContent {
            AppTheme(appearance = Appearance.Dark) {
                TermThemePickerScreen(
                    darkSlot = true,
                    selectedFamilyId = "vesper",
                    onSelect = {},
                    onBack = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("term-theme-picker-list")
            .performScrollToNode(hasText("Vesper"))
        compose.onNodeWithTag("term-theme-family-vesper").assertIsSelected()
    }
}
