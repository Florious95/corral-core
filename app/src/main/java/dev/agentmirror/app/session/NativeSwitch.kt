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

package dev.agentmirror.app.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.agentmirror.app.ui.components.ruledSurface
import dev.agentmirror.app.ui.theme.LocalAppPalette
import dev.agentmirror.app.ui.theme.LocalThemeSuite

/*
 * The way back from Pi's TUI to the native conversation on a managed pane: a small pill on the
 * terminal card and, when the session is mid-turn, the same consent the GUI asks for.
 */

@Composable
internal fun NativeSwitchPill(switching: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalAppPalette.current
    val kit = LocalThemeSuite.current
    val shape = kit.geometry.shape(RoundedCornerShape(50))
    Text(
        if (switching) "切换中…" else "⇄ 原生对话",
        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = p.accent),
        modifier = modifier
            .clip(shape)
            .background(p.screenBackground.copy(alpha = 0.92f), shape)
            .then(
                if (kit.surfaces.isGlass) Modifier.background(p.accentContainer, shape)
                else Modifier.ruledSurface(fill = p.sheetBackground, stroke = kit.colors.headerRule, strokeWidth = kit.geometry.hairline, shape = shape),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
            .testTag("session-native-switch"),
    )
}

@Composable
internal fun NativeSwitchConfirm(open: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val p = LocalAppPalette.current
    val kit = LocalThemeSuite.current
    AnimatedVisibility(visible = open, enter = fadeIn(tween(140)), exit = fadeOut(tween(120)), modifier = Modifier.zIndex(30f)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) },
            contentAlignment = Alignment.Center,
        ) {
            val shape = kit.geometry.shape(RoundedCornerShape(24.dp))
            Column(
                Modifier
                    .padding(horizontal = 28.dp)
                    .widthIn(max = 360.dp)
                    .fillMaxWidth()
                    .animateEnterExit(enter = scaleIn(spring(dampingRatio = 0.82f, stiffness = 560f), 0.94f), exit = scaleOut(tween(120), 0.97f))
                    .ruledSurface(fill = p.sheetBackground, stroke = if (kit.surfaces.isGlass) p.cardBorder else kit.colors.headerRule, strokeWidth = kit.geometry.hairline, shape = shape)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp)
                    .testTag("session-native-confirm"),
            ) {
                Text("切换回原生对话", style = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, color = p.titleText))
                Text(
                    "当前任务正在运行中，切换模式将中断并丢弃当前未完成任务，是否确认切换？",
                    style = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, color = p.bodyText),
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                    val button = kit.geometry.shape(RoundedCornerShape(50))
                    Text(
                        "取消",
                        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = p.titleText),
                        modifier = Modifier.clip(button).background(p.rowPressed).clickable(onClick = onDismiss)
                            .padding(horizontal = 18.dp, vertical = 10.dp).testTag("session-native-cancel"),
                    )
                    Text(
                        "确认切换",
                        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = p.onAccent),
                        modifier = Modifier.clip(button).background(p.accent).clickable(onClick = onConfirm)
                            .padding(horizontal = 18.dp, vertical = 10.dp).testTag("session-native-ok"),
                    )
                }
            }
        }
    }
}
