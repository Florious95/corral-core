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

import dev.agentmirror.app.diag.DiagLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** 消息中心的一行：不可变记录 + 本设备已读状态。 */
data class NotificationItem(val record: NotificationRecord, val read: Boolean) {
    val key: NotificationKey get() = record.key
}

/** null 为一级全量；二级只匹配同目录，不包含子目录或相似前缀。 */
internal fun NotificationRecord.belongsToWorkspace(workspace: String?): Boolean =
    workspace == null || normalizeWorkspacePath(workspace)?.let { it == normalizeWorkspacePath(this.workspace) } == true

private fun normalizeWorkspacePath(path: String?): String? {
    val normalized = path?.takeIf { it.isNotBlank() }?.trimEnd('/')?.ifEmpty { "/" } ?: return null
    // macOS 的已知系统目录别名；不能把任意 /private/foo 误当成 /foo。
    return when {
        normalized == "/private/tmp" || normalized.startsWith("/private/tmp/") -> normalized.removePrefix("/private")
        normalized == "/private/var" || normalized.startsWith("/private/var/") -> normalized.removePrefix("/private")
        normalized == "/private/etc" || normalized.startsWith("/private/etc/") -> normalized.removePrefix("/private")
        else -> normalized
    }
}

/**
 * 进程级通知库（契约 §9）：先落库去重，再发 UI 变化 / 系统提醒。
 *
 * 所有读写都串行在单线程 [executor] 上（首个任务就是从 [file] 加载），因此加载前到达的帧
 * 不会被加载覆盖；UI 只读 [items] / [unreadCount] 两个 StateFlow。落盘是临时文件 + rename，
 * 放在 noBackupFilesDir，最多 [maxRecords] 条，超出裁掉最旧。
 *
 * @inv 同一 `(host_id,id)` 只存一份；内容不同的同 id 视为协议损坏，保留首份且不再提醒
 * @inv 提醒 claim（alerted）与记录同存同落盘：同一条最多尝试一次系统提醒
 */
