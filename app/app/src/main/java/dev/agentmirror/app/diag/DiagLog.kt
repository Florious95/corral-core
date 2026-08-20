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

package dev.agentmirror.app.diag

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 诊断日志进程级入口（feat-diagnostic-log-export）：环形缓冲 + 写入点脱敏 + 落盘导出。
 *
 * 使用（调用点在 tsnet/conn/session/termview/ui，见各包 @consumes 声明）：
 * - 事件驱动记录：[DiagLog.record(tag, msg)]——内存环形缓冲覆盖最旧（有界），零线程；
 * - 凭据注册：[DiagLog.registerSecret(value)] 在配对 token / TS authkey 注入点调用，
 *   之后任何 record 在落缓冲前就替换为 [REDACTED]（写入点脱敏，不是导出时过滤）；
 * - 导出：[DiagLog.exportTo(file)] 把环形缓冲按时间序倾倒到文件（磁盘有界，
 *   超限截断最旧行）；设置页一键导出消费它（系统分享 / SAF 另存）。
 *
 * 线程安全：公开方法可任意线程调用（内部 [ReentrantLock] 串行化；[record] 本身是
 * 现有事件流的轻量追加，持有锁临界区最小）。**本对象零定时器零线程**——记录只发生在
 * 调用方事件流里，空闲时零 CPU（静默经济红线）。
 *
 * 时钟：默认 [Clock] 取系统墙钟毫秒（导出可读）；测试注入假时钟保证确定性（有界/脱敏
 * 红测需要）。记录格式：`YYYY-MM-DDTHH:MM:SS.mmm [TAG] message`。
 *
 * @contract
 * @pre none（对象无需前置初始化；[initialize] 仅影响磁盘导出目录，非必需）
 * @post record 追加到环形缓冲（写满覆盖最旧，容量 [maxEntries]）；registerSecret 之后
 *       record 落缓冲前脱敏；exportTo 把缓冲按时间序写文件、超限截断最旧行、并轮转
 *       导出目录（超 [maxExportFiles] 删最旧）
 * @err none（record/registerSecret 不抛；exportTo 失败返回 [ExportResult.Failed]，不抛）
 * @inv 环形缓冲条数恒 ≤ [maxEntries]；落盘文件字节恒 ≤ [maxFileBytes]；导出目录文件数
 *       恒 ≤ [maxExportFiles]（资源有界红线覆盖导出层）；已注册 secret 在任意后续 record
 *       的输出文本中零命中；本对象无任何常驻线程/定时器
 */
object DiagLog {

    /** 脱敏替换串（写入点替换后的统一占位；导出/内存中都是它，绝无原文）。 */
    const val REDACTED = "[REDACTED]"

    /** 环形缓冲容量上限（条）；写满覆盖最旧。 */
    const val DEFAULT_MAX_ENTRIES = 4096

    /** 落盘文件字节上限；超限截断最旧行（资源有界红线，磁盘侧）。 */
    const val DEFAULT_MAX_FILE_BYTES = 1024 * 1024 // 1 MiB

    /** 导出目录文件数上限；超出后按最旧轮转删除（round2 缺口：导出目录无上限）。 */
    const val DEFAULT_MAX_EXPORT_FILES = 8

    /** 默认磁盘导出目录名（Android 注入 filesDir 子目录；纯 JVM 测试可指向临时目录）。 */
    const val DEFAULT_STORAGE_DIR = "diag"

    /**
     * 配置（有界边界均可注入，红测用极小值验证覆盖语义）。
     * @contract
     * @pre maxEntries ≥ 1；maxFileBytes ≥ 1；maxLineBytes ≥ 1；maxExportFiles ≥ 0
     *      （0 或负数 = 导出目录不限文件数，一般不推荐）
     * @post 各字段原样持有
     * @err none
     * @inv 不变
     */
    data class Config(
        val maxEntries: Int = DEFAULT_MAX_ENTRIES,
        val maxFileBytes: Int = DEFAULT_MAX_FILE_BYTES,
        /** 单条记录文本上限；超长截断（防单条刷爆缓冲，防御性）。 */
        val maxLineBytes: Int = 2048,
        /** 导出目录文件数上限；超出后按最旧轮转删除（round2 缺口：导出目录无上限）。 */
        val maxExportFiles: Int = DEFAULT_MAX_EXPORT_FILES,
    )

    /** 导出结果：成功带文件字节数，失败带原因（失败可见红线，不许静默）。 */
    sealed interface ExportResult {
        data class Success(val bytes: Int, val lines: Int) : ExportResult
        data class Failed(val reason: String) : ExportResult
    }

