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

package dev.agentmirror.app.conversation

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One consistent line-icon family for the conversation surface: 24-unit grid, round caps and
 * joins, stroke ≈ 1.7/24. Drawn on Canvas — no emoji, no symbol-font fallback, crisp at any size.
 */
enum class Glyph { Bolt, Clip, Plus, Send, Stop, Back, More, Down, Chevron, Terminal, File, Pencil, Search, Spark, Wand, Check, Cross, Copy, Camera, Photo, Refresh }

@Composable
fun GlyphIcon(glyph: Glyph, tint: Color, size: Dp = 20.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) { drawGlyph(glyph, tint) }
}

fun DrawScope.drawGlyph(glyph: Glyph, tint: Color) {
    val u = size.minDimension / 24f
    val line = Stroke(width = 1.7f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(x * u, y * u)
    fun path(vararg points: Pair<Float, Float>, close: Boolean = false) = Path().apply {
        points.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * u, y * u) else lineTo(x * u, y * u) }
        if (close) close()
    }
    when (glyph) {
        Glyph.Bolt -> {
            val bolt = path(13.5f to 2.5f, 5f to 13.5f, 11.5f to 13.5f, 10.5f to 21.5f, 19f to 10.5f, 12.5f to 10.5f, close = true)
            drawPath(bolt, tint, style = line)
        }
        Glyph.Clip -> {
            val clip = Path().apply {
                moveTo(16.5f * u, 7.5f * u)
                lineTo(8.6f * u, 15.4f * u)
                cubicTo(7.4f * u, 16.6f * u, 9.4f * u, 18.6f * u, 10.6f * u, 17.4f * u)
                lineTo(18.6f * u, 9.4f * u)
                cubicTo(21.1f * u, 6.9f * u, 17.1f * u, 2.9f * u, 14.6f * u, 5.4f * u)
                lineTo(6.4f * u, 13.6f * u)
                cubicTo(2.6f * u, 17.4f * u, 8.6f * u, 23.4f * u, 12.4f * u, 19.6f * u)
                lineTo(19.5f * u, 12.5f * u)
            }
            drawPath(clip, tint, style = line)
        }
        Glyph.Plus -> {
            drawLine(tint, p(12f, 5f), p(12f, 19f), 1.9f * u, StrokeCap.Round)
            drawLine(tint, p(5f, 12f), p(19f, 12f), 1.9f * u, StrokeCap.Round)
        }
        Glyph.Send -> {
            val w = Stroke(width = 2.1f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawLine(tint, p(12f, 19f), p(12f, 5.5f), w.width, StrokeCap.Round)
            drawPath(path(6f to 11f, 12f to 5f, 18f to 11f), tint, style = w)
        }
        Glyph.Stop -> drawRoundRect(tint, topLeft = p(7f, 7f), size = Size(10f * u, 10f * u), cornerRadius = CornerRadius(2.6f * u))
        Glyph.Back -> drawPath(path(15f to 4.5f, 7.5f to 12f, 15f to 19.5f), tint, style = Stroke(2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
        Glyph.More -> listOf(6f, 12f, 18f).forEach { drawCircle(tint, 1.6f * u, p(it, 12f)) }
        Glyph.Down -> {
            drawLine(tint, p(12f, 5f), p(12f, 18.5f), 1.9f * u, StrokeCap.Round)
            drawPath(path(6.5f to 13f, 12f to 18.5f, 17.5f to 13f), tint, style = Stroke(1.9f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        Glyph.Chevron -> drawPath(path(7f to 9.5f, 12f to 14.5f, 17f to 9.5f), tint, style = Stroke(1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
        Glyph.Terminal -> {
            drawRoundRect(tint, topLeft = p(3f, 4.5f), size = Size(18f * u, 15f * u), cornerRadius = CornerRadius(3.5f * u), style = line)
            drawPath(path(7f to 9.5f, 10f to 12f, 7f to 14.5f), tint, style = line)
            drawLine(tint, p(12.5f, 15f), p(16.5f, 15f), line.width, StrokeCap.Round)
        }
        Glyph.File -> {
            drawPath(path(14f to 3f, 6.5f to 3f, 5f to 4.5f, 5f to 19.5f, 6.5f to 21f, 17.5f to 21f, 19f to 19.5f, 19f to 8f, close = true), tint, style = line)
            drawPath(path(14f to 3f, 14f to 8f, 19f to 8f), tint, style = line)
            drawLine(tint, p(8.5f, 13f), p(15.5f, 13f), line.width, StrokeCap.Round)
            drawLine(tint, p(8.5f, 16.5f), p(13f, 16.5f), line.width, StrokeCap.Round)
        }
        Glyph.Pencil -> {
            drawPath(path(15.5f to 4.5f, 19.5f to 8.5f, 8.5f to 19.5f, 4f to 20f, 4.5f to 15.5f, close = true), tint, style = line)
            drawLine(tint, p(13f, 7f), p(17f, 11f), line.width, StrokeCap.Round)
        }
        Glyph.Search -> {
            drawCircle(tint, 6.2f * u, p(10.5f, 10.5f), style = line)
            drawLine(tint, p(15f, 15f), p(20f, 20f), line.width, StrokeCap.Round)
        }
        Glyph.Spark -> {
            val star = Path().apply {
                moveTo(12f * u, 3f * u)
                quadraticTo(13.2f * u, 10.8f * u, 21f * u, 12f * u)
                quadraticTo(13.2f * u, 13.2f * u, 12f * u, 21f * u)
                quadraticTo(10.8f * u, 13.2f * u, 3f * u, 12f * u)
                quadraticTo(10.8f * u, 10.8f * u, 12f * u, 3f * u)
                close()
            }
            drawPath(star, tint)
        }
        Glyph.Wand -> {
            // A skill: the wand that casts it, one bright star at the tip, two embers.
            drawLine(tint, p(4.5f, 19.5f), p(13.5f, 10.5f), 2f * u, StrokeCap.Round)
            val star = Path().apply {
                moveTo(17f * u, 3f * u)
                quadraticTo(17.6f * u, 6.4f * u, 21f * u, 7f * u)
                quadraticTo(17.6f * u, 7.6f * u, 17f * u, 11f * u)
                quadraticTo(16.4f * u, 7.6f * u, 13f * u, 7f * u)
                quadraticTo(16.4f * u, 6.4f * u, 17f * u, 3f * u)
                close()
            }
            drawPath(star, tint)
            drawCircle(tint, 1.05f * u, p(9.5f, 4.5f))
            drawCircle(tint, 0.9f * u, p(20f, 14.5f))
        }
        Glyph.Check -> drawPath(path(5f to 12.5f, 10f to 17.5f, 19f to 7f), tint, style = Stroke(2.1f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
        Glyph.Cross -> {
            drawLine(tint, p(6.5f, 6.5f), p(17.5f, 17.5f), 2f * u, StrokeCap.Round)
            drawLine(tint, p(17.5f, 6.5f), p(6.5f, 17.5f), 2f * u, StrokeCap.Round)
        }
        Glyph.Copy -> {
            drawRoundRect(tint, topLeft = p(8.5f, 8.5f), size = Size(12f * u, 12f * u), cornerRadius = CornerRadius(2.8f * u), style = line)
            drawPath(path(15.5f to 5.5f, 15.5f to 4.8f, 14.2f to 3.5f, 4.8f to 3.5f, 3.5f to 4.8f, 3.5f to 14.2f, 4.8f to 15.5f, 5.5f to 15.5f), tint, style = line)
        }
        Glyph.Camera -> {
            drawRoundRect(tint, topLeft = p(2.5f, 6.5f), size = Size(19f * u, 13.5f * u), cornerRadius = CornerRadius(3.2f * u), style = line)
            drawPath(path(8f to 6.5f, 9.5f to 4f, 14.5f to 4f, 16f to 6.5f), tint, style = line)
            drawCircle(tint, 3.6f * u, p(12f, 13.2f), style = line)
        }
        Glyph.Photo -> {
            drawRoundRect(tint, topLeft = p(3f, 4f), size = Size(18f * u, 16f * u), cornerRadius = CornerRadius(3.2f * u), style = line)
            drawCircle(tint, 1.7f * u, p(8.5f, 9.5f))
            drawPath(path(3.5f to 17.5f, 9f to 12.5f, 13f to 16f, 16f to 13f, 20.5f to 17.5f), tint, style = line)
        }
        Glyph.Refresh -> {
            drawArc(tint, startAngle = -60f, sweepAngle = 290f, useCenter = false, topLeft = p(4.5f, 4.5f), size = Size(15f * u, 15f * u), style = line)
            drawPath(path(16.5f to 3.5f, 18.4f to 6f, 15.6f to 7.4f), tint, style = line)
        }
    }
}

/** Picks the tool's glyph and verb from its name; unknown tools stay generic, never wrong. */
fun toolGlyph(name: String): Glyph = when (name.lowercase()) {
    "bash", "shell", "exec", "terminal" -> Glyph.Terminal
    "read", "view", "cat" -> Glyph.File
    "edit", "write", "patch", "apply_patch", "multiedit" -> Glyph.Pencil
    "grep", "find", "ls", "glob", "search", "web_search" -> Glyph.Search
    else -> Glyph.Spark
}
