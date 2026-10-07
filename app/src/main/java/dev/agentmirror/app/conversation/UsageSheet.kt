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
// @pre native stats for this ref; Grok account quotas are separately sourced, never session ledger estimates
// @post absent ≠ measured zero; authorized quota defaults/reset bases stay visible; context uses same-snapshot used/limit
// @err unsupported host, failure and stale (other session) stay visible; no fixed-rate polling
// @inv reads happen on open, on refresh, and when the agent settles while the sheet is open

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
    val turnCount: Long? = null,
    val modelName: String? = null,
    val nativeSlash: Boolean = false,
    val nativeUsage: JsonObject? = null,
    val usageError: String? = null,
    val reasoning: Long? = null,
    val modelCalls: Long? = null,
    val grokQuota: JsonObject? = null,
    val quotaError: String? = null,
) {
    /** Everything the model read: uncached input + cache reads + cache writes. */
    val promptTokens: Long? get() = if (nativeSlash) input else if (input != null && cacheRead != null && cacheWrite != null) input + cacheRead + cacheWrite else null

    /** Cache reads over all prompt input; undefined (null) before any input. */
    val cacheHitPercent: Double? get() = if (nativeSlash) null else promptTokens?.takeIf { it > 0 }?.let { (cacheRead ?: 0L) * 100.0 / it }

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
        turnCount = data.long("turnCount"),
        modelName = data.str("modelName").takeIf { it.isNotBlank() },
        nativeSlash = data.str("agentProvider") == "grok" || data.str("source") == "native_slash",
        nativeUsage = data.obj("grokUsage"),
        usageError = data.str("usageError").takeIf { it.isNotBlank() },
        reasoning = tokens?.long("reasoning"),
        modelCalls = data.long("modelCalls"),
        grokQuota = data.obj("grokQuota").takeIf { data.str("agentProvider") == "grok" },
        quotaError = data.str("quotaError").takeIf { it.isNotBlank() && data.str("agentProvider") == "grok" },
    )
}

/** Only a native account percentage; missing/invalid never becomes zero or session cost math. */
internal fun quotaPercent(data: JsonObject?, window: String): Double? = (data?.obj(window)?.get("usedPercent") as? JsonPrimitive)
    ?.takeIf { !it.isString && it !is JsonNull }?.content?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }

/** What the usage sheet holds between reads. */
internal data class UsageLoad(
    val snapshot: UsageSnapshot? = null,
    val loading: Boolean = false,
    val error: String? = null,
    /** The host predates the stats command: an honest gap, not an error. */
    val unsupported: Boolean = false,
)

/** Account readings are never reused while a new actor/epoch read is pending or fails. */
internal fun UsageLoad.beginRead(): UsageLoad = copy(loading = true, snapshot = snapshot?.copy(grokQuota = null, quotaError = null))

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
    percent == 0.0 -> "0%"
    percent > 0 && percent < 0.1 -> "< 0.1%"
    else -> String.format(Locale.US, "%.1f%%", percent)
}

