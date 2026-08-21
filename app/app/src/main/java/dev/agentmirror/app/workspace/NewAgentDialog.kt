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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/**
 * 新建 Agent 弹层：选工作区 / Provider / Bypass。
 *
 * @contract
 * @pre ui 非空时已组合
 * @post 点确认只回调 onConfirm；未确认不发帧
 * @err none
 * @inv Pi 的 Bypass 勾选不改变 argv（无旗）
 */
@Composable
fun NewAgentDialog(
    ui: WorkspaceViewModel.NewAgentUi,
    onSelectCwd: (String) -> Unit,
    onSelectProvider: (String) -> Unit,
    onToggleBypass: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!ui.inFlight) onCancel() },
        modifier = Modifier.testTag("new-agent-dialog"),
        title = { Text("新建 Agent") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("工作区")
                ui.cwds.forEach { cwd ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelectCwd(cwd) }
                            .testTag("new-agent-cwd"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (cwd == ui.cwd) "● $cwd" else "○ $cwd")
                    }
                }
                Text("Provider")
                NewAgentProviders.ids.forEach { id ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelectProvider(id) }
                            .testTag("new-agent-provider-$id"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (id == ui.providerId) "● ${NewAgentProviders.displayName(id)}"
                            else "○ ${NewAgentProviders.displayName(id)}",
                        )
                    }
                }
                val pi = ui.providerId == "pi"
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !pi) { onToggleBypass(!ui.bypass) }
                        .testTag("new-agent-bypass"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = ui.bypass && !pi,
                        onCheckedChange = { if (!pi) onToggleBypass(it) },
                        enabled = !pi,
                    )
                    Text("Bypass 权限")
                }
                val err = ui.error
                if (!err.isNullOrEmpty()) {
                    Text(err)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !ui.inFlight && ui.cwd.isNotEmpty() && ui.providerId.isNotEmpty(),
                modifier = Modifier.testTag("new-agent-ok"),
            ) { Text("创建") }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.testTag("new-agent-cancel"),
            ) { Text("取消") }
        },
    )
}
