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
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Locale

/*
 * Transcript rows. Visual rules: the user speaks in a soft squircle bubble on the right; the
 * agent writes directly on the canvas (no box around prose); tools are compact engineering
 * cards that open with a spring; system moments are quiet dividers. Nothing shouts.
 */

internal val CaptionStyle = TextStyle(fontFamily = ConversationSans, fontSize = 11.5.sp, lineHeight = 15.sp, letterSpacing = 0.15.sp, lineHeightStyle = StableLines)
internal val LabelStyle = TextStyle(fontFamily = ConversationSans, fontSize = 13.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, lineHeightStyle = StableLines)
internal val MonoSmall = TextStyle(fontFamily = ConversationMono, fontSize = 12.sp, lineHeight = 16.sp, lineHeightStyle = StableLines)

/** Spring used by every disclosure: settles fast, a hint of give, no wobble. */
internal fun <T> disclosureSpring() = spring<T>(dampingRatio = 0.86f, stiffness = 420f)

/** A pasted log or a long brief folds to this many lines; the rest is one tap away. */
private const val USER_FOLD_LINES = 8

@Composable
fun UserBubble(turn: UserTurn, p: ConversationPalette, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(top = 14.dp), horizontalAlignment = Alignment.End) {
        val skill = turn.skill
        if (skill != null) {
            SkillCard(skill, p, expanded, onToggle)
            skill.request?.let { UserText(it, p, folded = false, onToggle = null, Modifier.padding(top = 8.dp)) }
        } else {
            val lines = remember(turn.text) { turn.text.count { it == '\n' } + 1 }
            val foldable = lines > USER_FOLD_LINES + 4 || turn.text.length > 1_200
            UserText(turn.text, p, folded = foldable && !expanded, onToggle = if (foldable) onToggle else null, lines = lines)
        }
        val meta = buildList {
            if (turn.imageCount > 0) add("${turn.imageCount} 张图片")
            when (turn.delivery) {
                Delivery.Sending -> add("发送中…")
                Delivery.Queued -> add("已排队，当前步骤结束后送达")
                Delivery.Delivered -> Unit
            }
        }
        if (meta.isNotEmpty()) {
            Row(Modifier.padding(top = 5.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (turn.imageCount > 0) GlyphIcon(Glyph.Photo, p.inkSoft, 13.dp, Modifier.padding(end = 4.dp))
                Text(meta.joinToString(" · "), style = CaptionStyle.copy(color = p.inkSoft))
            }
        }
    }
}

@Composable
private fun UserText(text: String, p: ConversationPalette, folded: Boolean, onToggle: (() -> Unit)?, modifier: Modifier = Modifier, lines: Int = 0) {
    val shape = lookShape(22.dp)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Spacer(Modifier.weight(0.14f))
        Column(
            Modifier
                .weight(0.86f, fill = false)
                .clip(shape)
                .background(p.userBubble)
                .hairlineBorder(LocalConversationLook.current, p, shape)
                .animateContentSize(disclosureSpring())
                .padding(horizontal = 15.dp, vertical = 10.dp)
                .testTag("conversation-user"),
        ) {
            SelectionContainer {
                Text(
                    text,
                    style = BodyStyle.copy(color = p.userInk),
                    maxLines = if (folded) USER_FOLD_LINES else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onToggle != null) {
                Text(
                    if (folded) "展开全文 · $lines 行" else "收起",
                    style = CaptionStyle.copy(color = p.userInk.copy(alpha = 0.72f), fontWeight = FontWeight.SemiBold),
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clip(lookPill())
                        .clickable(onClick = onToggle)
                        .padding(vertical = 4.dp)
                        .testTag("conversation-user-fold"),
                )
            }
        }
    }
}

/**
 * Pi's `/skill:name` expansion, folded: the wand, the skill's name, how much it brings and its
 * first line. The body (often hundreds of lines of instructions) opens on demand with the same
 * disclosure spring as tools, and is never parsed while folded.
 */
