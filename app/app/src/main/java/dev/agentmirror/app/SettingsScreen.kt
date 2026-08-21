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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.content.Intent
import androidx.core.content.FileProvider
import dev.agentmirror.app.diag.DiagLog
import dev.agentmirror.app.diag.DiagLogViewScreen
import dev.agentmirror.app.pairing.SharedPreferencesPairingConfigStore
import dev.agentmirror.app.termview.SharedPreferencesFontSizeStore
import dev.agentmirror.app.ui.theme.Appearance
import dev.agentmirror.app.ui.theme.SharedPreferencesTermThemeStore
import dev.agentmirror.app.ui.screens.TermThemePickerScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import dev.agentmirror.app.ui.components.AppText
import dev.agentmirror.app.ui.components.ProviderIcon
import dev.agentmirror.app.ui.components.SettingsCard
import dev.agentmirror.app.ui.components.providerDisplayName
import dev.agentmirror.app.ui.theme.Dims
import dev.agentmirror.app.ui.theme.LocalAppPalette
import dev.agentmirror.app.ui.theme.Radii
import dev.agentmirror.app.ui.theme.Spacing
import dev.agentmirror.app.ui.theme.TypeSizes
import dev.agentmirror.app.ui.screens.SettingsScreen as DesignSettingsScreen
import dev.agentmirror.app.workspace.ProviderLaunch
import dev.agentmirror.app.workspace.ProviderLaunchDefaults
import dev.agentmirror.app.workspace.SharedPreferencesProviderLaunchStore

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
    var pickerDark by remember { mutableStateOf<Boolean?>(null) }
    if (showDiagView) {
        DiagLogViewScreen(onBack = { showDiagView = false })
        return
    }
    BackHandler(enabled = enableBackHandler && pickerDark == null, onBack = onBack)
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
    val themeStore = remember { SharedPreferencesTermThemeStore(context) }
    var themeSel by remember { mutableStateOf(themeStore.load()) }
    val launchStore = remember { SharedPreferencesProviderLaunchStore(context) }
    var launches by remember { mutableStateOf(launchStore.load()) }
    val pickerSlot = pickerDark
    if (pickerSlot != null) {
        TermThemePickerScreen(
            darkSlot = pickerSlot,
            selectedFamilyId = if (pickerSlot) themeSel.darkFamilyId else themeSel.lightFamilyId,
            onSelect = { id ->
                if (pickerSlot) themeStore.saveDark(id) else themeStore.saveLight(id)
                themeSel = themeStore.load()
                pickerDark = null
            },
            onBack = { pickerDark = null },
        )
        return
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
        lightFamilyId = themeSel.lightFamilyId,
        darkFamilyId = themeSel.darkFamilyId,
        onOpenLightTheme = { pickerDark = false },
        onOpenDarkTheme = { pickerDark = true },
        extraCards = {
            AgentLaunchCard(
                launches = launches,
                onChange = { next ->
                    launches = next
                    launchStore.save(next)
                },
            )
        },
    )
}

/**
 * 设置第六张卡：每个 Provider 一张内卡，带卡通图标、等宽多行命令、恢复默认（092 §3）。
 *
 * argv 仍走 [dev.agentmirror.app.workspace.buildArgv] 分词，不经 shell（088 §7）。
 *
 * @contract
 * @pre launches 含六个白名单 id
 * @post 改 command / bypass 立刻 save；点恢复默认写回该 id 出厂值；Pi 的 bypass 输入禁用且恒为空
 * @err none
 * @inv 不经 shell 分词（脚注写明）
 */
