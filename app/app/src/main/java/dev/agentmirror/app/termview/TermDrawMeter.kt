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

package dev.agentmirror.app.termview

import dev.agentmirror.app.diag.DiagLog

/**
 * `onDraw` 墙钟与调用次数量具（真机可定罪；模拟器 gfxinfo 只作旁证）。
 *
 * 每帧只把样本写入有界环；DiagLog **最多 1Hz 一条摘要**（含首帧立即一条），
 * 10s 窗口新开行 ≤ [MAX_LINES_PER_10S]。不去重吞掉变化的 p95——摘要本身已带
 * 窗口 avg/p50/p95 与本帧操作数。
 *
 * [optEnabled] 为绘制快路径开关：false = 逐格铺默认底（改前基线）；true = 跳过
 * 已由清屏铺过的默认底 + 合并同色底 + 字形 advance 缓存（改后）。
 * 模拟器验收经 `files/term_draw_opt`（"0"/"1"）翻转，不造第二份 APK。
 */
internal object TermDrawMeter {
    const val TAG = "term-draw"
    const val OPT_FILE = "term_draw_opt"

    /** 说明.md 声明的 10s 窗口上限；判据 A-dw-diag 按此断言。 */
    const val MAX_LINES_PER_10S = 12

    @Volatile
    var optEnabled: Boolean = true

    private val lock = Any()
    private val ring = IntArray(RING)
    private var n = 0
    private var write = 0
    private var lastEmitMs = 0L
    private var firstOnDrawMs = 0L
    private var firstWithCellsMs = 0L

    fun resetForTest() {
        synchronized(lock) {
            n = 0
            write = 0
            lastEmitMs = 0L
            firstOnDrawMs = 0L
            firstWithCellsMs = 0L
            optEnabled = true
        }
    }

    /**
     * 记录一帧 `onDraw`。操作数两边都记（守卫/比较用），再记结论字段（p50/p95）。
     *
     * @contract
     * @pre dtUs ≥ 0；rows/cols/cell 尺寸可为 0（首帧未 seed）
     * @post 样本入环；距上次落日志 ≥ 1s 或本进程首帧时追加一条 [TAG]
     * @err none
     * @inv 环长恒 = [RING]；DiagLog 调用不在锁内
     */
    fun onDrawEnd(
        dtUs: Int,
        rows: Int,
        cols: Int,
        cellW: Int,
        cellH: Int,
        bgRect: Int,
        textDraw: Int,
        measureText: Int,
        geomRect: Int,
        dirtyRowsIn: Int,
        drawnRows: Int,
        presenterNull: Int,
        cellsNonBlank: Int,
        scrollDelta: Int,
        frameTimeNanos: Long,
        source: String,
    ) {
        val now = System.currentTimeMillis()
        val sample = dtUs.coerceAtLeast(0)
        var emit: String? = null
        synchronized(lock) {
            ring[write] = sample
            write = (write + 1) % RING
            if (n < RING) n++
            if (firstOnDrawMs == 0L) firstOnDrawMs = now
            if (cellsNonBlank > 0 && firstWithCellsMs == 0L) firstWithCellsMs = now
            val due = lastEmitMs == 0L || now - lastEmitMs >= EMIT_INTERVAL_MS
            if (!due) return
            lastEmitMs = now
            val stats = snapshotLocked()
            emit = "source=$source n=${stats.count} dt_us_avg=${stats.avg} " +
                "dt_us_p50=${stats.p50} dt_us_p95=${stats.p95} dt_us_last=$sample " +
                "rows=$rows cols=$cols cellW=$cellW cellH=$cellH " +
                "bgRect=$bgRect textDraw=$textDraw measureText=$measureText geomRect=$geomRect " +
                "dirtyRowsIn=$dirtyRowsIn drawnRows=$drawnRows presenterNull=$presenterNull " +
                "cellsNonBlank=$cellsNonBlank scrollDelta=$scrollDelta " +
                "opt=${if (optEnabled) 1 else 0} frameTimeNanos=$frameTimeNanos " +
                "t_firstOnDraw=$firstOnDrawMs t_firstOnDrawWithCells=$firstWithCellsMs"
        }
        val line = emit ?: return
        DiagLog.record(TAG, line)
    }

    private fun snapshotLocked(): Stats {
        if (n == 0) return Stats(0, 0, 0, 0)
        val copy = IntArray(n)
        val start = if (n < RING) 0 else write
        for (i in 0 until n) {
            copy[i] = ring[(start + i) % RING]
        }
        copy.sort()
        val avg = copy.fold(0L) { a, b -> a + b } / copy.size
        val p50 = copy[(copy.size - 1) * 50 / 100]
        val p95 = copy[(copy.size - 1) * 95 / 100]
        return Stats(copy.size, avg.toInt(), p50, p95)
    }

    private data class Stats(val count: Int, val avg: Int, val p50: Int, val p95: Int)

    private const val RING = 128
    private const val EMIT_INTERVAL_MS = 1000L
}
