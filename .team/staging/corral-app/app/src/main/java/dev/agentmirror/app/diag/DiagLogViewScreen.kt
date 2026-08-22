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

package dev.agentmirror.app.diag

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.agentmirror.app.ui.theme.Spacing

/**
 * 诊断日志 App 内展示页（feat-diag-inapp-view）：用户在 App 里就能看到、复制日志文本，
 * 粘进跟我们的对话——不用走「导出文件 → 传出去」那条在手机上很别扭的路径。
 *
 * 成败判据：十秒内把最近这段日志粘出来。「复制全部」是主路径（一按进剪贴板，
 * 不用手动选字）；文本可选中（[SelectionContainer]）是兜底，用于只想复制一小段的场景。
 *
 * 资源有界（红线）：环形缓冲上限 4096 条，但本页**只取最近 [maxRendered] 条**（见
 * [DiagLog.recentLines]），永远不会把整个缓冲塞进一个文本控件——逐行 [LazyColumn]，
 * 屏外行不参与合成/布局。复制按钮复制的也是这同一批（"复制全部"＝复制当前页展示的
 * 全部，而不是复制整个 4096 条环形缓冲），copy 与 render 用同一份数据，语义一致、
 * 复制操作本身零额外读缓冲。总条数与"更早"提示见 [DiagLogViewState]。
 *
 * 静默经济：打开时读一次（[remember]/[LaunchedEffect] 式一次性副作用），无定时器无轮询。
 *
 * @contract
 * @pre none
 * @post 渲染最近 min(totalCount, maxRendered) 条已脱敏文本，可选中；复制按钮把同一批
 *       文本以 export 同款换行格式写入系统剪贴板
 * @err none（剪贴板写入由系统 API 保证；空缓冲显示提示文案，不报错）
 * @inv 单次组合读取一次 [DiagLog]（不订阅、不刷新）；渲染与复制的数据来源都是
 *      [DiagLog.recentLines]，不新增任何绕过写入点脱敏的读取路径
 *
 * @consumes dev.agentmirror.app.ui.theme
 */
@Composable
internal fun DiagLogViewScreen(
    onBack: () -> Unit,
    maxRendered: Int = DEFAULT_MAX_RENDERED,
) {
    BackHandler(onBack = onBack)
    val clipboard = LocalClipboardManager.current
    val state = remember { loadDiagLogViewState(maxRendered) }
    var copyStatus by remember { mutableStateOf<String?>(null) }

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
            TextButton(onClick = onBack) { Text("‹ 设置") }
            Text(
                text = "诊断日志",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = Spacing.sm),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.pageH, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = state.hintText(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = {
                    clipboard.setText(AnnotatedString(state.displayText))
                    copyStatus = "已复制 ${state.renderedLines.size} 条到剪贴板"
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("复制全部")
            }
            copyStatus?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = Spacing.pageH)
                .navigationBarsPadding(),
        ) {
            if (state.renderedLines.isEmpty()) {
                Text(
                    text = "当前没有日志（尚未产生任何事件）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Spacing.lg),
                )
            } else {
                SelectionContainer {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.renderedLines) { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 默认渲染条数（4096 满缓冲下的性能实测见 DiagLogViewScreenTest；建议区间 200~500）。 */
internal const val DEFAULT_MAX_RENDERED = 300

/**
 * 展示页数据快照（纯数据，无 UI 依赖，测试直接构造/断言）。
 * @contract
 * @pre none
 * @post totalCount ≥ renderedLines.size；displayText 是 renderedLines 按 export 同款换行拼接
 * @err none
 * @inv renderedLines 恒为 [DiagLog] 最新的连续尾段（时间序）
 */
internal data class DiagLogViewState(
    val totalCount: Int,
    val renderedLines: List<String>,
) {
    val displayText: String get() = renderedLines.joinToString("\n")

    fun hintText(): String {
        val omitted = totalCount - renderedLines.size
        return if (omitted > 0) {
            "共 $totalCount 条，展示最近 ${renderedLines.size} 条（更早的 $omitted 条未展示）"
        } else {
            "共 $totalCount 条"
        }
    }
}

/** 单次读取（静默经济：打开页面读一次，不订阅不刷新）。 */
internal fun loadDiagLogViewState(maxRendered: Int = DEFAULT_MAX_RENDERED): DiagLogViewState =
    DiagLogViewState(
        totalCount = DiagLog.size(),
        renderedLines = DiagLog.recentLines(maxRendered),
    )