/** Formats a native instant, without promoting a period end/TUI clock into a quota reset. */
internal fun quotaTimeText(raw: String, zone: ZoneId = ZoneId.systemDefault()): String? = runCatching {
    Instant.parse(raw).atZone(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm XXX", Locale.US))
}.getOrNull()

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

    // Billing periods/on-demand spending are never rolling quota/reset windows.
    if (s.nativeSlash) {
        SheetLabel("账号配额与周期 · 当前原生 Grok 登录账号", p, Modifier.padding(top = 4.dp, bottom = 8.dp))
        s.grokQuota?.obj("currentPeriod")?.let { period ->
            val type = when (period.str("type")) {
                "USAGE_PERIOD_TYPE_WEEKLY" -> "每周"
                "USAGE_PERIOD_TYPE_MONTHLY" -> "每月"
                else -> "原生周期"
            }
            SheetFact(if (type == "每周") "每周账期" else "计费周期", type, p, tag = "grok-billing-period")
            period.str("end").let { quotaTimeText(it) }?.let { end ->
                SheetFact("账期结束时间（非额度重置）", end, p, tag = "grok-billing-period-end")
            }
        }
        quotaPercent(s.grokQuota, "onDemand")?.let { percent ->
            SheetFact("按需消费进度 / 封顶比", percentText(percent), p, tag = "grok-on-demand-percent")
            ContextBar(percent, p, Modifier.padding(top = 6.dp, bottom = 12.dp).testTag("grok-on-demand-progress"))
            Text("按需消费封顶比，不是 5 小时或每周配额。", style = CaptionStyle.copy(color = p.inkSoft))
        }
        val windows = listOf("fiveHour" to "5 小时滚动额度", "weekly" to "每周额度")
        windows.forEach { (window, label) ->
            quotaPercent(s.grokQuota, window)?.let { percent ->
                SheetFact(label, "${percentText(percent)}（已用）", p, tag = "grok-quota-$window-percent")
                ContextBar(percent, p, Modifier.padding(top = 6.dp, bottom = 12.dp).testTag("grok-quota-$window-progress"))
                val detail = s.grokQuota?.obj(window)
                detail?.str("resetsAt")?.let { quotaTimeText(it) }?.let { reset ->
                    Text("重置时间：$reset", style = CaptionStyle.copy(color = p.inkSoft), modifier = Modifier.testTag("grok-quota-$window-reset"))
                }
                if (detail?.str("basis") == "authorized_tui_default") {
                    Text("未提供百分比时按原生样式显示 0%。", style = CaptionStyle.copy(color = p.inkSoft), modifier = Modifier.testTag("grok-quota-$window-default"))
                }
                if (detail?.str("resetBasis") == "currentPeriod.end") {
                    Text("重置时间按每周账期结束显示。", style = CaptionStyle.copy(color = p.inkSoft), modifier = Modifier.testTag("grok-quota-$window-reset-basis"))
                }
            }
        }
        if (s.grokQuota != null && windows.all { quotaPercent(s.grokQuota, it.first) == null }) {
            Text("账号额度暂未取得，可手动刷新；缺值不代表 0%。", style = CaptionStyle.copy(color = p.inkSoft), modifier = Modifier.testTag("grok-quota-unreported"))
        }
        s.quotaError?.let { SheetCallout(Glyph.Gauge, it, p.warning, p, tag = "grok-quota-error") }
    }

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

    if (s.modelName != null) SheetFact("模型", s.modelName, p)
    if (s.turnCount != null) SheetFact("会话轮次", groupedCount(s.turnCount), p, tag = "conversation-usage-turns")

    // ---- session totals ----
    SheetLabel("会话累计", p, Modifier.padding(top = 18.dp, bottom = 8.dp))
    if (s.nativeSlash) {
        Text("Token / 回合来自官方 grok usage 当前会话账本（含继承历史）；上下文另取 /context 与 /session-info，不混算两者。", style = CaptionStyle.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.padding(bottom = 8.dp))
        s.usageError?.let { SheetCallout(Glyph.Gauge, it, p.warning, p, Modifier.padding(bottom = 8.dp), "conversation-usage-native-error") }
    }
    val hit = s.cacheHitPercent
    MetricGrid(
        listOf(
            Metric("输入", s.promptTokens, if (s.nativeSlash) "原生 CLI 口径" else "含缓存读写"),
            Metric("输出", s.output, null),
            Metric("缓存读取", s.cacheRead, hit?.let { "命中 ${percentText(it)}" } ?: if (s.promptTokens == 0L) "尚无输入" else "未报告命中率"),
            Metric("缓存写入", s.cacheWrite, null),
            Metric("总计", s.total, null),
            if (s.nativeSlash) Metric("推理", s.reasoning, "原生已记录 tokens") else Metric(
                "消息",
                s.totalMessages,
                listOfNotNull(s.userMessages?.let { "用户 $it" }, s.assistantMessages?.let { "助手 $it" }, s.toolResults?.let { "工具 $it" }).joinToString(" · ").ifEmpty { null },
            ),
        ).map { if (s.nativeSlash) it.copy(missing = "未报告") else it },
        p,
    )
    if (s.modelCalls != null) SheetFact("模型调用", groupedCount(s.modelCalls), p)
    s.nativeUsage?.let { GrokUsageDetails(it, p) }
    if (s.toolCalls != null) {
        Text(
            "模型发起工具调用 ${s.toolCalls} 次",
            style = CaptionStyle.copy(color = p.inkSoft, fontSize = 12.sp),
            modifier = Modifier.padding(top = 8.dp),
        )
    }

    // ---- cost ----
    SheetLabel("费用", p, Modifier.padding(top = 18.dp, bottom = 2.dp))
    SheetFact("会话估算", usdText(s.cost) ?: if (s.nativeSlash) "原生未报告" else "—", p, tag = "conversation-usage-cost")
    Text(
        if (s.nativeSlash && s.cost == null) "官方本地用量账本未返回费用；Token 数不推算成账单，不填 USD 0。" else if (s.nativeSlash) "官方本地用量账本记录 · 10¹⁰ ticks / USD；不是账户额度余额。" else "按 Provider 报价记录的累计估算，含工具与摘要调用；不是账户账单。",
        style = CaptionStyle.copy(color = p.inkSoft, fontSize = 12.sp, lineHeight = 17.sp),
        modifier = Modifier.padding(top = 6.dp),
    )

    val stamp = remember(s.sampledAt) { SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(s.sampledAt)) }
    Text(
        listOfNotNull(
            "更新于 $stamp · " + if (s.nativeSlash) "原生上下文快照" else "原生累计统计",
            "Agent 工作中，本轮结束后自动刷新".takeIf { running },
            "刷新失败：${load.error}".takeIf { load.error != null },
        ).joinToString("\n"),
        style = CaptionStyle.copy(color = if (load.error != null) p.danger else p.inkSoft, fontSize = 12.sp, lineHeight = 17.sp),
        modifier = Modifier.padding(top = 12.dp).testTag("conversation-usage-stamp"),
    )
}

