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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.kyant.backdrop.Backdrop
import com.kyant.shapes.RoundedRectangle
import dev.agentmirror.app.ui.components.RuleEdge
import dev.agentmirror.app.ui.components.edgeRule

/*
 * One bottom sheet for every conversation task panel (history, compaction, usage): the same
 * scrim, the same header, the same drag-to-dismiss, so each panel only brings its content.
 *
 *   Liquid Glass — a floating planar frosted card, 28dp squircle, a capsule grabber.
 *   Modernism    — a full-bleed brushed-titanium slab under a heavy ink rule, a mono eyebrow,
 *                  a ruled header base and a ruled footer; right angles everywhere.
 *
 * Nothing in it moves while idle: enter/exit are one short slide+fade, and the drag offset is a
 * graphicsLayer translation (no relayout per pointer move).
 */

private const val SHEET_ENTER_MS = 220
private const val SHEET_EXIT_MS = 160
private val DISMISS_DRAG = 96.dp
private const val DISMISS_VELOCITY = 1_400f

@Composable
internal fun ConversationSheet(
    open: Boolean,
    title: String,
    eyebrow: String,
    subtitle: String?,
    p: ConversationPalette,
    backdrop: Backdrop,
    tag: String,
    closeTag: String,
    onDismiss: () -> Unit,
    dismissible: Boolean = true,
    scrollable: Boolean = true,
    headerAction: (@Composable () -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val look = LocalConversationLook.current
    AnimatedVisibility(
        visible = open,
        enter = fadeIn(tween(SHEET_ENTER_MS)),
        exit = fadeOut(tween(SHEET_EXIT_MS)),
        modifier = Modifier.zIndex(7f),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = if (p.dark) 0.5f else 0.3f))
                    .pointerInput(dismissible) { detectTapGestures { if (dismissible) onDismiss() } },
            )
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(
                        if (look.glass) WindowInsets.statusBars.union(WindowInsets.navigationBars).union(WindowInsets.ime)
                        else WindowInsets.statusBars,
                    )
                    .padding(if (look.glass) 12.dp else 0.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                val maxPanel = maxHeight * if (look.glass) 0.9f else 0.94f
                val dismissPx = with(LocalDensity.current) { DISMISS_DRAG.toPx() }
                var drag by remember { mutableFloatStateOf(0f) }
                val dragState = rememberDraggableState { delta -> drag = (drag + delta).coerceAtLeast(0f) }
                val shape = if (look.glass) RoundedRectangle(28.dp) else null
                Column(
                    Modifier
                        .widthIn(max = 560.dp)
                        .fillMaxWidth()
                        .heightIn(max = maxPanel)
                        .animateEnterExit(
                            enter = slideInVertically(tween(SHEET_ENTER_MS, easing = FastOutSlowInEasing)) { it / 3 },
                            exit = slideOutVertically(tween(SHEET_EXIT_MS)) { it / 3 },
                        )
                        .graphicsLayer { translationY = drag }
                        .then(
                            if (shape != null) Modifier.frostedGlass(backdrop, shape, p, reading = true)
                            else Modifier.brushedMetal(p).edgeRule(RuleEdge.Top, p.ink, look.rule),
                        )
                        // Taps inside the panel never reach the scrim.
                        .pointerInput(Unit) { detectTapGestures { } }
                        .then(if (look.glass) Modifier else Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)))
                        .testTag(tag),
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .draggable(
                                dragState,
                                Orientation.Vertical,
                                enabled = dismissible,
                                onDragStopped = { velocity ->
                                    if (drag > dismissPx || velocity > DISMISS_VELOCITY) {
                                        onDismiss()
                                    } else {
                                        animate(drag, 0f, animationSpec = spring(dampingRatio = 0.9f, stiffness = 700f)) { value, _ -> drag = value }
                                    }
                                },
                            ),
                    ) {
                        if (look.glass) {
                            Box(
                                Modifier
                                    .padding(top = 8.dp)
                                    .align(Alignment.CenterHorizontally)
                                    .size(width = 36.dp, height = 4.dp)
                                    .clip(look.pill())
                                    .background(p.ink.copy(alpha = if (dismissible) 0.18f else 0.08f)),
                            )
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(start = 20.dp, end = if (look.glass) 12.dp else 20.dp, top = if (look.glass) 10.dp else 16.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                if (!look.glass) {
                                    Text(eyebrow, style = MonoSmall.copy(color = p.inkSoft, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp))
                                }
                                Text(
                                    title,
                                    style = TextStyle(fontFamily = ConversationSans, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = look.titleWeight, color = p.ink, letterSpacing = (-0.1).sp),
                                    modifier = Modifier.padding(top = if (look.glass) 0.dp else 2.dp),
                                )
                                if (subtitle != null) {
                                    Text(subtitle, style = CaptionStyle.copy(color = p.inkSoft, fontSize = 12.5.sp, lineHeight = 17.sp), modifier = Modifier.padding(top = 2.dp))
                                }
                            }
                            headerAction?.invoke()
                            if (dismissible) SheetIconButton(Glyph.Cross, "关闭", p, closeTag, onDismiss)
                        }
                        if (!look.glass) Box(Modifier.fillMaxWidth().height(look.hairline).background(p.rule))
                    }
                    // The top inset stays outside the scroll: scrolled content clips at a margin,
                    // never against the header rule.
                    Column(
                        Modifier
                            .weight(1f, fill = false)
                            .padding(top = if (look.glass) 2.dp else 14.dp)
                            .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                            .padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
                        content = content,
                    )
                    if (footer != null) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .then(if (look.glass) Modifier else Modifier.edgeRule(RuleEdge.Top, p.ink, look.rule))
                                .padding(start = 20.dp, end = 20.dp, top = if (look.glass) 4.dp else 12.dp, bottom = 16.dp),
                            content = footer,
                        )
                    }
                }
            }
        }
    }
}