@Composable
fun SkillCard(skill: SkillBlock, p: ConversationPalette, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val shape = lookShape(20.dp)
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, disclosureSpring(), label = "skill-chevron")
    val lines = remember(skill.content) { if (skill.content.isEmpty()) 0 else skill.content.count { it == '\n' } + 1 }
    val summary = remember(skill.content) { skillSummary(skill.content) }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Spacer(Modifier.weight(0.06f))
        Column(
            Modifier
                .weight(0.94f)
                .clip(shape)
                .background(p.surface)
                .hairlineBorder(LocalConversationLook.current, p, shape)
                .testTag("conversation-skill"),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .heightIn(min = 58.dp)
                    .padding(start = 11.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(36.dp).glyphTile(p.accent, p, lookShape(11.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    GlyphIcon(Glyph.Wand, p.accentInk, 19.dp)
                }
                Column(Modifier.weight(1f).padding(start = 11.dp, end = 8.dp)) {
                    Text(
                        skill.name,
                        style = MonoSmall.copy(color = p.ink, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("conversation-skill-name"),
                    )
                    Text(
                        listOf("调用技能", summary).filter { it.isNotBlank() }.joinToString(" · "),
                        style = CaptionStyle.copy(color = p.inkSoft, fontSize = 12.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text(
                    "$lines 行",
                    style = CaptionStyle.copy(color = p.inkSoft, fontFamily = ConversationMono),
                    modifier = Modifier.clip(lookPill()).background(p.ink.copy(alpha = 0.05f)).padding(horizontal = 8.dp, vertical = 4.dp),
                )
                GlyphIcon(Glyph.Chevron, p.inkSoft, 16.dp, Modifier.padding(start = 6.dp).rotate(chevron))
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(disclosureSpring(), expandFrom = Alignment.Top) + fadeIn(tween(180, delayMillis = 40)),
                exit = shrinkVertically(disclosureSpring(), shrinkTowards = Alignment.Top) + fadeOut(tween(110)),
            ) {
                SkillBody(skill, p)
            }
        }
    }
}

@Composable
private fun SkillBody(skill: SkillBlock, p: ConversationPalette) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(skill) { mutableStateOf(false) }
    Column {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(p.surfaceStroke))
        Box(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            SelectionContainer(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp)) {
                MarkdownText(skill.content, p)
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                skill.location,
                style = MonoSmall.copy(color = p.inkSoft, fontSize = 11.sp),
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
                modifier = Modifier.weight(1f),
            )
            Row(
                Modifier
                    .padding(start = 8.dp)
                    .clip(lookPill())
                    .clickable {
                        clipboard.setText(AnnotatedString(listOfNotNull(skill.content, skill.request).joinToString("\n\n")))
                        copied = true
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("conversation-skill-copy"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                GlyphIcon(if (copied) Glyph.Check else Glyph.Copy, if (copied) p.success else p.inkSoft, 14.dp)
                Text(if (copied) "已复制" else "复制", style = CaptionStyle.copy(color = if (copied) p.success else p.inkSoft))
            }
        }
    }
}

/** The skill's first real line, without Markdown markers: usually its title. */
internal fun skillSummary(content: String): String = content.lineSequence()
    .map { it.trim() }
    .firstOrNull { it.isNotEmpty() && !it.startsWith("```") }
    ?.trimStart('#', '>', '-', '*', ' ')
    .orEmpty()
    .take(120)

@Composable
fun AssistantProse(item: AssistantText, p: ConversationPalette, modifier: Modifier = Modifier) {
    if (item.text.isBlank()) return
    SelectionContainer(modifier.fillMaxWidth().padding(horizontal = 2.dp).testTag("conversation-assistant")) {
        MarkdownText(item.text, p)
    }
}

@Composable
fun ReasoningRow(item: Reasoning, p: ConversationPalette, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, disclosureSpring(), label = "reasoning-chevron")
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .clip(lookPill())
                .clickable(onClick = onToggle)
                .padding(start = 4.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Breathing(item.streaming) { alpha -> GlyphIcon(Glyph.Spark, p.accentInk.copy(alpha = alpha), 14.dp) }
            val label = when {
                item.streaming -> "思考中"
                item.redacted -> "思考过程（已加密）"
                item.endedAt != null && item.startedAt > 0 -> "已思考 ${seconds(item.endedAt - item.startedAt)}"
                else -> "思考过程"
            }
            if (item.streaming) Shimmer(label, p) else Text(label, style = CaptionStyle.copy(color = p.inkSoft, fontSize = 12.5.sp))
            GlyphIcon(Glyph.Chevron, p.inkSoft, 14.dp, Modifier.rotate(chevron))
        }
        if (item.streaming && !expanded) {
            val tail = item.text.trimEnd().substringAfterLast('\n').takeLast(120)
            if (tail.isNotBlank()) {
                Text(
                    tail,
                    style = CaptionStyle.copy(color = p.inkSoft, fontStyle = FontStyle.Italic, fontSize = 12.5.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 25.dp, end = 8.dp),
                )
            }
        }
        AnimatedVisibility(
            visible = expanded && item.text.isNotBlank(),
            enter = expandVertically(disclosureSpring(), expandFrom = Alignment.Top) + fadeIn(tween(160)),
            exit = shrinkVertically(disclosureSpring(), shrinkTowards = Alignment.Top) + fadeOut(tween(120)),
        ) {
            Row(Modifier.padding(start = 10.dp, top = 4.dp, bottom = 4.dp).height(IntrinsicSize.Min)) {
                Box(Modifier.width(2.dp).fillMaxHeight().clip(lookPill()).background(p.inkFaint))
                Spacer(Modifier.width(12.dp))
                SelectionContainer {
                    Text(item.text.trim(), style = BodyStyle.copy(color = p.inkSoft, fontSize = 13.5.sp, lineHeight = 21.sp, fontStyle = FontStyle.Italic))
                }
            }
        }
    }
}

