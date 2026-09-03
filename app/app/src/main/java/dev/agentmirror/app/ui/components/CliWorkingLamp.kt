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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.agentmirror.app.ui.model.SessionRowMotion
import dev.agentmirror.app.ui.theme.SessionRowMarker

/**
 * Ordinary session-list marker, sourced from the desktop `agents-dot` pulse.
 * Working is a solid 8dp green center with a 0→5dp→0 outward ring over 1.8s;
 * it is not the older two-alpha-frame busyDot. Idle is a static hollow marker.
 * None is an empty same-size slot: no question mark, no「未知」text.
 *
 * @contract
 * @pre motion is already fail-closed (unknown/abnormal/offline → None)
 * @post Working pulses; Idle is static; None is an empty same-size slot
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
        SessionRowMotion.Idle -> Canvas(
            modifier = slot.semantics { contentDescription = "idle:static" },
        ) {
            drawCircle(
                color = SessionRowMarker.idleBorder,
                radius = size.minDimension / 2f - 0.75.dp.toPx(),
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
        SessionRowMotion.Working -> {
            val transition = rememberInfiniteTransition(label = "sessionRowPulse")
            val elapsed by transition.animateFloat(
                initialValue = 0f,
                targetValue = SessionRowMarker.durationMillis.toFloat(),
                animationSpec = infiniteRepeatable(
                    animation = tween(SessionRowMarker.durationMillis, easing = LinearEasing),
                ),
                label = "sessionRowPulseElapsed",
            )
            val frame = SessionRowMarker.frameAt(elapsed.toLong())
            Canvas(
                modifier = slot.semantics {
                    contentDescription =
                        "working:radius=${frame.ringRadiusDp}:alpha=${frame.ringAlpha}"
                },
            ) {
                val center = Offset(size.width / 2f, size.height / 2f)
                drawCircle(
                    color = SessionRowMarker.ring.copy(alpha = frame.ringAlpha),
                    radius = size.minDimension / 2f + frame.ringRadiusDp.dp.toPx(),
                    center = center,
                    style = Stroke(width = 1.dp.toPx()),
                )
                drawCircle(
                    color = SessionRowMarker.center,
                    radius = size.minDimension / 2f,
                    center = center,
                )
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
