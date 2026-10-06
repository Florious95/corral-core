package dev.agentmirror.app.conversation

// @contract
// @pre metadata comes from the host's verified current-cwd Pi session catalog
// @post selecting an ID requests native RPC resume, never a host path
// @err loading, empty, failure and pending switch states remain visible
// @inv only the open picker renders list rows; host paths are not displayed
// @inv relative times are computed once per catalog, never by a ticking clock

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import dev.agentmirror.app.ui.components.RuleEdge
import dev.agentmirror.app.ui.components.edgeRule
import kotlinx.serialization.json.JsonObject
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToLong

internal data class SessionHistoryChoice(val id: String, val name: String, val firstMessage: String, val modifiedMs: Long, val current: Boolean) {
    val title: String get() = name.ifBlank { firstMessage }.ifBlank { "未命名会话" }

    /** The opening request under a named session; nothing when it would only repeat the title. */
    val excerpt: String? get() = firstMessage.takeIf { name.isNotBlank() && it.isNotBlank() && it != name }
}

internal fun sessionHistoryChoices(data: JsonObject): List<SessionHistoryChoice> =
    data.arr("sessions").orEmpty().mapNotNull { entry ->
        val row = entry as? JsonObject ?: return@mapNotNull null
        val id = row.str("session_id").ifBlank { return@mapNotNull null }
        SessionHistoryChoice(id, row.str("name"), row.str("first_message"), row.long("modified_ms") ?: 0, row.bool("current") == true)
    }

