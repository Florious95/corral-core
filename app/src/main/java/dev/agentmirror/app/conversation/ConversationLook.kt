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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import dev.agentmirror.app.ui.theme.ResolvedThemeSuite

/*
 * The app style (Settings › 界面风格) applied to the conversation: material, geometry and type
 * weight only. Colour stays with the terminal theme through [ConversationPalette], so every one of
 * the 30 families keeps its contrast-repaired pairs in both styles.
 *
 *   Liquid Glass — floating frosted panels sampling the transcript, squircles, ambient glow.
 *   Modernism    — opaque brushed panels on a hairline grid, right angles, heavier titles, no
 *                  backdrop sampling at all (no glass, no glow, no recorded layer).
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
 * Glass samples [backdrop]; Ruled is an opaque brushed fill and a hairline, never a sampler, so it
 * is safe anywhere and costs no blur pass. [radius] null means a capsule.
 */
internal fun Modifier.panelSurface(look: ConversationLook, backdrop: Backdrop, radius: Dp?, p: ConversationPalette): Modifier =
    if (look.glass && !look.sharp) {
        frostedGlass(backdrop, if (radius == null) Capsule() else RoundedRectangle(radius), p)
    } else {
        val shape = if (radius == null) look.pill() else look.shape(radius)
        background(Brush.verticalGradient(listOf(p.panel, p.panelEnd)), shape).border(look.hairline, p.rule, shape)
    }
