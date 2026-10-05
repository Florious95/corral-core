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

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle

/** An image picked for the next prompt: shown as a thumbnail while it uploads to the host. */
data class PendingImage(val id: Long, val preview: Bitmap?, val hostPath: String? = null, val failed: Boolean = false)

/** What rises above the dock. Only one at a time. */
enum class ComposerSheet { None, Slash, Shortcuts, Attach }

/** A row of the composer sheet. */
data class SheetEntry(val key: String, val title: String, val detail: String, val glyph: Glyph, val match: Int = 0)

/** Slash candidates: host commands first-class, RPC built-ins always present. */
fun slashEntries(query: String, commands: List<SlashCommand>): List<SheetEntry> {
    val q = query.removePrefix("/").lowercase()
    val builtIn = listOf(
        SlashCommand("compact", "压缩上下文，保留要点继续工作", "builtin"),
        SlashCommand("new", "开始一个全新的会话", "builtin"),
    )
    return (builtIn + commands).distinctBy { it.name }
        .mapNotNull { c ->
            val name = c.name.lowercase()
            val rank = when {
                q.isEmpty() -> 1
                name.startsWith(q) -> 0
                name.contains(q) -> 1
                c.description.lowercase().contains(q) -> 2
                else -> return@mapNotNull null
            }
            SheetEntry(
                key = "/${c.name}",
                title = "/${c.name}",
                detail = c.description.ifBlank { sourceLabel(c.source) },
                glyph = when (c.source) {
                    "skill" -> Glyph.Spark
                    "prompt" -> Glyph.File
                    "builtin" -> Glyph.Refresh
                    else -> Glyph.Bolt
                },
                match = if (name.startsWith(q)) q.length else 0,
            ) to rank
        }
        .sortedBy { it.second }
        .map { it.first }
}

private fun sourceLabel(source: String) = when (source) {
    "skill" -> "技能"
    "prompt" -> "提示模板"
    "extension" -> "扩展命令"
    else -> "命令"
}

/**
 * Floating frosted capsule: ⚡ shortcuts, attachments, the prompt, and one round action that is
 * Send, Stop (agent working, nothing typed) or disabled. No terminal key row exists here.
 */
@Composable
fun ConversationDock(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    images: List<PendingImage>,
    onRemoveImage: (Long) -> Unit,
    sheet: ComposerSheet,
    sheetEntries: List<SheetEntry>,
    onSheet: (ComposerSheet) -> Unit,
    onSheetEntry: (SheetEntry) -> Unit,
    running: Boolean,
    connected: Boolean,
    placeholder: String,
    onSend: () -> Unit,
    onStop: () -> Unit,
    backdrop: Backdrop,
    p: ConversationPalette,
    modifier: Modifier = Modifier,
) {
    val uploading = images.any { it.hostPath == null && !it.failed }
    val hasContent = value.text.isNotBlank() || images.any { it.hostPath != null }
    val action = when {
        hasContent && connected && !uploading -> DockAction.Send
        running && !hasContent && connected -> DockAction.Stop
        else -> DockAction.Disabled
    }
    Column(modifier.fillMaxWidth()) {
        AnimatedVisibility(
            visible = sheet != ComposerSheet.None && sheetEntries.isNotEmpty(),
            enter = fadeIn(tween(140)) + slideInVertically(spring(dampingRatio = 0.82f, stiffness = 520f)) { it / 5 } +
                scaleIn(spring(dampingRatio = 0.82f, stiffness = 520f), initialScale = 0.97f, transformOrigin = TransformOrigin(0.5f, 1f)),
            exit = fadeOut(tween(110)) + slideOutVertically(tween(140)) { it / 8 } + scaleOut(tween(140), targetScale = 0.98f, transformOrigin = TransformOrigin(0.5f, 1f)),
        ) {
            ComposerSheetPanel(sheetEntries, onSheetEntry, backdrop, p, Modifier.padding(bottom = 8.dp))
        }
        val shape = RoundedRectangle(26.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .frostedGlass(backdrop, shape, p)
                .testTag("conversation-dock"),
        ) {
            if (images.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    images.forEach { image -> ImageChip(image, onRemoveImage, p) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.Bottom) {
                DockIconButton(Glyph.Bolt, p, sheet == ComposerSheet.Shortcuts, "conversation-shortcuts") {
                    onSheet(if (sheet == ComposerSheet.Shortcuts) ComposerSheet.None else ComposerSheet.Shortcuts)
                }
                DockIconButton(Glyph.Clip, p, sheet == ComposerSheet.Attach, "conversation-attach") {
                    onSheet(if (sheet == ComposerSheet.Attach) ComposerSheet.None else ComposerSheet.Attach)
                }
                Box(Modifier.weight(1f).heightIn(min = 40.dp).padding(start = 6.dp, end = 6.dp), contentAlignment = Alignment.CenterStart) {
                    if (value.text.isEmpty()) {
                        Text(placeholder, style = BodyStyle.copy(color = p.inkSoft), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        textStyle = BodyStyle.copy(color = p.ink),
                        cursorBrush = SolidColor(p.accent),
                        maxLines = 6,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("conversation-input"),
                    )
                }
                SendButton(action, p, onSend = onSend, onStop = onStop)
            }
        }
    }
}

private enum class DockAction { Send, Stop, Disabled }

@Composable
private fun SendButton(action: DockAction, p: ConversationPalette, onSend: () -> Unit, onStop: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium), label = "send-press")
    val fill by animateColorAsState(
        when (action) {
            DockAction.Send -> p.accent
            DockAction.Stop -> p.ink
            DockAction.Disabled -> p.ink.copy(alpha = 0.08f)
        },
        tween(180),
        label = "send-fill",
    )
    Box(
        Modifier
            .size(40.dp)
            .scale(scale)
            .clip(Capsule())
            .background(fill)
            .clickable(interactionSource = interaction, indication = null, enabled = action != DockAction.Disabled) {
                if (action == DockAction.Stop) onStop() else onSend()
            }
            .testTag(if (action == DockAction.Stop) "conversation-stop" else "conversation-send"),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = action,
            transitionSpec = { (fadeIn(tween(140)) + scaleIn(tween(160), 0.7f)) togetherWith (fadeOut(tween(100)) + scaleOut(tween(120), 0.7f)) },
            label = "send-glyph",
        ) { a ->
            when (a) {
                DockAction.Send -> GlyphIcon(Glyph.Send, p.onAccent, 20.dp)
                DockAction.Stop -> GlyphIcon(Glyph.Stop, p.canvas, 18.dp)
                DockAction.Disabled -> GlyphIcon(Glyph.Send, p.inkSoft.copy(alpha = 0.7f), 20.dp)
            }
        }
    }
}

