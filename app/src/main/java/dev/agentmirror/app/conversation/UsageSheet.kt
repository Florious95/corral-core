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
// @pre data is Pi's get_session_stats (P22) for this ref, projected without the host path
// @post absent ≠ zero: a missing field renders "—", never 0; percent only from same-snapshot used/limit
// @err unsupported host, failure and stale (other session) stay visible; no fixed-rate polling
// @inv reads happen on open, on refresh, and when the agent settles while the sheet is open

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import dev.agentmirror.app.ui.components.RuleEdge
import dev.agentmirror.app.ui.components.edgeRule
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One get_session_stats reading. Every number is nullable: absent is not zero. */
internal data class UsageSnapshot(
    val sessionId: String,
    val input: Long?,
    val output: Long?,
    val cacheRead: Long?,
    val cacheWrite: Long?,
    val total: Long?,
    /** The native decimal literal (USD), kept as text so formatting never invents digits. */
    val cost: String?,
    val hasContext: Boolean,
    /** Pi's current-context estimate; null after a compaction until the next model response. */
    val contextTokens: Long?,
    val contextWindow: Long?,
    val userMessages: Long?,
    val assistantMessages: Long?,
    val toolCalls: Long?,
    val toolResults: Long?,
    val totalMessages: Long?,
    val sampledAt: Long,
) {
    /** Everything the model read: uncached input + cache reads + cache writes. */
    val promptTokens: Long? get() = if (input != null && cacheRead != null && cacheWrite != null) input + cacheRead + cacheWrite else null

    /** Cache reads over all prompt input; undefined (null) before any input. */
    val cacheHitPercent: Double? get() = promptTokens?.takeIf { it > 0 }?.let { (cacheRead ?: 0L) * 100.0 / it }

    /** Derived from this snapshot's own used/limit pair only. */
    val contextPercent: Double? get() = if (contextTokens != null && contextWindow != null && contextWindow > 0) contextTokens * 100.0 / contextWindow else null
}

