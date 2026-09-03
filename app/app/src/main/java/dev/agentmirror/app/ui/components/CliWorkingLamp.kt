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

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.agentmirror.app.ui.model.SessionRowMotion
import dev.agentmirror.app.ui.theme.LocalAppPalette
import dev.agentmirror.app.ui.theme.SessionRowMarker

/**
 * Ordinary session-list marker, sourced from Codex CLI's native shimmer bullet.
 * Working is one `•` glyph with the upstream 2s cosine sweep; idle is a static
 * `◦`; None is an empty same-size slot: no question mark, no「未知」text.
 *
 * @contract
 * @pre motion is already fail-closed (unknown/abnormal/offline → None)
 * @post Working shimmers; Idle is static; None is an empty same-size slot
 * @err none
 * @inv never renders a question mark or the word 未知
 */
@Composable
fun CliWorkingLamp(
    motion: SessionRowMotion,
    modifier: Modifier = Modifier,
) {
    val slot = modifier.size(SessionRowMarker.size)
    when (motion) {
        SessionRowMotion.None -> Box(slot)
        SessionRowMotion.Idle -> MarkerText(
            glyph = SessionRowMarker.idleGlyph,
            color = LocalAppPalette.current.metaText,
            description = "idle:static",
            modifier = slot,
        )
        SessionRowMotion.Working -> {
            val p = LocalAppPalette.current
            val transition = rememberInfiniteTransition(label = "sessionRowPulse")
            val elapsed by transition.animateFloat(
                initialValue = 0f,
                targetValue = SessionRowMarker.periodMillis.toFloat(),
                animationSpec = infiniteRepeatable(
                    animation = tween(SessionRowMarker.periodMillis, easing = LinearEasing),
                ),
                label = "sessionRowPulseElapsed",
            )
            val sourceElapsed = elapsed.toLong() / SessionRowMarker.redrawCadenceMillis *
                SessionRowMarker.redrawCadenceMillis
            val frame = SessionRowMarker.frameAt(
                sourceElapsed,
                foreground = p.rowTitleText,
                background = p.listBackground,
            )
            MarkerText(
                glyph = frame.glyph,
                color = frame.color,
                description = "working:glyph=${frame.glyph}:intensity=${frame.intensity}",
                modifier = slot,
            )
        }
    }
}

@Composable
private fun MarkerText(
    glyph: String,
    color: Color,
    description: String,
    modifier: Modifier,
) {
    Box(
        modifier = modifier.semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            color = color,
            fontSize = SessionRowMarker.fontSize,
            fontWeight = FontWeight.Bold,
            lineHeight = SessionRowMarker.fontSize,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Semantics/test tag for the left-slot lamp of one row. */
fun motionTestTag(prefix: String, id: String): String = "$prefix-motion-$id"

/**
 * [CliWorkingLamp] with the row's motion test tag applied.
 *
 * @contract
 * @pre prefix/id identify the row; motion is fail-closed
 * @post lamp is tagged for virtual-clock tests; drawing matches [CliWorkingLamp]
 * @err none
 * @inv tag is not a click target
 */
@Composable
fun CliWorkingLampTagged(
    motion: SessionRowMotion,
    prefix: String,
    id: String,
    modifier: Modifier = Modifier,
) {
    CliWorkingLamp(motion, modifier.testTag(motionTestTag(prefix, id)))
}