    /**
     * 时钟抽象（测试注入假时钟保证确定性；生产 [System.currentTimeMillis]）。
     * @contract
     * @pre none
     * @post nowMs() 返回单调/墙钟毫秒，可重复调用
     * @err none
     * @inv 实现方保证返回值语义
     */
    fun interface Clock {
        fun nowMs(): Long
    }

    /** 单条记录（环形缓冲元素）。 */
    private data class Entry(val line: String)

    private val lock = ReentrantLock()
    private var config = Config()
    private var clock: Clock = Clock { System.currentTimeMillis() }
    private var storageDir: String? = null

    /** 已注册的凭据（写入点脱敏源；进程存活期累积，导出永远不含原文）。 */
    private val secrets = mutableListOf<String>()

    /** 环形缓冲：容量固定，写满覆盖最旧（下标取模）。 */
    private val buffer = ArrayDeque<Entry>()

    /**
     * 同 tag 最近一次 coalesce 键。Compose 会在 180ms 内重建多个 View，
     * 实例字段拦不住；进程级按操作数合并（值不变不新开行，变了立刻新行）。
     */
    private val lastCoalesce = mutableMapOf<String, Coalesce>()

    private data class Coalesce(val key: String, var count: Int)

    /** 只写一次的系统启动时间锚点（format 共享；[formatter] 非线程安全，锁内用）。 */
    private val formatter: ThreadLocal<SimpleDateFormat> =
        ThreadLocal.withInitial { formatEpoch() }

    private fun formatEpoch(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }

    /**
     * 初始化磁盘导出目录与配置（幂等；设置页导出前必须已初始化，否则导出走失败路径）。
     *
     * @contract
     * @pre none（dir 可为空串 → 禁用落盘导出，仅内存缓冲）
     * @post storageDir 更新为入参（非空时目录存在性由导出时创建）；config/clock 更新
     * @err none
     * @inv 不重置已有缓冲与 secrets
     */
    fun initialize(storageDir: String?, cfg: Config = Config()) {
        lock.withLock {
            this.storageDir = storageDir
            config = cfg
        }
    }

    /** 测试/重建注入假时钟（确定性红测）。 */
    fun setClockForTest(c: Clock) {
        lock.withLock { clock = c }
    }

    /**
     * 注册凭据（写入点脱敏源）：之后任何 [record] 的输出文本中该值零命中。
     *
     * 调用点：配对 token 注入（[dev.agentmirror.app.pairing.PairingViewModel]）、
     * TS authkey 注入（[dev.agentmirror.app.tsnet.TsnetWire.ensureStarted]）。
     * 脱敏语义：注册后 record 里凡是出现该字符串的位置（包括 URL query、header、
     * 异常消息）都被替换为 [REDACTED]——替换在**写入缓冲前**完成。
     *
     * @contract
     * @pre none（secret 可为任意字符串；空串忽略）
     * @post secret 已登记（重复注册去重）；后续 record 输出中该值零命中
     * @err none
     * @inv 不影响已写入缓冲的记录（写入时已脱敏）
     */
    fun registerSecret(secret: String) {
        val s = secret.trim()
        if (s.isEmpty()) return
        lock.withLock {
            if (s !in secrets) secrets.add(s)
        }
    }

    /**
     * 记录一条诊断日志（事件驱动：调用方事件流里调用，本函数零线程零分配常量级）。
     *
     * 脱敏在写入点：先把 message 过 [REDACTED] 替换（已注册 secrets + 结构兜底），再追加
     * 到环形缓冲。**内存缓冲里存的就已经是脱敏后文本**——导出时只是倾倒，不再过滤。
     *
     * @contract
     * @pre none（tag 非空；message 任意，含可能携带凭据的异常文案）
     * @post 一条 `[tag] message` 追加到环形缓冲（写满覆盖最旧）；message 中已注册 secret
     *       与结构敏感串（tskey 前缀 / Bearer 头 / URI userinfo）已被替换为 [REDACTED]。
     *       [coalesceKey] 非空且与该 tag 上次相同：不新开行，把最近一条同 tag 行改成 `×N`
     *       （操作数没变）；键一变立刻新开行（操作数变了不许吞）。
     * @err none（不抛异常）
     * @inv 缓冲条数恒 ≤ maxEntries；任何 registerSecret 过的值在缓冲输出中零命中
     */
    fun record(tag: String, message: String, coalesceKey: String? = null) {
        val safe = redact(message)
        val ts = clock.nowMs()
        val line = formatLine(ts, tag, safe)
        lock.withLock {
            if (coalesceKey != null) {
                val prev = lastCoalesce[tag]
                if (prev != null && prev.key == coalesceKey) {
                    prev.count += 1
                    val needle = "[$tag]"
                    val idx = buffer.indexOfLast { it.line.contains(needle) }
                    if (idx >= 0) {
                        val stripped = COALESCE_REPEAT.replace(buffer[idx].line, "")
                        buffer[idx] = Entry("$stripped ×${prev.count}")
                    }
                    return
                }
                lastCoalesce[tag] = Coalesce(coalesceKey, 1)
            }
            if (buffer.size >= config.maxEntries) buffer.removeFirst()
            buffer.addLast(Entry(line))
        }
    }

