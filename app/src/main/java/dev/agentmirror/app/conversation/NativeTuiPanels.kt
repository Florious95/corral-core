package dev.agentmirror.app.conversation

// @contract
// @pre current-session native request tokens and metadata arrive on the authenticated stream
// @post explicit choices use original options; command ACK never claims task/tool completion
// @err stale/expired requests and native failures are visible without automatic approval
// @inv bounded inputs; permanent permissions need a second native-scope decision

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.kyant.backdrop.Backdrop
import kotlinx.serialization.json.*

data class NativeOption(val id: String, val name: String, val kind: String = "select")
data class NativeInteraction(val id: String, val method: String, val title: String, val message: String, val prefill: String, val placeholder: String, val sessionId: String?, val expiresAt: Long, val options: List<NativeOption>)
internal fun nativeInteraction(e: JsonObject): NativeInteraction? {
    val id = e.str("id").takeIf { it.length == 32 } ?: return null
    val options = e.arr("options").orEmpty().take(128).mapNotNull {
        when (it) {
            is JsonPrimitive -> it.contentOrNull?.let { v -> NativeOption(v, v) }
            is JsonObject -> NativeOption(it.str("optionId"), it.str("name"), it.str("kind"))
            else -> null
        }
    }
    return NativeInteraction(id, e.str("method"), e.str("title").take(640), e.str("message").take(16000), e.str("prefill").take(200000), e.str("placeholder").take(640), e.str("sessionId").ifBlank { null }, e.long("expiresAt") ?: return null, options)
}

internal fun nativeInteractionRemainingMs(expiresAt: Long, localNow: Long, serverSkewMs: Long): Long =
    (expiresAt - localNow - serverSkewMs).coerceAtLeast(0)

@Composable
internal fun NativeTextField(value: String, label: String, p: ConversationPalette, tag: String, multiline: Boolean = false, onValue: (String) -> Unit) {
    Text(label, style = MonoSmall.copy(color = p.inkSoft), modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
    BasicTextField(value, { if (it.length <= 200000) onValue(it) }, textStyle = TextStyle(color = p.ink, fontSize = 16.sp), singleLine = !multiline, cursorBrush = SolidColor(p.ink),
        modifier = Modifier.fillMaxWidth().heightIn(min = if (multiline) 100.dp else 48.dp, max = 240.dp).background(p.canvas).border(0.5.dp, p.rule).padding(12.dp).testTag(tag))
}

@Composable
internal fun NativeInteractionCard(request: NativeInteraction, p: ConversationPalette, connected: Boolean, serverSkewMs: Long, onReply: (JsonObject, (Boolean, String?) -> Unit) -> Unit) {
    var value by remember(request.id) { mutableStateOf(request.prefill) }
    var pending by remember(request.id) { mutableStateOf(false) }
    var error by remember(request.id) { mutableStateOf<String?>(null) }
    var permanent by remember(request.id) { mutableStateOf<NativeOption?>(null) }
    var expired by remember(request.id, serverSkewMs) { mutableStateOf(nativeInteractionRemainingMs(request.expiresAt, System.currentTimeMillis(), serverSkewMs) == 0L) }
    LaunchedEffect(request.id, serverSkewMs) { kotlinx.coroutines.delay(nativeInteractionRemainingMs(request.expiresAt, System.currentTimeMillis(), serverSkewMs)); expired = true }
    fun send(cancel: Boolean = false, confirmed: Boolean? = null, selection: String? = null, always: Boolean = false) {
        if (pending || !connected || expired) return
        pending = true
        onReply(buildJsonObject {
            put("type", "interaction_reply"); put("requestId", request.id)
            if (cancel) put("cancelled", true)
            if (confirmed != null) put("confirmed", confirmed)
            if (selection != null) put("value", selection)
            if (always) put("confirmPermanent", true)
        }) { ok, reason -> pending = ok; if (!ok) error = reason ?: "决定未送达" }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).background(p.canvas).border(0.5.dp, p.rule).padding(16.dp).testTag("native-interaction-${request.method}")) {
        Text(request.title.ifBlank { "原生交互" }, style = TextStyle(color = p.ink, fontSize = 18.sp))
        if (request.message.isNotBlank()) Text(request.message, style = MonoSmall.copy(color = p.inkSoft), modifier = Modifier.padding(vertical = 8.dp))
        Text(if (expired) "请求已过期，已安全取消" else if (pending) "决定已提交；不代表工具已执行" else "等待你的决定 · 不会自动同意", style = MonoSmall.copy(color = p.inkSoft))
        val enabled = connected && !pending && !expired
        when (request.method) {
            "confirm" -> {
                SheetButton("确认", SheetButtonKind.Primary, p, "native-dialog-confirm", enabled = enabled) { send(confirmed = true) }
                SheetButton("否", SheetButtonKind.Secondary, p, "native-dialog-deny", enabled = enabled) { send(confirmed = false) }
            }
            "select", "permission" -> request.options.forEachIndexed { index, option ->
                SheetButton(option.name, SheetButtonKind.Secondary, p, "native-dialog-option-$index", enabled = enabled) {
                    if (option.kind.endsWith("_always")) permanent = option else send(selection = option.id)
                }
            }
            "input", "editor" -> {
                NativeTextField(value, request.placeholder.ifBlank { "输入内容（空字符串也会原样提交）" }, p, "native-dialog-value", request.method == "editor") { value = it }
                SheetButton("提交输入", SheetButtonKind.Primary, p, "native-dialog-submit", enabled = enabled) { send(selection = value) }
            }
        }
        SheetButton("取消请求", SheetButtonKind.Secondary, p, "native-dialog-cancel", enabled = enabled) { send(cancel = true) }
        error?.let { Text(it, style = MonoSmall.copy(color = p.danger)) }
    }
    permanent?.let { option ->
        NativeDecisionDialog("确认永久范围", "原生范围：${option.name}\n这是永久选择，不会缩小为当前文件或目录。", p, onCancel = { permanent = null }) {
            permanent = null; send(selection = option.id, always = true)
        }
    }
}

