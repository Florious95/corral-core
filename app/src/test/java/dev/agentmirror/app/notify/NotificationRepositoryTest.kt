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

package dev.agentmirror.app.notify

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executor

/** 本地通知库：去重、已读、提醒 claim、裁剪与落盘恢复（契约 §9）。 */
class NotificationRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val direct = Executor { it.run() }

    private fun repo(file: File? = null, max: Int = NotificationRepository.MAX_RECORDS) =
        NotificationRepository(file, direct, max)

    @Test
    fun live_storesOnceAndAlertsOnce() {
        val r = repo()
        val alerts = mutableListOf<String>()
        r.acceptLive(record("5"), eligibleForAlert = true) { alerts += it.id }
        r.acceptLive(record("5"), eligibleForAlert = true) { alerts += it.id }
        assertEquals(1, r.items.value.size)
        assertEquals(1, r.unreadCount.value)
        assertEquals(listOf("id-5"), alerts)
    }

    @Test
    fun history_neverAlerts_butLaterLiveOfSameRecordMayAlertOnce() {
        val r = repo()
        val alerts = mutableListOf<String>()
        r.acceptHistory(listOf(record("1"), record("2")))
        assertEquals(2, r.items.value.size)
        assertTrue(alerts.isEmpty())
        // live 与 history 竞态：记录已被历史入库，live 仍有一次提醒资格。
        r.acceptLive(record("2"), eligibleForAlert = true) { alerts += it.id }
        r.acceptLive(record("2"), eligibleForAlert = true) { alerts += it.id }
        assertEquals(listOf("id-2"), alerts)
        assertEquals(2, r.items.value.size)
    }

    @Test
    fun ineligibleLive_isStoredWithoutAlert() {
        val r = repo()
        var alerted = false
        r.acceptLive(record("3"), eligibleForAlert = false) { alerted = true }
        assertFalse(alerted)
        assertEquals(1, r.items.value.size)
    }

    @Test
    fun sameIdDifferentContent_isProtocolCorruption_keepsFirstAndNoAlert() {
        val r = repo()
        var alerts = 0
        r.acceptHistory(listOf(record("4", body = "原文")))
        r.acceptLive(record("4", body = "篡改"), eligibleForAlert = true) { alerts++ }
        assertEquals("原文", r.items.value.single().record.body)
        assertEquals(0, alerts)
    }

    @Test
    fun markRead_andMarkAllRead_updateUnreadCount() {
        val r = repo()
        r.acceptHistory(listOf(record("1"), record("2"), record("3")))
        assertEquals(3, r.unreadCount.value)
        r.markRead(NotificationKey("h", "id-2"))
        assertEquals(2, r.unreadCount.value)
        assertTrue(r.items.value.single { it.key == NotificationKey("h", "id-2") }.read)
        r.markAllRead()
        assertEquals(0, r.unreadCount.value)
        assertTrue(r.items.value.all { it.read })
    }

    @Test
    fun level1_includesEveryWorkspaceAndUnlinkedMessages() = runBlocking {
        val r = repo()
        r.acceptHistory(listOf(record("1"), record("2", workspace = "/work/other"), record("3", workspace = null)))
        assertEquals(listOf("3", "2", "1"), r.itemsFor(null).first().map { it.record.seq })
        assertEquals(3, r.unreadCountFor(null).first())
        r.markAllRead(null)
        assertEquals(0, r.unreadCountFor(null).first())
        assertTrue(r.items.value.all { it.read })
    }

    @Test
    fun level2_strictDirectoryFilter_excludesChildrenPrefixesAndMissingWorkspace() = runBlocking {
        val r = repo()
        r.acceptHistory(
            listOf(
                record("1"),
                record("2", workspace = "/work/other"),
                record("3", workspace = "/work/project/child"),
                record("4", workspace = "/work/project-other"),
                record("5", workspace = null),
                record("6", workspace = ""),
                record("7", workspace = "/work/Project"),
            ),
        )
        assertEquals(listOf("1"), r.itemsFor("/work/project").first().map { it.record.seq })
        assertEquals(1, r.unreadCountFor("/work/project").first())
        assertTrue(r.itemsFor("/work/missing").first().isEmpty())
        assertEquals(0, r.unreadCountFor("/work/missing").first())
        assertTrue("empty L2 context is not the global scope", r.itemsFor("").first().isEmpty())
    }

    @Test
    fun workspaceFilter_normalizesTrailingSlashAndKnownMacAliasesOnly() = runBlocking {
        val r = repo()
        r.acceptHistory(
            listOf(
                record("1", workspace = "/private/tmp/project/"),
                record("2", workspace = "/tmp/project"),
                record("3", workspace = "/private/var/project"),
                record("4", workspace = "/private/etc/project/"),
                record("5", workspace = "/private/work/project"),
                record("6", workspace = "/tmp/project2"),
                record("7", workspace = "/"),
            ),
        )
        assertEquals(listOf("2", "1"), r.itemsFor("/tmp/project/").first().map { it.record.seq })
        assertEquals(listOf("2", "1"), r.itemsFor("/private/tmp/project").first().map { it.record.seq })
        assertEquals(listOf("3"), r.itemsFor("/var/project/").first().map { it.record.seq })
        assertEquals(listOf("4"), r.itemsFor("/etc/project").first().map { it.record.seq })
        assertTrue(r.itemsFor("/work/project").first().isEmpty())
        assertEquals(listOf("7"), r.itemsFor("/").first().map { it.record.seq })
    }

    @Test
    fun level2_markAllRead_onlyMarksMatchingWorkspaceAndPersistsIsolation() = runBlocking {
        val file = File(tmp.root, "scoped.json")
        val r = repo(file)
        r.acceptHistory(
            listOf(
                record("1", workspace = "/tmp/project"),
                record("2", workspace = "/private/tmp/project/"),
                record("3", workspace = "/tmp/other"),
                record("4", workspace = "/tmp/project/child"),
                record("5", workspace = null),
            ),
        )
        r.markAllRead("/tmp/project/")
        val restored = repo(file)
        assertEquals(0, restored.unreadCountFor("/private/tmp/project").first())
        assertEquals(1, restored.unreadCountFor("/tmp/other").first())
        assertEquals(3, restored.unreadCount.value)
        assertEquals(setOf("1", "2"), restored.items.value.filter { it.read }.map { it.record.seq }.toSet())
        restored.markAllRead("/tmp/missing")
        assertEquals(3, restored.unreadCount.value)
    }

    @Test
    fun level2_projectionsUpdateAfterLiveHistoryAndReadWithoutDroppingGlobalRecords() = runBlocking {
        val r = repo()
        val scopedItems = r.itemsFor("/work/project")
        val scopedUnread = r.unreadCountFor("/work/project")
        assertTrue(scopedItems.first().isEmpty())
        r.acceptLive(record("1"), eligibleForAlert = true) {}
        r.acceptLive(record("2", workspace = "/work/other"), eligibleForAlert = true) {}
        assertEquals(1, scopedUnread.first())
        r.acceptHistory(listOf(record("3", workspace = "/work/project/")))
        assertEquals(listOf("3", "1"), scopedItems.first().map { it.record.seq })
        assertEquals(2, scopedUnread.first())
        r.markRead(record("1").key)
        assertEquals(1, scopedUnread.first())
        assertEquals(3, r.items.value.size)
        assertEquals(2, r.unreadCount.value)
    }

    @Test
    fun items_newestFirst_seqComparedNumerically() {
        val r = repo()
        val sameInstant = "2026-10-01T01:00:00.123Z"
        r.acceptHistory(
            listOf(
                record("9", timestamp = sameInstant),
                record("10", timestamp = sameInstant),
                record("2", timestamp = "2026-09-30T23:59:59.999Z"),
                record("11", timestamp = "2026-10-01T02:00:00.000Z"),
            ),
        )
        assertEquals(listOf("11", "10", "9", "2"), r.items.value.map { it.record.seq })
    }

    @Test
    fun bounded_prunesOldestRecords() {
        val r = repo(max = 3)
        r.acceptHistory((1..5).map { record("$it", timestamp = "2026-10-01T01:00:0$it.000Z") })
        assertEquals(listOf("5", "4", "3"), r.items.value.map { it.record.seq })
    }

    @Test
    fun persistence_restoresRecordsReadStateAlertClaimCursorAndSource() {
        val file = File(tmp.root, "notifications/notifications_v1.json")
        val first = repo(file)
        first.beginSync("h") {}
        first.acceptLive(record("1", body = "第一行\n第二行\n\n  缩进"), eligibleForAlert = true) {}
        first.acceptHistory(listOf(record("2")))
        first.markRead(NotificationKey("h", "id-2"))
        first.commitCursor("h", NotificationCursor("s", "2"))
        assertTrue(file.isFile)

        val second = repo(file)
        assertTrue(second.loaded.value)
        assertEquals(listOf("2", "1"), second.items.value.map { it.record.seq })
        assertEquals("第一行\n第二行\n\n  缩进", second.items.value.single { it.key == NotificationKey("h", "id-1") }.record.body)
        assertEquals(1, second.unreadCount.value)
        assertEquals("h", second.sourceHostId.value)
        var cursor: NotificationCursor? = null
        second.beginSync("h") { cursor = it }
        assertEquals(NotificationCursor("s", "2"), cursor)
        // 提醒 claim 跨进程保留：重启后同一条 live 重放不再响。
        var alerted = false
        second.acceptLive(record("1", body = "第一行\n第二行\n\n  缩进"), eligibleForAlert = true) { alerted = true }
        assertFalse(alerted)
    }

    @Test
    fun corruptFile_startsEmptyAndKeepsEvidence() {
        val file = File(tmp.root, "n.json").apply { writeText("{not json") }
        val r = repo(file)
        assertTrue(r.loaded.value)
        assertTrue(r.items.value.isEmpty())
        assertNull(r.sourceHostId.value)
        assertTrue(File(tmp.root, "n.json.corrupt").isFile)
    }

    @Test
    fun beginSync_unknownHostHasNoCursor() {
        val r = repo()
        var cursor: NotificationCursor? = NotificationCursor("x", "1")
        r.beginSync("other") { cursor = it }
        assertNull(cursor)
        assertEquals("other", r.sourceHostId.value)
    }

    companion object {
        fun record(
            seq: String,
            body: String = "正文 $seq",
            timestamp: String = "2026-10-01T01:00:00.123Z",
            sessionRef: String? = "/tmp/tmux-501/team\u001f%3",
            hostId: String = "h",
            agentName: String? = "Sol",
            workspace: String? = "/work/project",
        ) = NotificationRecord(
            id = "id-$seq",
            hostId = hostId,
            streamId = "s",
            seq = seq,
            timestamp = timestamp,
            title = "标题 $seq",
            body = body,
            sessionRef = sessionRef,
            sessionInstance = sessionRef?.let { "inst" },
            workspace = workspace,
            agentName = agentName,
            level = "success",
        )
    }
}