    /** 时间戳 + tag + 脱敏后文本 → 一行日志（锁外可格式化，无共享态）。 */
    private fun formatLine(ts: Long, tag: String, message: String): String {
        val t = formatter.get().format(Date(ts))
        val trimmedTag = tag.take(24)
        return "$t [$trimmedTag] $message".take(config.maxLineBytes)
    }

    /** 写入点脱敏：注册 secrets 精确替换 + 结构兜底（tskey 前缀 / Bearer / URI userinfo）。 */
    private fun redact(text: String): String {
        var out = text
        lock.withLock {
            for (s in secrets) {
                if (s.length >= 4) out = out.replace(s, REDACTED)
            }
        }
        return redactStructural(out)
    }

    /** 结构兜底：不依赖注册也拦得住的敏感形状。 */
    private fun redactStructural(text: String): String {
        var out = text
        // TS authkey：tskey-auth- 前缀（可能带后续 segment）。取前缀后 8..64 个可见字符段。
        out = TSKEY_PATTERN.replace(out) { REDACTED }
        // Bearer 头：`Bearer <token>`，token 通常 ≥ 8 字符且不含空格（含空格则截到空格前）。
        out = BEARER_PATTERN.replace(out) { m -> "Bearer $REDACTED" }
        // URI userinfo（http://user:pass@host）：user:pass 或 user 都是敏感面，替换为
        // `[REDACTED]@`，保留 scheme 与 host（诊断仍能看出连的是哪台主机，但凭据零命中）。
        out = USERINFO_PATTERN.replace(out) { m ->
            val scheme = m.groupValues[1]
            val host = m.groupValues[3]
            "$scheme$REDACTED@$host"
        }
        return out
    }

    /** 导出当前环形缓冲到文件（时间序倾倒；超限截断最旧行）。 */
    fun exportTo(file: File): ExportResult {
        val snapshot = lock.withLock {
            clock.nowMs() // 确保导出时点单调
            buffer.toList().map { it.line }
        }
        return try {
            if (snapshot.isEmpty()) return ExportResult.Success(0, 0)
            file.parentFile?.mkdirs()
            // 磁盘有界：写完后若超限，从头截断最旧行直到不超（覆盖式追加，幂等）。
            file.appendText(snapshot.joinToString("\n") + "\n", Charsets.UTF_8)
            trimFileToCap(file)
            // 导出目录有界（round2 缺口修复）：写完即轮转，超 maxExportFiles 删最旧。
            pruneExports(file)
            val bytes = file.length().toInt()
            ExportResult.Success(bytes, snapshot.size)
        } catch (e: Exception) {
            ExportResult.Failed(e.message ?: "export io error")
        }
    }

    /** 磁盘有界：文件超 maxFileBytes 时截断最旧行（从头删行直到 ≤ cap）。 */
    private fun trimFileToCap(file: File) {
        val cap = lock.withLock { config.maxFileBytes }
        if (file.length() <= cap) return
        val lines = file.readLines()
        var keep = lines
        while (keep.joinToString("\n").toByteArray(Charsets.UTF_8).size > cap && keep.size > 1) {
            keep = keep.drop(1)
        }
        file.writeText(keep.joinToString("\n"), Charsets.UTF_8)
    }

    /**
     * 导出目录文件数上限轮转：超 [Config.maxExportFiles] 时删最旧（保留最新 [cap] 个）。
     * 用 [listExports] 取清单（接口不再是死代码），最新在前、最旧在后，删尾部超限个。
     * @contract
     * @pre none
     * @post 导出目录里 .log 文件数 ≤ maxExportFiles；删除的是最旧（lastModified 最小）
     * @err none（删除失败静默——导出主路径不因清理失败而失败，可下次轮转再清）
     * @inv 不动 [newest] 自身；只删比它更旧且超限的文件
     */
    private fun pruneExports(newest: File) {
        val cap = lock.withLock { config.maxExportFiles }
        if (cap <= 0) return // 0/负 = 不限
        val all = listExports() // 最新在前（lastModified 降序）
        if (all.size <= cap) return
        // 跳过最新 cap 个，删剩下的（最旧的超限部分）。
        all.drop(cap).forEach { runCatching { it.delete() } }
    }

