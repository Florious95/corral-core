package dev.agentmirror.app.provider

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import dev.agentmirror.app.ui.model.SessionItem
import dev.agentmirror.app.ui.model.SessionStatus
import dev.agentmirror.app.ui.screens.FavoritesScreen
import dev.agentmirror.app.ui.screens.SessionListRows
import dev.agentmirror.app.ui.theme.AgentMirrorTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Focused real-Compose gesture flow for Provider marks and row favorite menus. */
class ProviderUiSmokeTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun providerMarksAndRowGesturesRemainTruthful() {
        val showFavorites = mutableStateOf(false)
        val opened = AtomicInteger()
        val toggled = AtomicInteger()
        val ordinary = listOf(
            item("run", SessionStatus.Busy, false, true, "claude_code", "working", "normal"),
            item("idle", SessionStatus.Idle, false, true, "claude_code", "idle", "normal"),
            item("abnormal", SessionStatus.Abnormal, false, true, "codex", "idle", "abnormal"),
            item("unknown", SessionStatus.Unknown, false, true, "pi", "future", "normal"),
        )
        val favorites = listOf(
            item("online", SessionStatus.Busy, true, true, "grok", "working", "normal"),
            item("offline", SessionStatus.Unknown, true, false, "unknown", "unknown", "unknown"),
        )

        rule.setContent {
            AgentMirrorTheme {
                if (showFavorites.value) {
                    FavoritesScreen(favorites, { opened.incrementAndGet() }, { toggled.incrementAndGet() })
                } else {
                    SessionListRows(ordinary, { opened.incrementAndGet() }, { toggled.incrementAndGet() }, tagPrefix = "prov")
                }
            }
        }

        rule.onNodeWithContentDescription("Claude Code，运行中").assertExists()
        rule.onNodeWithContentDescription("Claude Code，空闲").assertExists()
        rule.onNodeWithContentDescription("Codex，异常").assertExists()
        rule.onNodeWithContentDescription("Pi，未知").assertExists()
        rule.onNodeWithContentDescription("Codex，空闲").assertDoesNotExist()
        rule.onNodeWithContentDescription("Pi，空闲").assertDoesNotExist()

        rule.onNodeWithTag("prov-provider-run").performTouchInput { click() }
        rule.waitForIdle()
        assertEquals(0, toggled.get())
        rule.onNodeWithTag("prov-row-idle").performClick()
        rule.waitForIdle()
        assertEquals(2, opened.get())

        rule.onNodeWithTag("prov-row-run").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onAllNodesWithText("收藏").assertCountEquals(1)
        assertForbiddenActionsAbsent()
        rule.onNodeWithText("收藏").performClick()
        rule.waitForIdle()
        assertEquals(1, toggled.get())

        rule.runOnIdle { showFavorites.value = true }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Grok Code，运行中").assertExists()
        rule.onNodeWithContentDescription("未知 Provider，未知").assertExists()
        rule.onNodeWithTag("fav-row-online").performClick()
        rule.waitForIdle()
        assertEquals(3, opened.get())
        rule.onNodeWithTag("fav-row-offline").performTouchInput { click() }
        rule.waitForIdle()
        assertEquals(3, opened.get())

        rule.onNodeWithTag("fav-row-online").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onAllNodesWithText("取消收藏").assertCountEquals(1)
        assertForbiddenActionsAbsent()
        rule.onNodeWithText("取消收藏").performClick()
        rule.waitForIdle()
        assertEquals(2, toggled.get())

        rule.onNodeWithTag("fav-row-offline").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onAllNodesWithText("取消收藏").assertCountEquals(1)
        assertForbiddenActionsAbsent()
        rule.onNodeWithText("取消收藏").performClick()
        rule.waitForIdle()
        assertEquals(3, toggled.get())
    }

    private fun assertForbiddenActionsAbsent() {
        listOf("关闭会话", "销毁会话", "创建会话", "打开 Agent", "Provider 配置").forEach {
            rule.onNodeWithText(it).assertDoesNotExist()
        }
    }

    private fun item(
        id: String,
        status: SessionStatus,
        starred: Boolean,
        online: Boolean,
        provider: String,
        activity: String,
        health: String,
    ) = SessionItem(id, id, "/workspace/$id", status, starred, online, provider, activity, health)
}
