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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
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
 *   Modernism    — clean opaque flat panels on a hairline grid, right angles, heavier
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
                hairline = 0.5.dp,
                rule = 0.5.dp,
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
 * Glass samples [backdrop]; Modernism is one flat colour and a hairline, never a sampler, so it
 * is safe anywhere and costs no blur pass. [radius] null means a capsule.
 */
internal fun Modifier.panelSurface(look: ConversationLook, backdrop: Backdrop, radius: Dp?, p: ConversationPalette, reading: Boolean = false): Modifier =
    if (look.glass && !look.sharp) {
        frostedGlass(backdrop, if (radius == null) Capsule() else RoundedRectangle(radius), p, reading)
    } else {
        val shape = if (radius == null) look.pill() else look.shape(radius)
        flatPanel(p, shape).border(look.hairline, p.rule, shape)
    }

/**
 * Planar frosted glass: vibrancy + blur of what lies beneath, a readable tint, one even 0.5dp
 * hairline and a soft ambient shadow. Deliberately absent: lens refraction (the bulging-jelly
 * edge), angled specular highlights and inner shadows — light is not faked on a flat pane.
 * [reading] surfaces (menus, sheets) have an opaque neutral protective surface: saturated
 * transcript/logo colours must never bleed behind reading text. Glass remains in the geometry,
 * hairline and shadow, not a coloured blotch from backdrop sampling.
 */
internal fun Modifier.frostedGlass(backdrop: Backdrop, shape: RoundedRectangularShape, p: ConversationPalette, reading: Boolean = false): Modifier =
    drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur((if (reading) 32.dp else 20.dp).toPx())
        },
        highlight = { Highlight(width = 0.5.dp, alpha = 1f, style = HighlightStyle.Plain(color = p.glassStroke)) },
        shadow = { Shadow(radius = 16.dp, color = Color.Black.copy(alpha = if (p.dark) 0.22f else 0.07f)) },
        onDrawSurface = {
            drawRect(if (reading) p.panel.copy(alpha = 1f) else p.glass.glassReadable())
        },
    )

/** A pure flat surface: no texture, noise, grain, tile, gradient or shader. */
internal fun Modifier.flatPanel(p: ConversationPalette, shape: Shape = RectangleShape): Modifier =
    background(p.panel, shape)

/** The look's one hairline: 0.5dp ink veil on glass, the ruled line in Modernism; [tone] for a status outline. */
internal fun Modifier.hairlineBorder(look: ConversationLook, p: ConversationPalette, shape: Shape, tone: Color? = null): Modifier =
    border(look.hairline, tone ?: if (look.glass) p.surfaceStroke else p.rule, shape)

/** A flat tinted tile behind a glyph: one colour, no gradient, no glow. */
internal fun Modifier.glyphTile(tone: Color, p: ConversationPalette, shape: Shape): Modifier =
    clip(shape).background(tone.copy(alpha = if (p.dark) 0.16f else 0.11f))
