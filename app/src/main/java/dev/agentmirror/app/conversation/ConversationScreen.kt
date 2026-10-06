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

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import dev.agentmirror.app.diag.DiagLog
import dev.agentmirror.app.perf.PerfTrace
import dev.agentmirror.app.service.ServiceWire
import dev.agentmirror.app.session.Attachment
import dev.agentmirror.app.session.ClearFocusWhenImeHides
import dev.agentmirror.app.session.HttpUrlConnectionUploader
import dev.agentmirror.app.session.SharedPreferencesShortcutCommandStore
import dev.agentmirror.app.session.ShortcutResolution
import dev.agentmirror.app.session.UploadOutcome
import dev.agentmirror.app.session.resolveShortcutCommand
import dev.agentmirror.app.session.textFileAttachment
import dev.agentmirror.app.session.textFileReference
import dev.agentmirror.app.ui.components.RuleEdge
import dev.agentmirror.app.ui.components.edgeRule
import dev.agentmirror.app.ui.theme.AppTheme
import dev.agentmirror.app.ui.theme.LocalAppPalette
import dev.agentmirror.app.ui.theme.LocalThemeSuite
import dev.agentmirror.app.ui.theme.SharedPreferencesTermThemeStore
import dev.agentmirror.app.ui.theme.TermPalette
import dev.agentmirror.app.ui.theme.isDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong

/*
 * Native conversation screen (issue #50). Layering, back to front:
 *   canvas + ambient glow ─┐ recorded into a LayerBackdrop
 *   transcript (LazyColumn)┘
 *   frosted header · inline status capsule · jump-to-latest · frosted dock (sample the backdrop)
 * The transcript uses reverseLayout, so streaming growth stays pinned to the bottom with zero
 * scroll calls per token, and reading history is never yanked away.
 */

private const val LONG_TEXT_BYTES = 24_000

/**
 * Route entry for a managed conversation pane. [onOpenTerminal] is the only way out to the TUI:
 * an explicit tap, or — only when this ref never produced a ready stream — capability fallback.
 */