/** A 40dp glyph button for sheet headers (close, refresh); the target stays ≥40dp in both looks. */
@Composable
internal fun SheetIconButton(glyph: Glyph, label: String, p: ConversationPalette, tag: String, onClick: () -> Unit) {
    val look = LocalConversationLook.current
    val shape = look.pill()
    Box(
        Modifier
            .padding(start = 4.dp)
            .size(40.dp)
            .clip(shape)
            .background(p.ink.copy(alpha = if (look.glass) 0.06f else 0f))
            .then(if (look.glass) Modifier else Modifier.border(look.hairline, p.rule, shape))
            .clickable(onClick = onClick)
            .semantics { contentDescription = label; role = Role.Button }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, p.inkSoft, 16.dp)
    }
}

internal enum class SheetButtonKind { Primary, Danger, Secondary }

/** A 48dp action: accent fill (primary), danger wash (destructive), or a quiet tonal/ruled one. */
@Composable
internal fun SheetButton(
    label: String,
    kind: SheetButtonKind,
    p: ConversationPalette,
    tag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val look = LocalConversationLook.current
    val shape = look.pill()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val (fill, ink) = when {
        !enabled -> p.ink.copy(alpha = 0.06f) to p.inkSoft
        kind == SheetButtonKind.Primary -> p.accent to p.onAccent
        kind == SheetButtonKind.Danger -> p.danger.copy(alpha = if (p.dark) 0.18f else 0.12f) to p.danger
        else -> p.ink.copy(alpha = if (look.glass) 0.07f else 0f) to p.ink
    }
    val outline = when {
        look.glass -> null
        kind == SheetButtonKind.Danger && enabled -> p.danger
        kind == SheetButtonKind.Secondary || !enabled -> p.rule
        else -> null
    }
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(fill)
            .then(if (outline != null) Modifier.border(look.hairline, outline, shape) else Modifier)
            .background(if (pressed) p.ink.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = LabelStyle.copy(color = ink, fontSize = 14.5.sp, fontWeight = look.titleWeight), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** A section label: quiet, tracked, never all-caps CJK. */
@Composable
internal fun SheetLabel(text: String, p: ConversationPalette, modifier: Modifier = Modifier) {
    Text(text, style = CaptionStyle.copy(color = p.inkSoft, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp), modifier = modifier)
}

/** A label/value line; Modernism rules it off from its neighbour. */
@Composable
internal fun SheetFact(label: String, value: String, p: ConversationPalette, valueColor: Color = p.ink, tag: String? = null) {
    val look = LocalConversationLook.current
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (look.glass) Modifier else Modifier.edgeRule(RuleEdge.Bottom, p.rule, look.hairline))
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = BodyStyle.copy(color = p.inkSoft, fontSize = 13.5.sp, lineHeight = 19.sp), modifier = Modifier.weight(1f))
        Text(
            value,
            style = BodyStyle.copy(color = valueColor, fontSize = 13.5.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
            modifier = Modifier.padding(start = 12.dp).then(if (tag != null) Modifier.testTag(tag) else Modifier),
        )
    }
}

/** A calm callout: glyph + text on a tone wash; Modernism adds a ruled edge instead of a fill. */
@Composable
internal fun SheetCallout(glyph: Glyph, text: String, tone: Color, p: ConversationPalette, modifier: Modifier = Modifier, tag: String? = null) {
    val look = LocalConversationLook.current
    val shape = look.shape(14.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tone.copy(alpha = if (look.glass) (if (p.dark) 0.10f else 0.07f) else 0.05f))
            .then(if (look.glass) Modifier else Modifier.edgeRule(RuleEdge.Start, tone, 2.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
        verticalAlignment = Alignment.Top,
    ) {
        GlyphIcon(glyph, tone, 15.dp, Modifier.padding(top = 2.dp))
        Text(text, style = BodyStyle.copy(color = p.ink, fontSize = 13.sp, lineHeight = 19.sp), modifier = Modifier.padding(start = 9.dp))
    }
}

/** A live-only status line: the dot breathes while work is really in flight, and only then. */
@Composable
internal fun SheetProgress(text: String, detail: String?, p: ConversationPalette, tag: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag(tag), verticalAlignment = Alignment.Top) {
        Breathing(true) { a -> Box(Modifier.padding(top = 6.dp).size(8.dp).clip(lookPill()).background(p.accent.copy(alpha = a))) }
        Column(Modifier.padding(start = 10.dp)) {
            Text(text, style = BodyStyle.copy(color = p.ink, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium))
            if (detail != null) Text(detail, style = CaptionStyle.copy(color = p.inkSoft, fontSize = 12.5.sp, lineHeight = 17.sp), modifier = Modifier.padding(top = 2.dp))
        }
    }
}
