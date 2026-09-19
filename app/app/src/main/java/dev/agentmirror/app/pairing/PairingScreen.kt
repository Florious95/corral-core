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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import dev.agentmirror.app.tsnet.ConnectionPath
import dev.agentmirror.app.tsnet.TsnetState
import dev.agentmirror.app.ui.theme.MonoFontFamily
import dev.agentmirror.app.ui.theme.Spacing
import kotlinx.coroutines.delay
import java.nio.ByteBuffer

/**
 * 配对页 Compose 屏（LAN-first 现代化重构版）：
 * - 局域网优先（LAN-first）：自动扫描并以精致卡片展示局域网内主机（电脑名 + HostID + LAN 徽标）；
 * - 选中交互：点击目标主机卡片优雅展开输入 Token 直连；
 * - 坚固兜底：清晰直观的手动直连卡片（支持直接输入 局域网 IP:端口 与 Token）；
 * - 扩展支持：折叠的 Tailscale 远程连接配置；可选的扫码配对；
 * - 状态可视与时钟泵：超时自动裁决，状态反馈及时明确。
 */
@Composable
fun PairingScreen(
    viewModel: PairingViewModel,
    onPaired: (PairingConfig) -> Unit,
    onSkip: () -> Unit,
    onRescan: (() -> Unit)? = null,
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
            // 状态区：连接中 / 成功 / 失败 状态横幅置顶展示，保证即时反馈
            StatusArea(
                status = status,
                onRetry = { viewModel.retry() },
                candidateUrls = viewModel.candidateUrls,
                onRetryCandidate = { viewModel.retryCandidate(it) },
            )

            // 1. 局域网优先扫描展示区（核心视觉与交互区）
            HostBindingCard(
                viewModel = viewModel,
                onRescan = onRescan,
            )

            // 2. 手动直连兜底通道（局域网 IP:端口 + Token 直连）
            ManualDirectCard(viewModel = viewModel)

            // 3. 扫码配对（支持折叠展开取景器）
            ScanSection(
                viewModel = viewModel,
                cameraPermissionState = cameraPermissionState,
                onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onOpenSettings = {
                    settingsLauncher.launch(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            "package:${context.packageName}".toUri(),
                        ),
                    )
                },
            )

            // 4. Tailscale 远程连接配置（折叠高级入口）
            TailscaleConfigCard(viewModel = viewModel)

            // 滚动尾部呼吸位
            Box(Modifier.height(Spacing.sm))
        }
    }
}

/** 顶栏：标题 + 副标题引导 + 「以后再说」跳过 */
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
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "镜像主机 tmux · 局域网高速直连",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onSkip) { Text("以后再说") }
    }
}

/** 通用分节卡容器：统一度量与圆角 */
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

/**
 * 局域网主机发现卡片列表（LAN-first 核心）：
 * - 扫描中：微动效与提示；
 * - 已发现：大字电脑名 + 等宽 HostID + LAN 药丸徽标；
 * - 交互：点击选中卡片展开输入 Token 与连接主机；
 * - 空态：未搜到时不展示无头 Token 框，展示温和提示与重新扫描入口。
 */
