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

package dev.agentmirror.app.session

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import dev.agentmirror.app.conn.ConnectionState
import dev.agentmirror.app.conn.InputKey
import dev.agentmirror.app.diag.DiagLog
import dev.agentmirror.app.termview.SharedPreferencesFontSizeStore
import dev.agentmirror.app.termview.TermSurfaceView
import dev.agentmirror.app.tsnet.ConnectionPath
import dev.agentmirror.app.ui.components.SessionSwitchSheet
import dev.agentmirror.app.ui.model.SessionStatus
import dev.agentmirror.app.ui.model.sessionStatusFromL2
import dev.agentmirror.app.ui.screens.SessionShellScreen
import dev.agentmirror.app.ui.screens.TerminalKey
import dev.agentmirror.app.ui.theme.AppTheme
import dev.agentmirror.app.ui.theme.DarkPalette
import dev.agentmirror.app.ui.theme.LocalAppPalette
import dev.agentmirror.app.ui.theme.MonoFontFamily
import dev.agentmirror.app.ui.theme.Spacing
import dev.agentmirror.app.ui.theme.TermPalette
import dev.agentmirror.app.workspace.FavoriteKey
import dev.agentmirror.app.workspace.L2Entry
import dev.agentmirror.app.workspace.cwdDisplayName
import dev.agentmirror.app.workspace.favoriteKey
import dev.agentmirror.app.workspace.toSessionItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * 会话页 Compose 屏（003 四标准的渲染壳）：终端画布 + 紧凑顶栏 + 底部输入区。018 重设计版。
 *
 * 图29/图31 实锤缺陷修复：
 * - 紧凑顶栏（018 §一.7 顶栏不喧宾夺主）：48dp 单行，会话名等宽**单行中段省略**——
 *   64 字符名不再换两行压住返回键与状态栏；statusBarsPadding 进 safe-area；
 * - IME 重排（图31）：MainActivity 已把窗口锁 adjustResize（edge-to-edge 下系统默认
 *   解析成 pan，整窗上移 + imePadding 双重补偿 = 巨幅空洞的根因）；本屏底部集群
 *   （状态区+键条+输入条）统一 navigationBarsPadding().imePadding()，键盘弹出时终端区
 *   weight 收缩内容重排跟随，不留洞；
 * - 底部集群坐 surfaceContainer 面板底，与终端画布形成明确分区。
 *
 * 薄层纪律不变：所有业务状态与动作在 [SessionViewModel]（纯 JVM 已测），本组合只做绑定。
 */
