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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.kyant.shapes.RoundedRectangularShape
import dev.agentmirror.app.diag.DiagLog
import dev.agentmirror.app.perf.PerfTrace
import dev.agentmirror.app.service.ServiceWire
import dev.agentmirror.app.session.Attachment
import dev.agentmirror.app.session.HttpUrlConnectionUploader
import dev.agentmirror.app.session.SharedPreferencesShortcutCommandStore
import dev.agentmirror.app.session.ShortcutResolution
import dev.agentmirror.app.session.UploadOutcome
import dev.agentmirror.app.session.resolveShortcutCommand
import dev.agentmirror.app.session.textFileAttachment
import dev.agentmirror.app.session.textFileReference
import dev.agentmirror.app.ui.components.glassControl
import dev.agentmirror.app.ui.components.glassReadable
import dev.agentmirror.app.ui.theme.AppTheme
import dev.agentmirror.app.ui.theme.LocalAppPalette
import dev.agentmirror.app.ui.theme.SharedPreferencesTermThemeStore
import dev.agentmirror.app.ui.theme.TermPalette
import dev.agentmirror.app.ui.theme.isDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

/** Liquid-glass surface from the app's own material system, tinted by the conversation palette. */
internal fun Modifier.frostedGlass(backdrop: Backdrop, shape: RoundedRectangularShape, p: ConversationPalette): Modifier =
    glassControl(
        backdrop = backdrop,
        shape = shape,
        surface = p.glass.glassReadable(),
        blurRadius = 20.dp,
        lensHeight = 10.dp,
        lensAmount = 16.dp,
        crystalHighlight = true,
        shadow = Shadow(radius = 18.dp, color = Color.Black.copy(alpha = if (p.dark) 0.32f else 0.10f)),
    )

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
    android.util.Log.d("ConvTrace", "route_compose phase=$phase items=${state.items.size} t=${android.os.SystemClock.elapsedRealtime()}")
    LaunchedEffect(phase) {
        if (phase == LinkPhase.Unavailable && !session.everReady) {
            DiagLog.record("conversation", "fallback_tui ref_hash=${ref.hashCode()} reason=unavailable_before_ready")
            onOpenTerminal(true)
        }
    }
    AppTheme {
        val dark = LocalAppPalette.current.isDark
        val palette = remember(dark) { ConversationPalette.of(TermPalette.of(dark)) }
        SystemBarInk(palette.dark)
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
    var toast by remember { mutableStateOf<String?>(null) }
    var headerPx by remember { mutableIntStateOf(0) }
    var dockPx by remember { mutableIntStateOf(0) }
    val imageIds = remember { AtomicLong() }

    val connected = phase == LinkPhase.Live
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
            val resolved = resolveShortcutCommand(command, "pi") as? ShortcutResolution.Found ?: return@mapNotNull null
            SheetEntry("shortcut:${command.id}", command.name, resolved.text, Glyph.Bolt)
        }.ifEmpty { listOf(SheetEntry("shortcut:none", "还没有快捷命令", "在 设置 › 快捷命令 中为 Pi 添加常用指令", Glyph.Bolt)) }
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
    fun scrollToLatest() = scope.launch { if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0) listState.animateScrollToItem(0) }

    fun send() {
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

    BackHandler(enabled = activeSheet != ComposerSheet.None || menuOpen) {
        when {
            menuOpen -> menuOpen = false
            sheet != ComposerSheet.None -> sheet = ComposerSheet.None
            else -> slashDismissedFor = draft.text
        }
    }
    BackHandler(enabled = activeSheet == ComposerSheet.None && !menuOpen, onBack = onBack)

    // ---- layout -----------------------------------------------------------------------------
    val backdrop = rememberLayerBackdrop {
        drawRect(p.canvas)
        drawRect(
            Brush.radialGradient(
                listOf(p.glow, Color.Transparent),
                center = Offset(size.width * 0.18f, -size.height * 0.04f),
                radius = size.maxDimension * 0.75f,
            ),
        )
        drawContent()
    }
    val topPad = with(density) { headerPx.toDp() } + 10.dp
    val bottomPad = with(density) { dockPx.toDp() } + 14.dp
    val following by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 24 } }
    // PerfTrace first_draw (setprop-gated, free when off): the first frame that paints transcript
    // rows, comparable with the terminal path's route_enter → first_draw.
    val perfOpenId = remember(ref) { PerfTrace.idFor(ref) }
    val firstDrawn = remember(ref) { booleanArrayOf(perfOpenId == null) }

    androidx.compose.runtime.SideEffect { android.util.Log.d("ConvTrace", "applied items=${state.items.size} t=${android.os.SystemClock.elapsedRealtime()}") }
    Box(Modifier.fillMaxSize().background(p.canvas).testTag("conversation-screen").drawWithContent { android.util.Log.d("ConvTrace", "root_draw items=${state.items.size} t=${android.os.SystemClock.elapsedRealtime()}"); drawContent() }) {
        val rows = remember(state.items) { state.items.asReversed() }
        LazyColumn(
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = topPad, bottom = bottomPad),
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .drawWithContent {
                    drawContent()
                    if (!firstDrawn[0] && rows.isNotEmpty()) {
                        firstDrawn[0] = true
                        PerfTrace.firstDraw(perfOpenId!!, rows.size)
                    }
                }
                .pointerInput(Unit) { detectTapGestures(onTap = { focus.clearFocus(); sheet = ComposerSheet.None }) }
                .testTag("conversation-list"),
        ) {
            val working = workingLabel(state)
            if (working != null) {
                item(key = "working") { WorkingIndicator(working, p, Modifier.animateItem()) }
            }
            items(rows, key = { it.key }, contentType = { it::class }) { item ->
                val mod = Modifier.animateItem(fadeInSpec = tween(220), placementSpec = spring(dampingRatio = 0.9f, stiffness = 380f), fadeOutSpec = tween(140))
                when (item) {
                    is UserTurn -> UserBubble(item, p, expanded[item.key] == true, { expanded[item.key] = expanded[item.key] != true }, mod)
                    is AssistantText -> AssistantProse(item, p, mod)
                    is Reasoning -> ReasoningRow(item, p, expanded[item.key] == true, { expanded[item.key] = expanded[item.key] != true }, mod)
                    is ToolCall -> ToolCallCard(item, p, expanded[item.key] == true, state.serverSkewMs, { expanded[item.key] = expanded[item.key] != true }, mod)
                    is Notice -> NoticeRow(item, p, mod)
                }
            }
            if (state.historyTruncated) {
                item(key = "truncated") {
                    NoticeRow(Notice("truncated", NoticeTone.Divider, "更早的消息未保留"), p, Modifier.animateItem())
                }
            }
            if (state.items.isEmpty()) {
                item(key = "empty") {
                    Box(Modifier.fillMaxWidth().fillParentMaxHeight(0.82f).animateItem(), contentAlignment = Alignment.Center) {
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
            name = state.sessionName ?: name,
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
            onMore = { menuOpen = !menuOpen },
            modifier = Modifier.align(Alignment.TopCenter).onSizeChanged { headerPx = it.height }.zIndex(2f),
        )

        // Inline, non-blocking connection capsule.
        AnimatedVisibility(
            visible = phase == LinkPhase.Reconnecting || phase == LinkPhase.Ended,
            enter = fadeIn(tween(200)) + slideInVertically(spring(dampingRatio = 0.8f, stiffness = 400f)) { -it / 2 },
            exit = fadeOut(tween(160)) + slideOutVertically(tween(160)) { -it / 2 },
            modifier = Modifier.align(Alignment.TopCenter).padding(top = with(density) { headerPx.toDp() } + 6.dp).zIndex(3f),
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

        // Header overflow menu.
        HeaderMenu(
            open = menuOpen,
            p = p,
            backdrop = backdrop,
            topPadding = with(density) { headerPx.toDp() },
            onDismiss = { menuOpen = false },
            onTerminal = { menuOpen = false; onOpenTerminal() },
            onCompact = { menuOpen = false; hub.send(ref, "compact") { ok, r -> if (!ok) toast = "压缩未执行：${r ?: "主机没有确认"}" } },
            onNewSession = { menuOpen = false; hub.send(ref, "new_session") { ok, r -> if (!ok) toast = "新会话未创建：${r ?: "主机没有确认"}" } },
            enabled = connected,
        )

        // Bottom stack: toast · jump-to-latest · dock.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // Measured outside the insets: the transcript must clear the whole stack.
                .onSizeChanged { dockPx = it.height }
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
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
                            .frostedGlass(backdrop, Capsule(), p)
                            .padding(horizontal = 16.dp, vertical = 9.dp)
                            .testTag("conversation-toast"),
                    )
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = !following && state.items.isNotEmpty(),
                    enter = fadeIn(tween(160)) + scaleIn(spring(dampingRatio = 0.7f, stiffness = 500f), 0.6f),
                    exit = fadeOut(tween(120)) + scaleOut(tween(140), 0.6f),
                    modifier = Modifier.align(Alignment.CenterEnd).padding(bottom = 10.dp),
                ) {
                    GlassCircleButton(Glyph.Down, p, backdrop, "conversation-jump") { scrollToLatest() }
                }
            }
            ConversationDock(
                value = draft,
                onValueChange = {
                    draft = it
                    if (slashDismissedFor != null && slashDismissedFor != it.text) slashDismissedFor = null
                },
                images = images,
                onRemoveImage = { id -> images.removeAll { it.id == id } },
                sheet = activeSheet,
                sheetEntries = sheetEntries,
                onSheet = { sheet = it },
                onSheetEntry = ::onSheetEntry,
                running = state.running,
                connected = connected,
                placeholder = when {
                    !connected -> "连接后即可发送"
                    state.running -> "补充指令，Pi 会在下一步看到"
                    else -> "给 Pi 发消息，输入 / 查看命令"
                },
                onSend = ::send,
                onStop = { hub.send(ref, "abort") { ok, r -> if (!ok) toast = "停止未生效：${r ?: "主机没有确认"}" } },
                backdrop = backdrop,
                p = p,
            )
        }
    }
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
private class ConversationAttachment(private val hub: ConversationHub, private val ref: String) : RememberObserver {
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
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(p.canvas.copy(alpha = 0.92f), p.canvas.copy(alpha = 0.72f), Color.Transparent)))
            .statusBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassCircleButton(Glyph.Back, p, backdrop, "conversation-back", onClick = onBack)
            Row(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp)
                    .frostedGlass(backdrop, Capsule(), p)
                    .padding(horizontal = 16.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        name,
                        style = TextStyle(fontFamily = ConversationSans, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = p.ink, letterSpacing = (-0.1).sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Breathing(pulsing) { a -> Box(Modifier.size(6.dp).clip(Capsule()).background(statusTone.copy(alpha = a))) }
                        Text(
                            status,
                            style = CaptionStyle.copy(color = p.inkSoft),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 6.dp).testTag("conversation-status"),
                        )
                    }
                }
            }
            GlassCircleButton(Glyph.More, p, backdrop, "conversation-more", onClick = onMore)
        }
    }
}