@Composable
fun ToolCallCard(tool: ToolCall, p: ConversationPalette, expanded: Boolean, skewMs: Long, onToggle: () -> Unit, modifier: Modifier = Modifier, streaming: Boolean = false) {
    val shape = lookShape(18.dp)
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, disclosureSpring(), label = "tool-chevron")
    val tone = when (tool.phase) {
        ToolPhase.Failed -> p.danger
        ToolPhase.Succeeded -> p.success
        else -> p.accentInk
    }
    // A flat pane: surface fill and one hairline. A failed call is outlined in danger so it
    // reads from across the transcript without any glow.
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.surface)
            .hairlineBorder(LocalConversationLook.current, p, shape, tone = p.danger.copy(alpha = 0.36f).takeIf { tool.phase == ToolPhase.Failed })
            .testTag("conversation-tool-${tool.id}"),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .heightIn(min = 54.dp)
                .padding(start = 11.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(32.dp).glyphTile(tone, p, lookShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                GlyphIcon(if (skillRead(tool) != null) Glyph.Wand else toolGlyph(tool.name), tone, 17.dp)
            }
            Column(Modifier.weight(1f).padding(start = 11.dp, end = 8.dp)) {
                Text(toolTitle(tool), style = LabelStyle.copy(color = p.ink, fontWeight = LocalConversationLook.current.titleWeight), maxLines = 1, overflow = TextOverflow.Ellipsis)
                val summary = toolSummary(tool)
                if (summary.isNotBlank()) {
                    Text(summary, style = MonoSmall.copy(color = p.inkSoft), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
            }
            ToolStatus(tool, p, skewMs, tool.shouldAnimate(streaming))
            GlyphIcon(Glyph.Chevron, p.inkSoft, 16.dp, Modifier.padding(start = 6.dp).rotate(chevron))
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(disclosureSpring(), expandFrom = Alignment.Top) + fadeIn(tween(180, delayMillis = 40)),
            exit = shrinkVertically(disclosureSpring(), shrinkTowards = Alignment.Top) + fadeOut(tween(110)),
        ) {
            Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val (language, input) = toolInput(tool)
                if (input.isNotBlank()) CodeBlock(language, input, p, maxLines = 40)
                when {
                    tool.output.isNotBlank() -> Box(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                        CodeBlock("输出", tool.output.trimEnd(), p)
                    }
                    tool.phase == ToolPhase.Running -> Text("等待输出…", style = CaptionStyle.copy(color = p.inkSoft), modifier = Modifier.padding(start = 4.dp))
                }
                val footer = buildList {
                    tool.exitCode?.let { add("exit $it") }
                    duration(tool)?.let(::add)
                    if (tool.truncated) add("输出已截断")
                }
                if (footer.isNotEmpty()) Text(footer.joinToString(" · "), style = CaptionStyle.copy(color = p.inkSoft), modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

/**
 * The status badge: a micro-glyph and a word, tinted only when the state wants attention
 * (running, failed, interrupted); a finished call stays neutral with a success tick. Motion is
 * reserved for the live turn — a historical pending call is a still hollow ring.
 */
@Composable
private fun ToolStatus(tool: ToolCall, p: ConversationPalette, skewMs: Long, streaming: Boolean) {
    val look = LocalConversationLook.current
    val tone = when (tool.phase) {
        ToolPhase.Running -> p.accentInk
        ToolPhase.Failed -> p.danger
        ToolPhase.Interrupted -> p.warning
        else -> null
    }
    val shape = look.pill()
    val fill = tone?.copy(alpha = if (p.dark) 0.14f else 0.10f) ?: p.ink.copy(alpha = 0.05f)
    Row(
        Modifier
            .clip(shape)
            .background(fill)
            .then(if (look.sharp) Modifier.border(look.hairline, (tone ?: p.inkSoft).copy(alpha = 0.45f), shape) else Modifier)
            .heightIn(min = 22.dp)
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .testTag("conversation-tool-status-${tool.phase.name.lowercase()}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        when (tool.phase) {
            ToolPhase.Composing, ToolPhase.Pending -> {
                // Pending can be an unfinished historical call, even during a new live turn.
                Breathing(streaming) { a -> Box(Modifier.size(7.dp).clip(shape).border(1.3.dp, p.inkSoft.copy(alpha = a), shape)) }
                Text(if (tool.phase == ToolPhase.Composing) "生成中" else "排队", style = CaptionStyle.copy(color = p.inkSoft))
            }
            ToolPhase.Running -> {
                Breathing(streaming) { a -> Box(Modifier.size(6.dp).clip(shape).background(p.accent.copy(alpha = a))) }
                var now by remember(tool.key, streaming) { mutableLongStateOf(System.currentTimeMillis()) }
                if (streaming) {
                    LaunchedEffect(tool.key) {
                        while (true) {
                            now = System.currentTimeMillis()
                            delay(100)
                        }
                    }
                }
                val elapsed = if (tool.startedAt > 0) (now + skewMs - tool.startedAt).coerceAtLeast(0) else 0
                Text(seconds(elapsed), style = CaptionStyle.copy(color = p.accentInk, fontFamily = ConversationMono))
            }
            ToolPhase.Succeeded -> {
                GlyphIcon(Glyph.Check, p.success, 12.dp)
                Text(duration(tool) ?: "完成", style = CaptionStyle.copy(color = p.inkSoft, fontFamily = ConversationMono))
            }
            ToolPhase.Failed -> {
                GlyphIcon(Glyph.Cross, p.danger, 11.dp)
                Text(tool.exitCode?.let { "exit $it" } ?: "失败", style = CaptionStyle.copy(color = p.danger, fontFamily = ConversationMono))
            }
            ToolPhase.Interrupted -> {
                GlyphIcon(Glyph.Stop, p.warning, 11.dp)
                Text("已中断", style = CaptionStyle.copy(color = p.warning))
            }
        }
    }
}

@Composable
fun NoticeRow(notice: Notice, p: ConversationPalette, modifier: Modifier = Modifier) {
    if (notice.tone == NoticeTone.Divider) {
        Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(0.6.dp).background(p.inkFaint))
            Text(
                listOfNotNull(notice.title, notice.detail).joinToString(" · "),
                style = CaptionStyle.copy(color = p.inkSoft, fontWeight = FontWeight.Medium),
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Box(Modifier.weight(1f).height(0.6.dp).background(p.inkFaint))
        }
        return
    }
    val tone = when (notice.tone) {
        NoticeTone.Error -> p.danger
        NoticeTone.Warning -> p.warning
        else -> p.accentInk
    }
    val shape = lookShape(16.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tone.copy(alpha = if (p.dark) 0.09f else 0.06f))
            .border(0.5.dp, tone.copy(alpha = 0.22f), shape)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.Top,
    ) {
        GlyphIcon(if (notice.tone == NoticeTone.Info) Glyph.Spark else Glyph.Cross.takeIf { notice.tone == NoticeTone.Error } ?: Glyph.Bolt, tone, 15.dp, Modifier.padding(top = 1.dp))
        Column(Modifier.padding(start = 10.dp)) {
            Text(notice.title, style = LabelStyle.copy(color = p.ink, fontSize = 13.sp))
            notice.detail?.let {
                Text(it, style = CaptionStyle.copy(color = p.inkSoft, fontSize = 12.5.sp, lineHeight = 18.sp), modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** Three breathing dots plus a calm label; appears only when nothing else on screen moves. */
@Composable
fun WorkingIndicator(label: String, p: ConversationPalette, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "working")
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "working-phase")
    Row(modifier.padding(start = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val t = ((phase - i * 0.18f) % 1f + 1f) % 1f
            val wave = if (t < 0.5f) FastOutSlowInEasing.transform(t * 2) else FastOutSlowInEasing.transform((1 - t) * 2)
            Box(
                Modifier
                    .padding(end = 5.dp)
                    .size(7.dp)
                    .scale(0.75f + 0.35f * wave)
                    .alpha(0.35f + 0.65f * wave)
                    .clip(lookPill())
                    .background(p.accent),
            )
        }
        Text(label, style = CaptionStyle.copy(color = p.inkSoft, fontSize = 12.5.sp), modifier = Modifier.padding(start = 6.dp))
    }
}

/** Placeholder rhythm of a real transcript while the first stream connects. */
@Composable
fun ConversationSkeleton(p: ConversationPalette, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val shift by transition.animateFloat(-1f, 2f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "skeleton-shift")
    val base = p.ink.copy(alpha = if (p.dark) 0.07f else 0.06f)
    val hi = p.ink.copy(alpha = if (p.dark) 0.13f else 0.11f)
    fun Modifier.shimmer() = drawBehind {
        drawRect(
            Brush.linearGradient(
                listOf(base, hi, base),
                start = Offset(size.width * shift - size.width, 0f),
                end = Offset(size.width * shift, size.height),
            ),
        )
    }
    Column(modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(Modifier.width(190.dp).height(42.dp).clip(lookShape(22.dp)).shimmer())
        }
        Box(Modifier.fillMaxWidth(0.92f).height(14.dp).clip(lookPill()).shimmer())
        Box(Modifier.fillMaxWidth(0.78f).height(14.dp).clip(lookPill()).shimmer())
        Box(Modifier.fillMaxWidth().height(54.dp).clip(lookShape(18.dp)).shimmer())
        Box(Modifier.fillMaxWidth(0.64f).height(14.dp).clip(lookPill()).shimmer())
    }
}

@Composable
fun ConversationEmpty(agent: String, model: String?, p: ConversationPalette, onSuggestion: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(60.dp)
                .clip(lookShape(20.dp))
                .background(p.accent),
            contentAlignment = Alignment.Center,
        ) {
            GlyphIcon(Glyph.Spark, p.onAccent, 28.dp)
        }
        Text("开始和 $agent 对话", style = TextStyle(fontFamily = ConversationSans, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = p.ink, letterSpacing = (-0.2).sp), modifier = Modifier.padding(top = 18.dp))
        Text(
            model?.let { "$it · 原生对话" } ?: "原生对话 · 结构化流式",
            style = CaptionStyle.copy(color = p.inkSoft, fontSize = 13.sp),
            modifier = Modifier.padding(top = 6.dp),
        )
        Column(Modifier.padding(top = 26.dp).widthIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("梳理这个仓库的结构和入口", "找出最近改动里可能的问题", "跑一遍测试并总结失败原因").forEach { text ->
                val shape = lookShape(16.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(p.surface)
                        .hairlineBorder(LocalConversationLook.current, p, shape)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSuggestion(text) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text, style = BodyStyle.copy(color = p.ink, fontSize = 14.sp, lineHeight = 20.sp), modifier = Modifier.weight(1f))
                    GlyphIcon(Glyph.Send, p.inkSoft, 15.dp, Modifier.rotate(45f))
                }
            }
        }
    }
}

@Composable
internal fun Breathing(active: Boolean, content: @Composable (Float) -> Unit) {
    if (!active) {
        content(1f)
        return
    }
    val transition = rememberInfiniteTransition(label = "breathing")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathing-alpha",
    )
    content(alpha)
}

