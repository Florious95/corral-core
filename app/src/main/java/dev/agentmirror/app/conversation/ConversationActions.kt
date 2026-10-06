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

// @contract
// @pre the agent provider and link state are the only inputs; the menu sends nothing by itself
// @post history is always row 0; an action this agent cannot do is hidden, one it cannot do *now*
//       is shown disabled with the reason
// @inv a click re-resolves against the latest state before acting (no stale enabled row acts)

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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.kyant.backdrop.Backdrop

/**
 * What this client knows each agent's native bridge can do — one table instead of scattered
 * provider checks. Pi: catalogued history, get_session_stats, typed compact.customInstructions,
 * and compact aborts in-flight work itself. Grok: native list/load history and read-only
 * context/session-info stats are mapped; compact stays the native bare `/compact` command.
 */
internal data class AgentAbilities(
    val history: Boolean,
    val usage: Boolean,
    val compactInstructions: Boolean,
    val compactInterruptsWork: Boolean,
    val export: Boolean = false,
) {
    companion object {
        fun of(provider: String) = when (provider) {
            "pi" -> AgentAbilities(history = true, usage = true, compactInstructions = true, compactInterruptsWork = true, export = true)
            "grok" -> AgentAbilities(history = true, usage = true, compactInstructions = false, compactInterruptsWork = false, export = true)
            else -> AgentAbilities(history = false, usage = false, compactInstructions = false, compactInterruptsWork = false)
        }
    }
}

/** The overflow menu's registry, in display order; [group] draws the hairline breaks. */
internal enum class ConversationAction(val glyph: Glyph, val title: String, val hint: String?, val group: Int, val tag: String) {
    History(Glyph.History, "历史会话", null, 0, "conversation-menu-history"),
    Compact(Glyph.Compress, "压缩上下文", "/compact", 1, "conversation-menu-compact"),
    NewSession(Glyph.NewChat, "开始新会话", "/new", 1, "conversation-menu-new"),
    Fork(Glyph.NewChat, "从消息分叉", null, 1, "conversation-menu-fork"),
    Clone(Glyph.NewChat, "克隆当前会话", null, 1, "conversation-menu-clone"),
    Rewind(Glyph.History, "回滚轮次", null, 1, "conversation-menu-rewind"),
    Tasks(Glyph.Terminal, "原生任务与命令", null, 2, "conversation-menu-tasks"),
    Export(Glyph.File, "导出会话", null, 2, "conversation-menu-export"),
    Usage(Glyph.Gauge, "用量与上下文", null, 2, "conversation-menu-usage"),
    Terminal(Glyph.Terminal, "切换到终端", null, 3, "conversation-menu-terminal"),
}

internal data class ResolvedAction(val action: ConversationAction, val detail: String, val enabled: Boolean)

/**
 * Resolves the registry against the live state. Hidden = this agent cannot do it at all;
 * disabled = it can, just not now (the detail says why). History is never hidden: it is the
 * menu's anchor, so an agent without a catalogue says so instead of showing an empty list.
 */
internal fun resolveActions(provider: String, connected: Boolean, restoring: Boolean, compacting: Boolean): List<ResolvedAction> {
    val can = AgentAbilities.of(provider)
    val offline = when {
        restoring -> "恢复历史完成后可用"
        !connected -> "连接后可用"
        else -> null
    }
    return ConversationAction.entries.mapNotNull { action ->
        when (action) {
            ConversationAction.History ->
                if (!can.history) ResolvedAction(action, "${agentName(provider)} 暂不支持浏览历史会话", false)
                else ResolvedAction(action, offline ?: "浏览并恢复此目录的会话", offline == null)
            ConversationAction.Compact -> when {
                compacting -> ResolvedAction(action, "正在压缩…", true)
                else -> ResolvedAction(action, offline ?: if (can.compactInstructions) "可指定保留重点" else "整理模型上下文", offline == null)
            }
            ConversationAction.NewSession -> ResolvedAction(action, offline ?: "当前会话保存在历史中", offline == null)
            ConversationAction.Fork -> ResolvedAction(action, offline ?: if (provider == "pi") "选择用户消息，新上下文停在它之前" else "Grok 仅证实完整克隆，不伪造节点分叉", offline == null && provider == "pi")
            ConversationAction.Clone -> if (provider !in setOf("pi", "grok")) null else ResolvedAction(action, offline ?: "复制完整当前上下文，原会话保持不变", offline == null)
            ConversationAction.Rewind -> ResolvedAction(action, offline ?: if (provider == "grok") "原生回滚未取得成功闭包，不伪造截断" else "Pi RPC 没有原生回滚；可使用分叉", false)
            ConversationAction.Tasks -> if (provider != "grok") null else ResolvedAction(action, offline ?: "研究、工作流、目标及已广告原生命令", offline == null)
            ConversationAction.Export -> if (!can.export) null else ResolvedAction(action, offline ?: "下载并分享原生会话文件", offline == null)
            ConversationAction.Usage -> if (!can.usage) null else ResolvedAction(action, offline ?: "Token、费用与上下文占用", offline == null)
            ConversationAction.Terminal -> ResolvedAction(action, offline ?: "同一面板运行原生终端", offline == null)
        }
    }
}

