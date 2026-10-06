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
// @pre instructions are sent only to an agent whose bridge forwards them (Pi customInstructions)
// @post a non-empty instruction is never silently dropped: unsupported agents cannot type one
// @err rejection, timeout, failure and cancellation stay visible; nothing is retried by itself
// @inv the saving is shown only from the agent's own before/after estimates, never predicted

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop

/** Bound on the instruction text (UTF-8 bytes): a product policy, not a native Pi limit. */
internal const val COMPACT_INSTRUCTIONS_MAX_BYTES = 4_096

/** One submitted compaction: [baseline] is the outcome seq already on screen when it was sent. */
internal data class CompactRun(val id: Int, val baseline: Long, val replied: Boolean? = null, val reason: String? = null)

internal sealed interface CompactPhase {
    data object Configure : CompactPhase
    data object Submitting : CompactPhase
    data object Running : CompactPhase
    /** The host admitted the request but this agent reports no structured compaction events. */
    data object Accepted : CompactPhase
    data class Done(val outcome: CompactionOutcome) : CompactPhase
    data class Rejected(val reason: String) : CompactPhase
}

/**
 * What the sheet shows, derived only from facts: a newer compaction_end beats everything, live
 * compaction beats a late timeout reply (Pi answers only after it finishes), and a reply decides
 * the rest. A compaction someone started from the composer shows as running too.
 */
internal fun compactPhase(run: CompactRun?, compacting: Boolean, outcome: CompactionOutcome?): CompactPhase = when {
    run != null && outcome != null && outcome.seq > run.baseline -> CompactPhase.Done(outcome)
    compacting -> CompactPhase.Running
    run == null -> CompactPhase.Configure
    run.replied == true -> CompactPhase.Accepted
    run.replied == false -> CompactPhase.Rejected(run.reason ?: "主机没有确认")
    else -> CompactPhase.Submitting
}

