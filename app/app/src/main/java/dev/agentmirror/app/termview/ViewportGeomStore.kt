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

import android.content.Context
import androidx.core.content.edit

/**
 * 上次成功视口行列（S1：订阅不再写死 40×120）。
 *
 * 命中条件：字号 sp 与 densityDpi 都与写入时一致。字号/密度变了弃缓存，否则列错
 * （083 格对齐）。旋转后 viewW/viewH 对不上由调用方视为未命中或等 081 那一次 resize。
 */
data class ViewportGeom(
    val rows: Int,
    val cols: Int,
    val cellW: Int,
    val cellH: Int,
    val fontSizeSp: Int,
    val viewW: Int,
    val viewH: Int,
    val densityDpi: Int,
)

/** Persists the last valid terminal viewport geometry. */
interface ViewportGeomStore {
    fun load(): ViewportGeom?
    fun save(geom: ViewportGeom)
}

/** SharedPreferences implementation of [ViewportGeomStore]. */
class SharedPreferencesViewportGeomStore(context: Context) : ViewportGeomStore {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): ViewportGeom? {
        if (!prefs.contains(KEY_COLS) || !prefs.contains(KEY_ROWS)) return null
        val rows = prefs.getInt(KEY_ROWS, 0)
        val cols = prefs.getInt(KEY_COLS, 0)
        if (rows < 1 || cols < 1) return null
        return ViewportGeom(
            rows = rows,
            cols = cols,
            cellW = prefs.getInt(KEY_CELL_W, 0),
            cellH = prefs.getInt(KEY_CELL_H, 0),
            fontSizeSp = prefs.getInt(
                KEY_FONT_SP,
                kotlin.math.round(SharedPreferencesFontSizeStore.DEFAULT_FONT_SIZE_SP).toInt(),
            ),
            viewW = prefs.getInt(KEY_VIEW_W, 0),
            viewH = prefs.getInt(KEY_VIEW_H, 0),
            densityDpi = prefs.getInt(KEY_DPI, 0),
        )
    }

    override fun save(geom: ViewportGeom) {
        if (geom.rows < 1 || geom.cols < 1) return
        prefs.edit {
            putInt(KEY_ROWS, geom.rows)
            putInt(KEY_COLS, geom.cols)
            putInt(KEY_CELL_W, geom.cellW)
            putInt(KEY_CELL_H, geom.cellH)
            putInt(KEY_FONT_SP, geom.fontSizeSp)
            putInt(KEY_VIEW_W, geom.viewW)
            putInt(KEY_VIEW_H, geom.viewH)
            putInt(KEY_DPI, geom.densityDpi)
        }
    }

    companion object {
        private const val PREFS_NAME = "term_viewport_geom"
        private const val KEY_ROWS = "rows"
        private const val KEY_COLS = "cols"
        private const val KEY_CELL_W = "cell_w"
        private const val KEY_CELL_H = "cell_h"
        private const val KEY_FONT_SP = "font_sp"
        private const val KEY_VIEW_W = "view_w"
        private const val KEY_VIEW_H = "view_h"
        private const val KEY_DPI = "density_dpi"
    }
}