@Composable
fun HostBindingCard(
    viewModel: PairingViewModel,
    onRescan: (() -> Unit)? = null,
) {
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(
                    text = "局域网主机",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (viewModel.discoveryInFlight || viewModel.tsnetConnecting) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(14.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (onRescan != null && !viewModel.discoveryInFlight && !viewModel.tsnetConnecting) {
                TextButton(
                    onClick = onRescan,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text("重新扫描", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        if (viewModel.discoveredHosts.isEmpty()) {
            if (viewModel.discoveryInFlight || viewModel.tsnetConnecting) {
                // 正在扫描附近主机微动效卡
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(Spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        PulsingDot()
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = if (viewModel.tsnetConnecting) "正在接入 Tailnet 并扫描主机…"
                                    else "正在扫描附近局域网主机…",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = if (viewModel.tsnetConnecting) "接入成功后将自动列出远程主机"
                                    else "请确保手机与主机连接同一 Wi-Fi 网络",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                // 未扫描到主机空态（坚决不展示孤立无头的 Token 框）
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        Text(
                            text = "未发现局域网主机",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "请确认电脑已开启 AgentMirror 并处于同一 Wi-Fi。若网络开启了组播隔离，可使用下方「手动直连」。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            // 已发现局域网主机列表
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                viewModel.discoveredHosts.forEach { host ->
                    val isSelected = viewModel.selectedHostId == host.hostId
                    val displayName = HostRouter.displayName(host.name, host.hostId)
                    val authority = host.endpoints.firstOrNull()?.authority

                    Surface(
                        onClick = { viewModel.selectHost(host.hostId) },
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        border = if (isSelected) {
                            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                        } else {
                            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(Spacing.md)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.size(42.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text("🖥️", fontSize = 20.sp)
                                    }
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = displayName,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = "ID: ${host.hostId}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = MonoFontFamily,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (!authority.isNullOrBlank()) {
                                        Text(
                                            text = authority,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = MonoFontFamily,
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                    }
                                }
                                val hasTailscale = host.endpoints.any { it.path == ConnectionPath.TAILNET }
                                val hasLan = host.endpoints.any { it.path == ConnectionPath.LAN } || (!hasTailscale && host.endpoints.isEmpty())
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (hasTailscale) {
                                        TailscalePillBadge()
                                    }
                                    if (hasLan) {
                                        LanPillBadge()
                                    }
                                }
                            }

                            // 选中卡片后内联展开 Token 输入与连接操作
                            AnimatedVisibility(
                                visible = isSelected,
                                enter = fadeIn() + expandVertically(),
                                exit = fadeOut() + shrinkVertically(),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = Spacing.md),
                                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(1.dp)
                                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                                    )
                                    Text(
                                        text = "请输入此电脑显示的配对 Token",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    OutlinedTextField(
                                        value = viewModel.hostToken,
                                        onValueChange = { viewModel.hostToken = it },
                                        label = { Text("主机 Token") },
                                        placeholder = { Text("输入或粘贴配对 Token") },
                                        visualTransformation = PasswordVisualTransformation(),
                                        singleLine = true,
                                        shape = MaterialTheme.shapes.small,
                                        colors = manualFieldColors(),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    if (viewModel.selectedHostId == host.hostId && viewModel.formError != null) {
                                        Text(
                                            text = viewModel.formError!!,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                    Button(
                                        onClick = { viewModel.submitHostToken() },
                                        enabled = viewModel.pairingStatus !is PairingStatus.Pairing,
                                        shape = MaterialTheme.shapes.small,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text("连接主机")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 绿色优雅 LAN 药丸标签 */
@Composable
private fun LanPillBadge() {
    Surface(
        color = Color(0x2210B981),
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text = "LAN",
            color = Color(0xFF059669),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = MonoFontFamily,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
        )
    }
}

/** 蓝色优雅 Tailscale 药丸标签 */
@Composable
private fun TailscalePillBadge() {
    Surface(
        color = Color(0x222563EB),
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text = "Tailscale",
            color = Color(0xFF2563EB),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = MonoFontFamily,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
        )
    }
}

/** 呼吸脉冲扫描动效点 */
@Composable
private fun PulsingDot() {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha)),
    )
}

/**
 * 手动直连卡片（坚固兜底）：
 * 支持输入局域网 IP[:端口]（例如 192.168.31.116:9900）或完整 ws:// 地址 + Token。
 */
@Composable
private fun ManualDirectCard(viewModel: PairingViewModel) {
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "手动直连",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(4.dp),
            ) {
                Text(
                    text = "局域网兜底",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            text = "若局域网未能自动搜出，可直接输入电脑 IP 和端口直连。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = viewModel.manualUrl,
            onValueChange = { viewModel.manualUrl = it },
            label = { Text("局域网主机地址（IP:端口）") },
            placeholder = { Text("192.168.31.116:9900", fontFamily = MonoFontFamily) },
            supportingText = {
                Text("例如 192.168.31.116:9900 或完整 ws:// 地址")
            },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            colors = manualFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = viewModel.manualToken,
            onValueChange = { viewModel.manualToken = it },
            label = { Text("主机 Token") },
            placeholder = { Text("输入电脑显示的配对 Token") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            colors = manualFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (viewModel.selectedHostId == null && viewModel.formError != null) {
            Text(
                text = viewModel.formError!!,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(
            onClick = { viewModel.submitManual() },
            enabled = viewModel.pairingStatus !is PairingStatus.Pairing,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("连接")
        }
    }
}

/** 扫码连接分节（可折叠） */
@Composable
private fun ScanSection(
    viewModel: PairingViewModel,
    cameraPermissionState: CameraPermissionUiState,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    SectionCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "扫码配对",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "对准电脑终端显示的配对二维码",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = if (expanded) "收起 ▴" else "展开 ▾",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(
                modifier = Modifier.padding(top = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                if (cameraPermissionState == CameraPermissionUiState.Granted) {
                    ScanCard(viewModel)
                } else {
                    NoPermissionCard(
                        state = cameraPermissionState,
                        onRequest = onRequestPermission,
                        onOpenSettings = onOpenSettings,
                    )
                }
            }
        }
    }
}

/** 扫码卡：CameraX 预览（圆角裁切）+ ZXing 分析。 */
@Composable
private fun ScanCard(viewModel: PairingViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
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
                    image.close()
                    return
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

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(surfaceProvider)
                            }
                            val imageAnalysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                                .also { it.setAnalyzer(ContextCompat.getMainExecutor(ctx), analyzer) }
                            runCatching {
                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    imageAnalysis,
                                )
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (viewModel.pairingStatus is PairingStatus.Pairing) {
            Text(
                text = "已识别主机 · 正在验证身份",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            text = when (state) {
                CameraPermissionUiState.Requestable ->
                    "扫码需要相机权限。未授权时请改用上方局域网发现或手动直连。"
                CameraPermissionUiState.Denied ->
                    "相机权限已被拒绝，可再次授权；也可改用上方局域网发现或手动直连。"
                CameraPermissionUiState.PermanentlyDenied ->
                    "相机权限已被永久拒绝，请到系统设置中开启；也可改用下方手填连接。"
                CameraPermissionUiState.Granted -> return
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

/**
 * TS auth key 卡（折叠高级入口）：手填通道 + TS 态可视。
 * 彻底拔除“可选填 Tailscale auth key 后自动发现主机”误导性文案。
 */
@Composable
private fun TailscaleConfigCard(viewModel: PairingViewModel) {
    val isUp = viewModel.tsState is TsnetState.Up
    val isConnecting = viewModel.tsnetConnecting
    var expanded by remember {
        mutableStateOf(
            viewModel.manualTsAuthKey.isNotEmpty() ||
                viewModel.tsState !is TsnetState.Idle ||
                isConnecting,
        )
    }
    SectionCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Text(
                        text = "Tailscale 远程连接配置",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (isUp) {
                        Surface(
                            color = Color(0x2210B981),
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Text(
                                text = "● 已接入",
                                color = Color(0xFF059669),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Text(
                    text = "异地或蜂窝网络访问（高级配置）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = if (expanded) "收起 ▴" else "展开 ▾",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(
                modifier = Modifier.padding(top = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                OutlinedTextField(
                    value = viewModel.manualTsAuthKey,
                    onValueChange = { viewModel.manualTsAuthKey = it },
                    label = { Text("Tailscale Auth Key") },
                    placeholder = { Text("tskey-auth-...") },
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

                Button(
                    onClick = { viewModel.connectTailscale(viewModel.manualTsAuthKey) },
                    enabled = !isConnecting && viewModel.pairingStatus !is PairingStatus.Pairing,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isConnecting) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Text("正在接入 Tailnet...")
                        }
                    } else {
                        Text("接入 Tailnet 并搜索主机")
                    }
                }

                if (isUp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "● 已接入 Tailnet",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF059669),
                        )
                    }
                }
            }
        }
    }
}

/** TS 节点状态 → 状态行文案 */
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
                Text(
                    text = "正在验证主机身份…",
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(onClick = onRetry) { Text("重试") }
                }
                if (candidateUrls.isNotEmpty()) {
                    Text(
                        text = "候选地址（点击重试）：",
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

/** 手填输入框配色：卡内输入用更低一层底色拉开层次 */
@Composable
private fun manualFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
)

/** 扫码节流：连续帧不重复解码（同一 QR 不反复触发配对）。 */
private const val SCAN_THROTTLE_MS = 1_500L

/** 时钟泵周期（配对超时裁决节奏；与 SessionScreen 同款 100ms 泵）。 */
private const val TICK_MS = 100L
