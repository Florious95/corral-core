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

package dev.agentmirror.app.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.PathParser

/**
 * Frozen geometry copied from `icon()` in Agent App Prototype.dc.html.
 * Runtime draws these primitives; it does not parse the companion raw SVG.
 *
 * @contract
 * @pre canonicalId is one of claude_code, cursor
 * @post draws() is true only for those two; Codex and grok are never an X fallback
 * @err none
 * @inv does not load or parse the raw SVG bytes
 */
internal object ExtractedProviderIcon {
    const val BLOB_PATH =
        "M12 2.8c1.7-.8 3.6-.3 4.6 1.1 1.7.2 2.9 1.7 2.7 3.4 1.1 1.2 1.2 3 .2 4.3.5 1.6-.2 3.3-1.7 4-.4 1.6-1.9 2.7-3.6 2.5-1.1 1.2-3 1.4-4.3.5-1.7.3-3.3-.8-3.7-2.4-1.5-.6-2.4-2.2-2-3.8-1.1-1.3-1-3.1.1-4.3-.2-1.7 1-3.2 2.6-3.5C8.1 3.2 9.9 2.5 11.5 3l.5-.2Z"
    /** Retained only as a regression sentinel: no runtime path may draw this. */
    const val GROK_ICON_SWITCH_X_FALLBACK =
        "M9 15.5 15 9M9.2 9.2l2.3 2.3M14.8 14.8l-2.3-2.3"
    const val CURSOR_INNER =
        "M12 8l3.4 2v4L12 16l-3.4-2v-4L12 8Zm0 0v4m3.4-2L12 12m-3.4-2 3.4 2"
    const val CLAUDE_TEXT = "\u276F_"

    val ClaudeTint = Color(0xFFD97757)
    val CursorTint = Color(0xFF6D6A63)

    fun draws(canonicalId: String): Boolean = when (canonicalId) {
        "claude_code", "cursor" -> true
        else -> false
    }

    private val blobPath = PathParser.createPathFromPathData(BLOB_PATH)!!
    private val cursorInner = PathParser.createPathFromPathData(CURSOR_INNER)!!

    fun draw(scope: DrawScope, canonicalId: String) {
        val tint = when (canonicalId) {
            "claude_code" -> ClaudeTint
            "cursor" -> CursorTint
            else -> return
        }
        val canvas = scope.drawContext.canvas.nativeCanvas
        val scale = scope.size.minDimension / 24f
        canvas.save()
        canvas.scale(scale, scale)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = tint.toArgb()
            strokeWidth = 1.5f
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawPath(blobPath, stroke)
        when (canonicalId) {
            "claude_code" -> {
                val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = tint.toArgb()
                    textSize = 7.5f
                    textAlign = Paint.Align.CENTER
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                }
                canvas.drawText(CLAUDE_TEXT, 12f, 15f, text)
            }
            "cursor" -> {
                stroke.strokeWidth = 1.2f
                canvas.drawPath(cursorInner, stroke)
            }
        }
        canvas.restore()
    }
}