internal fun usageSnapshot(data: JsonObject, sampledAt: Long): UsageSnapshot {
    val tokens = data.obj("tokens")
    val context = data.obj("contextUsage")
    return UsageSnapshot(
        sessionId = data.str("sessionId"),
        input = tokens?.long("input"),
        output = tokens?.long("output"),
        cacheRead = tokens?.long("cacheRead"),
        cacheWrite = tokens?.long("cacheWrite"),
        total = tokens?.long("total"),
        cost = (data["cost"] as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.content,
        hasContext = context != null,
        contextTokens = context?.long("tokens"),
        contextWindow = context?.long("contextWindow"),
        userMessages = data.long("userMessages"),
        assistantMessages = data.long("assistantMessages"),
        toolCalls = data.long("toolCalls"),
        toolResults = data.long("toolResults"),
        totalMessages = data.long("totalMessages"),
        sampledAt = sampledAt,
    )
}

/** What the usage sheet holds between reads. */
internal data class UsageLoad(
    val snapshot: UsageSnapshot? = null,
    val loading: Boolean = false,
    val error: String? = null,
    /** The host predates the stats command: an honest gap, not an error. */
    val unsupported: Boolean = false,
)

internal fun groupedCount(n: Long): String = String.format(Locale.US, "%,d", n)

/** Native USD decimal → "USD 0.02036"; tiny but non-zero never collapses to "0.0000". */
internal fun usdText(raw: String?): String? {
    val value = raw?.toBigDecimalOrNull() ?: return null
    return when {
        value.signum() == 0 -> "USD 0"
        value.abs() < BigDecimal("0.0001") -> "< USD 0.0001"
        value.abs() >= BigDecimal.ONE -> "USD " + value.setScale(2, RoundingMode.HALF_UP).toPlainString()
        else -> "USD " + value.round(MathContext(4, RoundingMode.HALF_UP)).stripTrailingZeros().toPlainString()
    }
}

internal fun percentText(percent: Double): String = when {
    percent > 0 && percent < 0.1 -> "< 0.1%"
    else -> String.format(Locale.US, "%.1f%%", percent)
}

@Composable
internal fun UsageSheet(
    open: Boolean,
    load: UsageLoad,
    sessionId: String?,
    agent: String,
    model: String?,
    running: Boolean,
    connected: Boolean,
    p: ConversationPalette,
    backdrop: Backdrop,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onCompact: () -> Unit,
) {
    // A reading from another session (resume/new) is never shown as this one's.
    val snapshot = load.snapshot?.takeIf { it.sessionId.isEmpty() || sessionId == null || it.sessionId == sessionId }
    ConversationSheet(
        open = open,
        title = "用量与上下文",
        eyebrow = "USAGE",
        subtitle = listOf(agent, model ?: "当前模型", "本会话").joinToString(" · "),
        p = p,
        backdrop = backdrop,
        tag = "conversation-usage-sheet",
        closeTag = "conversation-usage-close",
        onDismiss = onDismiss,
        headerAction = {
            if (!load.unsupported) SheetIconButton(Glyph.Refresh, "刷新", p, "conversation-usage-refresh") { if (!load.loading) onRefresh() }
        },
        footer = if (connected && !load.unsupported) {
            { SheetButton("压缩上下文", SheetButtonKind.Secondary, p, "conversation-usage-compact", Modifier.fillMaxWidth(), onClick = onCompact) }
        } else null,
    ) {
        when {
            load.unsupported -> SheetCallout(
                Glyph.Gauge,
                "主机尚未提供结构化用量统计。更新主机服务后即可在这里查看 Token、费用与上下文占用。",
                p.inkSoft,
                p,
                Modifier.padding(top = 6.dp),
                tag = "conversation-usage-unsupported",
            )
            snapshot == null && load.error != null -> Column(Modifier.padding(top = 6.dp)) {
                SheetCallout(Glyph.Cross, "未读取到统计：${load.error}", p.danger, p, tag = "conversation-usage-error")
                SheetButton("重试", SheetButtonKind.Secondary, p, "conversation-usage-retry", Modifier.padding(top = 12.dp).fillMaxWidth(), onClick = onRefresh)
            }
            snapshot == null -> Text(
                "正在读取主机统计…",
                style = BodyStyle.copy(color = p.inkSoft, fontSize = 14.sp),
                modifier = Modifier.padding(vertical = 18.dp).testTag("conversation-usage-loading"),
            )
            else -> UsageBody(snapshot, load, running, p)
        }
    }
}

@Composable
private fun UsageBody(s: UsageSnapshot, load: UsageLoad, running: Boolean, p: ConversationPalette) {
    val look = LocalConversationLook.current
    val numeric = TextStyle(fontFamily = ConversationSans, fontFeatureSettings = "tnum", color = p.ink)

    // ---- current context: the hero reading ----
    SheetLabel("当前上下文", p, Modifier.padding(top = 4.dp))
    if (!s.hasContext) {
        Text("Agent 未提供上下文容量", style = BodyStyle.copy(color = p.inkSoft, fontSize = 14.sp), modifier = Modifier.padding(top = 8.dp))
    } else {
        Row(Modifier.padding(top = 6.dp).testTag("conversation-usage-context"), verticalAlignment = Alignment.Bottom) {
            Text(
                s.contextTokens?.let { "≈ ${groupedCount(it)}" } ?: "—",
                style = numeric.copy(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = look.titleWeight, letterSpacing = (-0.3).sp),
            )
            Text(
                s.contextWindow?.let { " / ${groupedCount(it)} tokens" } ?: " / 容量未知",
                style = numeric.copy(color = p.inkSoft, fontSize = 14.sp, lineHeight = 20.sp),
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        val percent = s.contextPercent
        ContextBar(percent, p, Modifier.padding(top = 10.dp))
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (s.contextTokens == null) "压缩后待新的模型响应确认" else "估算值 · 随对话增长",
                style = CaptionStyle.copy(color = p.inkSoft, fontSize = 12.sp),
                modifier = Modifier.weight(1f),
            )
            if (percent != null) {
                Text(
                    (if (percent > 100) "超出窗口 · " else "≈ ") + percentText(percent),
                    style = CaptionStyle.copy(color = if (percent > 100) p.danger else p.accentInk, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                    modifier = Modifier.testTag("conversation-usage-percent"),
                )
            }
        }
    }

    // ---- session totals ----
    SheetLabel("会话累计", p, Modifier.padding(top = 22.dp, bottom = 8.dp))
    val hit = s.cacheHitPercent
    MetricGrid(
        listOf(
            Metric("输入", s.promptTokens, "含缓存读写"),
            Metric("输出", s.output, null),
            Metric("缓存读取", s.cacheRead, hit?.let { "命中 ${percentText(it)}" } ?: "尚无输入"),
            Metric("缓存写入", s.cacheWrite, null),
            Metric("总计", s.total, null),
            Metric("消息", s.totalMessages, listOfNotNull(s.userMessages?.let { "用户 $it" }, s.assistantMessages?.let { "助手 $it" }).joinToString(" · ").ifEmpty { null }),
        ),
        p,
    )
    if (s.toolCalls != null || s.toolResults != null) {
        Text(
            "工具调用 ${s.toolCalls ?: "—"} 次 · 返回结果 ${s.toolResults ?: "—"} 个",
            style = CaptionStyle.copy(color = p.inkSoft, fontSize = 12.sp),
            modifier = Modifier.padding(top = 8.dp),
        )
    }

    // ---- cost ----
    SheetLabel("费用", p, Modifier.padding(top = 22.dp, bottom = 2.dp))
    SheetFact("会话估算", usdText(s.cost) ?: "—", p, tag = "conversation-usage-cost")
    Text(
        "按 Provider 报价记录的累计估算，含工具与摘要调用；不是账户账单。",
        style = CaptionStyle.copy(color = p.inkSoft, fontSize = 12.sp, lineHeight = 17.sp),
        modifier = Modifier.padding(top = 6.dp),
    )

    val stamp = remember(s.sampledAt) { SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(s.sampledAt)) }
    Text(
        listOfNotNull(
            "更新于 $stamp · 原生累计统计",
            "Agent 工作中，本轮结束后自动刷新".takeIf { running },
            "刷新失败：${load.error}".takeIf { load.error != null },
        ).joinToString("\n"),
        style = CaptionStyle.copy(color = if (load.error != null) p.danger else p.inkSoft, fontSize = 12.sp, lineHeight = 17.sp),
        modifier = Modifier.padding(top = 16.dp).testTag("conversation-usage-stamp"),
    )
}

private data class Metric(val label: String, val value: Long?, val note: String?)

/** Two columns: glass tiles on an 8dp gutter, or a ruled Modernist grid with shared hairlines. */
@Composable
private fun MetricGrid(metrics: List<Metric>, p: ConversationPalette) {
    val look = LocalConversationLook.current
    Column(
        Modifier.fillMaxWidth().then(if (look.glass) Modifier else Modifier.edgeRule(RuleEdge.Bottom, p.rule, look.hairline)),
        verticalArrangement = Arrangement.spacedBy(if (look.glass) 8.dp else 0.dp),
    ) {
        metrics.chunked(2).forEach { row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .then(if (look.glass) Modifier else Modifier.edgeRule(RuleEdge.Top, p.rule, look.hairline)),
            ) {
                row.forEachIndexed { i, metric ->
                    if (i > 0) {
                        if (look.glass) Spacer(Modifier.width(8.dp)) else Box(Modifier.width(look.hairline).fillMaxHeight().background(p.rule))
                    }
                    MetricCell(metric, p, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MetricCell(metric: Metric, p: ConversationPalette, modifier: Modifier) {
    val look = LocalConversationLook.current
    Column(
        modifier
            .fillMaxHeight()
            .then(if (look.glass) Modifier.clip(look.shape(14.dp)).background(p.ink.copy(alpha = if (p.dark) 0.05f else 0.04f)) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("conversation-usage-metric-${metric.label}"),
    ) {
        Text(metric.label, style = CaptionStyle.copy(color = p.inkSoft, fontSize = 12.sp))
        Text(
            metric.value?.let(::groupedCount) ?: "—",
            style = TextStyle(fontFamily = ConversationSans, fontFeatureSettings = "tnum", color = p.ink, fontSize = 19.sp, lineHeight = 25.sp, fontWeight = look.titleWeight),
            modifier = Modifier.padding(top = 2.dp),
        )
        if (metric.note != null) Text(metric.note, style = CaptionStyle.copy(color = p.inkSoft, fontSize = 11.5.sp))
    }
}

/** A flat 6dp meter: accent fill only for a known ratio; Modernism scribes 25/50/75% ticks. */
@Composable
private fun ContextBar(percent: Double?, p: ConversationPalette, modifier: Modifier = Modifier) {
    val look = LocalConversationLook.current
    val shape = look.pill()
    val over = percent != null && percent > 100
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(shape)
            .background(p.ink.copy(alpha = if (p.dark) 0.12f else 0.09f))
            .then(
                if (look.glass) Modifier else Modifier.drawWithContent {
                    drawContent()
                    val w = 1.dp.toPx()
                    listOf(0.25f, 0.5f, 0.75f).forEach { t -> drawRect(p.panel, Offset(size.width * t - w / 2, 0f), Size(w, size.height)) }
                },
            ),
    ) {
        if (percent != null && percent > 0) {
            Box(
                Modifier
                    // A real but tiny share keeps a visible sliver.
                    .widthIn(min = 3.dp)
                    .fillMaxWidth((percent / 100).toFloat().coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(shape)
                    .background(if (over) p.danger else p.accent),
            )
        }
    }
}
