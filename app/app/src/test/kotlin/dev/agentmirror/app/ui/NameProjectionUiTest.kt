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

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import dev.agentmirror.app.conn.Session
import dev.agentmirror.app.ui.components.SessionRow
import dev.agentmirror.app.ui.theme.AppTheme
import dev.agentmirror.app.workspace.FavoriteBook
import dev.agentmirror.app.workspace.FavoriteList
import dev.agentmirror.app.workspace.FavoriteRecord
import dev.agentmirror.app.workspace.MemoryFavoriteStore
import dev.agentmirror.app.workspace.UNKNOWN_SESSION_DISPLAY_NAME
import dev.agentmirror.app.workspace.favoriteKey
import dev.agentmirror.app.workspace.toL2Entry
import dev.agentmirror.app.workspace.toSessionItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** NAME-A：DTO → L2 → design row must preserve official display names and structure. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NameProjectionUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun codexOfficialNameAndPiSessionNameReachRealComposeRow() {
        val codex = Session(
            ref = "/sock-codex\u001f%0",
            name = "编码甲 — 官方完整标题",
            cwd = "/workspace",
            rows = 24,
            cols = 80,
            title = "编码甲 — 官方完整标题",
            provider = "codex",
            activity = "idle",
            status = "idle",
            windowName = "node-codex",
            windowIndex = "3",
        )
        val pi = Session(
            ref = "/sock-pi\u001f%1",
            name = "node-pi",
            cwd = "/workspace",
            rows = 24,
            cols = 80,
            provider = "pi",
            activity = "idle",
            status = "idle",
            sessionName = "π官方甲",
            windowName = "node-pi",
            windowIndex = "4",
        )
        val codexItem = codex.toL2Entry().toSessionItem(starred = false)
        val piItem = pi.toL2Entry().toSessionItem(starred = false)

        assertEquals("编码甲 — 官方完整标题", codexItem.displayName)
        assertNotEquals("node-codex", codexItem.displayName)
        assertEquals("π官方甲", piItem.displayName)
        assertNotEquals("node-pi", piItem.displayName)

        compose.setContent {
            AppTheme {
                SessionRow(codexItem, "l2", {}, {}, false)
                SessionRow(piItem, "l2", {}, {}, false)
            }
        }
        compose.onNodeWithTag("l2-row-${codexItem.id}").assertExists()
        compose.onNodeWithTag("l2-row-${piItem.id}").assertExists()
        compose.onNodeWithText("编码甲 — 官方完整标题", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("π官方甲", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("node-codex", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun onlineFavoriteUsesLiveNameAndMissingPiNameDoesNotUseStoredName() {
        val codex = Session(
            ref = "/sock-codex\u001f%0",
            name = "编码甲 — 官方完整标题",
            cwd = "/workspace",
            rows = 24,
            cols = 80,
            provider = "codex",
            activity = "idle",
            status = "idle",
            windowName = "node-codex",
            windowIndex = "3",
        ).toL2Entry()
        val stored = FavoriteRecord(
            ref = codex.ref,
            sessionName = "旧名",
            windowIndex = "3",
            windowName = "node-codex",
            cwd = codex.cwd,
            addedAt = 1L,
        )
        val book = FavoriteBook(MemoryFavoriteStore(listOf(stored)))
        val row = book.rows(listOf(codex)).single()

        assertEquals("编码甲 — 官方完整标题", row.identityLabel)
        assertEquals(codex.ref, row.ref)
        assertEquals("node-codex", row.windowName)
        compose.setContent {
            FavoriteList(listOf(row), { _, _ -> }, {})
        }
        compose.onNodeWithText("编码甲 — 官方完整标题", useUnmergedTree = true).assertExists()

        val missingPi = Session(
            ref = "/sock-pi\u001f%1",
            name = "node-pi",
            cwd = "/workspace",
            rows = 24,
            cols = 80,
            provider = "pi",
            activity = "idle",
            status = "idle",
            sessionName = null,
            windowName = "node-pi",
            windowIndex = "4",
        ).toL2Entry()
        val missingPiRow = FavoriteBook(
            MemoryFavoriteStore(
                listOf(
                    FavoriteRecord(
                        ref = missingPi.ref,
                        sessionName = "旧 Pi 名",
                        windowIndex = "4",
                        windowName = "node-pi",
                        cwd = missingPi.cwd,
                        addedAt = 2L,
                    ),
                ),
            ),
        ).rows(listOf(missingPi)).single()
        assertEquals(UNKNOWN_SESSION_DISPLAY_NAME, missingPiRow.identityLabel)
        assertNotEquals("旧 Pi 名", missingPiRow.identityLabel)
        assertNotEquals("node-pi", missingPiRow.identityLabel)
    }

    @Test
    fun emptyCodexNameIsUnknownAndRenamingKeepsRefAndFavoriteKey() {
        val emptyCodex = Session(
            ref = "/sock-codex\u001f%2",
            name = "",
            cwd = "/workspace",
            rows = 24,
            cols = 80,
            provider = "codex",
            windowName = "node-codex",
            windowIndex = "5",
            activity = "unknown",
            status = "unknown",
        ).toL2Entry()
        assertEquals(UNKNOWN_SESSION_DISPLAY_NAME, emptyCodex.identityLabel)
        assertEquals("node-codex", emptyCodex.navigationName)

        val before = Session(
            ref = "/sock-codex\u001f%3",
            name = "编码甲 | 官方完整标题",
            cwd = "/workspace",
            rows = 24,
            cols = 80,
            provider = "codex",
            windowName = "node-codex",
            windowIndex = "6",
            activity = "idle",
            status = "idle",
        ).toL2Entry()
        val after = Session(
            ref = before.ref,
            name = "审查乙 | 官方完整标题",
            cwd = before.cwd,
            rows = 24,
            cols = 80,
            provider = "codex",
            windowName = before.windowName,
            windowIndex = before.windowIndex,
            activity = "idle",
            status = "idle",
        ).toL2Entry()
        assertEquals(before.ref, after.ref)
        assertEquals(before.favoriteKey(), after.favoriteKey())
        assertNotEquals(before.identityLabel, after.identityLabel)
    }
}
