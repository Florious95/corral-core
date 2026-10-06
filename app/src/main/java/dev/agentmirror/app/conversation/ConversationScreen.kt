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
    var menuOpen by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }
    var historyOpen by remember(ref) { mutableStateOf(false) }
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
        menuOpen = false
        pickerOpen = !pickerOpen
        if (pickerOpen) {
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
        menuOpen = false
        pickerOpen = false
        historyOpen = true
        historyLoading = true
        historyError = null
        val generation = ++historyGeneration
        hub.controlWithData(ref, command("list_sessions")) { ok, reason, data ->
            if (generation == historyGeneration && historyOpen) {
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
                historyRestoring = false
                historyOpen = false
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

    // Back peels one layer at a time: picker/menu → panel → composer → leave.
    BackHandler(enabled = activeSheet != ComposerSheet.None || menuOpen || pickerOpen || historyOpen || editorFocused || confirmSwitch || historyConfirm != null) {
        when {
            historyConfirm != null -> historyConfirm = null
            historyOpen && historyRestoring -> onBack()
            historyOpen -> { historyOpen = false; historyGeneration++ }
            confirmSwitch -> confirmSwitch = false
            pickerOpen -> pickerOpen = false
            menuOpen -> menuOpen = false
            sheet != ComposerSheet.None -> sheet = ComposerSheet.None
            activeSheet == ComposerSheet.Slash -> slashDismissedFor = draft.text
            else -> collapseComposer("back")
        }
    }
    BackHandler(enabled = activeSheet == ComposerSheet.None && !menuOpen && !pickerOpen && !historyOpen && !editorFocused && !confirmSwitch && historyConfirm == null, onBack = onBack)

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
                            ConversationEmpty(state.model, p, onSuggestion = { s -> draft = TextFieldValue(s, TextRange(s.length)) })
                        } else if (!connected) {
                            ConversationSkeleton(p)
                        }
                    }
                }
            }
        }

        // Frosted header.
        ConversationHeader(
            name = state.sessionName ?: historyTitle?.takeIf { it.id == state.sessionId }?.title ?: name,
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
            onMore = { if (historyRestoring) historyOpen = true else { historyOpen = false; pickerOpen = false; menuOpen = !menuOpen } },
            onModel = { if (historyRestoring) historyOpen = true else { historyOpen = false; openPicker() } },
            onHistory = if (state.agentProvider == "pi") ::loadHistory else null,
            historyEnabled = connected,
            pickerOpen = pickerOpen,
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
            open = pickerOpen,
            state = state,
            pendingModel = pendingModel,
            pendingLevel = pendingLevel,
            p = p,
            backdrop = backdrop,
            topPx = { headerPx.intValue },
            onDismiss = { pickerOpen = false },
            onModel = ::chooseModel,
            onLevel = ::chooseLevel,
        )

        SessionHistoryPicker(
            open = historyOpen,
            choices = historyChoices,
            loading = historyLoading,
            restoring = historyRestoring,
            error = historyError,
            p = p,
            backdrop = backdrop,
            topPx = { headerPx.intValue },
            onDismiss = { historyOpen = false; if (!historyRestoring) historyGeneration++ },
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

        // Header overflow menu.
        HeaderMenu(
            open = menuOpen,
            p = p,
            backdrop = backdrop,
            topPx = { headerPx.intValue },
            onDismiss = { menuOpen = false },
            onTerminal = {
                menuOpen = false
                if (inFlight) {
                    switchReason = null
                    collapseComposer("confirm")
                    confirmSwitch = true
                } else {
                    switchToTerminal(force = false)
                }
            },
            onCompact = { menuOpen = false; hub.send(ref, "compact") { ok, r -> if (!ok) toast = "压缩未执行：${r ?: "主机没有确认"}" } },
            onNewSession = { menuOpen = false; hub.send(ref, "new_session") { ok, r -> if (!ok) toast = "新会话未创建：${r ?: "主机没有确认"}" } },
            enabled = connected,
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
                        // Modernism: a full-bleed brushed sill under a heavy ink rule, down to the edge.
                        Modifier
                            .brushedMetal(p)
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
    onHistory: (() -> Unit)?,
    historyEnabled: Boolean,
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
                    // Modernism: an opaque brushed lintel standing on a heavy ink rule.
                    Modifier
                        .brushedMetal(p)
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
            if (onHistory != null) {
                Text("历史", style = LabelStyle.copy(color = if (historyEnabled) p.accentInk else p.inkSoft),
                    modifier = Modifier.clip(look.pill()).clickable(enabled = historyEnabled, onClick = onHistory)
                        .padding(horizontal = 10.dp, vertical = 12.dp).testTag("conversation-history"))
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

@Composable
private fun HeaderMenu(
    open: Boolean,
    p: ConversationPalette,
    backdrop: Backdrop,
    topPx: () -> Int,
    onDismiss: () -> Unit,
    onTerminal: () -> Unit,
    onCompact: () -> Unit,
    onNewSession: () -> Unit,
    enabled: Boolean,
) {
    if (open) {
        Box(Modifier.fillMaxSize().zIndex(5f).pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) })
    }
    Box(Modifier.fillMaxSize().zIndex(6f), contentAlignment = Alignment.TopEnd) {
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(tween(120)) + scaleIn(spring(dampingRatio = 0.8f, stiffness = 600f), 0.9f, androidx.compose.ui.graphics.TransformOrigin(1f, 0f)),
            exit = fadeOut(tween(100)) + scaleOut(tween(120), 0.95f, androidx.compose.ui.graphics.TransformOrigin(1f, 0f)),
            modifier = Modifier.offset { IntOffset(0, topPx()) }.padding(end = 12.dp),
        ) {
            Column(
                Modifier
                    .widthIn(min = 210.dp)
                    .panelSurface(LocalConversationLook.current, backdrop, 20.dp, p)
                    .padding(6.dp)
                    .testTag("conversation-menu"),
            ) {
                MenuRow(Glyph.Terminal, "切换到终端", "同一面板运行对应 CLI 的原生终端", p, enabled, "conversation-menu-terminal", onTerminal)
                MenuRow(Glyph.Refresh, "压缩上下文", "/compact", p, enabled, "conversation-menu-compact", onCompact)
                MenuRow(Glyph.Spark, "开始新会话", "/new", p, enabled, "conversation-menu-new", onNewSession)
            }
        }
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
                .background(Color.Black.copy(alpha = if (p.dark) 0.5f else 0.32f))
                .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .padding(horizontal = 28.dp)
                    .widthIn(max = 360.dp)
                    .fillMaxWidth()
                    .animateEnterExit(enter = scaleIn(spring(dampingRatio = 0.82f, stiffness = 560f), 0.94f), exit = scaleOut(tween(120), 0.97f))
                    .panelSurface(look, backdrop, 26.dp, p)
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
                        // Danger ink on a danger wash: the ink is contrast-repaired for every theme.
                        style = LabelStyle.copy(color = p.danger, fontSize = 14.sp),
                        modifier = Modifier
                            .clip(look.pill())
                            .background(p.danger.copy(alpha = if (p.dark) 0.18f else 0.12f))
                            .clickable(onClick = onConfirm)
                            .padding(horizontal = 18.dp, vertical = 10.dp)
                            .testTag("conversation-confirm-ok"),
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuRow(glyph: Glyph, title: String, detail: String, p: ConversationPalette, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(LocalConversationLook.current.shape(14.dp))
            .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(glyph, if (enabled) p.accentInk else p.inkSoft, 18.dp)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = LabelStyle.copy(color = if (enabled) p.ink else p.inkSoft, fontSize = 14.sp))
            Text(detail, style = CaptionStyle.copy(color = p.inkSoft))
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

