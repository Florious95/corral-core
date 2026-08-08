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

package dev.agentmirror.app.workspace

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 工作区两级导航（需求 001 舰队视角 → 002 两级分组）。
 *
 * - 一级：工作目录列表（会话数徽章 + 聚合状态徽章），cwd 为聚合键；
 * - 二级：进入工作区后展示该 cwd 下会话列表（状态徽章，unknown 灰显不阻塞）；
 * - 点会话：路由到会话页（本任务只做占位；会话页归 session-ui 任务挂载）。
 *
 * 状态全部来自 [WorkspaceViewModel]；聚合字段是服务端权威值，本屏只渲染不重算（012）。
 */
@Composable
fun WorkspaceScreen(
    viewModel: WorkspaceViewModel,
    onOpenSession: (ref: String, name: String) -> Unit,
) {
    val state by viewModel.uiState.collectAsState()

    var selectedCwd by remember { mutableStateOf<String?>(null) }
    val selectedWorkspace = state.workspaces.firstOrNull { it.cwd == selectedCwd }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶栏：连接状态提示条（断连显示重连中，conn 层自动重连，UI 只反映状态）。
        ConnectionBar(connection = state.connection)

        when {
            state.isDisconnected -> DisconnectedContent(state)
            state.isEmpty -> EmptyGuideContent()
            selectedWorkspace == null -> WorkspaceList(
                workspaces = state.workspaces,
                onOpenWorkspace = { selectedCwd = it.cwd },
            )
            else -> SessionList(
                workspace = selectedWorkspace,
                onBack = { selectedCwd = null },
                onOpenSession = onOpenSession,
            )
        }
    }
}

/** 顶栏连接状态条：断连/重连中给提示条；就绪/连接中给细线（渲染态锚点）。 */
@Composable
private fun ConnectionBar(connection: ConnectionUi) {
    val barModifier = Modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .padding(horizontal = 16.dp, vertical = 6.dp)
    when (connection) {
        ConnectionUi.READY -> Spacer(Modifier.height(2.dp))
        ConnectionUi.CONNECTING -> Row(
            modifier = barModifier,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
            Text("连接中…", style = MaterialTheme.typography.labelMedium)
        }
        ConnectionUi.RECONNECTING -> Row(
            modifier = barModifier,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
            Text("重连中…", style = MaterialTheme.typography.labelMedium)
        }
        ConnectionUi.STOPPED -> Row(modifier = barModifier) {
            Text("连接已关闭", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** 断连态：保留最后一次已知列表，顶栏已提示重连；不阻塞浏览（004 无状态免疫）。 */
@Composable
private fun DisconnectedContent(state: WorkspaceUiState) {
    if (state.workspaces.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("正在连接主机…", style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        WorkspaceList(workspaces = state.workspaces, onOpenWorkspace = {})
    }
}

/** 空态：就绪但主机上无工作区 → 引导文案（无工作区 ≠ 错误）。 */
@Composable
private fun EmptyGuideContent() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "暂无工作区\n\n先在主机上启动一个 tmux 中的 Agent CLI，\n它会被自动纳管到这里。",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** 一级：工作目录（cwd 聚合）列表。 */
@Composable
private fun WorkspaceList(
    workspaces: List<WorkspaceUi>,
    onOpenWorkspace: (WorkspaceUi) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(workspaces, key = { it.cwd }) { ws ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenWorkspace(ws) },
            ) {
                Column {
                    WorkspaceRow(
                        cwd = ws.cwd,
                        sessionCount = ws.sessionCount,
                        aggregateState = ws.aggregateState,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                    HorizontalDivider(color = Color.Transparent)
                }
            }
        }
    }
}

/** 二级：选中 cwd 下的会话列表（ref 寻址、name 展示；unknown 灰显不阻塞）。 */
@Composable
private fun SessionList(
    workspace: WorkspaceUi,
    onBack: () -> Unit,
    onOpenSession: (ref: String, name: String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        // 二级页头：返回一级 + 当前 cwd。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "‹ 工作区",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clickable(onClick = onBack),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = workspace.cwd,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        LazyColumn(Modifier.weight(1f)) {
            items(workspace.sessions, key = { it.ref }) { s ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenSession(s.ref, s.name) },
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = s.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            StateBadge(state = s.state)
                        }
                        HorizontalDivider(color = Color.Transparent)
                    }
                }
            }
        }
    }
}
