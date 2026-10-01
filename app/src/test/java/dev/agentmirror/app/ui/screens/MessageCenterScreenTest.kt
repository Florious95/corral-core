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

package dev.agentmirror.app.ui.screens

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.agentmirror.app.notify.NotificationItem
import dev.agentmirror.app.notify.NotificationKey
import dev.agentmirror.app.notify.NotificationLevel
import dev.agentmirror.app.notify.NotificationRecord
import dev.agentmirror.app.notify.NotificationRepositoryTest.Companion.record
import dev.agentmirror.app.notify.NotificationSupport
import dev.agentmirror.app.ui.theme.AppTheme
import dev.agentmirror.app.ui.theme.Appearance
import dev.agentmirror.app.ui.theme.ThemeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset

/** 消息中心：全文零截断、级别、进入终端 / 标已读回调，两套风格都能渲染。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class MessageCenterScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val longBody = "第一行：验收通过\n第二行：报告已落盘\n\n" + (1..60).joinToString("\n") { "明细第 $it 行" }

    private fun render(
        items: List<NotificationItem>,
        themeId: ThemeId = ThemeId.LiquidGlass,
        sourceHostId: String? = "h",
        support: NotificationSupport = NotificationSupport.Supported,
        onOpen: (NotificationItem) -> Unit = {},
        onRead: (NotificationKey) -> Unit = {},
        onAllRead: () -> Unit = {},
        providerOf: (NotificationRecord) -> String? = { null },
    ) {
        compose.setContent {
            AppTheme(appearance = Appearance.Light, themeId = themeId) {
                MessageCenterScreen(
                    items = items,
                    sourceHostId = sourceHostId,
                    support = support,
                    onBack = {},
                    onOpenSession = onOpen,
                    onMarkRead = onRead,
                    onMarkAllRead = onAllRead,
                    providerOf = providerOf,
                )
            }
        }
    }

    @Test
    fun card_showsFullUntruncatedBodyTitleAgentAndLevel() {
        render(listOf(NotificationItem(record("1", body = longBody), read = false)))
        compose.onNodeWithTag("message-body-id-1", useUnmergedTree = true).assertExists()
        compose.onNodeWithText(longBody).assertExists()
        compose.onNodeWithText("标题 1").assertExists()
        compose.onNodeWithText("Sol").assertExists()
        compose.onNodeWithTag("message-level-success", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("message-unread-id-1", useUnmergedTree = true).assertExists()
    }

    @Test
    fun linkedCard_wholeCardTapAndEnterTerminal_bothOpenSession() {
        val opened = mutableListOf<String>()
        val read = mutableListOf<NotificationKey>()
        render(
            listOf(NotificationItem(record("1"), read = false)),
            onOpen = { opened += it.record.id },
            onRead = { read += it },
        )
        compose.onNodeWithText("进入终端").performClick()
        assertEquals(listOf("id-1"), opened)
        // 整卡可点：点标题 / 正文都直达该 Agent 终端（标已读由打开回调负责）。
        compose.onNodeWithText("标题 1").performClick()
        compose.onNodeWithText("正文 1").performClick()
        assertEquals(listOf("id-1", "id-1", "id-1"), opened)
        assertEquals(emptyList<NotificationKey>(), read)
    }

    @Test
    fun unlinkedCardTap_onlyMarksRead() {
        val opened = mutableListOf<String>()
        val read = mutableListOf<NotificationKey>()
        render(
            listOf(NotificationItem(record("1", sessionRef = null), read = false)),
            onOpen = { opened += it.record.id },
            onRead = { read += it },
        )
        compose.onNodeWithText("标题 1").performClick()
        assertEquals(emptyList<String>(), opened)
        assertEquals(listOf(NotificationKey("h", "id-1")), read)
    }

    @Test
    fun agentTile_brandMarkForKnownProvider_terminalGlyphOtherwise_withWorkspaceChip() {
        render(
            listOf(
                NotificationItem(record("1"), read = false),
                NotificationItem(record("2", agentName = "Sol"), read = true),
            ),
            providerOf = { if (it.id == "id-1") "codex" else null },
        )
        compose.onNodeWithTag("message-agent-icon-id-1", useUnmergedTree = true).assertContentDescriptionEquals("Codex")
        compose.onNodeWithTag("message-agent-icon-id-2", useUnmergedTree = true).assertContentDescriptionEquals("Agent")
        compose.onNodeWithTag("message-workspace-id-1", useUnmergedTree = true).assertExists()
        compose.onAllNodesWithText("/work/project", useUnmergedTree = true).assertCountEquals(2)
        // 首字白方块已移除：卡头不再出现 Agent 名首字单独成块。
        compose.onAllNodesWithText("S", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun providerInference_matchesWholeWordsOnly() {
        assertEquals("codex", inferProvider("Codex · 验收"))
        assertEquals("claude_code", inferProvider("Claude Code"))
        assertEquals("pi", inferProvider("pi-dev"))
        assertNull(inferProvider("pipeline"))
        assertNull(inferProvider("Sol"))
        assertNull(inferProvider(null))
    }

    @Test
    fun unlinkedRecord_hasNoTerminalButton() {
        render(listOf(NotificationItem(record("1", sessionRef = null), read = true)))
        compose.onNodeWithText("标题 1").assertExists()
        compose.onNodeWithTag("message-open-session-id-1", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("来自其他主机，无法直达终端").assertDoesNotExist()
    }

    @Test
    fun otherHostRecord_explainsWhyItCannotOpen() {
        render(listOf(NotificationItem(record("2", hostId = "other"), read = true)))
        compose.onNodeWithTag("message-open-session-id-2", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("来自其他主机，无法直达终端").assertExists()
    }

    @Test
    fun markAll_disabledWhenEverythingRead_andEmptyStateShown() {
        render(emptyList())
        compose.onNodeWithTag("message-center-empty").assertExists()
        compose.onNodeWithText("暂无消息").assertExists()
        compose.onNodeWithTag("message-center-mark-all").assertIsNotEnabled()
    }

    @Test
    fun modernistTheme_rendersSameContent() {
        var allRead = 0
        render(
            listOf(NotificationItem(record("1", body = longBody), read = false)),
            themeId = ThemeId.Modernist,
            support = NotificationSupport.Unsupported,
            onAllRead = { allRead++ },
        )
        compose.onNodeWithText(longBody).assertExists()
        compose.onNodeWithText("进入终端").assertExists()
        compose.onNodeWithTag("message-center-unsupported").assertExists()
        compose.onNodeWithTag("message-center-mark-all").performClick()
        assertEquals(1, allRead)
    }

    @Test
    fun bell_showsCappedUnreadBadge() {
        var clicks = 0
        compose.setContent {
            AppTheme(appearance = Appearance.Light, themeId = ThemeId.LiquidGlass) {
                MessageBellButton(unreadCount = 120, onClick = { clicks++ })
            }
        }
        compose.onNodeWithText("99+").assertExists()
        compose.onNodeWithTag("message-center-badge").assertExists()
        compose.onNodeWithTag("message-center-bell").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun levelLabelsAndColorsAreDistinct() {
        assertEquals(listOf("信息", "完成", "警告", "错误"), NotificationLevel.entries.map(::levelLabel))
        assertEquals(4, NotificationLevel.entries.map(::levelColor).toSet().size)
    }

    @Test
    fun timeFormatting_relativeTodayYesterdayAndOlder() {
        val now = java.time.Instant.parse("2026-10-01T12:00:00.000Z").toEpochMilli()
        val utc = ZoneOffset.UTC
        assertEquals("刚刚", formatNotificationTime("2026-10-01T11:59:30.000Z", now, utc))
        assertEquals("5 分钟前", formatNotificationTime("2026-10-01T11:55:00.000Z", now, utc))
        assertEquals("09:05", formatNotificationTime("2026-10-01T09:05:00.000Z", now, utc))
        assertEquals("昨天 23:10", formatNotificationTime("2026-09-30T23:10:00.000Z", now, utc))
        assertEquals("9月2日 08:00", formatNotificationTime("2026-09-02T08:00:00.000Z", now, utc))
        assertEquals("2025年12月31日", formatNotificationTime("2025-12-31T08:00:00.000Z", now, utc))
        assertEquals("not-a-time", formatNotificationTime("not-a-time", now, utc))
    }
}
