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
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.delay
import java.nio.ByteBuffer

/**
 * 配对页 Compose 屏（薄渲染壳）：扫码 view + 手填兜底表单 + TS token 占位入口。
 *
 * 所有业务状态与动作在 [PairingViewModel]（纯 JVM 已测）；本组合只做绑定：
 * - 扫码：CameraX 分析流 → ZXing 解码 QR → [PairingViewModel.onQrText]（零 GMS，008）；
 * - 手填：地址 + token 兜底（扫码不可用/相机被拒时）；
 * - TS token：仅入口占位（app-tsnet 接入前禁用，接入选单回填）。
 */
@Composable
fun PairingScreen(
    viewModel: PairingViewModel,
    onPaired: (PairingConfig) -> Unit,
    onSkip: () -> Unit,
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCameraPermission = granted }

    val status = viewModel.pairingStatus

    // 时钟泵：配对超时裁决的唯一生产节奏（红线5 失败可见，同构 SessionScreen.kt:85）。
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

    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(onSkip = onSkip)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 扫码区：有权限渲染 CameraX 预览；无权限给授权按钮 + 手填兜底提示。
            if (hasCameraPermission) {
                ScanCard(viewModel)
            } else {
                NoPermissionCard(onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) })
            }
            ManualFormCard(viewModel)
            TsTokenCard()
            StatusArea(status, onRetry = { viewModel.retry() })
        }
    }
}

/** 顶栏：标题 + 「以后再说」跳过（首启可跳过，从设置可随时重配）。 */
@Composable
private fun TopBar(onSkip: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "连接主机",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onSkip) { Text("以后再说") }
    }
}

/** 扫码卡：CameraX 预览 + ZXing 分析。 */
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

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("扫码连接", style = MaterialTheme.typography.titleMedium)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant),
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
        viewModel.recognizedUrl?.let { url ->
            // 识别摘要只上屏地址，绝不上屏裸 JSON（含 token）——QR 是 token 唯一合法出口（§9）。
            Text(
                text = "已识别 · 正在连接 $url",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "对准主机终端上的二维码即可自动配对。",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 相机未授权卡：明确引导授权 + 提示可手填兜底（不静默，003）。 */
@Composable
private fun NoPermissionCard(onRequest: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("扫码连接", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "扫码需要相机权限。\n未授权时请改用下方手填连接。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onRequest) { Text("授予相机权限") }
    }
}

/** 手填兜底：地址 + token（扫码不可用/被拒时）。 */
@Composable
private fun ManualFormCard(viewModel: PairingViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("手填连接", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = viewModel.manualUrl,
            onValueChange = { viewModel.manualUrl = it },
            label = { Text("服务端 ws 地址") },
            placeholder = { Text("ws://192.168.1.5:9900/ws") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = viewModel.manualToken,
            onValueChange = { viewModel.manualToken = it },
            label = { Text("配对 token") },
            singleLine = true,
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

/** TS token 入口（占位）：App 内嵌 tailscale 归 app-tsnet 任务；接入前禁用并说明。 */
@Composable
private fun TsTokenCard() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Tailscale 入网（可选）", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = "",
            onValueChange = {},
            label = { Text("Tailscale auth key") },
            enabled = false,
            supportingText = { Text("即将推出：填入后扫码即自动加入 tailnet。") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 配对状态区：进行中/成功/失败全部明确可见（003 静默失效最高罪）。 */
@Composable
private fun StatusArea(status: PairingStatus, onRetry: () -> Unit) {
    when (status) {
        PairingStatus.Idle -> Unit
        is PairingStatus.Pairing -> Text(
            // 整改点①：识别成功立即自动配对并显示「连接中…」进度态，含目标地址（token 不上屏，§9）。
            text = "连接中… ${status.targetUrl}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        PairingStatus.Success -> Text(
            text = "配对成功！",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        is PairingStatus.Failed -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = status.message,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
            if (status.cause != PairingFailCause.PARSE_ERROR) {
                // 整改点②：失败给显式报错 + 重试按钮（解析失败无配置，重试无意义，应重扫/手填）。
                Button(onClick = onRetry) { Text("重试") }
            }
        }
    }
}

/** 扫码节流：连续帧不重复解码（同一 QR 不反复触发配对）。 */
private const val SCAN_THROTTLE_MS = 1_500L

/** 时钟泵周期（配对超时裁决节奏；与 SessionScreen 同款 100ms 泵）。 */
private const val TICK_MS = 100L
