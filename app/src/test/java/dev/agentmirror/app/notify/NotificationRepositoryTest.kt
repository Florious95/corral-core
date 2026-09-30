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
            workspace = "/work/project",
            agentName = agentName,
            level = "success",
        )
    }
}