@Composable
internal fun Shimmer(text: String, p: ConversationPalette) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(-0.6f, 1.6f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "shimmer-x")
    Text(
        text,
        style = CaptionStyle.copy(
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            brush = Brush.horizontalGradient(
                0f to p.inkSoft,
                (x - 0.25f).coerceIn(0f, 1f) to p.inkSoft,
                x.coerceIn(0f, 1f) to p.ink,
                (x + 0.25f).coerceIn(0f, 1f) to p.inkSoft,
                1f to p.inkSoft,
            ),
        ),
    )
}

// ---- tool presentation ------------------------------------------------------------------

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

/** A `read` of some skill's SKILL.md: the model loading a skill on its own. */
private fun skillRead(tool: ToolCall): String? {
    if (tool.name.lowercase() != "read") return null
    val path = tool.arguments?.let { it.text("path") ?: it.text("file_path") } ?: return null
    if (!path.endsWith("/SKILL.md") && path != "SKILL.md") return null
    return path.removeSuffix("SKILL.md").trimEnd('/').substringAfterLast('/').ifBlank { "skill" }
}

internal fun toolTitle(tool: ToolCall): String = if (skillRead(tool) != null) {
    if (tool.finished) "加载了技能" else "加载技能"
} else when (tool.name.lowercase()) {
    "bash", "shell", "exec" -> if (tool.finished) "运行了命令" else "运行命令"
    "read", "view" -> if (tool.finished) "读取了文件" else "读取文件"
    "edit", "multiedit", "patch", "apply_patch" -> if (tool.finished) "编辑了文件" else "编辑文件"
    "write" -> if (tool.finished) "写入了文件" else "写入文件"
    "grep", "search" -> "搜索内容"
    "find", "glob" -> "查找文件"
    "ls" -> "列出目录"
    else -> tool.name.ifBlank { "工具" }
}