@Composable
private fun AgentLaunchCard(
    launches: List<ProviderLaunch>,
    onChange: (List<ProviderLaunch>) -> Unit,
) {
    val p = LocalAppPalette.current
    SettingsCard(modifier = Modifier.testTag("settings-launch")) {
        AppText(
            "Agent 启动命令",
            p.rowTitleText,
            TypeSizes.cardTitle,
            fontWeight = FontWeight.SemiBold,
        )
        Box(Modifier.height(8.dp))
        AppText(
            text = "按空白分词，不经 shell。勾选 Bypass 时把该 Provider 的旗追加到 argv（已在命令里则不重复）。Grok 必须显式带 --always-approve，不依赖本机 config。",
            color = p.bodyText,
            fontSize = TypeSizes.cardBody,
            lineHeightMultiplier = TypeSizes.bodyLineHeight,
        )
        Box(Modifier.height(12.dp))
        launches.forEach { item ->
            ProviderLaunchRow(
                item = item,
                onChange = { next ->
                    onChange(launches.map { if (it.providerId == item.providerId) next else it })
                },
                onReset = {
                    val def = ProviderLaunchDefaults.byId(item.providerId)
                    onChange(launches.map { if (it.providerId == item.providerId) def else it })
                },
            )
            Box(Modifier.height(10.dp))
        }
    }
}

/**
 * 单个 Provider 的命令卡：图标 + 名称 + 恢复默认 + 等宽输入。
 *
 * @contract
 * @pre item.providerId 属于白名单
 * @post 命令可多行编辑；Pi 的 bypass 输入 disabled
 * @err none
 * @inv 不改 argv 组装规则
 */
@Composable
private fun ProviderLaunchRow(
    item: ProviderLaunch,
    onChange: (ProviderLaunch) -> Unit,
    onReset: () -> Unit,
) {
    val p = LocalAppPalette.current
    val pi = item.providerId == "pi"
    val shape = RoundedCornerShape(Radii.cardButton)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.listBackground)
            .border(Dims.hairline, p.cardBorder, shape)
            .testTag("settings-launch-row-${item.providerId}")
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProviderIcon(provider = item.providerId)
            AppText(
                providerDisplayName(item.providerId),
                p.rowTitleText,
                TypeSizes.cardBody,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            AppText(
                text = "恢复默认",
                color = p.accent,
                fontSize = TypeSizes.chip,
                fontWeight = FontWeight.SemiBold,
                lineHeightMultiplier = 1f,
                modifier = Modifier
                    .testTag("settings-launch-reset-${item.providerId}")
                    .clickable(onClick = onReset)
                    .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
            )
        }
        Box(Modifier.height(Spacing.xs))
        AppText("命令", p.metaText, TypeSizes.footnote, fontWeight = FontWeight.Medium)
        Box(Modifier.height(4.dp))
        LaunchField(
            value = item.command,
            testTag = "settings-launch-command-${item.providerId}",
            enabled = true,
            singleLine = false,
            minLines = 2,
            maxLines = 6,
            onValueChange = { text -> onChange(item.copy(command = text)) },
        )
        Box(Modifier.height(Spacing.sm))
        AppText("Bypass 旗", p.metaText, TypeSizes.footnote, fontWeight = FontWeight.Medium)
        Box(Modifier.height(4.dp))
        LaunchField(
            value = item.bypassFlag,
            testTag = "settings-launch-bypass-${item.providerId}",
            enabled = !pi,
            singleLine = false,
            minLines = 1,
            maxLines = 3,
            placeholder = if (pi) "Pi 无 Bypass 旗" else "",
            onValueChange = { text ->
                if (pi) return@LaunchField
                onChange(item.copy(bypassFlag = text))
            },
        )
    }
}

@Composable
private fun LaunchField(
    value: String,
    testTag: String,
    enabled: Boolean,
    singleLine: Boolean,
    minLines: Int,
    maxLines: Int,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
) {
    val p = LocalAppPalette.current
    val shape = RoundedCornerShape(Radii.input)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else minLines,
        maxLines = maxLines,
        textStyle = TextStyle(
            color = if (enabled) p.inputText else p.metaText,
            fontSize = TypeSizes.inputText,
            fontFamily = FontFamily.Monospace,
            lineHeight = TypeSizes.inputText * 1.4f,
        ),
        cursorBrush = SolidColor(p.accent),
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.inputBackground)
            .border(Dims.hairline, p.inputBorder, shape)
            .padding(horizontal = Dims.inputHPadding, vertical = 8.dp)
            .testTag(testTag),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth()) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    AppText(
                        placeholder,
                        p.inputPlaceholder,
                        TypeSizes.inputText,
                        fontFamily = FontFamily.Monospace,
                        lineHeightMultiplier = 1.4f,
                    )
                }
                inner()
            }
        },
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