@Composable
fun ConversationRoute(
    ref: String,
    name: String,
    onBack: () -> Unit,
    onOpenTerminal: (fallback: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val hub = ConversationCenter.hub
    val session = remember(ref) { hub.session(ref) }
    remember(ref) { ConversationAttachment(hub, ref) }
    val state by session.state.collectAsState()
    val phase by session.phase.collectAsState()
    val commands by session.commands.collectAsState()
    val mode by session.mode.collectAsState()
    LaunchedEffect(phase) {
        if (phase == LinkPhase.Unavailable && !session.everReady) {
            DiagLog.record("conversation", "fallback_tui ref_hash=${ref.hashCode()} reason=unavailable_before_ready")
            onOpenTerminal(true)
        }
    }
    // The host confirms the agent's native TUI holds the pane: show it.
    // This is a confirmed mode, not a capability fallback.
    LaunchedEffect(phase, mode) {
        if (phase == LinkPhase.Live && mode == PaneMode.Tui) {
            DiagLog.record("conversation", "pane_mode ref_hash=${ref.hashCode()} mode=tui")
            onOpenTerminal(false)
        }
    }
    AppTheme {
        val dark = LocalAppPalette.current.isDark
        // Keyed on the scheme instance: switching terminal family within one slot repaints too.
        val scheme = TermPalette.of(dark)
        val palette = remember(scheme) { ConversationPalette.of(scheme) }
        val look = ConversationLook.of(LocalThemeSuite.current)
        SystemBarInk(palette.dark)
        androidx.compose.runtime.CompositionLocalProvider(LocalConversationLook provides look) {
        ConversationScreen(
            ref = ref,
            name = name,
            state = state,
            phase = phase,
            commands = commands,
            p = palette,
            context = context,
            onBack = onBack,
            onOpenTerminal = { onOpenTerminal(false) },
        )
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun ConversationScreen(
    ref: String,
    name: String,
    state: ConversationState,
    phase: LinkPhase,
    commands: List<SlashCommand>,
    p: ConversationPalette,
    context: Context,
    onBack: () -> Unit,
    onOpenTerminal: () -> Unit,
) {
    val hub = ConversationCenter.hub
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val focus = LocalFocusManager.current
    val listState = rememberLazyListState()
    val expanded = remember(ref) { mutableStateMapOf<String, Boolean>() }
    var draft by remember(ref) { mutableStateOf(TextFieldValue("")) }
    val images = remember(ref) { mutableStateListOf<PendingImage>() }
    var sheet by remember { mutableStateOf(ComposerSheet.None) }
    var slashDismissedFor by remember { mutableStateOf<String?>(null) }
    // One overlay at a time — menu, model picker or a task sheet — so opening one atomically
    // replaces another and Back peels exactly one layer.
    var overlay by remember(ref) { mutableStateOf(Overlay.None) }
    var historyLoading by remember(ref) { mutableStateOf(false) }
    var historyRestoring by remember(ref) { mutableStateOf(false) }
    var historyChoices by remember(ref) { mutableStateOf(emptyList<SessionHistoryChoice>()) }
    var historyError by remember(ref) { mutableStateOf<String?>(null) }
    var historyGeneration by remember(ref) { mutableIntStateOf(0) }
    var historyTarget by remember(ref) { mutableStateOf<SessionHistoryChoice?>(null) }
    var historyStream by remember(ref) { mutableStateOf<String?>(null) }
    var historyHead by remember(ref) { mutableStateOf(0L) }
    var historyConfirm by remember(ref) { mutableStateOf<SessionHistoryChoice?>(null) }
    var historyTitle by remember(ref) { mutableStateOf<SessionHistoryChoice?>(null) }
    var confirmSwitch by remember { mutableStateOf(false) }
    var switchReason by remember(ref) { mutableStateOf<String?>(null) }
    var pendingModel by remember(ref) { mutableStateOf<ModelChoice?>(null) }
    var pendingLevel by remember(ref) { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    var compactRun by remember(ref) { mutableStateOf<CompactRun?>(null) }
    var compactInstructions by remember(ref) { mutableStateOf("") }
    var usage by remember(ref) { mutableStateOf(UsageLoad()) }
    var usageGeneration by remember(ref) { mutableIntStateOf(0) }
    var exporting by remember(ref) { mutableStateOf(false) }
    var points by remember(ref) { mutableStateOf(emptyList<NativePoint>()) }
    var pointsLoading by remember(ref) { mutableStateOf(false) }
    var pointsError by remember(ref) { mutableStateOf<String?>(null) }
    var mutationConsent by remember(ref) { mutableStateOf<Pair<String, NativePoint?>?>(null) }
    var mutationForce by remember(ref) { mutableStateOf(false) }
    var mutationDraft by remember(ref) { mutableStateOf<String?>(null) }
    var renameOpen by remember(ref) { mutableStateOf(false) }
    var editorApplied by remember(ref) { mutableStateOf(0L) }
    LaunchedEffect(state.extensionEditor) {
        state.extensionEditor?.let { (seq, text) -> if (seq > editorApplied) {
            editorApplied = seq
            // Do not silently discard an existing user draft or its attachments.
            val merged = if (draft.text.isBlank()) text else draft.text + "\n" + text
            draft = TextFieldValue(merged, TextRange(merged.length))
        } }
    }
    // Geometry is read at layout time only (list padding, overlay offsets): the dock rising or the
    // IME sliding re-measures, it never recomposes the screen.
    val headerPx = remember { mutableIntStateOf(0) }
    val dockPx = remember { mutableIntStateOf(0) }
    val imageIds = remember { AtomicLong() }

    // ---- composer focus: one intent source (TUI dock state machine) ------------------------
    val keyboard = LocalSoftwareKeyboardController.current
    var editorFocused by remember(ref) { mutableStateOf(false) }
    val expansion = remember(ref) { mutableIntStateOf(0) }
    var collapseRequested by remember(ref) { mutableStateOf(false) }
    fun collapseComposer(source: String) {
        if (collapseRequested || !editorFocused) return
        collapseRequested = true
        ConvTrace.log("dock collapse source=$source generation=${expansion.intValue}")
        keyboard?.hide()
        focus.clearFocus(force = true)
    }
    ClearFocusWhenImeHides(
        collapseRequested = collapseRequested,
        expansionRequest = expansion,
        onImeHideStarted = { collapseComposer("ime-hide") },
    )

    val connected = phase == LinkPhase.Live && !historyRestoring
    val abilities = AgentAbilities.of(state.agentProvider)
    val slashActive = draft.text.startsWith("/") && !draft.text.contains(' ') && !draft.text.contains('\n') && slashDismissedFor != draft.text
    val activeSheet = when {
        sheet != ComposerSheet.None -> sheet
        slashActive -> ComposerSheet.Slash
        else -> ComposerSheet.None
    }
    val shortcutCommands = remember(activeSheet == ComposerSheet.Shortcuts) { SharedPreferencesShortcutCommandStore(context).load() }
    val sheetEntries = when (activeSheet) {
        ComposerSheet.Slash -> slashEntries(draft.text, commands)
        ComposerSheet.Shortcuts -> shortcutCommands.mapNotNull { command ->
            val resolved = resolveShortcutCommand(command, state.agentProvider) as? ShortcutResolution.Found ?: return@mapNotNull null
            SheetEntry("shortcut:${command.id}", command.name, resolved.text, Glyph.Bolt)
        }.ifEmpty { listOf(SheetEntry("shortcut:none", "还没有快捷命令", "在 设置 › 快捷命令 中添加常用指令", Glyph.Bolt)) }
        ComposerSheet.Attach -> listOf(
            SheetEntry("attach:camera", "拍照", "拍一张照片附在消息里", Glyph.Camera),
            SheetEntry("attach:photo", "从相册选择", "选择一张图片附在消息里", Glyph.Photo),
        )
        ComposerSheet.None -> emptyList()
    }

    LaunchedEffect(toast) {
        if (toast != null) {
            delay(2_800)
            toast = null
        }
    }

    // ---- attachments ----------------------------------------------------------------------
    fun upload(attachment: Attachment, preview: Bitmap?) {
        val id = imageIds.incrementAndGet()
        images += PendingImage(id, preview)
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { uploadToHost(attachment) }
            val at = images.indexOfFirst { it.id == id }
            if (at < 0) return@launch
            images[at] = when (outcome) {
                is UploadOutcome.Success -> images[at].copy(hostPath = outcome.path)
                is UploadOutcome.Failure -> {
                    toast = "图片上传失败：${outcome.reason}"
                    images[at].copy(failed = true)
                }
            }
        }
    }
    var pendingCapture by remember { mutableStateOf<Uri?>(null) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val loaded = withContext(Dispatchers.IO) { readImage(context, uri) }
            if (loaded == null) toast = "无法读取所选图片" else upload(loaded.first, loaded.second)
        }
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = pendingCapture
        pendingCapture = null
        if (saved && uri != null) {
            scope.launch {
                val loaded = withContext(Dispatchers.IO) { readImage(context, uri) }
                if (loaded == null) toast = "无法读取拍照图片" else upload(loaded.first, loaded.second)
            }
        } else if (uri != null) {
            context.contentResolver.delete(uri, null, null)
        }
    }
    val takePreview = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out); out.toByteArray() }
            }
            upload(Attachment("camera-${System.currentTimeMillis()}.jpg", "image/jpeg", bytes), bitmap)
        }
    }
    val launchCamera = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "camera-${System.currentTimeMillis()}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                },
            )
            if (uri == null) toast = "无法创建拍照文件" else {
                pendingCapture = uri
                takePhoto.launch(uri)
            }
        } else {
            takePreview.launch(null)
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else toast = "相机权限未授权，请到系统设置中开启"
    }

    // ---- sending --------------------------------------------------------------------------
    fun scrollToLatest() = scope.launch {
        if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0) {
            ConvTrace.log("jump from=${listState.firstVisibleItemIndex}/${listState.firstVisibleItemScrollOffset}")
            listState.animateScrollToItem(0)
        }
    }

    fun send() {
        if (!connected) return
        val text = promptText(draft.text)
        val paths = images.mapNotNull { it.hostPath }
        if (text.isEmpty() && paths.isEmpty()) return
        val restore = draft
        val restoreImages = images.toList()
        draft = TextFieldValue("")
        images.clear()
        sheet = ComposerSheet.None
        scrollToLatest()
        val onResult: (Boolean, String?) -> Unit = { ok, reason ->
            if (!ok) {
                if (draft.text.isEmpty()) draft = restore
                if (images.isEmpty()) images.addAll(restoreImages)
                toast = "未送达：${reason ?: "主机没有确认"}"
            }
        }
        when (text) {
            "/compact" -> hub.send(ref, "compact", onResult = onResult)
            "/new", "/clear" -> hub.send(ref, "new_session", onResult = onResult)
            else -> {
                val behavior = if (state.running) "steer" else null
                if (text.toByteArray().size > LONG_TEXT_BYTES) {
                    // Long drafts travel as a host text file, exactly like the terminal path.
                    scope.launch {
                        val outcome = withContext(Dispatchers.IO) { uploadToHost(textFileAttachment(text)) }
                        if (outcome is UploadOutcome.Success) {
                            hub.send(ref, "prompt", textFileReference(outcome.path), paths, behavior, onResult)
                        } else {
                            onResult(false, (outcome as UploadOutcome.Failure).reason)
                        }
                    }
                } else {
                    hub.send(ref, "prompt", text, paths, behavior, onResult)
                }
            }
        }
    }

    fun onSheetEntry(entry: SheetEntry) {
        when {
            entry.key.startsWith("/") -> {
                val text = entry.key + " "
                draft = TextFieldValue(text, TextRange(text.length))
            }
            entry.key == "attach:photo" -> pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            entry.key == "attach:camera" -> {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
                else cameraPermission.launch(Manifest.permission.CAMERA)
            }
            entry.key.startsWith("shortcut:") && entry.key != "shortcut:none" -> {
                val text = entry.detail
                draft = TextFieldValue(text, TextRange(text.length))
            }
        }
        sheet = ComposerSheet.None
    }

    // ---- model & thinking ------------------------------------------------------------------
    fun command(type: String, vararg fields: Pair<String, String>) = buildJsonObject {
        put("type", type)
        fields.forEach { (k, v) -> put(k, v) }
    }
    fun openPicker() {
        overlay = if (overlay == Overlay.Picker) Overlay.None else Overlay.Picker
        if (overlay == Overlay.Picker) {
            hub.control(ref, command("get_available_models"))
            hub.control(ref, command("get_available_thinking_levels"))
        }
    }
    fun chooseModel(choice: ModelChoice) {
        pendingModel = choice
        hub.control(ref, command("set_model", "provider" to choice.provider, "modelId" to choice.id)) { ok, reason ->
            pendingModel = null
            if (ok) {
                // The new model decides which levels exist and may clamp the current one.
                hub.control(ref, command("get_available_thinking_levels"))
                hub.control(ref, command("get_state"))
            } else {
                toast = "未切换到 ${choice.name}：${reason ?: "主机没有确认"}"
            }
        }
    }
    fun chooseLevel(level: String) {
        pendingLevel = level
        hub.control(ref, command("set_thinking_level", "level" to level)) { ok, reason ->
            pendingLevel = null
            if (!ok) toast = "思考强度未调整：${reason ?: "主机没有确认"}"
        }
    }

    // ---- native Pi history (same process, interactive switch_session) --------------------------
    fun loadHistory() {
        if (historyRestoring) { hub.retry(ref); return }
        overlay = Overlay.History
        historyLoading = true
        historyError = null
        val generation = ++historyGeneration
        hub.controlWithData(ref, command("list_sessions")) { ok, reason, data ->
            if (generation == historyGeneration && overlay == Overlay.History) {
                historyLoading = false
                if (ok && data?.arr("sessions") != null) historyChoices = sessionHistoryChoices(data)
                else historyError = reason ?: "主机未返回历史会话列表"
            }
        }
    }
    fun resumeHistory(choice: SessionHistoryChoice, force: Boolean) {
        historyConfirm = null
        historyTarget = choice
        historyStream = null
        historyRestoring = true
        historyError = null
        collapseComposer("restore-history")
        val generation = ++historyGeneration
        hub.controlWithData(ref, buildJsonObject {
            put("type", "resume_session")
            put("sessionId", choice.id)
            put("force", force)
        }) { ok, reason, data ->
            if (generation == historyGeneration) {
                if (ok && data != null && data.str("session_id") == choice.id && data.str("stream").isNotBlank()) {
                    historyStream = data.str("stream")
                    historyHead = data.long("head_seq") ?: Long.MAX_VALUE
                } else {
                    historyRestoring = false
                    if (!force && data?.bool("busy") == true) {
                        historyError = reason
                        historyConfirm = choice
                    } else historyError = reason ?: "主机未确认目标会话"
                }
            }
        }
    }
    // Ordinary streaming must not recreate a no-op job for every lastSeq change.
    // Keep every stream/session/head/Live barrier while an actual restore is in progress.
    if (historyRestoring) {
        LaunchedEffect(historyStream, historyHead, state.stream, state.lastSeq, state.sessionId, historyTarget?.id, phase) {
            if (historyStream != null && state.stream == historyStream &&
                state.lastSeq >= historyHead && state.sessionId == historyTarget?.id && phase == LinkPhase.Live) {
                historyTitle = historyTarget
                mutationDraft?.let { text ->
                    val merged = if (draft.text.isBlank()) text else draft.text + "\n" + text
                    draft = TextFieldValue(merged, TextRange(merged.length))
                }
                mutationDraft = null
                historyRestoring = false
                if (overlay == Overlay.History) overlay = Overlay.None
                historyError = null
                expanded.clear()
                listState.scrollToItem(0)
            }
        }
        LaunchedEffect(historyGeneration) {
            delay(45_000)
            historyError = "历史流尚未完整到达，请重连重试；输入暂未开放"
        }
    }

    // ---- in-pane switch to the native TUI --------------------------------------------------------
    // Real work in flight, read from what the agent reported (not from what is on screen).
    val inFlight = state.running || state.compacting || state.queued > 0 ||
        state.items.any { it is ToolCall && !it.finished }
    fun switchToTerminal(force: Boolean) {
        confirmSwitch = false
        toast = "正在切换到终端…"
        hub.switchMode(ref, PaneMode.Tui, force) { ok, reason, busy ->
            when {
                ok -> onOpenTerminal()
                busy && !force -> { toast = null; switchReason = reason; collapseComposer("confirm"); confirmSwitch = true }
                else -> toast = "未切换到终端：${reason ?: "主机没有确认"}"
            }
        }
    }

    // ---- overflow actions and their sheets ----------------------------------------------------
    fun openCompact() {
        collapseComposer("compact-sheet")
        // A finished run is history: reopening starts a fresh form (the draft text is kept).
        if (compactRun != null && !state.compacting && compactPhase(compactRun, false, state.compaction) != CompactPhase.Submitting) compactRun = null
        overlay = Overlay.Compact
    }
    fun submitCompact() {
        val run = CompactRun(id = (compactRun?.id ?: 0) + 1, baseline = state.compaction?.seq ?: 0L)
        compactRun = run
        hub.controlWithData(ref, buildJsonObject {
            put("type", "compact")
            // Sent verbatim (trim only decides emptiness); never to an agent that would drop it.
            if (abilities.compactInstructions && compactInstructions.isNotBlank()) put("customInstructions", compactInstructions)
        }) { ok, reason, _ ->
            if (compactRun?.id == run.id) compactRun = run.copy(replied = ok, reason = reason)
            if (ok) compactInstructions = ""
        }
    }
    fun readUsage() {
        val generation = ++usageGeneration
        usage = usage.copy(loading = true)
        hub.controlWithData(ref, command("get_session_stats")) { ok, reason, data ->
            if (generation != usageGeneration) return@controlWithData
            usage = when {
                ok && data != null -> UsageLoad(snapshot = usageSnapshot(data, System.currentTimeMillis()))
                // A host older than the stats projection refuses the command by name.
                reason == "command is not available from the phone" -> UsageLoad(unsupported = true)
                else -> usage.copy(loading = false, error = reason ?: "主机没有确认")
            }
        }
    }
    fun openUsage() {
        collapseComposer("usage-sheet")
        // The host may have been updated since it last refused: ask again.
        if (usage.unsupported) usage = UsageLoad()
        overlay = Overlay.Usage
    }
    // Read once on open, then again whenever the agent settles (turn end, compaction end) or the
    // session changes while the sheet is up; never on a timer, never while it is closed.
    if (overlay == Overlay.Usage) {
        val settled = connected && !state.running && !state.compacting
        LaunchedEffect(settled, state.sessionId) {
            if (connected && !usage.unsupported && (settled || usage.snapshot == null)) readUsage()
        }
    }
    fun exportSession() {
        if (exporting) {
            overlay = Overlay.Export
            return
        }
        val sid = state.sessionId
        val base = ServiceWire.uploadBaseUrl
        val token = ServiceWire.currentConfig()?.token
        if (sid.isNullOrBlank() || base.isNullOrBlank() || token.isNullOrBlank()) {
            toast = "会话或配对身份未确认，未导出"
            return
        }
        exporting = true
        overlay = Overlay.Export
        toast = "正在生成并下载会话导出…"
        scope.launch {
            try {
                val artifact = withContext(Dispatchers.IO) { downloadSessionExport(context, base, token, ref, sid) }
                if (overlay == Overlay.Export) overlay = Overlay.None
                shareSessionExport(context, artifact.first, artifact.second)
                toast = "导出已下载，系统分享已打开"
            } catch (_: Exception) {
                toast = "导出未完成，请确认主机已更新且会话仍在线后重试"
            } finally {
                exporting = false
                if (overlay == Overlay.Export) overlay = Overlay.None
            }
        }
    }
    fun loadPoints(rewind: Boolean) {
        overlay = if (rewind) Overlay.Rewind else Overlay.Fork
        pointsLoading = true; pointsError = null; points = emptyList()
        hub.controlWithData(ref, command(if (rewind) "rewind_points" else "fork_points")) { ok, reason, data ->
            pointsLoading = false
            if (ok && data != null) points = nativePoints(data) else pointsError = reason ?: "原生节点未返回"
        }
    }
    fun mutate(kind: String, point: NativePoint?, force: Boolean) {
        mutationConsent = null; mutationForce = false
        historyRestoring = true; historyStream = null; historyTarget = null
        mutationDraft = null; collapseComposer("native-session-mutation")
        val generation = ++historyGeneration
        hub.controlWithData(ref, buildJsonObject {
            put("type", kind); put("force", force)
            point?.let { put("pointId", it.id) }
        }) { ok, reason, data ->
            if (generation == historyGeneration) {
                if (ok && data != null && data.str("session_id").isNotBlank() && data.str("stream").isNotBlank()) {
                    historyTarget = SessionHistoryChoice(data.str("session_id"), data.str("sessionName").ifBlank { "新会话" }, "", 0L, false)
                    historyStream = data.str("stream"); historyHead = data.long("head_seq") ?: Long.MAX_VALUE
                    mutationDraft = data.str("draft").ifBlank { null }
                    overlay = Overlay.None
                    historyTitle = null
                } else {
                    historyRestoring = false
                    if (!force && data?.bool("busy") == true) { mutationConsent = kind to point; mutationForce = true }
                    toast = reason ?: "主机未确认原生会话操作"
                }
            }
        }
    }
    fun runAction(action: ConversationAction) {
        // Re-resolved at click time: a row painted enabled a moment ago cannot act on stale state.
        val resolved = resolveActions(state.agentProvider, connected, historyRestoring, state.compacting).firstOrNull { it.action == action }
        overlay = Overlay.None
        if (resolved?.enabled != true) return
        when (action) {
            ConversationAction.History -> loadHistory()
            ConversationAction.Compact -> openCompact()
            ConversationAction.NewSession -> mutate("new_session", null, false)
            ConversationAction.Rename -> renameOpen = true
            ConversationAction.Fork -> loadPoints(false)
            ConversationAction.Rewind -> loadPoints(true)
            ConversationAction.Clone -> { mutationConsent = "clone_session" to null; mutationForce = false }
            ConversationAction.Tasks -> overlay = Overlay.Tasks
            ConversationAction.Export -> exportSession()
            ConversationAction.Usage -> openUsage()
            ConversationAction.Terminal -> if (inFlight) {
                switchReason = null
                collapseComposer("confirm")
                confirmSwitch = true
            } else {
                switchToTerminal(force = false)
            }
        }
    }

    // Back peels one layer at a time: dialog → overlay (menu, picker, sheet) → panel → composer → leave.
    BackHandler(enabled = activeSheet != ComposerSheet.None || overlay != Overlay.None || editorFocused || confirmSwitch || historyConfirm != null) {
        when {
            historyConfirm != null -> historyConfirm = null
            overlay == Overlay.History && historyRestoring -> onBack()
            overlay == Overlay.History -> { overlay = Overlay.None; historyGeneration++ }
            confirmSwitch -> confirmSwitch = false
            overlay != Overlay.None -> overlay = Overlay.None
            sheet != ComposerSheet.None -> sheet = ComposerSheet.None
            activeSheet == ComposerSheet.Slash -> slashDismissedFor = draft.text
            else -> collapseComposer("back")
        }
    }
    BackHandler(enabled = activeSheet == ComposerSheet.None && overlay == Overlay.None && !editorFocused && !confirmSwitch && historyConfirm == null, onBack = onBack)

    // ---- layout -----------------------------------------------------------------------------
    val look = LocalConversationLook.current
    // Glass records the transcript (plus the ambient glow) for the panels to sample; Modernism
    // samples nothing, so it records nothing.
    val backdrop = rememberLayerBackdrop {
        drawRect(p.canvas)
        if (look.glass) {
            drawRect(
                Brush.radialGradient(
                    listOf(p.glow, Color.Transparent),
                    center = Offset(size.width * 0.18f, -size.height * 0.04f),
                    radius = size.maxDimension * 0.75f,
                ),
            )
        }
        drawContent()
    }
    val listPadding = remember(density) { LiveListPadding(density, headerPx, dockPx) }
    val followSlackPx = with(density) { FOLLOW_SLACK.roundToPx() }
    val following by remember(followSlackPx) {
        derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset <= followSlackPx }
    }
    // PerfTrace first_draw (setprop-gated, free when off): the first frame that paints transcript
    // rows, comparable with the terminal path's route_enter → first_draw.
    val perfOpenId = remember(ref) { PerfTrace.idFor(ref) }
    val firstDrawn = remember(ref) { booleanArrayOf(perfOpenId == null) }

    if (ConvTrace.enabled) {
        LaunchedEffect(listState) {
            snapshotFlow {
                val info = listState.layoutInfo
                "scroll first=${listState.firstVisibleItemIndex} key=${info.visibleItemsInfo.firstOrNull()?.key} " +
                    "offset=${listState.firstVisibleItemScrollOffset} back=${listState.canScrollBackward} " +
                    "scrolling=${listState.isScrollInProgress} following=$following dockPx=${dockPx.intValue} headerPx=${headerPx.intValue} " +
                    "viewport=${info.viewportStartOffset}..${info.viewportEndOffset} before=${info.beforeContentPadding} after=${info.afterContentPadding} items=${info.totalItemsCount}"
            }.collect(ConvTrace::log)
        }
    }
    // Test tags double as resource ids so device automation can address controls, never coordinates.
    Box(Modifier.fillMaxSize().background(p.canvas).semantics { testTagsAsResourceId = true }.testTag("conversation-screen")) {
        val rows = remember(state.items) { state.items.asReversed() }
        val working = workingLabel(state)
        // LazyList keeps its first visible row by key, so a row inserted at the bottom would land
        // below the fold. A reader already at the newest row stays pinned to the new newest row;
        // a reader up in history keeps their place.
        val newestKey: Any? = if (working != null) "working" else rows.firstOrNull()?.key
        val newestSeen = remember { arrayOfNulls<Any>(1) }
        val pin = following && newestSeen[0] != null && newestSeen[0] != newestKey
        newestSeen[0] = newestKey
        if (pin) {
            androidx.compose.runtime.SideEffect {
                ConvTrace.log("pin newest=$newestKey")
                listState.requestScrollToItem(0)
            }
        }
        LazyColumn(
            state = listState,
            reverseLayout = true,
            contentPadding = listPadding,
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
            modifier = Modifier
                .fillMaxSize()
                .then(if (look.glass) Modifier.layerBackdrop(backdrop) else Modifier)
                .drawWithContent {
                    drawContent()
                    if (!firstDrawn[0] && rows.isNotEmpty()) {
                        firstDrawn[0] = true
                        PerfTrace.firstDraw(perfOpenId!!, rows.size)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        sheet = ComposerSheet.None
                        collapseComposer("outside-tap")
                    })
                }
                .testTag("conversation-list"),
        ) {
            if (working != null) {
                item(key = "working") { WorkingIndicator(working, p, Modifier.animateItem(placementSpec = null)) }
            }
            state.interactions.asReversed().forEach { request ->
                item(key = "interaction:${request.id}") {
                    NativeInteractionCard(request, p, phase == LinkPhase.Live) { decision, callback -> hub.control(ref, decision, callback) }
                }
            }
            items(rows, key = { it.key }, contentType = { it::class }) { item ->
                // No placement spring: a row moved by its neighbour growing (streaming, disclosure,
                // dock rise) must track it in the same frame, never lag and overlap it.
                val mod = Modifier.animateItem(fadeInSpec = tween(220), placementSpec = null, fadeOutSpec = tween(140))
                when (item) {
                    is UserTurn -> UserBubble(item, p, expanded[item.key] == true, { expanded[item.key] = expanded[item.key] != true }, mod)
                    is AssistantText -> AssistantProse(item, p, mod)
                    is Reasoning -> ReasoningRow(item, p, expanded[item.key] == true, { expanded[item.key] = expanded[item.key] != true }, mod)
                    is ToolCall -> ToolCallCard(item, p, expanded[item.key] == true, state.serverSkewMs, { expanded[item.key] = expanded[item.key] != true }, mod, streaming = item.shouldAnimate(state.running))
                    is Notice -> NoticeRow(item, p, mod)
                }
            }
            state.extensionTitle?.let { title -> item(key = "extension-title") { NoticeRow(Notice("extension-title", NoticeTone.Info, "扩展标题", title), p) } }
            if (state.historyTruncated) {
                item(key = "truncated") {
                    NoticeRow(Notice("truncated", NoticeTone.Divider,
                        if (state.historyContentClipped) "部分超长内容已截短显示，完整历史保存在主机文件"
                        else "已加载最新 ${state.items.size} 条历史消息，更早消息已保存在主机文件"), p, Modifier.animateItem(placementSpec = null))
                }
            }
            if (state.items.isEmpty()) {
                item(key = "empty") {
                    Box(Modifier.fillMaxWidth().fillParentMaxHeight(0.82f).animateItem(placementSpec = null), contentAlignment = Alignment.Center) {
                        if (connected && !state.running) {
                            ConversationEmpty(agentName(state.agentProvider), state.model, p, onSuggestion = { s -> draft = TextFieldValue(s, TextRange(s.length)) })
                        } else if (!connected) {
                            ConversationSkeleton(p)
                        }
                    }
                }
            }
        }

        // Frosted header.
        ConversationHeader(
            name = if (historyRestoring && historyTarget != null) historyTarget!!.title else state.sessionName ?: historyTitle?.takeIf { it.id == state.sessionId }?.title ?: if (state.stream != null) "新会话" else name,
            status = headerStatus(state, phase),
            statusTone = when {
                phase == LinkPhase.Ended -> p.danger
                phase != LinkPhase.Live -> p.warning
                state.running -> p.accent
                else -> p.success
            },
            pulsing = state.running || phase == LinkPhase.Connecting || phase == LinkPhase.Reconnecting,
            p = p,
            backdrop = backdrop,
            onBack = onBack,
            onMore = { overlay = if (historyRestoring) Overlay.History else if (overlay == Overlay.Menu) Overlay.None else Overlay.Menu },
            onModel = { if (historyRestoring) overlay = Overlay.History else openPicker() },
            pickerOpen = overlay == Overlay.Picker,
            modifier = Modifier.align(Alignment.TopCenter).onSizeChanged { headerPx.intValue = it.height }.zIndex(2f),
        )

        // Inline, non-blocking connection capsule.
        AnimatedVisibility(
            visible = phase == LinkPhase.Reconnecting || phase == LinkPhase.Ended,
            enter = fadeIn(tween(200)) + slideInVertically(spring(dampingRatio = 0.8f, stiffness = 400f)) { -it / 2 },
            exit = fadeOut(tween(160)) + slideOutVertically(tween(160)) { -it / 2 },
            modifier = Modifier.align(Alignment.TopCenter).offset { IntOffset(0, headerPx.intValue) }.padding(top = 6.dp).zIndex(3f),
        ) {
            ConnectionCapsule(
                phase = phase,
                p = p,
                backdrop = backdrop,
                onRetry = {
                    ServiceWire.reconnectNow()
                    hub.retry(ref)
                },
                onOpenTerminal = onOpenTerminal,
            )
        }

        ModelPicker(
            open = overlay == Overlay.Picker,
            state = state,
            pendingModel = pendingModel,
            pendingLevel = pendingLevel,
            p = p,
            backdrop = backdrop,
            topPx = { headerPx.intValue },
            onDismiss = { if (overlay == Overlay.Picker) overlay = Overlay.None },
            onModel = ::chooseModel,
            onLevel = ::chooseLevel,
        )

        NativePointsSheet(overlay == Overlay.Fork || overlay == Overlay.Rewind, overlay == Overlay.Rewind, pointsLoading || historyRestoring, points, pointsError, p, backdrop,
            onDismiss = { overlay = Overlay.None }, onReload = { loadPoints(overlay == Overlay.Rewind) },
            onChoose = { mutationConsent = (if (overlay == Overlay.Rewind) "rewind_session" else "fork_session") to it; mutationForce = false })
        NativeTasksSheet(overlay == Overlay.Tasks, commands, p, backdrop, { overlay = Overlay.None }, onMapped = { native ->
            when (native) {
                "model", "models", "think" -> openPicker()
                "usage", "context", "session-info" -> openUsage()
                "resume", "history" -> loadHistory()
                "compact" -> openCompact()
                "export" -> exportSession()
                "new", "clear" -> { mutationConsent = "new_session" to null; mutationForce = false }
                "fork" -> { mutationConsent = "clone_session" to null; mutationForce = false }
                "rewind", "undo" -> { overlay = Overlay.None; toast = "Grok 原生回滚尚未取得成功闭包，未提交或伪造截断" }
                "rename", "title" -> { overlay = Overlay.None; renameOpen = true }
                else -> return@NativeTasksSheet false
            }
            true
        }) { text, callback -> hub.send(ref, "prompt", text, onResult = callback) }
        mutationConsent?.let { (kind, point) -> NativeDecisionDialog(
            if (mutationForce) "确认停止当前任务" else if (kind == "rewind_session") "确认回滚轮次" else "确认创建分支会话",
            (if (mutationForce) "将停止当前任务与排队输入。\n" else "") + if (kind == "rewind_session") "只回滚对话，不还原文件；原生失败将明确显示。" else "原会话保持不变。现有草稿与附件保留；原生返回文本追加到草稿，不自动发送。",
            p, { mutationConsent = null }) { mutate(kind, point, mutationForce) } }
        if (renameOpen) RenameSessionDialog(state.sessionName.orEmpty(), p, { renameOpen = false }) { title, callback ->
            hub.control(ref, buildJsonObject { put("type", "rename_session"); put("name", title) }, callback)
        }
        SessionHistoryPicker(
            open = overlay == Overlay.History,
            choices = historyChoices,
            loading = historyLoading,
            restoring = historyRestoring,
            restoringTitle = historyTarget?.title,
            error = historyError,
            p = p,
            backdrop = backdrop,
            onDismiss = { if (overlay == Overlay.History) overlay = Overlay.None; if (!historyRestoring) historyGeneration++ },
            onRetry = ::loadHistory,
            onChoose = { resumeHistory(it, force = false) },
        )
        ConfirmDialog(
            open = historyConfirm != null,
            title = "恢复历史会话",
            body = "当前任务正在运行。确认停止并丢弃排队输入，再恢复所选历史会话？",
            confirm = "停止并恢复",
            p = p,
            backdrop = backdrop,
            onDismiss = { historyConfirm = null },
            onConfirm = { historyConfirm?.let { resumeHistory(it, force = true) } },
        )

        // Header overflow menu: the action registry, resolved against the live state.
        HeaderMenu(
            open = overlay == Overlay.Menu,
            actions = resolveActions(state.agentProvider, connected, historyRestoring, state.compacting),
            p = p,
            backdrop = backdrop,
            topPx = { headerPx.intValue },
            onDismiss = { if (overlay == Overlay.Menu) overlay = Overlay.None },
            onAction = ::runAction,
        )

        CompactSheet(
            open = overlay == Overlay.Compact,
            phase = compactPhase(compactRun, state.compacting, state.compaction),
            instructions = compactInstructions,
            onInstructions = { compactInstructions = it },
            acceptsInstructions = abilities.compactInstructions,
            busy = inFlight && !state.compacting,
            interruptsBusyWork = abilities.compactInterruptsWork,
            connected = connected,
            p = p,
            backdrop = backdrop,
            onDismiss = { if (overlay == Overlay.Compact) overlay = Overlay.None },
            onSubmit = ::submitCompact,
            onReset = { compactRun = null },
        )

        ConversationSheet(
            open = overlay == Overlay.Export,
            title = "导出会话",
            eyebrow = "EXPORT",
            subtitle = "Pi HTML / Grok Markdown · 下载完成后系统分享",
            p = p,
            backdrop = backdrop,
            tag = "conversation-export-sheet",
            closeTag = "conversation-export-close",
            onDismiss = { if (overlay == Overlay.Export) overlay = Overlay.None },
            footer = { SheetButton("收起", SheetButtonKind.Secondary, p, "conversation-export-hide", Modifier.fillMaxWidth()) { overlay = Overlay.None } },
        ) {
            SheetProgress("正在生成并下载原生会话文件…", "文件可能包含会话及工具输出，请仅分享给可信目标。", p, "conversation-export-progress")
        }

        UsageSheet(
            open = overlay == Overlay.Usage,
            load = usage,
            sessionId = state.sessionId,
            agent = agentName(state.agentProvider),
            model = state.model,
            running = state.running,
            connected = connected,
            p = p,
            backdrop = backdrop,
            onDismiss = { if (overlay == Overlay.Usage) overlay = Overlay.None },
            onRefresh = ::readUsage,
            onCompact = ::openCompact,
        )

        ConfirmDialog(
            open = confirmSwitch,
            title = "切换到终端模式",
            body = "切换模式或升级会话进程将中断并丢弃当前未完成任务，是否确认切换？" +
                (switchReason?.takeIf { it.isNotBlank() }?.let { "\n\n$it" } ?: ""),
            confirm = "确认切换",
            p = p,
            backdrop = backdrop,
            onDismiss = { confirmSwitch = false },
            onConfirm = { switchToTerminal(force = true) },
        )

        // Overlays ride on top of the dock but are not part of its measure: the jump control or a
        // toast appearing never changes the transcript's bottom edge (that feedback loop was the
        // landing jolt), and a panel floats over the transcript instead of shoving it up.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .offset { IntOffset(0, -dockPx.intValue) }
                .padding(start = 12.dp, end = 12.dp)
                .zIndex(4f),
        ) {
            Box(Modifier.fillMaxWidth()) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = toast != null,
                    enter = fadeIn(tween(160)) + scaleIn(tween(200), 0.94f),
                    exit = fadeOut(tween(200)),
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    Text(
                        toast.orEmpty(),
                        style = TextStyle(fontFamily = ConversationSans, fontSize = 13.sp, color = p.ink),
                        modifier = Modifier
                            .padding(bottom = 10.dp)
                            .widthIn(max = 320.dp)
                            .panelSurface(look, backdrop, null, p)
                            .padding(horizontal = 16.dp, vertical = 9.dp)
                            .testTag("conversation-toast"),
                    )
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = !following && state.items.isNotEmpty() && activeSheet == ComposerSheet.None,
                    enter = fadeIn(tween(160)) + scaleIn(spring(dampingRatio = 0.7f, stiffness = 500f), 0.6f),
                    exit = fadeOut(tween(120)) + scaleOut(tween(140), 0.6f),
                    modifier = Modifier.align(Alignment.CenterEnd).padding(bottom = 10.dp),
                ) {
                    GlassCircleButton(Glyph.Down, p, backdrop, "conversation-jump") { scrollToLatest() }
                }
            }
            ComposerSheetOverlay(activeSheet, sheetEntries, ::onSheetEntry, backdrop, p)
        }

        // The dock alone owns the transcript's bottom clearance: composer + nav/IME insets.
        ConversationDock(
            value = draft,
            onValueChange = {
                draft = it
                if (slashDismissedFor != null && slashDismissedFor != it.text) slashDismissedFor = null
            },
            images = images,
            onRemoveImage = { id -> images.removeAll { it.id == id } },
            sheet = activeSheet,
            onSheet = { sheet = it },
            expanded = editorFocused,
            onFocusChanged = { focused ->
                if (focused && !editorFocused) {
                    expansion.intValue++
                    collapseRequested = false
                }
                if (focused != editorFocused) ConvTrace.log("dock focus=$focused generation=${expansion.intValue}")
                editorFocused = focused
                if (!focused && sheet == ComposerSheet.Shortcuts) sheet = ComposerSheet.None
            },
            running = state.running,
            connected = connected,
            placeholder = when {
                historyRestoring -> "恢复历史完成后即可发送"
                !connected -> "连接后即可发送"
                state.running -> "补充指令，由主机确认是否接收"
                else -> "发送消息，输入 / 查看命令"
            },
            onSend = ::send,
            onStop = { hub.send(ref, "abort") { ok, r -> if (!ok) toast = "停止未生效：${r ?: "主机没有确认"}" } },
            backdrop = backdrop,
            p = p,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // Measured outside the insets: the transcript must clear the keyboard too.
                .onSizeChanged { dockPx.intValue = it.height }
                .then(
                    if (look.glass) {
                        Modifier
                    } else {
                        // Modernism: a clean flat sill on a 0.5dp hairline, down to the edge.
                        Modifier
                            .flatPanel(p)
                            .edgeRule(RuleEdge.Top, p.ink, look.rule)
                    },
                )
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .padding(
                    start = if (look.glass) 12.dp else 6.dp,
                    end = if (look.glass) 12.dp else 6.dp,
                    top = if (look.glass) 0.dp else look.rule,
                    bottom = if (look.glass) 10.dp else 4.dp,
                )
                .zIndex(4f),
        )
    }
}

