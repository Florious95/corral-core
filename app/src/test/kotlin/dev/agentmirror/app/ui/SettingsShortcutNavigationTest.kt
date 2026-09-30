package dev.agentmirror.app.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import dev.agentmirror.app.session.ShortcutCommand
import dev.agentmirror.app.session.SharedPreferencesShortcutCommandStore
import dev.agentmirror.app.ui.screens.SettingsScreen
import dev.agentmirror.app.ui.screens.ShortcutCommandsScreen
import dev.agentmirror.app.ui.screens.buildShortcutCommand
import dev.agentmirror.app.ui.screens.shortcutCoverageSummary
import dev.agentmirror.app.ui.theme.AppTheme
import dev.agentmirror.app.ui.theme.Appearance
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import dev.agentmirror.app.SettingsScreen as SettingsRoute

/**
 * 设置页重构：快捷命令从主页平铺改为「入口行 → 二级页」。
 * 主页只剩入口（数量 + 覆盖），增删改在二级页；返回键 / 返回钮逐级回主页；数据仍落同一个存储。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsShortcutNavigationTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val handoff = ShortcutCommand(
        id = "handoff",
        name = "交接",
        providerCommands = mapOf("pi" to "/skill:handoff ", "codex" to "\$handoff", "grok" to "/handoff"),
    )
    private val review = ShortcutCommand(
        id = "review",
        name = "代码审查",
        providerCommands = mapOf("pi" to "/skill:code-review"),
    )

    private val context get() = compose.activity.applicationContext

    private fun clearStore() {
        context.getSharedPreferences("shortcut_commands", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Before
    fun setUp() = clearStore()

    @After
    fun tearDown() = clearStore()

    @Test
    fun mainPageShowsCompactEntryInsteadOfInlineCommands() {
        var opened = 0
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
                    shortcutCommands = listOf(handoff, review),
                    onOpenShortcutCommands = { opened++ },
                )
            }
        }
        compose.onNodeWithTag("settings-shortcut-entry").assertExists()
        compose.onNodeWithTag("settings-shortcut-count", useUnmergedTree = true).assertTextEquals("2")
        compose.onNodeWithText("Pi 2 · Codex 1 · Grok 1").assertExists()
        // 命令本身与编辑表单都不在主页上
        compose.onNodeWithText("交接").assertDoesNotExist()
        compose.onNodeWithTag("shortcut-command-name").assertDoesNotExist()
        compose.onNodeWithTag("settings-shortcut-entry").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun routeOpensSubPageAddsCommandAndBackReturnsToMain() {
        var exitedSettings = 0
        compose.setContent {
            AppTheme(appearance = Appearance.Light) {
                SettingsRoute(onBack = { exitedSettings++ }, onRePair = {}, appearance = Appearance.Light)
            }
        }
        compose.onNodeWithText("尚未添加 · 在会话输入栏一键插入常用指令").assertExists()
        compose.onNodeWithTag("settings-shortcut-entry").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("shortcut-commands-screen").assertExists()
        compose.onNodeWithTag("shortcut-command-empty").assertExists()

        compose.onNodeWithTag("shortcut-command-add").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("shortcut-command-save").assertIsNotEnabled()
        compose.onNodeWithTag("shortcut-command-name").performTextInput("  交接  ")
        compose.onNodeWithTag("shortcut-command-pi").performTextInput("/skill:handoff ")
        compose.onNodeWithTag("shortcut-command-save").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("shortcut-command-editor").assertDoesNotExist()

        val saved = SharedPreferencesShortcutCommandStore(context).load()
        assertEquals(1, saved.size)
        assertEquals("交接", saved.single().name)
        assertEquals(mapOf("pi" to "/skill:handoff "), saved.single().providerCommands)
        assertTrue(saved.single().id.isNotBlank())
        compose.onNodeWithTag("shortcut-command-row-${saved.single().id}").assertExists()

        // 系统返回：先回设置主页，不离开设置
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        compose.onNodeWithTag("shortcut-commands-screen").assertDoesNotExist()
        compose.onNodeWithTag("settings-shortcut-count", useUnmergedTree = true).assertTextEquals("1")
        assertEquals(0, exitedSettings)

        // 再返回才离开设置
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        assertEquals(1, exitedSettings)
    }

    @Test
    fun subPageBackButtonReturnsToMain() {
        SharedPreferencesShortcutCommandStore(context).save(listOf(handoff))
        compose.setContent {
            AppTheme(appearance = Appearance.Dark) {
                SettingsRoute(onBack = {}, onRePair = {}, appearance = Appearance.Dark)
            }
        }
        compose.onNodeWithTag("settings-shortcut-entry").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("shortcut-command-row-handoff").assertExists()
        compose.onNodeWithTag("shortcut-commands-back").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("shortcut-commands-screen").assertDoesNotExist()
        compose.onNodeWithTag("settings-scroll").assertExists()
    }

    @Test
    fun providerFilterNarrowsListToConfiguredCommands() {
        compose.setContent {
            AppTheme(appearance = Appearance.Light) {
                ShortcutCommandsScreen(commands = listOf(handoff, review), onSave = {}, onDelete = {}, onBack = {})
            }
        }
        compose.onNodeWithTag("shortcut-filter-all").assertIsSelected()
        compose.onNodeWithTag("shortcut-command-row-review").assertExists()
        compose.onNodeWithTag("shortcut-filter-codex").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("shortcut-filter-codex").assertIsSelected()
        compose.onNodeWithTag("shortcut-command-row-handoff").assertExists()
        compose.onNodeWithTag("shortcut-command-row-review").assertDoesNotExist()
    }

    @Test
    fun editKeepsIdAndDeleteNeedsConfirmation() {
        var commands by mutableStateOf(listOf(handoff, review))
        val deleted = mutableListOf<String>()
        compose.setContent {
            AppTheme(appearance = Appearance.Light) {
                ShortcutCommandsScreen(
                    commands = commands,
                    onSave = { saved -> commands = commands.map { if (it.id == saved.id) saved else it } },
                    onDelete = { id ->
                        deleted += id
                        commands = commands.filterNot { it.id == id }
                    },
                    onBack = {},
                )
            }
        }
        compose.onNodeWithTag("shortcut-command-row-handoff").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("shortcut-command-name").performTextReplacement("交接并归档")
        compose.onNodeWithTag("shortcut-command-codex").performTextReplacement("")
        compose.onNodeWithTag("shortcut-command-save").performClick()
        compose.waitForIdle()
        assertEquals(listOf("handoff", "review"), commands.map { it.id })
        assertEquals("交接并归档", commands.first().name)
        assertEquals(mapOf("pi" to "/skill:handoff ", "grok" to "/handoff"), commands.first().providerCommands)

        compose.onNodeWithTag("shortcut-command-row-review").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("shortcut-command-delete").performClick()
        compose.waitForIdle()
        assertTrue("第一次点删除只进入确认态", deleted.isEmpty())
        compose.onNodeWithText("确认删除").assertExists()
        compose.onNodeWithTag("shortcut-command-delete").performClick()
        compose.waitForIdle()
        assertEquals(listOf("review"), deleted)
        compose.onNodeWithTag("shortcut-command-row-review").assertDoesNotExist()
    }

    private fun fieldText(tag: String): String =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    @Test
    fun syncActionCopiesExactRowTextToEveryProviderOnSave() {
        val saved = mutableListOf<ShortcutCommand>()
        compose.setContent {
            AppTheme(appearance = Appearance.Light) {
                ShortcutCommandsScreen(commands = emptyList(), onSave = { saved += it }, onDelete = {}, onBack = {})
            }
        }
        compose.onNodeWithTag("shortcut-command-add").performClick()
        compose.waitForIdle()
        // 空行不可同步（防一键清空其他 Provider）
        compose.onNodeWithTag("shortcut-command-sync-pi").assertIsNotEnabled()
        compose.onNodeWithTag("shortcut-command-name").performTextInput("交接")
        compose.onNodeWithTag("shortcut-command-codex").performTextInput("/handoff 收尾 ")
        compose.onNodeWithTag("shortcut-command-sync-pi").assertIsNotEnabled()
        compose.onNodeWithTag("shortcut-command-sync-codex").assertIsEnabled().performClick()
        compose.waitForIdle()
        // 三行一致后同步钮全部变灰（完成反馈）
        listOf("pi", "codex", "grok").forEach {
            compose.onNodeWithTag("shortcut-command-sync-$it").assertIsNotEnabled()
        }
        compose.onNodeWithTag("shortcut-command-save").performClick()
        compose.waitForIdle()

        val text = "/handoff 收尾 " // 尾随空格原样保留
        assertEquals(mapOf("pi" to text, "codex" to text, "grok" to text), saved.single().providerCommands)
    }

    @Test
    fun syncActionIsDraftOnlyAndCancelDiscardsIt() {
        val saved = mutableListOf<ShortcutCommand>()
        compose.setContent {
            AppTheme(appearance = Appearance.Dark) {
                ShortcutCommandsScreen(commands = listOf(review), onSave = { saved += it }, onDelete = {}, onBack = {})
            }
        }
        compose.onNodeWithTag("shortcut-command-row-review").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("shortcut-command-sync-pi").performClick()
        compose.waitForIdle()
        assertEquals("/skill:code-review", fieldText("shortcut-command-grok"))
        compose.onNodeWithTag("shortcut-command-cancel").performClick()
        compose.waitForIdle()
        assertTrue("取消不得保存同步草稿", saved.isEmpty())

        compose.onNodeWithTag("shortcut-command-row-review").performClick()
        compose.waitForIdle()
        assertEquals("", fieldText("shortcut-command-grok"))
        compose.onNodeWithTag("shortcut-command-sync-pi").assertIsEnabled()
    }

    @Test
    fun buildShortcutCommandValidatesAndPreservesData() {
        assertNull(buildShortcutCommand(null, "   ", mapOf("pi" to "/x"), newId = { "new" }))

        val created = buildShortcutCommand(null, " 新建 ", mapOf("pi" to "/a ", "codex" to "", "grok" to "/g"), newId = { "new" })
        assertEquals(ShortcutCommand("new", "新建", mapOf("pi" to "/a ", "grok" to "/g")), created)

        val original = ShortcutCommand("keep", "旧", mapOf("pi" to "/old", "claude_code" to "/cc"))
        val edited = buildShortcutCommand(original, "新", mapOf("pi" to "", "codex" to "\$c", "grok" to ""), newId = { error("编辑不得换 id") })
        assertEquals(ShortcutCommand("keep", "新", mapOf("claude_code" to "/cc", "codex" to "\$c")), edited)
    }

    @Test
    fun coverageSummaryCountsConfiguredProviders() {
        assertEquals("尚未添加 · 在会话输入栏一键插入常用指令", shortcutCoverageSummary(emptyList()))
        assertEquals("Pi 2 · Codex 1 · Grok 1", shortcutCoverageSummary(listOf(handoff, review)))
    }
}