@Composable
fun SessionScreen(
    viewModel: SessionViewModel,
    name: String,
    connectionPath: ConnectionPath? = null,
    onBack: () -> Unit,
    overlaySessions: List<L2Entry> = emptyList(),
    overlayFavorited: Set<FavoriteKey> = emptySet(),
    onToggleOverlayFavorite: (L2Entry) -> Unit = {},
    onOpenOverlaySession: (ref: String, name: String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingCaptureUri by remember { mutableStateOf<Uri?>(null) }

    // 附件选择:Photo Picker(ActivityResultContracts.PickVisualMedia,无权限弹窗)。
    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) uploadPickedImage(context, viewModel, uri, scope)
    }
    val takePhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { saved ->
        val uri = pendingCaptureUri
        pendingCaptureUri = null
        if (saved && uri != null) {
            uploadPickedImage(context, viewModel, uri, scope)
        } else if (uri != null) {
            context.contentResolver.delete(uri, null, null)
        }
    }
    val takePhotoPreview = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview(),
    ) { bitmap: Bitmap? ->
        if (bitmap == null) {
            viewModel.transientError = "拍照已取消"
        } else {
            uploadCapturedPreview(viewModel, bitmap, scope)
        }
    }
    val launchCameraCapture = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = createCameraImageUri(context)
            if (uri == null) {
                viewModel.transientError = "无法创建拍照文件"
            } else {
                pendingCaptureUri = uri
                takePhoto.launch(uri)
            }
        } else {
            // API 26–28 向公共 MediaStore 写入需要旧存储权限；缩略图契约无需扩大权限面。
            takePhotoPreview.launch(null)
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) launchCameraCapture()
        else viewModel.transientError = "相机权限未授权，请到系统设置中开启后重试"
    }

    // 连接时钟泵归属（feat-fg-service-wiring + fix-app-runtime-sa）：重连调度 + 输入超时
    // 裁决由前台服务驱动（MirrorForegroundService.pumpOnce，2s 一拍），本屏不再调用 vm.onTick
    // （连接时钟不在屏组合持有）。服务被杀时根组合的 OnScreenFallbackPump 兜底接管（前台
    // 界面仍推进），服务恢复即让出。剩余本地拍只做视口信号收敛（syncFromPresenter：滚动到顶时
    // 按页补更老历史，006）——纯 UI 本地逻辑、零连接状态零网络，不违背"连接由服务承接"。
    LaunchedEffect(viewModel) {
        while (true) {
            viewModel.syncFromPresenter()
            delay(TICK_MS)
        }
    }

    // 回执/错误瞬时态自动收起（「已发送」/「已注入」短暂可见；错误多停留一会儿）。
    LaunchedEffect(viewModel.inputStatus, viewModel.uploadStatus, viewModel.transientError) {
        val holdMs = when {
            viewModel.inputStatus is InputStatus.Failed -> TRANSIENT_MS * 3
            viewModel.uploadStatus is UploadStatus.Failed -> TRANSIENT_MS * 3
            viewModel.transientError != null -> TRANSIENT_MS * 3
            viewModel.inputStatus is InputStatus.Sent || viewModel.uploadStatus is UploadStatus.Success -> TRANSIENT_MS
            else -> return@LaunchedEffect
        }
        delay(holdMs)
        viewModel.dismissTransient()
    }

    var mirror by remember { mutableStateOf(TextFieldValue("")) }
    var attachMenu by remember { mutableStateOf(false) }
    val pickImage = {
        pickMedia.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }
    val requestTakePhoto = {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            launchCameraCapture()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    AppTheme {
        val darkTheme = LocalAppPalette.current === DarkPalette
        val themeToken = TermPalette.token(darkTheme)
        val overlayItems = overlaySessions.map {
            it.toSessionItem(starred = overlayFavorited.contains(it.favoriteKey()))
        }
        val workspaceName = overlaySessions.firstOrNull()?.cwd?.let(::cwdDisplayName).orEmpty()
        val status = overlaySessions.find { it.ref == viewModel.ref }
            ?.let { sessionStatusFromL2(it.status) }
            ?: SessionStatus.Unknown
        val byRef = overlaySessions.associateBy { it.ref }
        LaunchedEffect(status, overlaySessions, viewModel.ref) {
            val hit = overlaySessions.find { it.ref == viewModel.ref }
            DiagLog.record(
                "session-lamp",
                "src=level2-push ref=${viewModel.ref} overlay_n=${overlaySessions.size} " +
                    "hit=${hit != null} hit_status=${hit?.status?.wire ?: "<none>"} " +
                    "hit_ref=${hit?.ref ?: "<none>"} lamp=${status.name} " +
                    "eq_ref=${hit != null && hit.ref == viewModel.ref}",
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            SessionShellScreen(
                sessionDisplayName = name,
                status = status,
                connectionPath = connectionPath.takeIf {
                    viewModel.connectionState == ConnectionState.READY
                },
                draft = mirror,
                onDraftChange = {
                    viewModel.onPassthroughInput(mirror, it)
                    mirror = it
                },
                onSend = {
                    viewModel.sendDraft()
                    mirror = TextFieldValue("")
                },
                onBack = onBack,
                onOpenSwitcher = viewModel::openOverlay,
                onKeyPress = { viewModel.sendKey(it.toInputKey()) },
                onAttach = { attachMenu = true },
                sendEnabled = viewModel.inputStatus !is InputStatus.Sending,
                connectionBanner = viewModel.connectionBanner,
            ) {
                Box(Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { ctx ->
                            TermSurfaceView(ctx).also {
                                val savedSp = SharedPreferencesFontSizeStore(ctx).load()
                                    ?: SharedPreferencesFontSizeStore.DEFAULT_FONT_SIZE_SP
                                it.fontSizeSp = savedSp.toFloat()
                                it.presenter = viewModel.presenter
                                it.onRemoteScrollBy = viewModel::onScrollWheel
                                it.nightOverride = darkTheme
                            }
                        },
                        update = { view ->
                            view.nightOverride = darkTheme
                            view.presenter = viewModel.presenter
                            view.onRemoteScrollBy = viewModel::onScrollWheel
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .semantics { contentDescription = themeToken },
                    )
                    Text(
                        text = themeToken,
                        color = Color.Transparent,
                        fontSize = 1.sp,
                        lineHeight = 1.sp,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .size(1.dp),
                        maxLines = 1,
                    )
                    if (viewModel.showBackToBottom) {
                        BackToBottomButton(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(Spacing.lg),
                            onClick = { viewModel.onScrollToBottom() },
                        )
                    }
                    if (viewModel.inCopyMode) {
                        CopyModeIndicator(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(Spacing.sm),
                        )
                    }
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                        StatusArea(viewModel)
                    }
                }
            }
            if (viewModel.overlayOpen) {
                SessionSwitchSheet(
                    visible = true,
                    workspaceName = workspaceName,
                    sessions = overlayItems,
                    currentSessionId = viewModel.ref,
                    onDismiss = viewModel::closeOverlay,
                    onSelect = { item ->
                        val entry = byRef[item.id] ?: return@SessionSwitchSheet
                        viewModel.closeOverlay()
                        onOpenOverlaySession(entry.ref, entry.identityLabel)
                    },
                    onToggleStar = { item ->
                        byRef[item.id]?.let(onToggleOverlayFavorite)
                    },
                )
            }
            Box(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 72.dp)) {
                DropdownMenu(
                    expanded = attachMenu,
                    onDismissRequest = { attachMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("拍照") },
                        onClick = {
                            attachMenu = false
                            requestTakePhoto()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("从相册选择") },
                        onClick = {
                            attachMenu = false
                            pickImage()
                        },
                    )
                }
            }
        }
    }
}

