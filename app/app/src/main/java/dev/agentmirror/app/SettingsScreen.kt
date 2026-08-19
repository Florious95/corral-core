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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import androidx.core.content.FileProvider
import dev.agentmirror.app.diag.DiagLog
import dev.agentmirror.app.diag.DiagLogViewScreen
import dev.agentmirror.app.pairing.SharedPreferencesPairingConfigStore
import dev.agentmirror.app.termview.SharedPreferencesFontSizeStore
import dev.agentmirror.app.ui.theme.Appearance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import dev.agentmirror.app.ui.screens.SettingsScreen as DesignSettingsScreen

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
    enableBackHandler: Boolean = true,
    appearance: Appearance = Appearance.System,
    onAppearanceChange: (Appearance) -> Unit = {},
) {
    var showDiagView by remember { mutableStateOf(false) }
    if (showDiagView) {
        DiagLogViewScreen(onBack = { showDiagView = false })
        return
    }
    BackHandler(enabled = enableBackHandler, onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fontSizeStore = remember { SharedPreferencesFontSizeStore(context) }
    var fontSizeSp by remember {
        mutableIntStateOf(fontSizeStore.load() ?: SharedPreferencesFontSizeStore.DEFAULT_FONT_SIZE_SP)
    }
    val paired = remember { SharedPreferencesPairingConfigStore(context).load() != null }
    val buildLabel = remember {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.versionName ?: context.packageName
    }
    DesignSettingsScreen(
        paired = paired,
        terminalFontSize = fontSizeSp,
        appearance = appearance,
        buildLabel = buildLabel,
        onRepair = onRePair,
        onFontSizeChange = { sp ->
            fontSizeSp = sp
            fontSizeStore.save(sp)
        },
        onAppearanceChange = onAppearanceChange,
        onExportLogs = {
            scope.launch {
                val result = withContext(Dispatchers.IO) { exportDiagLog(context) }
                when (result) {
                    is ExportOutcome.Success -> shareFile(context, result.file)
                    is ExportOutcome.Failed, is ExportOutcome.Empty -> Unit
                }
            }
        },
        onViewLogs = { showDiagView = true },
    )
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
    // round2 缺口修复：导出前先注入 storageDir，让 exportTo 内部的轮转（pruneExports 经
    // listExports）能看到真实导出目录并清理最旧——否则 storageDir 为 null、listExports 空、
    // 上限永远不生效（导出目录线性增长不收敛）。initialize 幂等，重复导出不重置缓冲。
    DiagLog.initialize(dir.path)
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
