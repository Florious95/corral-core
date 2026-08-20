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
 * `onDraw` 墙钟与分段量具。
 *
 * 计时边界（写进日志，真机可复现）：
 * - 本类是 [android.view.View]，**不是** SurfaceView。没有 `lockCanvas` /
 *   `unlockCanvasAndPost`。`dt_lock_us=-1` `dt_post_us=-1` 是故意的哨兵，不是漏记。
 * - `dt_us` = [TermSurfaceView.onDraw] 方法体入口→出口（含 `super.onDraw` 与我们的绘制）。
 *   不含 ViewRoot/HWUI 合成、不含 GPU。那些在方法返回之后。
 * - `dt_super_us` = `super.onDraw`
 * - `dt_chrome_us` = `publishThemeChrome`
 * - `dt_clear_us` = 整画布铺默认底那一次 `drawRect`
 * - `dt_lines_us` = 逐行 `drawLine`（铺格 + drawText + 几何）
 * - `dt_body_us` = chrome + clear + lines（我们自己的 CPU，不含 super）
 *
 * p95 环只收「有字」的稳态帧：`cellsNonBlank>0 && textDraw>0`，并丢掉前 [WARMUP]
 * 帧。空屏 / 首帧单独 `source=onDrawEmpty`，不进 p95。
 *
 * DiagLog 最多 1Hz 一条（加上 n 刚到 [MIN_FRAMES] 时一条），10s 窗口 ≤ [MAX_LINES_PER_10S]。
 */
internal object TermDrawMeter {
    const val TAG = "term-draw"
    const val OPT_FILE = "term_draw_opt"
    const val BURST_FILE = "term_draw_burst"
    const val MAX_LINES_PER_10S = 12
    const val MIN_FRAMES = 120
    const val WARMUP = 10

    @Volatile
    var optEnabled: Boolean = true

    private val lock = Any()
    private val dtRing = IntArray(RING)
    private val superRing = IntArray(RING)
    private val bodyRing = IntArray(RING)
    private val clearRing = IntArray(RING)
    private val linesRing = IntArray(RING)
    private var n = 0
    private var write = 0
    private var lastEmitMs = 0L
    private var firstOnDrawMs = 0L
    private var firstWithCellsMs = 0L
    private var warmupLeft = WARMUP
    private var skippedEmpty = 0
    private var collectUntilMin = false
    private var collectDeadlineMs = 0L
    private var emittedMinFrames = false

    fun resetForTest() {
        synchronized(lock) {
            n = 0
            write = 0
            lastEmitMs = 0L
            firstOnDrawMs = 0L
            firstWithCellsMs = 0L
            warmupLeft = WARMUP
            skippedEmpty = 0
            collectUntilMin = false
            collectDeadlineMs = 0L
            emittedMinFrames = false
            optEnabled = true
        }
    }

    /** 开始采集：清环后一直请帧直到稳态环 ≥ [MIN_FRAMES] 或 15s 到点。空屏不计入 n。 */
    fun armBurst(frames: Int) {
        synchronized(lock) {
            n = 0
            write = 0
            warmupLeft = WARMUP
            emittedMinFrames = false
            lastEmitMs = 0L
            collectUntilMin = frames > 0
            collectDeadlineMs = System.currentTimeMillis() + 15_000L
        }
    }

    fun consumeBurstFrame(): Boolean {
        synchronized(lock) {
            if (!collectUntilMin) return false
            if (n >= MIN_FRAMES || System.currentTimeMillis() > collectDeadlineMs) {
                collectUntilMin = false
                return false
            }
            return true
        }
    }