private fun TerminalKey.toInputKey(): InputKey = when (this) {
    TerminalKey.Esc -> InputKey.ESC
    TerminalKey.Tab -> InputKey.TAB
    TerminalKey.Up -> InputKey.UP
    TerminalKey.Down -> InputKey.DOWN
    TerminalKey.Left -> InputKey.LEFT
    TerminalKey.Right -> InputKey.RIGHT
    TerminalKey.CtrlC -> InputKey.CTRL_C
}

/** 附件入口只做动作分流：拍照与系统 Photo Picker 最终复用同一上传链。 */
@Composable
internal fun AttachmentButton(
    enabled: Boolean,
    onPickImage: () -> Unit,
    onTakePhoto: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            enabled = enabled,
        ) {
            Text(
                text = "＋",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { contentDescription = "添加图片附件" },
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("拍照") },
                onClick = {
                    expanded = false
                    onTakePhoto()
                },
            )
            DropdownMenuItem(
                text = { Text("从相册选择") },
                onClick = {
                    expanded = false
                    onPickImage()
                },
            )
        }
    }
}

/**
 * 快捷键条（R-1，017）：输入条上方，最小集 Esc / Tab / ↑ ↓ ← → / Ctrl-C（2026-08-15 裁定）。
 *
 * 键帽升级为 tonal Surface 芯片：ripple 点击态 + M3 最小 48dp 触控目标（Surface onClick
 * 自动外扩触控区，视觉高度不变，018 §一.4/一.6）；窄屏横向滚动不折行。
 * 参数化（enabled/onKey）而非直收 VM：供 KeyBarFitTest 独立渲染打几何断言
 * （fix-term-bg-cjk 顺带：真机最右「→」被右缘裁半）。
 * 每键点按即发 keys 帧（走 VM [SessionViewModel.sendKey]，input_ack 必达回执——003 发送必达）；
 * 在途发送（InputStatus.Sending）时整体置灰，与草稿共用发送闸。
 * 文案锁中文（R-6 当期裁定）；每键带 contentDescription 语义标注（R-7 顺带）。
 */
@Composable
internal fun KeyBar(enabled: Boolean, onKey: (InputKey) -> Unit) {
    // 横向预算收紧（fix-term-bg-cjk 顺带）：7 键各带 M3 48dp 触控地板，加上原 md 键帽
    // 内距/6dp 间距/sm 外距，系统字号放大档（fontScale≥1.2）下固有和超出 411dp 屏宽，
    // 真机最右「→」静置被右缘裁半。内距 md→sm、间距 6→4、外距 sm→xs 后 fontScale 1.4
    // 仍整排可见（KeyBarFitTest 锁定）；更窄屏/更大字号由 horizontalScroll 兜底。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (entry in KEY_BAR_ENTRIES) {
            Surface(
                onClick = { onKey(entry.key) },
                enabled = enabled,
                shape = MaterialTheme.shapes.extraSmall,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = MonoFontFamily,
                    modifier = Modifier
                        .semantics { contentDescription = entry.contentDescription }
                        .padding(horizontal = Spacing.sm, vertical = 6.dp),
                )
            }
        }
    }
}

/** 快捷键条条目：显示文案 + 线上键值 + 无障碍语义标注（2026-08-15 裁定顺序）。 */
private data class KeyBarEntry(
    val label: String,
    val key: InputKey,
    val contentDescription: String,
)

/** 快捷键条最小集（2026-08-15 用户裁定顺序：Esc / Tab / ↑ ↓ ← → / Ctrl-C）。 */
private val KEY_BAR_ENTRIES = listOf(
    KeyBarEntry("Esc", InputKey.ESC, "Esc 键：中断当前步骤"),
    KeyBarEntry("Tab", InputKey.TAB, "Tab 键：补全"),
    KeyBarEntry("↑", InputKey.UP, "上方向键"),
    KeyBarEntry("↓", InputKey.DOWN, "下方向键"),
    KeyBarEntry("←", InputKey.LEFT, "左方向键"),
    KeyBarEntry("→", InputKey.RIGHT, "右方向键"),
    KeyBarEntry("Ctrl-C", InputKey.CTRL_C, "Ctrl-C 键：发送中断信号"),
)