@Composable
internal fun NativeDecisionDialog(title: String, message: String, p: ConversationPalette, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Dialog(onDismissRequest = onCancel) {
        Column(Modifier.fillMaxWidth().background(p.canvas).border(0.5.dp, p.rule).padding(20.dp)) {
            Text(title, style = TextStyle(color = p.ink, fontSize = 18.sp))
            Text(message, style = MonoSmall.copy(color = p.inkSoft), modifier = Modifier.padding(vertical = 16.dp))
            SheetButton("取消", SheetButtonKind.Secondary, p, "native-consent-cancel", onClick = onCancel)
            SheetButton("明确确认", SheetButtonKind.Primary, p, "native-consent-confirm", onClick = onConfirm)
        }
    }
}

@Composable
internal fun RenameSessionDialog(initial: String, p: ConversationPalette, onDismiss: () -> Unit, onRename: (String, (Boolean, String?) -> Unit) -> Unit) {
    var title by remember { mutableStateOf(initial) }
    var pending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = { if (!pending) onDismiss() }) {
        Column(Modifier.fillMaxWidth().background(p.canvas).border(0.5.dp, p.rule).padding(20.dp).testTag("conversation-rename-dialog")) {
            Text("重命名会话", style = TextStyle(color = p.ink, fontSize = 18.sp))
            NativeTextField(title, "会话标题", p, "conversation-rename-input") { title = it.take(160) }
            error?.let { Text(it, style = MonoSmall.copy(color = p.danger)) }
            SheetButton("保存标题", SheetButtonKind.Primary, p, "conversation-rename-save", enabled = !pending && title.isNotBlank()) {
                pending = true; onRename(title) { ok, reason -> pending = false; if (ok) onDismiss() else error = reason }
            }
            SheetButton("取消", SheetButtonKind.Secondary, p, "conversation-rename-cancel", enabled = !pending, onClick = onDismiss)
        }
    }
}

