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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.agentmirror.app.ui.theme.AgentMirrorTheme
import dev.agentmirror.app.workspace.WorkspaceScreen
import dev.agentmirror.app.workspace.WorkspaceViewModel

/**
 * Compose 应用根组合。
 *
 * 依需求 004「客户端无状态」，本组件只做路由（两级导航：舰队 → 会话，见需求 001），
 * 不持有任何业务状态；[WorkspaceViewModel] 为根级单例（接线层在此挂载 conn 层回调）。
 * 点会话跳会话页：当前为占位（会话页归 session-ui 任务挂载，替换 [SessionPagePlaceholder]）。
 */
@Composable
fun AgentMirrorApp() {
    AgentMirrorTheme {
        // 根级 ViewModel：接线层（service 任务）将把 ConnectionManager 回调接进来。
        val viewModel = remember { WorkspaceViewModel() }
        var activeSession by remember { mutableStateOf<Pair<String, String>?>(null) }
        val session = activeSession
        if (session != null) {
            // 会话页占位路由：session-ui 任务挂载正式会话页后替换此处。
            SessionPagePlaceholder(ref = session.first, name = session.second) {
                activeSession = null
            }
        } else {
            WorkspaceScreen(
                viewModel = viewModel,
                onOpenSession = { ref, name -> activeSession = ref to name },
            )
        }
    }
}

/**
 * 会话页占位路由（workspace 任务临时锚点）。
 *
 * 会话页（终端镜像 + 输入条）归 session-ui 任务挂载，本占位只保证路由链可编译、
 * 可跳转、可返回；session-ui 交付后替换此组合即可，workspace 侧不再动。
 */
@Composable
private fun SessionPagePlaceholder(
    ref: String,
    name: String,
    onBack: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "会话页占位：$name（ref=$ref）\n点击返回",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.clickable(onClick = onBack),
        )
    }
}
