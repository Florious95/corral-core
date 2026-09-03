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

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.agentmirror.app.ui.model.SessionRowMotion
import dev.agentmirror.app.ui.theme.Dims
import dev.agentmirror.app.ui.theme.LocalAppPalette
import dev.agentmirror.app.ui.theme.Motion

/**
 * Existing list/CLI working lamp (StatusChip busyDot / SessionShell RunningDot).
 * Frames: alpha 1.0 ↔ 0.35, tween(Motion.statusDotPulse / 2, emphasized), Reverse.
 * Idle uses the same 5dp lamp without the infinite transition. None is an empty
 * same-size slot: no question mark, no「未知」text.
 */
@Composable
fun CliWorkingLamp(
    motion: SessionRowMotion,
    modifier: Modifier = Modifier,
) {
    val p = LocalAppPalette.current
    val slot = modifier.size(Dims.statusDotSize)
    when (motion) {
        SessionRowMotion.None -> Box(slot)
        SessionRowMotion.Idle -> Box(
            slot
                .clip(CircleShape)
                .background(p.idleChipText)
                .semantics { contentDescription = "idle:static" },
        )
        SessionRowMotion.Working -> {
            val transition = rememberInfiniteTransition(label = "busyDot")
            val pulse by transition.animateFloat(
                initialValue = 1f,
                targetValue = 0.35f,
                animationSpec = infiniteRepeatable(
                    animation = tween(Motion.statusDotPulse / 2, easing = Motion.emphasized),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "busyDotAlpha",
            )
            Box(
                slot
                    .alpha(pulse)
                    .clip(CircleShape)
                    .background(p.busyDot)
                    .semantics { contentDescription = "working:$pulse" },
            )
        }
    }
}

fun motionTestTag(prefix: String, id: String): String = "$prefix-motion-$id"

@Composable
fun CliWorkingLampTagged(
    motion: SessionRowMotion,
    prefix: String,
    id: String,
    modifier: Modifier = Modifier,
) {
    CliWorkingLamp(motion, modifier.testTag(motionTestTag(prefix, id)))
}
