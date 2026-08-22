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

package dev.agentmirror.app.pairing

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import dev.agentmirror.app.tsnet.TsnetState
import dev.agentmirror.app.ui.theme.MonoFontFamily
import dev.agentmirror.app.ui.theme.Spacing
import kotlinx.coroutines.delay
import java.nio.ByteBuffer

/**
 * 配对页 Compose 屏（薄渲染壳）：扫码卡 + 手填兜底卡 + TS auth key 卡。018 重设计版。
 *
 * 视觉重做要点：
 * - safe-area：顶栏 statusBarsPadding，滚动区尾部 navigationBarsPadding + imePadding
 *   （手填表单聚焦时键盘不遮挡输入，018 §一.2）；
 * - 三段内容全部升级为 surfaceContainer 圆角卡（M3 分组语言，替代裸文本直排）；
 * - 状态区专门设计：进行中=进度横幅（spinner+地址）、失败=错误卡+重试、成功=确认横幅
 *   （018 §一.5 状态可视；003 静默失效最高罪）。
 *
 * 业务绑定不变：扫码 CameraX→ZXing→[PairingViewModel.onQrText]（零 GMS，008）；
 * 手填兜底；TS auth key 手填 + 入网状态可视（feat-ts-wire 接活）。
 */
@Composable
fun PairingScreen(
    viewModel: PairingViewModel,
    onPaired: (PairingConfig) -> Unit,
    onSkip: () -> Unit,
) {
    val context = LocalContext.current
    var cameraPermissionState by remember {
        mutableStateOf(
            if (context.hasCameraPermission()) CameraPermissionUiState.Granted
            else CameraPermissionUiState.Requestable,
        )
    }
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        cameraPermissionState = if (context.hasCameraPermission()) {
            CameraPermissionUiState.Granted
        } else {
            CameraPermissionUiState.PermanentlyDenied
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraPermissionState = cameraPermissionUiState(
            granted = granted,
            requested = true,
            shouldShowRationale = context.findActivity()?.let { activity ->
                androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                    activity,
                    Manifest.permission.CAMERA,
                )
            } == true,
        )
    }

    val status = viewModel.pairingStatus

    // 时钟泵：配对超时裁决的唯一生产节奏（红线5 失败可见，同构 SessionScreen 时钟泵）。
    // 缺陷实锤：此前全仓唯一 onTick 调用在 SessionScreen（那是 SessionViewModel 的），
    // 配对页无人调 onTick → PAIR_TIMEOUT_MS 永不触发 → 地址不可达/握手静默挂起时无限
    // 「连接中…」。LaunchedEffect 随本组合生命周期启停：配对页离屏（成功路由/跳过/销毁）
    // 即取消协程停泵，不空转。
    LaunchedEffect(viewModel) {
        while (true) {
            viewModel.onTick(System.currentTimeMillis())
            delay(TICK_MS)
        }
    }

    // 配对成功：路由层切工作区（防重复触发）。
    LaunchedEffect(status) {
        if (status is PairingStatus.Success) {
            viewModel.pendingConfig?.let { onPaired(it) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TopBar(onSkip = onSkip)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.pageH)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            // 扫码区：有权限渲染 CameraX 预览；无权限给授权按钮 + 手填兜底提示。
            if (cameraPermissionState == CameraPermissionUiState.Granted) {
                ScanCard(viewModel)
            } else {
                NoPermissionCard(
                    state = cameraPermissionState,
                    onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    onOpenSettings = {
                        settingsLauncher.launch(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                // UseKtx stage3 #16：KTX String.toUri 替代 Uri.parse。
                                "package:${context.packageName}".toUri(),
                            ),
                        )
                    },
                )
            }
            ManualFormCard(viewModel)
            TsTokenCard(viewModel)
            StatusArea(
                status = status,
                onRetry = { viewModel.retry() },
                // fix-pairing-candidates：失败后候选列表可见可点（一键重试单项，主选打头）。
                candidateUrls = viewModel.candidateUrls,
                onRetryCandidate = { viewModel.retryCandidate(it) },
            )
            // 滚动尾部呼吸位（卡片不贴屏幕底）。
            Box(Modifier.height(Spacing.sm))
        }
    }
}

/** 顶栏：标题 + 副标题引导 + 「以后再说」跳过（首启可跳过，从设置可随时重配）。 */
@Composable
private fun TopBar(onSkip: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = Spacing.pageH, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "连接主机",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "镜像主机 tmux 里的 Agent 终端",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onSkip) { Text("以后再说") }
    }
}

/** 分组卡容器：三段内容统一的 surfaceContainer 圆角卡壳（M3 分组语言）。 */
@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            content()
        }
    }
}

