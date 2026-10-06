package dev.agentmirror.app.conversation

// @contract
// @pre metadata comes from the host's verified current-cwd Pi session catalog
// @post selecting an ID requests native RPC resume, never a host path
// @err loading, empty, failure and pending switch states remain visible
// @inv only the open picker renders list rows; host paths are not displayed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kyant.backdrop.Backdrop
import kotlinx.serialization.json.JsonObject
import java.text.DateFormat
import java.util.Date

internal data class SessionHistoryChoice(val id: String, val name: String, val firstMessage: String, val modifiedMs: Long, val current: Boolean) {
    val title: String get() = name.ifBlank { firstMessage }.ifBlank { "未命名会话" }
}

internal fun sessionHistoryChoices(data: JsonObject): List<SessionHistoryChoice> =
    data.arr("sessions").orEmpty().mapNotNull { entry ->
        val row = entry as? JsonObject ?: return@mapNotNull null
        val id = row.str("session_id").ifBlank { return@mapNotNull null }
        SessionHistoryChoice(id, row.str("name"), row.str("first_message"), row.long("modified_ms") ?: 0, row.bool("current") == true)
    }

@Composable
internal fun SessionHistoryPicker(
    open: Boolean,
    choices: List<SessionHistoryChoice>,
    loading: Boolean,
    restoring: Boolean,
    error: String?,
    p: ConversationPalette,
    backdrop: Backdrop,
    topPx: () -> Int,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onChoose: (SessionHistoryChoice) -> Unit,
) {
    if (!open) return
    Box(Modifier.fillMaxSize().zIndex(5f).background(Color.Black.copy(alpha = 0.2f))
        .pointerInput(restoring) { detectTapGestures { if (!restoring) onDismiss() } })
    Column(Modifier.fillMaxWidth().offset { IntOffset(0, topPx()) }.padding(horizontal = 12.dp)
        .zIndex(6f).panelSurface(LocalConversationLook.current, backdrop, 20.dp, p)
        .pointerInput(Unit) { detectTapGestures { } }.padding(16.dp).testTag("conversation-history-picker")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("历史会话", style = LabelStyle.copy(color = p.ink))
            if (!restoring) Text("关闭", style = CaptionStyle.copy(color = p.inkSoft), modifier = Modifier.clickable(onClick = onDismiss).testTag("conversation-history-close"))
        }
        Text("当前目录 · 点击恢复并继续对话", style = CaptionStyle.copy(color = p.inkSoft), modifier = Modifier.padding(top = 6.dp, bottom = 10.dp))
        when {
            error != null -> Column {
                Text(error, style = BodyStyle.copy(color = p.danger), modifier = Modifier.testTag("conversation-history-error"))
                Text("重试", style = LabelStyle.copy(color = p.accentInk), modifier = Modifier.padding(top = 12.dp).clickable(onClick = onRetry).testTag("conversation-history-retry"))
            }
            restoring -> Text("正在恢复历史消息…", style = BodyStyle.copy(color = p.accentInk), modifier = Modifier.testTag("conversation-history-restoring"))
            loading -> Text("正在读取历史会话…", style = BodyStyle.copy(color = p.inkSoft), modifier = Modifier.testTag("conversation-history-loading"))
            choices.isEmpty() -> Text("当前目录还没有已保存的会话", style = BodyStyle.copy(color = p.inkSoft), modifier = Modifier.testTag("conversation-history-empty"))
            else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp).testTag("conversation-history-list")) {
                items(choices, key = { it.id }) { choice ->
                    Column(Modifier.fillMaxWidth().clickable { onChoose(choice) }.padding(vertical = 12.dp).testTag("conversation-history-session-${choice.id}")) {
                        Text(choice.title + if (choice.current) " · 当前" else "", style = LabelStyle.copy(color = p.ink), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (choice.name.isNotBlank() && choice.firstMessage.isNotBlank()) Text(choice.firstMessage, style = BodyStyle.copy(color = p.inkSoft), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        if (choice.modifiedMs > 0) Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(choice.modifiedMs)), style = CaptionStyle.copy(color = p.inkSoft), modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}
