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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.kyant.backdrop.Backdrop
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle

/*
 * Model and thinking strength, opened from the header capsule. Everything shown is what Pi itself
 * reports for this session (get_available_models / get_available_thinking_levels / get_state);
 * a tap sends set_model / set_thinking_level and the selection moves only when Pi confirms.
 */

/** Pi's thinking levels in the words a phone user reads. Unknown levels keep Pi's own name. */
internal fun thinkingLabel(level: String): String = when (level) {
    "off" -> "关闭"
    "minimal" -> "极简"
    "low" -> "低"
    "medium" -> "中"
    "high" -> "高"
    "xhigh" -> "超高"
    "max" -> "极限"
    else -> level
}

@Composable
internal fun ModelPicker(
    open: Boolean,
    state: ConversationState,
    pendingModel: ModelChoice?,
    pendingLevel: String?,
    p: ConversationPalette,
    backdrop: Backdrop,
    topPx: () -> Int,
    onDismiss: () -> Unit,
    onModel: (ModelChoice) -> Unit,
    onLevel: (String) -> Unit,
) {
    if (open) {
        Box(Modifier.fillMaxSize().zIndex(5f).background(Color.Black.copy(alpha = if (p.dark) 0.64f else 0.48f)).pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) })
    }
    Box(Modifier.fillMaxSize().zIndex(6f), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(tween(140)) + scaleIn(spring(dampingRatio = 0.82f, stiffness = 560f), 0.94f, TransformOrigin(0.5f, 0f)),
            exit = fadeOut(tween(110)) + scaleOut(tween(130), 0.97f, TransformOrigin(0.5f, 0f)),
            modifier = Modifier.offset { IntOffset(0, topPx()) }.padding(horizontal = 12.dp),
        ) {
            Column(
                Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .panelSurface(LocalConversationLook.current, backdrop, 24.dp, p, reading = true)
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp)
                    .testTag("conversation-model-picker"),
            ) {
                Eyebrow("模型", p)
                val models = state.models
                when {
                    models == null -> Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Breathing(true) { a -> Box(Modifier.size(7.dp).clip(lookPill()).background(p.accent.copy(alpha = a))) }
                        Text("正在读取可用模型…", style = CaptionStyle.copy(color = p.inkSoft, fontSize = 12.5.sp), modifier = Modifier.padding(start = 8.dp))
                    }
                    models.isEmpty() -> Text(
                        "主机上的 Pi 没有已授权的模型",
                        style = CaptionStyle.copy(color = p.inkSoft, fontSize = 12.5.sp),
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                    else -> Column(
                        Modifier.padding(top = 8.dp).heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val groups = remember(models) { models.groupBy { it.provider }.toList() }
                        groups.forEach { (provider, choices) ->
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (groups.size > 1) Text(provider, style = MonoSmall.copy(color = p.inkSoft, fontSize = 11.sp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    choices.forEach { choice ->
                                        val current = choice.id == state.modelId && choice.provider == state.modelProvider
                                        ModelChip(choice, current, pending = pendingModel == choice, p) { onModel(choice) }
                                    }
                                }
                            }
                        }
                    }
                }
                Box(Modifier.padding(vertical = 14.dp).fillMaxWidth().height(0.5.dp).background(p.surfaceStroke))
                Eyebrow("思考强度", p)
                val levels = state.thinkingLevels
                if (levels.size <= 1) {
                    Text(
                        if (levels.isEmpty()) "正在读取…" else "当前模型不支持调节思考强度",
                        style = CaptionStyle.copy(color = p.inkSoft, fontSize = 12.5.sp),
                        modifier = Modifier.padding(top = 10.dp),
                    )
                } else {
                    Segmented(levels, pendingLevel ?: state.thinkingLevel, pending = pendingLevel != null, p, onLevel, Modifier.padding(top = 10.dp))
                }
                if (state.running) {
                    Text(
                        "Agent 正在工作 · 新设置从下一步开始生效",
                        style = CaptionStyle.copy(color = p.inkSoft),
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Eyebrow(text: String, p: ConversationPalette) {
    Text(text, style = CaptionStyle.copy(color = p.inkSoft, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp))
}

@Composable
private fun ModelChip(choice: ModelChoice, current: Boolean, pending: Boolean, p: ConversationPalette, onClick: () -> Unit) {
    val fill = p.canvas
    val ink = p.ink
    val shape = lookPill()
    Row(
        Modifier
            .heightIn(min = 36.dp)
            .widthIn(max = 260.dp)
            .clip(shape)
            .background(fill)
            .border(0.5.dp, if (current) p.ink.copy(alpha = 0.56f) else p.surfaceStroke, shape)
            .clickable(enabled = !current && !pending, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 8.dp)
            .testTag("conversation-model-${choice.provider}/${choice.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when {
            pending -> Breathing(true) { a -> Box(Modifier.size(6.dp).clip(lookPill()).background(p.accent.copy(alpha = a))) }
            current -> GlyphIcon(Glyph.Check, ink, 13.dp)
        }
        Text(choice.name, style = LabelStyle.copy(color = ink, fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (choice.reasoning) GlyphIcon(Glyph.Spark, if (current) ink.copy(alpha = 0.8f) else p.accentInk, 11.dp)
    }
}

/** Equal segments over a sliding pill; the pill springs to the confirmed (or pending) level. */
@Composable
private fun Segmented(levels: List<String>, selected: String?, pending: Boolean, p: ConversationPalette, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val index = levels.indexOf(selected)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(lookPill())
            .background(p.canvas)
            .border(0.5.dp, p.rule, lookPill())
            .padding(3.dp)
            .testTag("conversation-thinking"),
    ) {
        val segment = maxWidth / levels.size
        val x by animateDpAsState(segment * index.coerceAtLeast(0), spring(dampingRatio = 0.78f, stiffness = 520f), label = "segment-x")
        if (index >= 0) {
            Box(
                Modifier
                    .offset(x = x)
                    .width(segment)
                    .fillMaxHeight()
                    .clip(lookPill())
                    .background(p.canvas)
                    .border(0.5.dp, p.ink.copy(alpha = if (pending) 0.32f else 0.56f), lookPill()),
            )
        }
        Row(Modifier.fillMaxSize()) {
            levels.forEachIndexed { i, level ->
                val on = i == index
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(lookPill())
                        .clickable(enabled = !on && !pending, interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(level) }
                        .testTag("conversation-thinking-$level"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        thinkingLabel(level),
                        style = LabelStyle.copy(color = p.ink, fontSize = 12.5.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