class NotificationRepository(
    private val file: File?,
    private val executor: Executor = defaultExecutor(),
    private val maxRecords: Int = MAX_RECORDS,
) {
    private class Entry(val record: NotificationRecord, var read: Boolean, var alerted: Boolean)

    private val entries = LinkedHashMap<NotificationKey, Entry>()
    private val cursors = HashMap<String, NotificationCursor>()
    private var dirty = false

    private val _items = MutableStateFlow<List<NotificationItem>>(emptyList())
    val items: StateFlow<List<NotificationItem>> = _items.asStateFlow()

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    fun itemsFor(workspace: String?): Flow<List<NotificationItem>> =
        if (workspace == null) items else items.map { rows -> rows.filter { it.record.belongsToWorkspace(workspace) } }.distinctUntilChanged()

    fun unreadCountFor(workspace: String?): Flow<Int> =
        if (workspace == null) unreadCount else itemsFor(workspace).map { rows -> rows.count { !it.read } }.distinctUntilChanged()

    /** 本地库加载完成（通知点击的冷启动路由要等它，避免把刚落盘的记录当成已裁剪）。 */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** 最近一次协商成功的通知来源 host_id（跨进程保存，冷启动时用于校验跳转目标主机）。 */
    private val _sourceHostId = MutableStateFlow<String?>(null)
    val sourceHostId: StateFlow<String?> = _sourceHostId.asStateFlow()

    init {
        executor.execute(::load)
    }

    /**
     * 实时事件入库。[eligibleForAlert] 由调用方按认证 source/stream 与 head_seq 判定；
     * 只有合格且此前没有 claim 的记录才回调 [onAlert]（在入库、落盘之后）。
     */
    fun acceptLive(record: NotificationRecord, eligibleForAlert: Boolean, onAlert: (NotificationRecord) -> Unit) {
        executor.execute {
            val entry = upsert(record) ?: return@execute
            val alert = eligibleForAlert && !entry.alerted
            if (alert) {
                entry.alerted = true
                dirty = true
            }
            commit()
            if (alert) onAlert(record)
        }
    }

    /** 历史页入库：只补消息中心，绝不触发系统提醒。落盘后才回调 [then]（再申请下一页）。 */
    fun acceptHistory(records: List<NotificationRecord>, then: () -> Unit = {}) {
        executor.execute {
            records.forEach { upsert(it) }
            commit()
            then()
        }
    }

    /** 记录协商来源并在同一串行队列里读出该主机已确认的同步游标。 */
    fun beginSync(hostId: String, block: (NotificationCursor?) -> Unit) {
        executor.execute {
            if (_sourceHostId.value != hostId) {
                _sourceHostId.value = hostId
                dirty = true
                commit()
            }
            block(cursors[hostId])
        }
    }

    /** 完整同步结束才推进游标（契约 §4.3：H 只在最后一页后提交）。 */
    fun commitCursor(hostId: String, cursor: NotificationCursor) {
        executor.execute {
            if (cursors[hostId] != cursor) {
                cursors[hostId] = cursor
                dirty = true
            }
            commit()
        }
    }

    fun markRead(key: NotificationKey) {
        executor.execute {
            val entry = entries[key] ?: return@execute
            if (!entry.read) {
                entry.read = true
                dirty = true
                commit()
            }
        }
    }

    fun markAllRead(workspace: String? = null) {
        executor.execute {
            entries.values.filter { !it.read && it.record.belongsToWorkspace(workspace) }.forEach {
                it.read = true
                dirty = true
            }
            commit()
        }
    }

    /** 入库：新记录插入；同 id 同内容幂等返回原条目；同 id 异内容返回 null（协议损坏）。 */
    private fun upsert(record: NotificationRecord): Entry? {
        val existing = entries[record.key]
        if (existing != null) {
            if (existing.record != record) {
                DiagLog.record("notify", "conflicting_record id=${record.id} seq=${record.seq}")
                return null
            }
            return existing
        }
        return Entry(record, read = false, alerted = false).also {
            entries[record.key] = it
            dirty = true
        }
    }

    private fun commit() {
        if (!dirty) return
        dirty = false
        if (entries.size > maxRecords) {
            entries.values.sortedWith(OLDEST_FIRST).take(entries.size - maxRecords)
                .forEach { entries.remove(it.record.key) }
        }
        publish()
        save()
    }

    private fun publish() {
        val sorted = entries.values.sortedWith(OLDEST_FIRST).asReversed()
        _items.value = sorted.map { NotificationItem(it.record, it.read) }
        _unreadCount.value = sorted.count { !it.read }
    }

    private fun load() {
        try {
            val f = file
            if (f != null && f.isFile) {
                val disk = NotificationCodec.json.decodeFromString(Disk.serializer(), f.readText())
                disk.entries.filter { it.record.isValid() }.forEach {
                    entries[it.record.key] = Entry(it.record, it.read, it.alerted)
                }
                cursors.putAll(disk.cursors)
                _sourceHostId.value = disk.sourceHostId
            }
        } catch (e: Exception) {
            // 损坏的本地缓存不能冒充空历史继续覆盖：挪到旁边留证，再从空库起步（服务端历史可补拉）。
            DiagLog.record("notify", "load_failed ex=${e.javaClass.simpleName}")
            file?.let { it.renameTo(File(it.parentFile, it.name + ".corrupt")) }
            entries.clear()
            cursors.clear()
        }
        publish()
        _loaded.value = true
    }

    private fun save() {
        val f = file ?: return
        try {
            f.parentFile?.mkdirs()
            val disk = Disk(
                sourceHostId = _sourceHostId.value,
                cursors = cursors.toMap(),
                entries = entries.values.map { DiskEntry(it.record, it.read, it.alerted) },
            )
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(NotificationCodec.json.encodeToString(Disk.serializer(), disk))
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        } catch (e: Exception) {
            DiagLog.record("notify", "save_failed ex=${e.javaClass.simpleName}")
        }
    }

    @Serializable
    private data class Disk(
        val version: Int = 1,
        val sourceHostId: String? = null,
        val cursors: Map<String, NotificationCursor> = emptyMap(),
        val entries: List<DiskEntry> = emptyList(),
    )

    @Serializable
    private data class DiskEntry(val record: NotificationRecord, val read: Boolean = false, val alerted: Boolean = false)

    companion object {
        /** 客户端缓存上限（契约 §9：最多 1000 条）。 */
        const val MAX_RECORDS = 1000

        /** 时间戳为固定宽度 UTC RFC3339，字典序即时间序；同刻按 seq 数值。 */
        private val OLDEST_FIRST = compareBy<Entry>({ it.record.timestamp }, { it.record.seqNumber })

        private fun defaultExecutor(): Executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "notify-store").apply { isDaemon = true }
        }
    }
}