/** The one floating layer above the transcript; [None] when the conversation has the stage. */
private enum class Overlay { None, Menu, Picker, History, Compact, Usage, Export, Fork, Rewind, Tasks }

/** Following tolerance: a hair above the newest row still counts as reading the latest. */
private val FOLLOW_SLACK = 24.dp

/** List padding resolved at measure time from the measured header and dock. */
@androidx.compose.runtime.Stable
private class LiveListPadding(
    private val density: androidx.compose.ui.unit.Density,
    private val headerPx: androidx.compose.runtime.IntState,
    private val dockPx: androidx.compose.runtime.IntState,
) : PaddingValues {
    override fun calculateLeftPadding(layoutDirection: androidx.compose.ui.unit.LayoutDirection) = 18.dp
    override fun calculateRightPadding(layoutDirection: androidx.compose.ui.unit.LayoutDirection) = 18.dp
    override fun calculateTopPadding() = with(density) { headerPx.intValue.toDp() } + 10.dp
    override fun calculateBottomPadding() = with(density) { dockPx.intValue.toDp() } + 14.dp
}

/**
 * Off-main warm-up for users in GUI mode: palettes for both slots, the Inter weight instances
 * and the markdown patterns, so the first conversation frame does no first-time work.
 */
@Composable
fun ConversationWarmup() {
    val context = LocalContext.current
    val fonts = LocalFontFamilyResolver.current
    LaunchedEffect(Unit) {
        if (SharedPreferencesDisplayModeStore(context).load() != DisplayMode.GUI) return@LaunchedEffect
        withContext(Dispatchers.Default) {
            TermPalette.bind(SharedPreferencesTermThemeStore(context))
            ConversationPalette.of(TermPalette.of(true))
            ConversationPalette.of(TermPalette.of(false))
            parseMarkdown("# warm\n- **a** `b` [c](https://d)\n```\ne\n```")
        }
        runCatching { fonts.preload(ConversationSans) }
    }
}

