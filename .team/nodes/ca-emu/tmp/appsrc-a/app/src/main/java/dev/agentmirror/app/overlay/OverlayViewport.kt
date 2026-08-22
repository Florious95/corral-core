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

package dev.agentmirror.app.overlay

/**
 * 已归档，2026-08-19 用户令暂不介入；展示不完全问题未修。
 *
 * 由面板像素尺寸 ÷ 单元格算出抓屏行列。不得用固定 80 列去切内容。
 */
object OverlayViewport {
    fun colsFor(widthPx: Float, cellWidthPx: Float): Int {
        if (widthPx <= 0f || cellWidthPx <= 0f) return 24
        return (widthPx / cellWidthPx).toInt().coerceIn(20, 240)
    }

    fun rowsFor(heightPx: Float, cellHeightPx: Float): Int {
        if (heightPx <= 0f || cellHeightPx <= 0f) return 12
        return (heightPx / cellHeightPx).toInt().coerceIn(8, 80)
    }
}