@Composable
internal fun CompactSheet(
    open: Boolean,
    phase: CompactPhase,
    instructions: String,
    onInstructions: (String) -> Unit,
    acceptsInstructions: Boolean,
    busy: Boolean,
    interruptsBusyWork: Boolean,
    connected: Boolean,
    p: ConversationPalette,
    backdrop: Backdrop,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit,
    onReset: () -> Unit,
) {
    val look = LocalConversationLook.current
    val bytes = instructions.toByteArray().size
    val tooLong = bytes > COMPACT_INSTRUCTIONS_MAX_BYTES
    val editable = phase == CompactPhase.Configure || phase is CompactPhase.Rejected
    ConversationSheet(
        open = open,
        title = "压缩上下文",
        eyebrow = "COMPACT",
        subtitle = "保留完整聊天记录，只重新整理模型上下文",
        p = p,
        backdrop = backdrop,
        tag = "conversation-compact-sheet",
        closeTag = "conversation-compact-close",
        onDismiss = onDismiss,
        footer = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when (phase) {
                    CompactPhase.Configure, is CompactPhase.Rejected -> {
                        SheetButton("取消", SheetButtonKind.Secondary, p, "conversation-compact-cancel", Modifier.weight(1f), onClick = onDismiss)
                        val blockedByBusy = busy && !interruptsBusyWork
                        SheetButton(
                            when {
                                blockedByBusy -> "等待当前任务结束"
                                busy -> "中断并压缩"
                                phase is CompactPhase.Rejected -> "重试"
                                else -> "开始压缩"
                            },
                            if (busy && !blockedByBusy) SheetButtonKind.Danger else SheetButtonKind.Primary,
                            p,
                            "conversation-compact-submit",
                            Modifier.weight(1.4f),
                            enabled = connected && !tooLong && !blockedByBusy,
                            onClick = onSubmit,
                        )
                    }
                    CompactPhase.Submitting, CompactPhase.Running -> SheetButton("收起", SheetButtonKind.Secondary, p, "conversation-compact-hide", Modifier.fillMaxWidth(), onClick = onDismiss)
                    CompactPhase.Accepted, is CompactPhase.Done -> SheetButton("完成", SheetButtonKind.Primary, p, "conversation-compact-done", Modifier.fillMaxWidth()) {
                        onReset()
                        onDismiss()
                    }
                }
            }
        },
    ) {
        if (editable) {
            SheetLabel("保留重点（可选）", p, Modifier.padding(top = 4.dp, bottom = 8.dp))
            val shape = look.shape(14.dp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(p.code)
                    .hairlineBorder(look, p, shape, tone = p.danger.takeIf { tooLong })
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                BasicTextField(
                    value = instructions,
                    onValueChange = onInstructions,
                    enabled = acceptsInstructions,
                    textStyle = BodyStyle.copy(color = p.codeInk, fontSize = 14.sp, lineHeight = 21.sp),
                    cursorBrush = SolidColor(p.accent),
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 63.dp).testTag("conversation-compact-instructions"),
                    decorationBox = { field ->
                        if (instructions.isEmpty()) {
                            Text(
                                if (acceptsInstructions) "例如：保留数据库结构的决定、失败的测试名和下一步" else "此 Agent 尚未确认支持自定义压缩指令",
                                style = BodyStyle.copy(color = p.codeSoft, fontSize = 14.sp, lineHeight = 21.sp),
                            )
                        }
                        field()
                    },
                )
            }
            if (!acceptsInstructions) {
                Text("将执行基础压缩；输入框保持关闭，指令不会被悄悄丢弃", style = CaptionStyle.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.padding(top = 6.dp))
            } else if (bytes > COMPACT_INSTRUCTIONS_MAX_BYTES * 3 / 4) {
                Text(
                    "$bytes / $COMPACT_INSTRUCTIONS_MAX_BYTES 字节" + if (tooLong) " · 超出上限，请精简" else "",
                    style = CaptionStyle.copy(color = if (tooLong) p.danger else p.inkSoft, fontSize = 12.sp, fontFamily = ConversationMono),
                    modifier = Modifier.padding(top = 6.dp).testTag("conversation-compact-bytes"),
                )
            }
            Column(Modifier.padding(top = 14.dp)) {
                SheetFact("预期节省", "完成后可估算", p)
                SheetFact("聊天记录", "完整保留", p)
            }
            Text(
                "会调用模型生成摘要，可能产生费用；压缩比例取决于上下文，不作保证。",
                style = CaptionStyle.copy(color = p.inkSoft, fontSize = 12.sp, lineHeight = 17.sp),
                modifier = Modifier.padding(top = 10.dp),
            )
            if (busy) {
                SheetCallout(
                    Glyph.Bolt,
                    if (interruptsBusyWork) "Agent 正在工作。开始压缩会先中断当前任务。" else "Agent 正在工作，空闲后才能压缩。",
                    p.warning,
                    p,
                    Modifier.padding(top = 12.dp),
                    tag = "conversation-compact-busy",
                )
            }
            if (phase is CompactPhase.Rejected) {
                SheetCallout(Glyph.Cross, "压缩未执行：${phase.reason}", p.danger, p, Modifier.padding(top = 12.dp), tag = "conversation-compact-error")
            }
        }
        when (phase) {
            CompactPhase.Submitting -> SheetProgress("正在提交压缩请求…", null, p, "conversation-compact-submitting")
            CompactPhase.Running -> SheetProgress("正在压缩上下文…", "可以收起，完成后结果会出现在对话中", p, "conversation-compact-running")
            CompactPhase.Accepted -> SheetCallout(Glyph.Check, "压缩请求已被接受，结果会显示在对话中。", p.success, p, Modifier.padding(top = 6.dp), tag = "conversation-compact-accepted")
            is CompactPhase.Done -> CompactResult(phase.outcome, p)
            else -> Unit
        }
    }
}

@Composable
private fun CompactResult(outcome: CompactionOutcome, p: ConversationPalette) {
    when {
        outcome.aborted -> SheetCallout(Glyph.Stop, "压缩已取消，上下文保持原样。", p.warning, p, Modifier.padding(top = 6.dp), tag = "conversation-compact-aborted")
        outcome.error != null -> SheetCallout(Glyph.Cross, "压缩失败：${outcome.error}", p.danger, p, Modifier.padding(top = 6.dp), tag = "conversation-compact-failed")
        else -> Column(Modifier.padding(top = 6.dp).testTag("conversation-compact-done-result")) {
            SheetCallout(Glyph.Check, "上下文已压缩，聊天记录完整保留。", p.success, p)
            val delta = compactionDelta(outcome.before, outcome.after)
            if (outcome.before != null && outcome.after != null) {
                Column(Modifier.padding(top = 12.dp)) {
                    SheetFact("压缩前（估算）", "${groupedCount(outcome.before)} tokens", p)
                    SheetFact("压缩后（估算）", "${groupedCount(outcome.after)} tokens", p)
                    if (delta != null) SheetFact("变化", delta, p, valueColor = if (outcome.after < outcome.before) p.success else p.inkSoft, tag = "conversation-compact-delta")
                }
            } else {
                Text("Agent 未报告可比较的压缩量", style = CaptionStyle.copy(color = p.inkSoft, fontSize = 12.5.sp), modifier = Modifier.padding(top = 10.dp))
            }
            Text(
                "数值为 Agent 的估算，下一次模型响应后以实际用量为准。",
                style = CaptionStyle.copy(color = p.inkSoft, fontSize = 12.sp, fontWeight = FontWeight.Normal),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