@Composable
private fun ConnectionCapsule(phase: LinkPhase, p: ConversationPalette, backdrop: Backdrop, onRetry: () -> Unit, onOpenTerminal: () -> Unit) {
    Row(
        Modifier
            .frostedGlass(backdrop, Capsule(), p)
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
            .testTag("conversation-connection"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ended = phase == LinkPhase.Ended
        Breathing(!ended) { a -> Box(Modifier.size(7.dp).clip(Capsule()).background((if (ended) p.danger else p.warning).copy(alpha = a))) }
        Text(
            if (ended) "Agent 已退出" else "连接中断，正在恢复…",
            style = CaptionStyle.copy(color = p.ink, fontSize = 12.5.sp, fontWeight = FontWeight.Medium),
            modifier = Modifier.padding(start = 8.dp, end = 10.dp),
        )
        Text(
            if (ended) "打开终端" else "立即重试",
            style = CaptionStyle.copy(color = p.onAccent, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
            modifier = Modifier
                .clip(Capsule())
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
    topPadding: androidx.compose.ui.unit.Dp,
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
            modifier = Modifier.padding(top = topPadding, end = 12.dp),
        ) {
            Column(
                Modifier
                    .widthIn(min = 210.dp)
                    .frostedGlass(backdrop, RoundedRectangle(20.dp), p)
                    .padding(6.dp)
                    .testTag("conversation-menu"),
            ) {
                MenuRow(Glyph.Terminal, "在终端中打开", "查看这个 Agent 的终端视图", p, true, "conversation-menu-terminal", onTerminal)
                MenuRow(Glyph.Refresh, "压缩上下文", "/compact", p, enabled, "conversation-menu-compact", onCompact)
                MenuRow(Glyph.Spark, "开始新会话", "/new", p, enabled, "conversation-menu-new", onNewSession)
            }
        }
    }
}

@Composable
private fun MenuRow(glyph: Glyph, title: String, detail: String, p: ConversationPalette, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedRectangle(14.dp))
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
        else -> listOfNotNull("Pi", state.model, state.thinkingLevel?.takeIf { it != "off" }).joinToString(" · ")
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