/** Native per-model/turn counters, not reconstructed from GUI message counts. */
@Composable
private fun GrokUsageDetails(data: JsonObject, p: ConversationPalette) {
    fun cost(counts: JsonObject): String? = counts.long("costUsdTicks")?.takeIf { it >= 0 }?.let { usdText(BigDecimal.valueOf(it).movePointLeft(10).toPlainString()) }
    data.obj("session")?.obj("modelUsage")?.let { models ->
        SheetLabel("按模型记录", p, Modifier.padding(top = 16.dp))
        models.entries.take(64).forEach { (model, raw) ->
            val counts = raw as? JsonObject ?: return@forEach
            SheetFact(model, counts.long("totalTokens")?.let { "${groupedCount(it)} tokens" } ?: "未报告", p)
            Text(listOfNotNull(counts.long("inputTokens")?.let { "输入 ${groupedCount(it)}" }, counts.long("outputTokens")?.let { "输出 ${groupedCount(it)}" }, counts.long("modelCalls")?.let { "调用 $it" }, cost(counts)).joinToString(" · "), style = CaptionStyle.copy(color = p.inkSoft))
        }
    }
    val turns = data["turns"] as? JsonArray ?: return
    if (turns.isNotEmpty()) {
        SheetLabel("最近回合 · 最多 50 条", p, Modifier.padding(top = 16.dp))
        turns.takeLast(50).forEach { raw ->
            val turn = raw as? JsonObject ?: return@forEach
            SheetFact("回合 ${turn.long("turnNumber")?.let(::groupedCount) ?: "未报告"}", turn.long("totalTokens")?.let { "${groupedCount(it)} tokens" } ?: "未报告", p)
            Text(listOfNotNull(turn.long("inputTokens")?.let { "输入 ${groupedCount(it)}" }, turn.long("outputTokens")?.let { "输出 ${groupedCount(it)}" }, turn.long("modelCalls")?.let { "调用 $it" }, cost(turn)).joinToString(" · "), style = CaptionStyle.copy(color = p.inkSoft))
        }
    }
}

private data class Metric(val label: String, val value: Long?, val note: String?, val missing: String = "—")

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
            .clip(look.shape(14.dp)).background(p.canvas)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("conversation-usage-metric-${metric.label}"),
    ) {
        Text(metric.label, style = CaptionStyle.copy(color = p.inkSoft, fontSize = 12.sp))
        Text(
            metric.value?.let(::groupedCount) ?: metric.missing,
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
            .background(p.canvas)
            // A genuine 0% has no fill, but the complete track remains visible in both themes.
            .border(0.5.dp, p.ink.copy(alpha = 0.5f), shape)
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