    /**
     * @contract
     * @pre 各 dt* ≥ 0；lock/post 在 View 路径上为 -1
     * @post 有字稳态帧入环；空屏不入 p95 环
     * @err none
     * @inv 环长恒 = [RING]
     */
    fun onDrawEnd(
        dtUs: Int,
        dtSuperUs: Int,
        dtChromeUs: Int,
        dtClearUs: Int,
        dtLinesUs: Int,
        dtBodyUs: Int,
        dtLockUs: Int,
        dtPostUs: Int,
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
        val comparable = presenterNull == 0 && cellsNonBlank > 0 && textDraw > 0
        var emit: String? = null
        synchronized(lock) {
            if (firstOnDrawMs == 0L) firstOnDrawMs = now
            if (cellsNonBlank > 0 && firstWithCellsMs == 0L) firstWithCellsMs = now
            if (!comparable) {
                skippedEmpty++
                val dueEmpty = lastEmitMs == 0L || now - lastEmitMs >= EMIT_INTERVAL_MS
                if (!dueEmpty) return
                lastEmitMs = now
                emit = formatLine(
                    source = "onDrawEmpty",
                    n = n,
                    dtLast = sample,
                    dtSuperLast = dtSuperUs,
                    dtChromeLast = dtChromeUs,
                    dtClearLast = dtClearUs,
                    dtLinesLast = dtLinesUs,
                    dtBodyLast = dtBodyUs,
                    dtLockUs = dtLockUs,
                    dtPostUs = dtPostUs,
                    rows = rows,
                    cols = cols,
                    cellW = cellW,
                    cellH = cellH,
                    bgRect = bgRect,
                    textDraw = textDraw,
                    measureText = measureText,
                    geomRect = geomRect,
                    dirtyRowsIn = dirtyRowsIn,
                    drawnRows = drawnRows,
                    presenterNull = presenterNull,
                    cellsNonBlank = cellsNonBlank,
                    scrollDelta = scrollDelta,
                    frameTimeNanos = frameTimeNanos,
                    now = now,
                )
            } else {
                if (warmupLeft > 0) {
                    warmupLeft--
                } else {
                    dtRing[write] = sample
                    superRing[write] = dtSuperUs.coerceAtLeast(0)
                    bodyRing[write] = dtBodyUs.coerceAtLeast(0)
                    clearRing[write] = dtClearUs.coerceAtLeast(0)
                    linesRing[write] = dtLinesUs.coerceAtLeast(0)
                    write = (write + 1) % RING
                    if (n < RING) n++
                }
                val hitMin = n >= MIN_FRAMES && !emittedMinFrames
                val due = lastEmitMs == 0L || now - lastEmitMs >= EMIT_INTERVAL_MS || hitMin
                if (!due) return
                if (hitMin) emittedMinFrames = true
                lastEmitMs = now
                emit = formatLine(
                    source = source,
                    n = n,
                    dtLast = sample,
                    dtSuperLast = dtSuperUs,
                    dtChromeLast = dtChromeUs,
                    dtClearLast = dtClearUs,
                    dtLinesLast = dtLinesUs,
                    dtBodyLast = dtBodyUs,
                    dtLockUs = dtLockUs,
                    dtPostUs = dtPostUs,
                    rows = rows,
                    cols = cols,
                    cellW = cellW,
                    cellH = cellH,
                    bgRect = bgRect,
                    textDraw = textDraw,
                    measureText = measureText,
                    geomRect = geomRect,
                    dirtyRowsIn = dirtyRowsIn,
                    drawnRows = drawnRows,
                    presenterNull = presenterNull,
                    cellsNonBlank = cellsNonBlank,
                    scrollDelta = scrollDelta,
                    frameTimeNanos = frameTimeNanos,
                    now = now,
                )
            }
        }
        val line = emit ?: return
        DiagLog.record(TAG, line)
    }

    private fun formatLine(
        source: String,
        n: Int,
        dtLast: Int,
        dtSuperLast: Int,
        dtChromeLast: Int,
        dtClearLast: Int,
        dtLinesLast: Int,
        dtBodyLast: Int,
        dtLockUs: Int,
        dtPostUs: Int,
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
        now: Long,
    ): String {
        val dt = stats(dtRing, n)
        val su = stats(superRing, n)
        val bo = stats(bodyRing, n)
        val cl = stats(clearRing, n)
        val li = stats(linesRing, n)
        return "source=$source n=${dt.count} dt_us_avg=${dt.avg} dt_us_p50=${dt.p50} dt_us_p95=${dt.p95} " +
            "dt_us_last=$dtLast " +
            "dt_super_us_avg=${su.avg} dt_super_us_p95=${su.p95} dt_super_us_last=$dtSuperLast " +
            "dt_body_us_avg=${bo.avg} dt_body_us_p95=${bo.p95} dt_body_us_last=$dtBodyLast " +
            "dt_clear_us_avg=${cl.avg} dt_clear_us_p95=${cl.p95} dt_clear_us_last=$dtClearLast " +
            "dt_lines_us_avg=${li.avg} dt_lines_us_p95=${li.p95} dt_lines_us_last=$dtLinesLast " +
            "dt_chrome_us_last=$dtChromeLast " +
            "dt_lock_us=$dtLockUs dt_post_us=$dtPostUs surface=view " +
            "rows=$rows cols=$cols cellW=$cellW cellH=$cellH " +
            "bgRect=$bgRect textDraw=$textDraw measureText=$measureText geomRect=$geomRect " +
            "dirtyRowsIn=$dirtyRowsIn drawnRows=$drawnRows presenterNull=$presenterNull " +
            "cellsNonBlank=$cellsNonBlank scrollDelta=$scrollDelta " +
            "opt=${if (optEnabled) 1 else 0} skippedEmpty=$skippedEmpty warmupLeft=$warmupLeft " +
            "frameTimeNanos=$frameTimeNanos t_firstOnDraw=$firstOnDrawMs " +
            "t_firstOnDrawWithCells=$firstWithCellsMs t_emit=$now"
    }

    private fun stats(ring: IntArray, count: Int): Stats {
        if (count <= 0) return Stats(0, 0, 0, 0)
        val copy = IntArray(count)
        val start = if (count < RING) 0 else write
        for (i in 0 until count) {
            copy[i] = ring[(start + i) % RING]
        }
        copy.sort()
        val avg = copy.fold(0L) { a, b -> a + b } / copy.size
        val p50 = copy[(copy.size - 1) * 50 / 100]
        val p95 = copy[(copy.size - 1) * 95 / 100]
        return Stats(copy.size, avg.toInt(), p50, p95)
    }

    private data class Stats(val count: Int, val avg: Int, val p50: Int, val p95: Int)

    private const val RING = 256
    private const val EMIT_INTERVAL_MS = 1000L
}