/**
 * 回执/错误状态区：失败/在途/上传/解码错误明确可见（003）。
 * 083 §4/§12：成功态不组「已发送」节点。不用 AnimatedVisibility——
 * 退出动画会把旧节点留在树上叠成蓝色悬浮堆。
 */
@Composable
private fun StatusArea(viewModel: SessionViewModel) {
    val message = when (val s = viewModel.inputStatus) {
        is InputStatus.Sent -> null
        is InputStatus.Failed -> s.message
        is InputStatus.Sending -> "发送中…"
        InputStatus.Idle -> null
    } ?: when (val u = viewModel.uploadStatus) {
        is UploadStatus.Uploading -> "上传中…"
        is UploadStatus.Success -> "已附加图片"
        is UploadStatus.Failed -> u.message
        UploadStatus.Idle -> null
    } ?: viewModel.transientError

    if (message == null) return
    val isError = viewModel.inputStatus is InputStatus.Failed ||
        viewModel.uploadStatus is UploadStatus.Failed ||
        viewModel.transientError != null
    Text(
        text = message,
        style = MaterialTheme.typography.labelMedium,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.pageH, vertical = Spacing.xs),
    )
}

/** 「回到底部」悬浮钮（锁定历史时点击恢复跟随，006）：tonal 胶囊 + ripple。 */
@Composable
private fun BackToBottomButton(
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedPill,
        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f),
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
    ) {
        Text(
            text = "↓ 回到底部",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
        )
    }
}

/** 悬浮钮胶囊形状（全圆角）。 */
private val RoundedPill = androidx.compose.foundation.shape.RoundedCornerShape(50)

/**
 * copy-mode 角标（缺陷④ 远端滚动投送）：pane 进入 tmux copy-mode 时显示，
 * 提示用户当前打字被 copy-mode 拦截（服务端 handleInput 会自动 cancel，
 * 但 UI 让用户「看得见自己在什么模式」，防止「敲了没反应」的困惑）。
 */
@Composable
private fun CopyModeIndicator(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedPill,
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.90f),
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Text(
            text = "copy-mode",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 4.dp),
        )
    }
}

/**
 * 上传已选图片：读 URI 字节 → 构造 [Attachment] → VM 上传（URI 读取与网络都在 IO 线程，
 * VM 的 Compose 状态写入线程安全）。
 */
private fun uploadPickedImage(
    context: Context,
    viewModel: SessionViewModel,
    uri: Uri,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val resolver = context.contentResolver
    scope.launch {
        val attachment = withContext(Dispatchers.IO) {
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            val name = attachmentFileName(queryDisplayName(context, uri), mime)
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null) null else Attachment(name, mime, bytes)
        }
        if (attachment != null) {
            withContext(Dispatchers.IO) { viewModel.uploadAttachment(attachment) }
        } else {
            viewModel.uploadStatus = UploadStatus.Failed("无法读取所选图片")
        }
    }
}

/** MediaStore/OpenableColumns 是 content URI 的真实展示名来源；URI 路径段只是内部数字 ID。 */
private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull()

/** 保留 provider 的真实展示名；仅在缺扩展名时用 MIME 补齐，避免 CLI 把图片当未知二进制。 */
internal fun attachmentFileName(displayName: String?, mimeType: String?): String {
    val name = displayName
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.takeIf { it.isNotBlank() }
        ?: "image"
    val dot = name.lastIndexOf('.')
    if (dot > 0 && dot < name.lastIndex) return name
    val extension = mimeType
        ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it.lowercase()) }
        ?.takeIf { it.isNotBlank() }
        ?: return name
    return "$name.$extension"
}

/** 为 TakePicture 预建全分辨率 MediaStore 目标；生成名自带 JPEG 扩展名并可直接进入上传链。 */
private fun createCameraImageUri(context: Context): Uri? {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "camera-${System.currentTimeMillis()}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
    }
    return context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
}

/** API 26–28 的无存储权限回退：压缩相机预览并复用附件上传状态机。 */
private fun uploadCapturedPreview(
    viewModel: SessionViewModel,
    bitmap: Bitmap,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    scope.launch {
        val attachment = withContext(Dispatchers.IO) {
            val bytes = ByteArrayOutputStream().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) return@withContext null
                output.toByteArray()
            }
            Attachment(
                name = "camera-${System.currentTimeMillis()}.jpg",
                mimeType = "image/jpeg",
                bytes = bytes,
            )
        }
        if (attachment == null) {
            viewModel.uploadStatus = UploadStatus.Failed("无法读取拍照图片")
        } else {
            withContext(Dispatchers.IO) { viewModel.uploadAttachment(attachment) }
        }
    }
}

private const val TICK_MS = 100L
private const val TRANSIENT_MS = 1_200L