/**
 * Subscribes while the first frame is still composing (not after it commits), so the replay
 * races the cold layout instead of queueing behind it. Abandoned compositions detach too.
 */
internal class ConversationAttachment(private val hub: ConversationHub, private val ref: String) : RememberObserver {
    init {
        hub.attach(ref)
    }

    override fun onRemembered() = Unit
    override fun onForgotten() = hub.detach(ref)
    override fun onAbandoned() = hub.detach(ref)
}

/** Status/navigation bar glyphs follow the conversation canvas, restored on exit. */
@Composable
private fun SystemBarInk(darkCanvas: Boolean) {
    val view = LocalView.current
    DisposableEffect(darkCanvas) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previousStatus = controller?.isAppearanceLightStatusBars
        val previousNav = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = !darkCanvas
        controller?.isAppearanceLightNavigationBars = !darkCanvas
        onDispose {
            previousStatus?.let { controller.isAppearanceLightStatusBars = it }
            previousNav?.let { controller.isAppearanceLightNavigationBars = it }
        }
    }
}

@Composable
private fun ConversationHeader(
    name: String,
    status: String,
    statusTone: Color,
    pulsing: Boolean,
    p: ConversationPalette,
    backdrop: Backdrop,
    onBack: () -> Unit,
    onMore: () -> Unit,
    onModel: () -> Unit,
    pickerOpen: Boolean,
    modifier: Modifier = Modifier,
) {
    val look = LocalConversationLook.current
    Box(
        modifier
            .fillMaxWidth()
            .then(
                if (look.glass) {
                    Modifier.background(Brush.verticalGradient(listOf(p.canvas.copy(alpha = 0.92f), p.canvas.copy(alpha = 0.72f), Color.Transparent)))
                } else {
                    // Modernism: an opaque flat lintel on a 0.5dp hairline.
                    Modifier
                        .flatPanel(p)
                        .edgeRule(RuleEdge.Bottom, p.ink, look.rule)
                },
            )
            .statusBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = if (look.glass) 10.dp else 8.dp + look.rule),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassCircleButton(Glyph.Back, p, backdrop, "conversation-back", onClick = onBack)
            val chevron by androidx.compose.animation.core.animateFloatAsState(if (pickerOpen) 180f else 0f, disclosureSpring(), label = "header-chevron")
            Row(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp)
                    .then(if (look.glass) Modifier.panelSurface(look, backdrop, null, p) else Modifier)
                    .clip(look.pill())
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onModel)
                    .padding(start = if (look.glass) 16.dp else 4.dp, end = 12.dp, top = 7.dp, bottom = 7.dp)
                    .testTag("conversation-model"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        name,
                        style = TextStyle(fontFamily = ConversationSans, fontSize = 15.sp, lineHeight = 20.sp, lineHeightStyle = StableLines, fontWeight = look.titleWeight, color = p.ink, letterSpacing = (-0.1).sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.lineBox(20.sp),
                    )
                    Row(Modifier.lineBox(CaptionStyle.lineHeight), verticalAlignment = Alignment.CenterVertically) {
                        Breathing(pulsing) { a -> Box(Modifier.size(6.dp).clip(look.pill()).background(statusTone.copy(alpha = a))) }
                        Text(
                            if (look.glass) status else status.uppercase(),
                            style = if (look.glass) CaptionStyle.copy(color = p.inkSoft) else MonoSmall.copy(color = p.inkSoft, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.4.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 6.dp).testTag("conversation-status"),
                        )
                    }
                }
                GlyphIcon(Glyph.Chevron, p.inkSoft, 16.dp, Modifier.padding(start = 6.dp).graphicsLayer { rotationZ = chevron })
            }
            GlassCircleButton(Glyph.More, p, backdrop, "conversation-more", onClick = onMore)
        }
    }
}