/** One-line gist: the command, the path, or the pattern — readable while still streaming. */
internal fun toolSummary(tool: ToolCall): String {
    skillRead(tool)?.let { return it }
    val args = tool.arguments
    if (args == null) {
        // Arguments still streaming: show the first string value as it types itself out.
        val draft = Regex("""^\{"[^"]+":"((?:[^"\\]|\\.)*)""").find(tool.argumentsDraft)?.groupValues?.get(1) ?: return ""
        return draft.replace("\\n", " ").replace("\\\"", "\"")
    }
    val primary = args.text("command") ?: args.text("path") ?: args.text("file_path") ?: args.text("pattern") ?: args.text("query") ?: args.text("url")
    val secondary = if (args.text("pattern") != null) args.text("path") else null
    return listOfNotNull(primary?.lineSequence()?.firstOrNull(), secondary).joinToString("  ").ifBlank {
        args.entries.joinToString(" ") { (k, v) -> "$k=${(v as? JsonPrimitive)?.contentOrNull ?: v.toString()}" }.take(160)
    }
}

/** Expanded input: shell commands read as shell, everything else as tidy JSON. */
internal fun toolInput(tool: ToolCall): Pair<String, String> {
    val args = tool.arguments ?: return "json" to tool.argumentsDraft
    args.text("command")?.let { if (tool.name.lowercase() in setOf("bash", "shell", "exec")) return "bash" to "$ $it" }
    return "json" to prettyJson(args)
}

private fun prettyJson(obj: JsonObject, indent: String = ""): String = buildString {
    append("{\n")
    obj.entries.forEachIndexed { i, (k, v) ->
        append(indent).append("  \"").append(k).append("\": ")
        when (v) {
            is JsonObject -> append(prettyJson(v, "$indent  "))
            else -> append(v.toString())
        }
        if (i < obj.size - 1) append(',')
        append('\n')
    }
    append(indent).append('}')
}

private fun duration(tool: ToolCall): String? {
    val end = tool.endedAt ?: return null
    if (tool.startedAt <= 0) return null
    return seconds(end - tool.startedAt)
}

internal fun seconds(ms: Long): String = when {
    ms < 1_000 -> "${ms.coerceAtLeast(0)}ms"
    ms < 60_000 -> String.format(Locale.US, "%.1fs", ms / 1000.0)
    else -> "${ms / 60_000}m ${(ms / 1000) % 60}s"
}

/** The CLI's display name from its provider id ("pi" → "Pi", "grok" → "Grok"). */
internal fun agentName(provider: String): String = provider.replaceFirstChar { it.uppercase() }.ifBlank { "Agent" }
