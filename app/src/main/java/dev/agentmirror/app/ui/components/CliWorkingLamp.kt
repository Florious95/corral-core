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

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.agentmirror.app.ui.model.SessionRowMotion
import dev.agentmirror.app.ui.theme.LocalAppPalette
import dev.agentmirror.app.ui.theme.SessionRowMarker

/**
 * Ordinary session-list marker, sourced from Codex CLI 0.149.0's native
 * conversation-leading spinner. Unicode Braille is used only as the frozen
 * source frame identity; final pixels are six Canvas circles so font metrics
 * cannot clip the top or bottom row.
 *
 * @contract
 * @pre motion is already fail-closed (unknown/abnormal/offline -> None)
 * @post Working paints a complete 2x3 mask in a 20dp slot; Idle/None paint no dots
 * @err none
 * @inv working is the exact ten-frame 100ms/1000ms sequence; no text glyph draw
 */
@Composable
fun CliWorkingLamp(
    motion: SessionRowMotion,
    modifier: Modifier = Modifier,
) {
    val slot = modifier.size(SessionRowMarker.size)
    when (motion) {
        SessionRowMotion.None -> Box(slot)
        SessionRowMotion.Idle -> Box(
            slot.semantics { contentDescription = "idle:static" },
        )
        SessionRowMotion.Working -> {
            val palette = LocalAppPalette.current
            val transition = rememberInfiniteTransition(label = "sessionRowSpinner")
            val elapsed by transition.animateFloat(
                initialValue = 0f,
                targetValue = SessionRowMarker.periodMillis.toFloat(),
                animationSpec = infiniteRepeatable(
                    animation = tween(SessionRowMarker.periodMillis, easing = LinearEasing),
                ),
                label = "sessionRowSpinnerElapsed",
            )
            val sourceElapsed = elapsed.toLong() / SessionRowMarker.frameIntervalMillis *
                SessionRowMarker.frameIntervalMillis
            val frame = SessionRowMarker.frameAt(sourceElapsed)
            Canvas(
                modifier = slot.semantics {
                    contentDescription =
                        "working:glyph=${frame.glyph}:elapsed=${frame.sourceMillis}:" +
                            "position=${frame.position}:mask=${frame.mask}"
                },
            ) {
                val radius = SessionRowMarker.dotRadius.toPx()
                val left = SessionRowMarker.dotColumnInset.toPx()
                val right = size.width - left
                val firstRow = SessionRowMarker.dotTopInset.toPx()
                val rowStep = SessionRowMarker.dotRowStep.toPx()
                val points = listOf(
                    left to firstRow,
                    left to firstRow + rowStep,
                    left to firstRow + rowStep * 2,
                    right to firstRow,
                    right to firstRow + rowStep,
                    right to firstRow + rowStep * 2,
                )
                points.forEachIndexed { dot, (x, y) ->
                    drawCircle(
                        color = if (frame.mask and (1 shl dot) != 0) {
                            palette.workingLampActive
                        } else {
                            palette.workingLampInactive
                        },
                        radius = radius,
                        center = androidx.compose.ui.geometry.Offset(x, y),
                    )
                }
            }
        }
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
