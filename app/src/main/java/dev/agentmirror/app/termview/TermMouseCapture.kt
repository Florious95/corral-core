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

/**
 * 触点 → 终端单元格（1-based）+ cell-motion 去重。
 *
 * 热路径：几次整数运算，无分配、无 map。只有跨单元格才视为新事件。
 */
class TermMouseCapture {
    var col: Int = 1
        private set
    var row: Int = 1
        private set

    private var lastCol: Int = 0
    private var lastRow: Int = 0
    private var primed: Boolean = false

    /**
     * 用当前网格字格宽高把像素换成 1-based 行列，钳在 [1, cols]×[1, rows]。
     * @return false 当字格或网格尺寸非法
     */
    fun hit(xPx: Float, yPx: Float, cellW: Int, cellH: Int, cols: Int, rows: Int): Boolean {
        if (cellW <= 0 || cellH <= 0 || cols <= 0 || rows <= 0) return false
        var c = (xPx / cellW).toInt() + 1
        var r = (yPx / cellH).toInt() + 1
        if (c < 1) c = 1 else if (c > cols) c = cols
        if (r < 1) r = 1 else if (r > rows) r = rows
        col = c
        row = r
        if (!primed) {
            lastCol = c
            lastRow = r
            primed = true
        }
        return true
    }

    /** 相对上次 [markReported]（或首次 hit）是否换了格。 */
    fun crossedCell(): Boolean = col != lastCol || row != lastRow

    fun markReported() {
        lastCol = col
        lastRow = row
    }

    fun reset() {
        primed = false
        lastCol = 0
        lastRow = 0
    }
}