/** 扫码卡：CameraX 预览（圆角裁切）+ ZXing 分析。 */
@Composable
private fun ScanCard(viewModel: PairingViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    // 每帧解码一次（图像分析回调直接同步解码，无需 coroutine）。
    val analyzer = remember {
        object : ImageAnalysis.Analyzer {
            private val reader = MultiFormatReader().apply {
                setHints(
                    mapOf(
                        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                        DecodeHintType.TRY_HARDER to true,
                    ),
                )
            }
            private var lastScanAt = 0L

            override fun analyze(image: ImageProxy) {
                val now = System.currentTimeMillis()
                if (now - lastScanAt < SCAN_THROTTLE_MS) {
                    image.close(); return
                }
                val payload = image.planes.firstOrNull()?.let { plane ->
                    val buffer: ByteBuffer = plane.buffer
                    val data = ByteArray(buffer.remaining())
                    buffer.get(data)
                    val source = PlanarYUVLuminanceSource(
                        data, image.width, image.height,
                        0, 0, image.width, image.height, false,
                    )
                    BinaryBitmap(HybridBinarizer(source))
                }
                val result = payload?.let {
                    runCatching { reader.decodeWithState(it) }.getOrNull()
                }
                if (result != null && result.text.isNotBlank()) {
                    lastScanAt = now
                    viewModel.onQrText(result.text)
                }
                image.close()
            }
        }
    }

    SectionCard {
        Text(
            text = "扫码连接",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).also { pv ->
                        val providerFuture = ProcessCameraProvider.getInstance(ctx)
                        providerFuture.addListener({
                            val provider = providerFuture.get()
                            val preview = Preview.Builder().build()
                            preview.surfaceProvider = pv.surfaceProvider
                            val analysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                                .also {
                                    // 主线程执行即可：ZXing 解码快且 analyze 内有节流（SCAN_THROTTLE_MS）。
                                    it.setAnalyzer(ContextCompat.getMainExecutor(ctx), analyzer)
                                }
                            provider.bindToLifecycle(
                                lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA,
                                preview, analysis,
                            )
                        }, ContextCompat.getMainExecutor(ctx))
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        // 识别摘要只上屏地址，绝不上屏裸 JSON（含 token）——QR 是 token 唯一合法出口（§9）。
        // 仅在配对进行中展示「正在连接」：失败态不得残留进行中文案（leader 追加范围，
        // 双保险：VM failPairing 已清 recognizedUrl，此处再按状态门控）。
        if (viewModel.pairingStatus is PairingStatus.Pairing) {
            viewModel.recognizedUrl?.let { url ->
                Text(
                    text = "已识别 · 正在连接 $url",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = MonoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = "对准主机终端上的二维码即可自动配对。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 相机权限 UI 状态：永久拒绝必须走系统设置，不能继续展示无效授权按钮。 */
internal enum class CameraPermissionUiState {
    Granted,
    Requestable,
    Denied,
    PermanentlyDenied,
}

/** 将系统权限结果收敛为可渲染状态，隔离 Android 回调细节以便 JVM 锁定二次拒绝语义。 */
internal fun cameraPermissionUiState(
    granted: Boolean,
    requested: Boolean,
    shouldShowRationale: Boolean,
): CameraPermissionUiState = when {
    granted -> CameraPermissionUiState.Granted
    !requested -> CameraPermissionUiState.Requestable
    shouldShowRationale -> CameraPermissionUiState.Denied
    else -> CameraPermissionUiState.PermanentlyDenied
}

/** 相机未授权卡：拒绝原因、下一步和手填兜底都明确可见（不静默，003 红线5）。 */
@Composable
internal fun NoPermissionCard(
    state: CameraPermissionUiState,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    SectionCard {
        Text(
            text = "扫码连接",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = when (state) {
                CameraPermissionUiState.Requestable ->
                    "扫码需要相机权限。未授权时请改用下方手填连接。"
                CameraPermissionUiState.Denied ->
                    "相机权限已被拒绝，可再次授权；也可改用下方手填连接。"
                CameraPermissionUiState.PermanentlyDenied ->
                    "相机权限已被永久拒绝，请到系统设置中开启；也可改用下方手填连接。"
                CameraPermissionUiState.Granted -> return@SectionCard
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state == CameraPermissionUiState.PermanentlyDenied) {
            Button(onClick = onOpenSettings) { Text("打开系统设置") }
        } else {
            Button(onClick = onRequest) {
                Text(if (state == CameraPermissionUiState.Denied) "再次授权" else "授予相机权限")
            }
        }
    }
}

/** LocalContext 可能包在主题 ContextWrapper 中，逐层找到权限 rationale 所需 Activity。 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** 当前相机权限事实源。 */
private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/** 手填兜底：地址 + token（扫码不可用/被拒时）。等宽输入（地址/token 都是机器串）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualFormCard(viewModel: PairingViewModel) {
    val candidates = viewModel.candidateUrls
    // 手填地址候选下拉（fix-pairing-candidates）：有候选时地址框旁出「▾」，可从候选选。
    var menuExpanded by remember { mutableStateOf(false) }
    SectionCard {
        Text(
            text = "手填连接",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        ExposedDropdownMenuBox(
            expanded = menuExpanded && candidates.isNotEmpty(),
            onExpandedChange = { menuExpanded = it },
        ) {
            OutlinedTextField(
                value = viewModel.manualUrl,
                onValueChange = { viewModel.manualUrl = it },
                label = { Text("服务端 ws 地址") },
                placeholder = { Text("ws://192.168.1.5:9900/ws", fontFamily = MonoFontFamily) },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                colors = manualFieldColors(),
                trailingIcon = {
                    if (candidates.isNotEmpty()) {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
            )
            DropdownMenu(
                expanded = menuExpanded && candidates.isNotEmpty(),
                onDismissRequest = { menuExpanded = false },
            ) {
                candidates.forEach { url ->
                    DropdownMenuItem(
                        text = { Text(url, fontFamily = MonoFontFamily) },
                        onClick = {
                            // 选中候选回填地址框（selectCandidateUrl），用户可改后「连接」。
                            viewModel.selectCandidateUrl(url)
                            menuExpanded = false
                        },
                    )
                }
            }
        }
        OutlinedTextField(
            value = viewModel.manualToken,
            onValueChange = { viewModel.manualToken = it },
            label = { Text("配对 token") },
            // token 与 TS authkey 同级敏感；手填时也必须遮罩，避免录屏/截图明文带出。
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            colors = manualFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        viewModel.formError?.let { err ->
            Text(
                text = err,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(
            onClick = { viewModel.submitManual() },
            enabled = viewModel.pairingStatus !is PairingStatus.Pairing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("连接")
        }
    }
}

/** 手填输入框配色：卡内输入用更低一层底色拉开层次（与会话页草稿框同语法）。 */
@Composable
private fun manualFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
)

/**
 * TS auth key 卡（feat-ts-wire 接活，替代「即将推出」空壳）：手填通道 + TS 态可视。
 *
 * - 输入密文态渲染（PasswordVisualTransformation）：authkey 同 token 红线不明文上屏
 *   （§2.1）；扫码带入的 key 不回填本框（QR 是唯一分发出口）。
 * - supportingText 呈现节点状态（018 标准5 状态可视）：入网中/已入网/失败原因；
 *   Idle 时为使用引导。key 随「连接」提交生效（[PairingViewModel.submitManual]），
 *   扫码路径则由 QR 自带 key 自动起网。
 */
@Composable
private fun TsTokenCard(viewModel: PairingViewModel) {
    SectionCard {
        Text(
            text = "Tailscale 入网（可选）",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        OutlinedTextField(
            value = viewModel.manualTsAuthKey,
            onValueChange = { viewModel.manualTsAuthKey = it },
            label = { Text("Tailscale auth key") },
            visualTransformation = PasswordVisualTransformation(),
            supportingText = {
                val (text, isError) = tsStateLine(viewModel.tsState)
                Text(
                    text = text,
                    color = if (isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            colors = manualFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * TS 节点状态 → 状态行文案（第二值 = 是否错误配色）。纯函数便于目检维护；
 * Error.reason 来自 TsnetManager/后端，其契约保证不含 authkey 值（红线）。
 */
private fun tsStateLine(state: TsnetState): Pair<String, Boolean> = when (state) {
    TsnetState.Idle -> "填入 auth key 后点「连接」，或直接扫携带 key 的二维码，自动加入 tailnet。" to false
    TsnetState.Starting -> "tailnet 入网中…" to false
    is TsnetState.Up -> "已入网：节点已连接，数据通道需要几秒建立。" to false
    is TsnetState.Error -> "入网失败：${state.reason}" to true
}

/**
 * 配对状态区（018 §一.5 专门设计）：进行中=tonal 进度横幅；成功=确认横幅；
 * 失败=错误卡 + 重试按钮。全部明确可见（003 静默失效最高罪）。
 */
@Composable
private fun StatusArea(
    status: PairingStatus,
    onRetry: () -> Unit,
    candidateUrls: List<String>,
    onRetryCandidate: (String) -> Unit,
) {
    when (status) {
        PairingStatus.Idle -> Unit
        is PairingStatus.Pairing -> Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                // 整改点①：识别成功立即自动配对并显示「连接中…」进度态，含目标地址（token 不上屏，§9）。
                Text(
                    text = "连接中… ${status.targetUrl}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        PairingStatus.Success -> Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "配对成功！",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
            )
        }
        is PairingStatus.Failed -> Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(
                    text = status.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                if (status.cause != PairingFailCause.PARSE_ERROR) {
                    // 整改点②：失败给显式报错 + 重试按钮（解析失败无配置，重试无意义，应重扫/手填）。
                    Button(onClick = onRetry) { Text("重试") }
                }
                if (candidateUrls.isNotEmpty()) {
                    // fix-pairing-candidates：全部候选失败后候选列表可见可点——一键重试单项。
                    // 可点行沿用 errorContainer 卡语言 + Mono 等宽；labelMedium 行高接近 48dp 触控。
                    Text(
                        text = "候选地址（点选一项重试）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    candidateUrls.forEach { url ->
                        Surface(
                            onClick = { onRetryCandidate(url) },
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.errorContainer,
                        ) {
                            Text(
                                text = url,
                                fontFamily = MonoFontFamily,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 扫码节流：连续帧不重复解码（同一 QR 不反复触发配对）。 */
private const val SCAN_THROTTLE_MS = 1_500L

/** 时钟泵周期（配对超时裁决节奏；与 SessionScreen 同款 100ms 泵）。 */
private const val TICK_MS = 100L