@Composable
private fun DockIconButton(glyph: Glyph, p: ConversationPalette, active: Boolean, tag: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.88f else 1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium), label = "dock-press")
    val fill by animateColorAsState(if (active) p.accent.copy(alpha = 0.16f) else Color.Transparent, tween(160), label = "dock-active")
    Box(
        Modifier
            .size(40.dp)
            .scale(scale)
            .clip(Capsule())
            .background(fill)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, if (active) p.accentInk else p.inkSoft, 20.dp)
    }
}

@Composable
private fun ImageChip(image: PendingImage, onRemove: (Long) -> Unit, p: ConversationPalette) {
    val shape = RoundedRectangle(12.dp)
    Box(Modifier.size(52.dp)) {
        Box(Modifier.size(48.dp).padding(top = 4.dp).clip(shape).background(p.code).border(0.5.dp, p.surfaceStroke, shape)) {
            image.preview?.let {
                Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp))
            }
            if (image.hostPath == null && !image.failed) {
                Box(Modifier.size(48.dp).background(p.canvas.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                    Breathing(true) { a -> GlyphIcon(Glyph.Refresh, p.ink.copy(alpha = a), 16.dp) }
                }
            }
            if (image.failed) {
                Box(Modifier.size(48.dp).background(p.danger.copy(alpha = 0.28f)), contentAlignment = Alignment.Center) {
                    GlyphIcon(Glyph.Cross, p.ink, 16.dp)
                }
            }
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .size(20.dp)
                .clip(Capsule())
                .background(p.ink)
                .clickable { onRemove(image.id) },
            contentAlignment = Alignment.Center,
        ) {
            GlyphIcon(Glyph.Cross, p.canvas, 10.dp)
        }
    }
}

@Composable
private fun ComposerSheetPanel(entries: List<SheetEntry>, onEntry: (SheetEntry) -> Unit, backdrop: Backdrop, p: ConversationPalette, modifier: Modifier = Modifier) {
    val shape = RoundedRectangle(22.dp)
    LazyColumn(
        modifier
            .fillMaxWidth()
            .heightIn(max = 300.dp)
            .frostedGlass(backdrop, shape, p)
            .testTag("conversation-sheet"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp),
    ) {
        items(entries, key = { it.key }) { entry ->
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedRectangle(16.dp))
                    .background(if (pressed) p.accent.copy(alpha = 0.12f) else Color.Transparent)
                    .clickable(interactionSource = interaction, indication = null) { onEntry(entry) }
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    .testTag("conversation-sheet-${entry.key}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(32.dp).clip(RoundedRectangle(10.dp)).background(p.ink.copy(alpha = 0.06f)),
                    contentAlignment = Alignment.Center,
                ) { GlyphIcon(entry.glyph, p.accentInk, 16.dp) }
                Column(Modifier.weight(1f).padding(start = 11.dp)) {
                    val title = buildAnnotatedString {
                        val split = (entry.match + 1).coerceAtMost(entry.title.length).takeIf { entry.match > 0 && entry.title.startsWith("/") } ?: 0
                        withStyle(SpanStyle(color = p.accentInk)) { append(entry.title.take(split)) }
                        append(entry.title.drop(split))
                    }
                    Text(
                        title,
                        style = TextStyle(fontFamily = if (entry.title.startsWith("/")) ConversationMono else ConversationSans, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = p.ink),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (entry.detail.isNotBlank()) {
                        Text(entry.detail, style = CaptionStyle.copy(color = p.inkSoft, fontSize = 12.sp, lineHeight = 16.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** Small round glass button used by the header and the jump-to-latest control. */
@Composable
fun GlassCircleButton(glyph: Glyph, p: ConversationPalette, backdrop: Backdrop, tag: String, size: Dp = 40.dp, tint: Color = p.ink, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium), label = "circle-press")
    Box(
        Modifier
            .size(size)
            .scale(scale)
            .frostedGlass(backdrop, Capsule(), p)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, tint, size * 0.48f)
    }
}
