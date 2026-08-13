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

package dev.agentmirror.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dev.agentmirror.app.diag.DiagLog
import dev.agentmirror.app.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 单档设置页：重新配对成功时覆盖现有主机配置，不提前清除仍可用的档案。
 *
 * 诊断日志导出（feat-diagnostic-log-export）：一键导出按钮——把内存环形缓冲倾倒到
 * 文件并经系统分享（FileProvider）发出去。用户复现完缺陷、烦躁时点一下就能把日志发
 * 给我们，交互最短。失败可见：导出失败 / 无内容都在界面明确提示，绝不静默。
 */
@Composable
internal fun SettingsScreen(
    onBack: () -> Unit,
    onRePair: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportStatus by remember { mutableStateOf<String?>(null) }
    var exportError by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .defaultMinSize(minHeight = 56.dp)
                .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ 工作区") }
            Text(
                text = "设置",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = Spacing.sm),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.pageH)
                .navigationBarsPadding(),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    Text("主机配对", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "当前只保留一个主机档案。重新配对成功后会覆盖现有档案。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onRePair, modifier = Modifier.fillMaxWidth()) {
                        Text("重新配对")
                    }
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    Text("诊断日志", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "一键导出诊断日志，帮助我们定位问题。日志会自动脱敏（配对 token、密钥等不会包含）。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = {
                            exportStatus = null
                            exportError = null
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { exportDiagLog(context) }
                                when (result) {
                                    is ExportOutcome.Success -> {
                                        shareFile(context, result.file)
                                        exportStatus = "已导出 ${result.bytes} 字节，正在分享…"
                                    }
                                    is ExportOutcome.Failed -> exportError = result.reason
                                    is ExportOutcome.Empty -> exportStatus = "当前没有日志可导出（尚未产生任何事件）"
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("导出诊断日志")
                    }
                    exportStatus?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    exportError?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

/** 导出结果（失败可见红线：导出必须可判定）。 */
private sealed interface ExportOutcome {
    data class Success(val file: File, val bytes: Int) : ExportOutcome
    data class Failed(val reason: String) : ExportOutcome
    data object Empty : ExportOutcome
}

/**
 * 把环形缓冲倾倒到 filesDir/diag/ 下的临时文件（导出用独立文件，不污染日志缓冲）。
 * @contract
 * @pre 无
 * @post 成功返回 Success(file)；缓冲为空返回 Empty；失败返回 Failed（不抛）
 * @err none（导出失败折叠为 Failed）
 * @inv 导出文件字节 ≤ DiagLog 磁盘上限；内容已脱敏（写入点脱敏）
 */
private fun exportDiagLog(context: android.content.Context): ExportOutcome {
    val dir = File(context.filesDir, DiagLog.DEFAULT_STORAGE_DIR).apply { mkdirs() }
    val file = File(dir, "diag-${System.currentTimeMillis()}.log")
    return when (val r = DiagLog.exportTo(file)) {
        is DiagLog.ExportResult.Success -> {
            if (r.lines == 0) ExportOutcome.Empty
            else ExportOutcome.Success(file, r.bytes)
        }
        is DiagLog.ExportResult.Failed -> ExportOutcome.Failed("导出失败：${r.reason}")
    }
}

/** 经系统分享把导出的日志文件发出去（FileProvider content URI，用户选目标 App）。 */
private fun shareFile(context: android.content.Context, file: File) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "分享诊断日志"))
}