@Composable
private fun ConnectionCapsule(phase: LinkPhase, p: ConversationPalette, backdrop: Backdrop, onRetry: () -> Unit, onOpenTerminal: () -> Unit) {
    val look = LocalConversationLook.current
    Row(
        Modifier
            .panelSurface(look, backdrop, null, p)
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
            .testTag("conversation-connection"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ended = phase == LinkPhase.Ended
        Breathing(!ended) { a -> Box(Modifier.size(7.dp).clip(look.pill()).background((if (ended) p.danger else p.warning).copy(alpha = a))) }
        Text(
            if (ended) "Agent 已退出" else "连接中断，正在恢复…",
            style = CaptionStyle.copy(color = p.ink, fontSize = 12.5.sp, fontWeight = FontWeight.Medium),
            modifier = Modifier.padding(start = 8.dp, end = 10.dp),
        )
        Text(
            if (ended) "打开终端" else "立即重试",
            style = CaptionStyle.copy(color = p.onAccent, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
            modifier = Modifier
                .clip(look.pill())
                .background(p.accent)
                .clickable(onClick = if (ended) onOpenTerminal else onRetry)
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .testTag(if (ended) "conversation-open-terminal" else "conversation-reconnect"),
        )
    }
}

/** A themed, centred confirmation over a scrim; cancel is the default (Back and scrim tap). */
@Composable
private fun ConfirmDialog(
    open: Boolean,
    title: String,
    body: String,
    confirm: String,
    p: ConversationPalette,
    backdrop: Backdrop,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val look = LocalConversationLook.current
    AnimatedVisibility(visible = open, enter = fadeIn(tween(140)), exit = fadeOut(tween(120)), modifier = Modifier.zIndex(8f)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = if (p.dark) 0.64f else 0.48f))
                .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .padding(horizontal = 28.dp)
                    .widthIn(max = 360.dp)
                    .fillMaxWidth()
                    .animateEnterExit(enter = scaleIn(spring(dampingRatio = 0.82f, stiffness = 560f), 0.94f), exit = scaleOut(tween(120), 0.97f))
                    .panelSurface(look, backdrop, 26.dp, p, reading = true)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp)
                    .testTag("conversation-confirm"),
            ) {
                Text(title, style = LabelStyle.copy(color = p.ink, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = look.titleWeight))
                Text(body, style = BodyStyle.copy(color = p.inkSoft, fontSize = 14.sp, lineHeight = 21.sp), modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                    Text(
                        "取消",
                        style = LabelStyle.copy(color = p.ink, fontSize = 14.sp),
                        modifier = Modifier
                            .clip(look.pill())
                            .background(p.ink.copy(alpha = 0.07f))
                            .clickable(onClick = onDismiss)
                            .padding(horizontal = 18.dp, vertical = 10.dp)
                            .testTag("conversation-confirm-cancel"),
                    )
                    Text(
                        confirm,
                        // Danger is a contrast-repaired foreground, never a warm surface wash.
                        style = LabelStyle.copy(color = p.danger, fontSize = 14.sp),
                        modifier = Modifier
                            .clip(look.pill())
                            .background(p.surface)
                            .clickable(onClick = onConfirm)
                            .padding(horizontal = 18.dp, vertical = 10.dp)
                            .testTag("conversation-confirm-ok"),
                    )
                }
            }
        }
    }
}

