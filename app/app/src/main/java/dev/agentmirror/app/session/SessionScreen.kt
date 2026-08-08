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

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.agentmirror.app.termview.TermSurfaceView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 会话页 Compose 屏（003 四标准的渲染壳）：终端画布 + 顶部连接条 + 底部本地输入条。
 *
 * 薄层：所有业务状态与动作在 [SessionViewModel]（纯 JVM 已测），本组合只做绑定——
 * - 终端：[TermSurfaceView] 嵌入 [AndroidView]，注入 [SessionViewModel.presenter]；
 * - 输入条：本地编辑零网络（003 第一条），发送走 VM（input_ack 必达回执）；
 * - 加号：Photo Picker（无权限弹窗）→ multipart 上传 → 主机路径注入光标处；
 * - 回执/错误/连接状态全部可见（静默失效猎杀）。
 */
@Composable
fun SessionScreen(
    viewModel: SessionViewModel,
    name: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 附件选择：Photo Picker（ActivityResultContracts.PickVisualMedia，无权限弹窗）。
    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) uploadPickedImage(context, viewModel, uri, scope)
    }

    // 时钟泵：重连调度 + 输入超时裁决 + 视口信号收敛（生产节奏，测试用假时钟）。
    LaunchedEffect(viewModel) {
        while (true) {
            viewModel.onTick(System.currentTimeMillis())
            viewModel.syncFromPresenter()
            delay(TICK_MS)
        }
    }

    // 回执/错误瞬时态自动收起（"已发送"/"已注入"短暂可见；错误多停留一会儿）。
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

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶栏：返回 + 会话名 + 连接状态条。
        TopBar(name = name, onBack = onBack, viewModel = viewModel)

        // 终端画布：占满中间区域。
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            AndroidView(
                factory = { ctx ->
                    TermSurfaceView(ctx).also { it.presenter = viewModel.presenter }
                },
                modifier = Modifier.fillMaxSize(),
            )
            // "回到底部"悬浮钮（锁定历史时出现，006 交互）。
            if (viewModel.showBackToBottom) {
                BackToBottomButton(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                    onClick = { viewModel.onScrollToBottom() },
                )
            }
        }

        // 状态区：发送回执 / 上传回执 / 协议错误（明确可见，静默失效猎杀）。
        StatusArea(viewModel)

        // 底部输入条：本地编辑零网络 + 加号附件 + 发送。
        InputBar(
            viewModel = viewModel,
            onPickImage = { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        )
    }
}

/** 顶栏：返回箭头 + 会话名 + 连接状态条。 */
@Composable
private fun TopBar(
    name: String,
    onBack: () -> Unit,
    viewModel: SessionViewModel,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ 返回") }
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
        }
        // 连接状态条：READY 时不显示；断连/重连中给明确提示（conn 层自动重连，这里只反映）。
        viewModel.connectionBanner?.let { banner ->
            Text(
                text = banner,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }
}

/** 底部输入条：加号（附件） + 草稿框 + 发送。 */
@Composable
private fun InputBar(
    viewModel: SessionViewModel,
    onPickImage: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 加号：相册/拍照（Photo Picker，无权限弹窗）→ 上传 → 路径注入。
        IconButton(
            onClick = onPickImage,
            enabled = viewModel.uploadStatus !is UploadStatus.Uploading,
        ) {
            Text("＋", style = MaterialTheme.typography.titleLarge)
        }
        OutlinedTextField(
            value = viewModel.textFieldValue,
            onValueChange = { viewModel.textFieldValue = it },
            placeholder = { Text("输入指令…") },
            modifier = Modifier.weight(1f),
            enabled = viewModel.inputStatus !is InputStatus.Sending,
            maxLines = 4,
        )
        // 发送：一次性注入并回车（本地先校验可发送性，未就绪立即报错）。
        TextButton(
            onClick = { viewModel.sendDraft() },
            enabled = viewModel.inputStatus !is InputStatus.Sending,
        ) {
            Text(
                text = if (viewModel.inputStatus is InputStatus.Sending) "…" else "发送",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/** 回执/错误状态区：发送回执、上传回执、协议/解码错误全部明确可见。 */
@Composable
private fun StatusArea(viewModel: SessionViewModel) {
    val message = when (val s = viewModel.inputStatus) {
        is InputStatus.Sent -> "已发送"
        is InputStatus.Failed -> s.message
        is InputStatus.Sending -> "发送中…"
        InputStatus.Idle -> null
    } ?: when (val u = viewModel.uploadStatus) {
        is UploadStatus.Uploading -> "上传中…"
        is UploadStatus.Success -> "已注入 ${u.path}"
        is UploadStatus.Failed -> u.message
        UploadStatus.Idle -> null
    } ?: viewModel.transientError

    if (message != null) {
        val isError = viewModel.inputStatus is InputStatus.Failed ||
            viewModel.uploadStatus is UploadStatus.Failed ||
            viewModel.transientError != null
        Text(
            text = message,
            style = MaterialTheme.typography.labelMedium,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
        )
    } else {
        Spacer(Modifier.height(2.dp))
    }
}

/** "回到底部"悬浮钮（锁定历史时点击恢复跟随，006）。 */
@Composable
private fun BackToBottomButton(
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Text(
        text = "回到底部",
        style = MaterialTheme.typography.labelLarge,
        color = Color.White,
        modifier = modifier
            .background(Color(0xB04A4A4A), shape = MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/**
 * 上传已选图片：读 URI 字节 → 构造 [Attachment] → VM 上传（URI 读取与网络都在 IO 线程，
 * VM 的 Compose 状态写入线程安全）。
 */
private fun uploadPickedImage(
    context: android.content.Context,
    viewModel: SessionViewModel,
    uri: Uri,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val resolver = context.contentResolver
    scope.launch {
        val attachment = withContext(Dispatchers.IO) {
            val name = (uri.lastPathSegment ?: "image")
                .substringAfterLast('/').takeIf { it.isNotBlank() } ?: "image"
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null) null else Attachment(name, mime, bytes)
        }
        if (attachment != null) {
            withContext(Dispatchers.IO) { viewModel.uploadAttachment(attachment) }
        }
    }
}

private const val TICK_MS = 100L
private const val TRANSIENT_MS = 1_200L