/** The ⋮ popover: a short grouped list anchored under the header's right edge. */
@Composable
internal fun HeaderMenu(
    open: Boolean,
    actions: List<ResolvedAction>,
    p: ConversationPalette,
    backdrop: Backdrop,
    topPx: () -> Int,
    onDismiss: () -> Unit,
    onAction: (ConversationAction) -> Unit,
) {
    if (open) {
        Box(Modifier.fillMaxSize().zIndex(5f).background(Color.Black.copy(alpha = if (p.dark) 0.64f else 0.48f)).pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) })
    }
    val look = LocalConversationLook.current
    Box(Modifier.fillMaxSize().zIndex(6f), contentAlignment = Alignment.TopEnd) {
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(tween(120)) + scaleIn(spring(dampingRatio = 0.86f, stiffness = 640f), 0.92f, TransformOrigin(1f, 0f)),
            exit = fadeOut(tween(100)) + scaleOut(tween(120), 0.96f, TransformOrigin(1f, 0f)),
            modifier = Modifier.offset { IntOffset(0, topPx()) }.padding(end = 12.dp),
        ) {
            Column(
                Modifier
                    .widthIn(min = 248.dp, max = 284.dp)
                    .heightIn(max = 500.dp)
                    .panelSurface(look, backdrop, 22.dp, p, reading = true)
                    .padding(vertical = if (look.glass) 6.dp else 0.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("conversation-menu"),
            ) {
                actions.forEachIndexed { i, resolved ->
                    if (i > 0 && actions[i - 1].action.group != resolved.action.group) {
                        Box(
                            Modifier
                                .padding(horizontal = if (look.glass) 14.dp else 0.dp, vertical = if (look.glass) 4.dp else 0.dp)
                                .fillMaxWidth()
                                .height(look.hairline)
                                .background(if (look.glass) p.surfaceStroke else p.rule),
                        )
                    }
                    MenuRow(resolved, p) { onAction(resolved.action) }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(resolved: ResolvedAction, p: ConversationPalette, onClick: () -> Unit) {
    val look = LocalConversationLook.current
    val action = resolved.action
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = if (look.glass) 6.dp else 0.dp)
            .clip(look.shape(14.dp))
            .background(p.canvas)
            .clickable(enabled = resolved.enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag(action.tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .then(if (look.glass) Modifier.glyphTile(if (resolved.enabled) p.accent else p.inkSoft, p, look.shape(10.dp)) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            GlyphIcon(action.glyph, if (resolved.enabled) p.accentInk else p.inkSoft, 18.dp)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(action.title, style = LabelStyle.copy(color = if (resolved.enabled) p.ink else p.inkSoft, fontSize = 14.5.sp, fontWeight = look.titleWeight))
            Text(resolved.detail, style = CaptionStyle.copy(color = p.inkSoft, fontSize = 12.sp, lineHeight = 16.sp), modifier = Modifier.padding(top = 1.dp))
        }
        if (action.hint != null) {
            Text(
                action.hint,
                style = MonoSmall.copy(color = p.inkSoft, fontSize = 11.sp),
                modifier = Modifier.padding(start = 10.dp),
            )
        } else if (action == ConversationAction.History || action == ConversationAction.Usage) {
            GlyphIcon(Glyph.Forward, p.inkSoft, 13.dp, Modifier.padding(start = 10.dp).width(13.dp))
        }
    }
}