/**
 * Reports exactly one line box and centres the text in it. Android widens a line for a CJK
 * fallback font beyond lineHeight, so "Pi · model" ↔ "工作中 · model" resized the header by 4px
 * every turn; the glyphs may now overhang by a hair instead.
 */
internal fun Modifier.lineBox(lineHeight: androidx.compose.ui.unit.TextUnit): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity))
    val height = lineHeight.roundToPx()
    layout(placeable.width, height) { placeable.place(0, (height - placeable.height) / 2) }
}

private fun workingLabel(state: ConversationState): String? = when {
    !state.running && !state.compacting -> null
    state.compacting -> "正在压缩上下文…"
    state.retry != null -> "第 ${state.retry.attempt}/${state.retry.maxAttempts} 次重试…"
    !state.quietlyWorking -> null
    state.queued > 0 -> "处理中 · 还有 ${state.queued} 条在排队"
    else -> "正在思考…"
}

private fun headerStatus(state: ConversationState, phase: LinkPhase): String = when (phase) {
    LinkPhase.Connecting -> "正在连接…"
    LinkPhase.Reconnecting -> "重新连接中…"
    LinkPhase.Ended -> "Agent 已退出"
    LinkPhase.Unavailable -> "原生对话不可用"
    LinkPhase.Live -> when {
        state.compacting -> "正在压缩上下文"
        state.retry != null -> "正在重试"
        state.running -> "工作中" + (state.model?.let { " · $it" } ?: "")
        else -> listOfNotNull("原生对话", state.model, state.thinkingLevel?.takeIf { it != "off" }?.let { "思考 ${thinkingLabel(it)}" }).joinToString(" · ")
    }
}

/**
 * The prompt exactly as typed, sent as one command: inner newlines, blank lines and the first
 * line's indentation survive; only blank leading lines and trailing whitespace are dropped.
 */
internal fun promptText(draft: String): String = draft.trimEnd().replaceFirst(LeadingBlankLines, "")

private val LeadingBlankLines = Regex("""^(?:[ \t]*\r?\n)+""")

private fun uploadToHost(attachment: Attachment): UploadOutcome {
    val base = ServiceWire.uploadBaseUrl ?: return UploadOutcome.Failure("未配置上传地址")
    return HttpUrlConnectionUploader().upload(base, ServiceWire.currentConfig()?.token, attachment)
}

/** Reads a picked image and a small preview; large images are downsampled for the thumbnail only. */
private fun readImage(context: Context, uri: Uri): Pair<Attachment, Bitmap?>? = runCatching {
    val resolver = context.contentResolver
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    val mime = resolver.getType(uri) ?: "image/jpeg"
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
    val preview = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    val ext = when (mime) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> "jpg"
    }
    Attachment("image-${System.currentTimeMillis()}.$ext", mime, bytes) to preview
}.getOrNull()