/** "刚刚" · "12 分钟前" · "今天 14:03" · "昨天 09:12" · "10月3日 18:40" · "2025年12月1日". */
internal fun relativeTime(ms: Long, now: Long, zone: TimeZone = TimeZone.getDefault()): String {
    if (ms <= 0) return ""
    val delta = now - ms
    if (delta in -60_000 until 60_000) return "刚刚"
    if (delta in 60_000 until 3_600_000) return "${delta / 60_000} 分钟前"
    val then = Calendar.getInstance(zone).apply { timeInMillis = ms }
    val today = Calendar.getInstance(zone).apply { timeInMillis = now }
    fun Calendar.dayStart(): Long = (clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    // Rounded so a DST day (23 or 25 h) still counts as one day.
    val days = ((today.dayStart() - then.dayStart()) / 86_400_000.0).roundToLong()
    val time = String.format(Locale.US, "%02d:%02d", then.get(Calendar.HOUR_OF_DAY), then.get(Calendar.MINUTE))
    val month = then.get(Calendar.MONTH) + 1
    val day = then.get(Calendar.DAY_OF_MONTH)
    return when {
        days == 0L -> "今天 $time"
        days == 1L -> "昨天 $time"
        then.get(Calendar.YEAR) == today.get(Calendar.YEAR) -> "${month}月${day}日 $time"
        else -> "${then.get(Calendar.YEAR)}年${month}月${day}日"
    }
}

/**
 * The session history drawer: one card per saved session (title, the opening request, when it
 * was last touched, a badge on the live one). Liquid Glass floats squircle cards; Modernism rules
 * the rows into a ledger with an accent edge on the current session.
 */
@Composable
internal fun SessionHistoryPicker(
    open: Boolean,
    choices: List<SessionHistoryChoice>,
    loading: Boolean,
    restoring: Boolean,
    restoringTitle: String?,
    error: String?,
    p: ConversationPalette,
    backdrop: Backdrop,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onChoose: (SessionHistoryChoice) -> Unit,
) {
    val look = LocalConversationLook.current
    ConversationSheet(
        open = open,
        title = "历史会话",
        eyebrow = "HISTORY",
        subtitle = if (restoring) "恢复完成前输入暂不开放" else "当前目录 · 选择一条恢复并继续对话",
        p = p,
        backdrop = backdrop,
        tag = "conversation-history-picker",
        closeTag = "conversation-history-close",
        onDismiss = onDismiss,
        dismissible = !restoring,
        scrollable = false,
    ) {
        when {
            error != null -> Column(Modifier.padding(top = 6.dp)) {
                SheetCallout(Glyph.Cross, error, p.danger, p, tag = "conversation-history-error")
                SheetButton("重试", SheetButtonKind.Secondary, p, "conversation-history-retry", Modifier.padding(top = 12.dp).fillMaxWidth(), onClick = onRetry)
            }
            restoring -> SheetProgress("正在恢复历史消息…", restoringTitle, p, "conversation-history-restoring")
            loading -> Text(
                "正在读取历史会话…",
                style = BodyStyle.copy(color = p.inkSoft, fontSize = 14.sp),
                modifier = Modifier.padding(vertical = 18.dp).testTag("conversation-history-loading"),
            )
            choices.isEmpty() -> Column(
                Modifier.fillMaxWidth().padding(vertical = 22.dp).testTag("conversation-history-empty"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(44.dp).glyphTile(p.accent, p, look.shape(14.dp)), contentAlignment = Alignment.Center) {
                    GlyphIcon(Glyph.History, p.accentInk, 22.dp)
                }
                Text("当前目录还没有已保存的会话", style = LabelStyle.copy(color = p.ink, fontSize = 14.5.sp), modifier = Modifier.padding(top = 12.dp))
                Text("发送消息后，Agent 会自动保存这次会话", style = CaptionStyle.copy(color = p.inkSoft, fontSize = 12.5.sp), modifier = Modifier.padding(top = 4.dp))
            }
            else -> {
                val now = remember(choices) { System.currentTimeMillis() }
                LazyColumn(
                    Modifier.fillMaxWidth().testTag("conversation-history-list"),
                    contentPadding = PaddingValues(top = if (look.glass) 4.dp else 0.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(if (look.glass) 8.dp else 0.dp),
                ) {
                    items(choices, key = { it.id }) { choice ->
                        HistoryCard(choice, relativeTime(choice.modifiedMs, now), p) { onChoose(choice) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(choice: SessionHistoryChoice, time: String, p: ConversationPalette, onClick: () -> Unit) {
    val look = LocalConversationLook.current
    val shape = look.shape(18.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(
                if (look.glass) {
                    Modifier
                        .background(if (choice.current) p.accent.copy(alpha = if (p.dark) 0.12f else 0.08f) else p.surface)
                        .hairlineBorder(look, p, shape, tone = p.accent.copy(alpha = 0.42f).takeIf { choice.current })
                } else {
                    Modifier
                        .edgeRule(RuleEdge.Bottom, p.rule, look.hairline)
                        .then(if (choice.current) Modifier.background(p.accent.copy(alpha = 0.06f)).edgeRule(RuleEdge.Start, p.accent, 2.dp) else Modifier)
                },
            )
            .background(if (pressed) p.ink.copy(alpha = 0.06f) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(start = if (look.glass) 14.dp else 12.dp, end = 12.dp, top = 12.dp, bottom = 11.dp)
            .testTag("conversation-history-session-${choice.id}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                choice.title,
                style = LabelStyle.copy(color = p.ink, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = look.titleWeight),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (choice.current) {
                Text(
                    "当前",
                    style = CaptionStyle.copy(color = p.accentInk, fontWeight = FontWeight.SemiBold, fontFamily = if (look.glass) ConversationSans else ConversationMono),
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clip(look.pill())
                        .background(p.accent.copy(alpha = if (p.dark) 0.18f else 0.12f))
                        .then(if (look.glass) Modifier else Modifier.border(look.hairline, p.accent, look.pill()))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .testTag("conversation-history-current"),
                )
            }
        }
        choice.excerpt?.let {
            Text(
                it,
                style = BodyStyle.copy(color = p.inkSoft, fontSize = 13.sp, lineHeight = 19.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (time.isNotEmpty()) {
                GlyphIcon(Glyph.History, p.inkSoft, 12.dp)
                Text(
                    time,
                    style = if (look.glass) CaptionStyle.copy(color = p.inkSoft) else MonoSmall.copy(color = p.inkSoft, fontSize = 11.sp),
                    modifier = Modifier.padding(start = 5.dp),
                )
            }
            Box(Modifier.weight(1f))
            Text(if (choice.current) "继续" else "恢复", style = CaptionStyle.copy(color = p.accentInk, fontWeight = FontWeight.SemiBold))
            GlyphIcon(Glyph.Forward, p.accentInk, 12.dp, Modifier.padding(start = 2.dp))
        }
    }
}