internal data class NativePoint(val id: String, val text: String, val index: Int?)
internal fun nativePoints(data: JsonObject) = data.arr("points").orEmpty().mapNotNull {
    val o = it as? JsonObject ?: return@mapNotNull null
    NativePoint(o.str("id").takeIf { id -> id.length == 32 } ?: return@mapNotNull null, o.str("text"), o.int("index"))
}

@Composable
internal fun NativePointsSheet(open: Boolean, rewind: Boolean, loading: Boolean, points: List<NativePoint>, error: String?, p: ConversationPalette, backdrop: Backdrop, onDismiss: () -> Unit, onReload: () -> Unit, onChoose: (NativePoint) -> Unit) {
    ConversationSheet(open, if (rewind) "回滚轮次" else "分叉会话", "NATIVE SESSION", if (rewind) "仅原生 conversation_only；不还原文件。success=false 不会当成功。" else "选择原生用户消息：新会话停在它之前，文本回草稿，不自动发送。", p, backdrop, "native-points-sheet", "native-points-close", onDismiss) {
        if (loading) Text("正在读取原生节点…", style = MonoSmall.copy(color = p.inkSoft))
        error?.let { Text(it, style = MonoSmall.copy(color = p.danger)) }
        if (!loading && error == null && points.isEmpty()) Text("当前没有可选原生节点", style = MonoSmall.copy(color = p.inkSoft))
        points.forEachIndexed { index, point -> SheetButton("${point.index?.let { "#${it + 1} · " }.orEmpty()}${point.text}", SheetButtonKind.Secondary, p, "native-point-$index", enabled = !loading) { onChoose(point) } }
        SheetButton("重新读取", SheetButtonKind.Secondary, p, "native-points-reload", enabled = !loading, onClick = onReload)
    }
}

/** Exact documented slash syntax; never shell interpolation or fabricated task IDs. */
internal fun nativeTaskCommand(type: String, action: String, text: String, budget: String, args: String): String {
    val input = text.trim()
    require(!input.contains('\n') || type == "deep-research") { "名称或目标不能包含换行" }
    return when (type) {
        "deep-research" -> { require(input.isNotBlank()) { "研究问题不能为空" }; "/deep-research $input" }
        "workflow" -> when (action) {
            "runs" -> "/workflow runs"
            "catalog" -> "/workflows"
            "pause", "resume", "stop", "save" -> { require(input.isNotBlank() && !input.any(Char::isWhitespace)) { "请输入原生 display-name" }; "/workflow $action $input" }
            else -> {
                require(input.isNotBlank() && !input.any(Char::isWhitespace)) { "请输入工作流名称" }
                val json = args.takeIf { it.isNotBlank() }?.let { Json.parseToJsonElement(it).also { obj -> require(obj is JsonObject) { "参数必须是 JSON 对象" } }.jsonObject }
                val b = budget.takeIf { it.isNotBlank() }?.toIntOrNull()
                require(budget.isBlank() || (b != null && b in 1..1024)) { "agent-budget 必须为1–1024" }
                require(b == null || json?.containsKey("agent_budget") != true) { "预算不能同时从表单与 JSON 设置" }
                "/workflow $input${b?.let { " --agent-budget $it" }.orEmpty()}${json?.let { " $it" }.orEmpty()}"
            }
        }
        "goal" -> when (action) {
            "status", "pause", "resume", "clear" -> "/goal $action"
            else -> { require(input.isNotBlank()) { "目标不能为空" }; val b = budget.takeIf { it.isNotBlank() }?.toLongOrNull(); require(budget.isBlank() || (b != null && b > 0)) { "token预算必须为正整数" }; "/goal $input${b?.let { " --budget $it" }.orEmpty()}" }
        }
        else -> { require(type.matches(Regex("[A-Za-z0-9:_-]+"))) { "无效原生命令" }; "/$type${input.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()}" }
    }
}

@Composable
internal fun NativeTasksSheet(open: Boolean, commands: List<SlashCommand>, p: ConversationPalette, backdrop: Backdrop, onDismiss: () -> Unit, onMapped: (String) -> Boolean, onSubmit: (String, (Boolean, String?) -> Unit) -> Unit) {
    var type by remember { mutableStateOf("deep-research") }
    var action by remember(type) { mutableStateOf(if (type == "workflow") "runs" else if (type == "goal") "status" else "start") }
    var text by remember(type, action) { mutableStateOf("") }
    var budget by remember(type, action) { mutableStateOf("") }
    var args by remember(type, action) { mutableStateOf("") }
    var consent by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf(false) }
    val advertised = commands.map { it.name.removePrefix("/") }.toSet()
    ConversationSheet(open, "Grok 原生任务", "NATIVE TASKS", "后台 ACK / end_turn 仅表示原生命令已接收；最终报告与任务状态才是完成证据。", p, backdrop, "grok-tasks-sheet", "grok-tasks-close", onDismiss) {
        val choices = listOf("deep-research", "workflow", "goal") + commands.take(128).map { it.name.removePrefix("/") }.filterNot { it in setOf("deep-research", "workflow", "goal") }.distinct()
        choices.forEach { name -> if (name in advertised) SheetButton("/$name${if (type == name) " · 已选择" else ""}", SheetButtonKind.Secondary, p, "grok-task-type-$name", enabled = !pending) { if (!onMapped(name)) { type = name; result = null } } }
        if (type !in advertised) Text("原生 metadata 未广告 /$type，本入口不可提交。", style = MonoSmall.copy(color = p.inkSoft))
        val actions = when (type) { "workflow" -> listOf("runs", "catalog", "start", "pause", "resume", "stop", "save"); "goal" -> listOf("status", "start", "pause", "resume", "clear"); else -> emptyList() }
        actions.forEach { option -> SheetButton("$option${if (action == option) " · 已选择" else ""}", SheetButtonKind.Secondary, p, "grok-task-action-$option", enabled = !pending) { action = option; result = null } }
        if (type !in setOf("workflow", "goal") || action in setOf("start", "pause", "resume", "stop", "save") && type == "workflow" || action == "start") NativeTextField(text, when(type) { "deep-research" -> "研究问题"; "workflow" -> "工作流名称 / 当前会话 display-name"; "goal" -> "目标"; else -> "原生参数（不推测未知语法）" }, p, "grok-task-text", type == "deep-research") { text = it }
        if (action == "start" && type in setOf("workflow", "goal")) NativeTextField(budget, if (type == "goal") "token预算（可留空，无伪造默认值）" else "agent-budget 1–1024（可留空）", p, "grok-task-budget") { budget = it }
        if (type == "workflow" && action == "start") NativeTextField(args, "原生 JSON 参数（可留空）", p, "grok-task-args", true) { args = it }
        result?.let { Text(it, style = MonoSmall.copy(color = p.inkSoft)) }
        SheetButton("提交原生命令", SheetButtonKind.Primary, p, "grok-task-submit", enabled = !pending && type in advertised && (action != "catalog" || "workflows" in advertised)) {
            try { consent = nativeTaskCommand(type, action, text, budget, args) } catch (e: Exception) { result = e.message ?: "参数无效" }
        }
    }
    consent?.let { command -> NativeDecisionDialog("确认原生任务操作", "$command\n可能启动后台任务、产生费用或修改当前目标。不会因 ACK 标记成功。", p, { consent = null }) {
        consent = null; pending = true
        onSubmit(command) { ok, reason -> pending = false; result = if (ok) "原生命令已接收，请核对对话报告和 /workflow runs 或 /goal status；未宣称任务完成。" else reason ?: "未送达" }
    } }
}