    /** 导出目录里已有的诊断文件清单（最新在前，按 lastModified 降序；无则空）。 */
    fun listExports(): List<File> {
        val dir = lock.withLock { storageDir } ?: return emptyList()
        val f = File(dir)
        if (!f.isDirectory) return emptyList()
        return f.listFiles { file -> file.isFile && file.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    /** 测试/重建复位（单例泄漏污染后续用例，[ServiceWire.resetForTest] 同款纪律）。 */
    fun resetForTest() {
        lock.withLock {
            buffer.clear()
            secrets.clear()
            lastCoalesce.clear()
            storageDir = null
            config = Config()
            clock = Clock { System.currentTimeMillis() }
        }
    }

    /** 当前缓冲条数（测试断言 / 有界红测）。 */
    fun size(): Int = lock.withLock { buffer.size }

    /**
     * App 内展示消费入口（feat-diag-inapp-view）：最近 [limit] 条已脱敏文本，格式与
     * [exportTo] 落盘的行一致（`时间戳 [TAG] message`）。**只返回字符串**——不暴露
     * [Entry] 或缓冲本身，UI 层拿不到脱敏前的任何东西（脱敏不许绕过红线）。
     *
     * @contract
     * @pre limit 任意（≤0 返回空表）
     * @post 返回缓冲末尾（最新）至多 limit 条，顺序保持时间序（旧→新）
     * @err none
     * @inv 不改变缓冲状态；返回值全部经 record 写入点脱敏
     */
    fun recentLines(limit: Int): List<String> = lock.withLock {
        if (limit <= 0) emptyList() else buffer.takeLast(limit).map { it.line }
    }

    /** 当前缓冲内容快照（测试断言 / 有界红测；导出前预检）。 */
    fun snapshotForTest(): List<String> = lock.withLock { buffer.map { it.line } }

    /** 当前已注册 secret 数（脱敏红测前置）。 */
    fun secretCountForTest(): Int = lock.withLock { secrets.size }

    /**
     * 崩溃/ANR 兜底记录（设计答复①，leader 认可）：崩溃堆栈是 cause 链最长的地方，
     * 必须经 [redactCauseChain]- 同款递归脱敏后才进缓冲。挂到
     * [Thread.setDefaultUncaughtExceptionHandler] 回调里调用（Android 侧在
     * Application/Activity onCreate 安装），不替换原 handler（链式调用）。
     *
     * @contract
     * @pre none（throwable 任意；tag 非空）
     * @post throwable 的整条 cause 链（每层 message + 类名）折叠成单行后追加到环形缓冲
     *       （写满覆盖最旧）；已注册 secret 在链任何层都不外泄
     * @err none（记录动作不抛，异常被 try/catch 包住——崩溃回调里再抛会吞掉原始崩溃）
     * @inv 缓冲条数恒 ≤ maxEntries；不替换调用方既有 handler（链式语义）
     */
    fun recordCrash(tag: String, throwable: Throwable) {
        val deep = runCatching {
            generateSequence(throwable) { it.cause }
                .map { it.message ?: it.javaClass.simpleName }
                .joinToString(" | caused-by ") { it.trim() }
        }.getOrElse { throwable.message ?: throwable.javaClass.simpleName }
        // 崩溃回调里再抛会吞掉原始崩溃：整个记录动作包 try/catch。
        runCatching { record(tag, "crash: $deep") }
    }

    // ---- 结构兜底正则（写入点脱敏用；standalone object 不允许 companion object，直接作顶层属性）----

    // 合并计数后缀：同一 coalesce 键反复命中时改写最近一条，不新开行。
    private val COALESCE_REPEAT = Regex(" ×\\d+$")

    // tskey-auth- 后跟 base62 长串；保守取 8..64 个非空白字符段。
    private val TSKEY_PATTERN = Regex("tskey-[A-Za-z0-9_-]{8,64}")
    // Bearer <token>：token ≥ 8 个非空白字符（短于 8 的不当敏感，防误伤）。
    private val BEARER_PATTERN = Regex("Bearer\\s+(\\S{8,})")
    // URI userinfo：scheme://[user:pass@]host——整段 userinfo 替换（user:pass 或仅 user）。
    private val USERINFO_PATTERN = Regex("(https?://)([^/@]+@)([^/\\s]+)")
}
