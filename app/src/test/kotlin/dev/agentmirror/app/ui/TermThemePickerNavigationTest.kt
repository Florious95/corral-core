package dev.agentmirror.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import dev.agentmirror.app.ui.theme.AppTheme
import dev.agentmirror.app.ui.theme.Appearance
import dev.agentmirror.app.ui.theme.SharedPreferencesTermThemeStore
import dev.agentmirror.app.ui.theme.TermPalette
import dev.agentmirror.app.ui.theme.TermSchemeCatalog
import dev.agentmirror.app.ui.theme.TermThemeSelection
import dev.agentmirror.app.ui.theme.TermThemeStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import dev.agentmirror.app.SettingsScreen as SettingsRoute

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TermThemePickerNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var exitedSettings = 0
    private val context get() = compose.activity.applicationContext

    @Before
    fun setUp() {
        clearStore()
    }

    @After
    fun tearDown() {
        clearStore()
    }

    private fun clearStore() {
        context.getSharedPreferences(TermThemeStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        TermPalette.resetBindingForTest()
    }

    private fun openPicker(dark: Boolean) {
        val appearance = if (dark) Appearance.Dark else Appearance.Light
        compose.setContent {
            AppTheme(appearance = appearance) {
                SettingsRoute(
                    onBack = { exitedSettings++ },
                    onRePair = {},
                    appearance = appearance,
                )
            }
        }
        compose.onNodeWithTag(if (dark) "term-theme-dark-row" else "term-theme-light-row")
            .performClick()
        compose.waitForIdle()
        assertPreview("vesper", dark)
    }

    private fun selectAndAssert(id: String, dark: Boolean) {
        val tag = "term-theme-family-$id"
        compose.onNodeWithTag("term-theme-picker-list").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("term-theme-picker-list").assertExists()
        compose.onNodeWithTag("term-theme-light-row").assertDoesNotExist()
        compose.onNodeWithTag(tag).assertIsSelected()
        val expected = if (dark) TermThemeSelection("vesper", id) else TermThemeSelection(id, "vesper")
        assertEquals(expected, SharedPreferencesTermThemeStore(context).load())
        val family = TermSchemeCatalog.families.single { it.id == id }
        assertEquals(if (dark) family.darkSource else family.lightSource, TermPalette.of(dark).source)
        assertPreview(id, dark)
        assertEquals(0, exitedSettings)
    }

    private fun assertPreview(id: String, dark: Boolean) {
        val family = TermSchemeCatalog.families.single { it.id == id }
        val colors = TermSchemeCatalog.colors(if (dark) family.darkSource else family.lightSource)
        val bounds = compose.onNodeWithText("claim-leader --team wiki-team")
            .fetchSemanticsNode().boundsInWindow
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        // Native Robolectric draws synchronously; PixelCopy's screenshot callback does not run here.
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        val background = bitmap.getPixel(bounds.left.toInt() + 1, bounds.top.toInt() + 1)
        bitmap.recycle()
        assertEquals("preview background for $id/$dark", colors.background, background)
    }

    @Test
    fun lightSlotAllowsContinuousSelectionAndLivePreviewWithoutLeaving() {
        openPicker(dark = false)
        listOf("solarized", "tokyo-night", "follow-system").forEach {
            selectAndAssert(it, dark = false)
        }
    }

    @Test
    fun darkSlotAllowsContinuousSelectionAndLivePreviewWithoutLeaving() {
        openPicker(dark = true)
        listOf("catppuccin", "rose-pine", "github").forEach {
            selectAndAssert(it, dark = true)
        }
    }

    @Test
    fun selectionPreservesSearchForFurtherComparisons() {
        openPicker(dark = false)
        compose.onNodeWithTag("term-theme-search-input").performTextInput("Night")
        selectAndAssert("tokyo-night", dark = false)
        compose.onNodeWithTag("term-theme-search-input").assertTextEquals("Night")
        selectAndAssert("night-owl", dark = false)
        compose.onNodeWithTag("term-theme-search-input").assertTextEquals("Night")
    }

    @Test
    fun backArrowReturnsToSettingsAndReopeningKeepsSavedSelection() {
        openPicker(dark = true)
        selectAndAssert("catppuccin", dark = true)
        compose.onNodeWithTag("term-theme-picker-back").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("term-theme-picker-list").assertDoesNotExist()
        compose.onNodeWithTag("term-theme-dark-row").assertExists()
        assertEquals(0, exitedSettings)
        compose.onNodeWithTag("term-theme-dark-row").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("term-theme-picker-list")
            .performScrollToNode(hasTestTag("term-theme-family-catppuccin"))
        compose.onNodeWithTag("term-theme-family-catppuccin").assertIsSelected()
        assertPreview("catppuccin", dark = true)
    }

    @Test
    fun systemBackReturnsOneLevelAfterSelection() {
        openPicker(dark = false)
        selectAndAssert("solarized", dark = false)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag("term-theme-picker-list").assertDoesNotExist()
        compose.onNodeWithTag("term-theme-light-row").assertExists()
        assertEquals(0, exitedSettings)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(1, exitedSettings)
    }
}
