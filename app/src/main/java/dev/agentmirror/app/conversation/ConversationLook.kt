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

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.kyant.shapes.RoundedRectangularShape
import dev.agentmirror.app.ui.components.glassReadable
import dev.agentmirror.app.ui.theme.ResolvedThemeSuite

/*
 * The app style (Settings › 界面风格) applied to the conversation: material, geometry and type
 * weight only. Colour stays with the terminal theme through [ConversationPalette], so every one of
 * the 30 families keeps its contrast-repaired pairs in both styles.
 *
 *   Liquid Glass — planar frosted panels sampling the transcript, squircles, one even 0.5dp
 *                  hairline. No bevel, no specular glint, no lens bulge, no inner shadow: depth
 *                  comes from translucency and a soft ambient shadow only.
 *   Modernism    — opaque brushed-titanium panels on a hairline grid, right angles, heavier
 *                  titles, no backdrop sampling at all (no glass, no glow, no recorded layer).
 */
@Immutable
internal data class ConversationLook(
    val glass: Boolean,
    val sharp: Boolean,
    /** Panel outlines and grid lines. */
    val hairline: Dp,
    /** The heavier architectural rule (header base, dock top) in Modernism. */
    val rule: Dp,
    val titleWeight: FontWeight,
) {
    /** A squircle of [radius], or a right angle. */
    fun shape(radius: Dp): Shape = if (sharp) RectangleShape else RoundedRectangle(radius)

    /** A capsule, or a right angle. */
    fun pill(): Shape = if (sharp) RectangleShape else Capsule()

    companion object {
        val Glass = ConversationLook(glass = true, sharp = false, hairline = 0.5.dp, rule = 0.dp, titleWeight = FontWeight.SemiBold)

        fun of(suite: ResolvedThemeSuite) = if (suite.surfaces.isGlass && !suite.geometry.sharpCorners) {
            Glass
        } else {
            ConversationLook(
                glass = suite.surfaces.isGlass,
                sharp = suite.geometry.sharpCorners,
                hairline = suite.geometry.hairline,
                rule = suite.geometry.headerRule,
                titleWeight = FontWeight.Bold,
            )
        }
    }
}

internal val LocalConversationLook = staticCompositionLocalOf { ConversationLook.Glass }

/** The current look's squircle of [radius] (a right angle in Modernism). */
@Composable
@ReadOnlyComposable
internal fun lookShape(radius: Dp): Shape = LocalConversationLook.current.shape(radius)

/** The current look's capsule (a right angle in Modernism). */
@Composable
@ReadOnlyComposable
internal fun lookPill(): Shape = LocalConversationLook.current.pill()

/**
 * The one floating-panel material (dock, header capsule, menus, sheets, toasts, round buttons).
 * Glass samples [backdrop]; Modernism is brushed titanium and a hairline, never a sampler, so it
 * is safe anywhere and costs no blur pass. [radius] null means a capsule.
 */
internal fun Modifier.panelSurface(look: ConversationLook, backdrop: Backdrop, radius: Dp?, p: ConversationPalette): Modifier =
    if (look.glass && !look.sharp) {
        frostedGlass(backdrop, if (radius == null) Capsule() else RoundedRectangle(radius), p)
    } else {
        val shape = if (radius == null) look.pill() else look.shape(radius)
        brushedMetal(p, shape).border(look.hairline, p.rule, shape)
    }

/**
 * Planar frosted glass: vibrancy + blur of what lies beneath, a readable tint, one even 0.5dp
 * hairline and a soft ambient shadow. Deliberately absent: lens refraction (the bulging-jelly
 * edge), angled specular highlights and inner shadows — light is not faked on a flat pane.
 */
internal fun Modifier.frostedGlass(backdrop: Backdrop, shape: RoundedRectangularShape, p: ConversationPalette): Modifier =
    drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(20.dp.toPx())
        },
        highlight = { Highlight(width = 0.5.dp, alpha = 1f, style = HighlightStyle.Plain(color = p.glassStroke)) },
        shadow = { Shadow(radius = 16.dp, color = Color.Black.copy(alpha = if (p.dark) 0.22f else 0.07f)) },
        onDrawSurface = { drawRect(p.glass.glassReadable()) },
    )

/**
 * Modernism's brushed titanium: the cool [ConversationPalette.panel] → [ConversationPalette.panelEnd]
 * diffusion under a fine horizontal grain. The grain is one small tile built once per process and
 * repeated by a shader, so a panel costs two rect draws: no animation, no per-frame path, no blur.
 */
internal fun Modifier.brushedMetal(p: ConversationPalette, shape: Shape = RectangleShape): Modifier =
    clip(shape).drawWithCache {
        val diffusion = Brush.verticalGradient(listOf(p.panel, p.panelEnd))
        val grain = ShaderBrush(ImageShader(BrushedGrain, TileMode.Repeated, TileMode.Repeated))
        onDrawBehind {
            drawRect(diffusion)
            drawRect(grain)
        }
    }

/** One stroke per lit/shaded streak; ≤3% alpha, so the grain reads as metal, never as stripes. */
private val BrushedGrain: ImageBitmap by lazy {
    val width = 512
    val height = 64
    val tile = ImageBitmap(width, height)
    val canvas = Canvas(tile)
    val paint = Paint()
    var seed = 0x5EED1L
    fun next(): Float {
        seed = (seed * 6364136223846793005L + 1442695040888963407L)
        return ((seed ushr 33) and 0xFFFFFFL).toFloat() / 0xFFFFFF
    }
    for (y in 0 until height) {
        repeat(2) {
            if (next() < 0.55f) {
                val start = next() * width
                val length = 60f + next() * 380f
                paint.color = (if (next() < 0.5f) Color.White else Color.Black).copy(alpha = 0.012f + next() * 0.018f)
                // Streaks wrap across the tile edge, so the repeat has no seam.
                canvas.drawRect(Rect(start, y.toFloat(), minOf(start + length, width.toFloat()), y + 1f), paint)
                if (start + length > width) canvas.drawRect(Rect(0f, y.toFloat(), start + length - width, y + 1f), paint)
            }
        }
    }
    tile
}

/** The look's one hairline: 0.5dp ink veil on glass, the ruled line in Modernism; [tone] for a status outline. */
internal fun Modifier.hairlineBorder(look: ConversationLook, p: ConversationPalette, shape: Shape, tone: Color? = null): Modifier =
    border(look.hairline, tone ?: if (look.glass) p.surfaceStroke else p.rule, shape)

/** A flat tinted tile behind a glyph: one colour, no gradient, no glow. */
internal fun Modifier.glyphTile(tone: Color, p: ConversationPalette, shape: Shape): Modifier =
    clip(shape).background(tone.copy(alpha = if (p.dark) 0.16f else 0.11f))
