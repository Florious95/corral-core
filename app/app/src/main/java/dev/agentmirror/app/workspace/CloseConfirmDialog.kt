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

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/**
 * 关闭会话二次确认（契约 088 E12）。未确认不得发帧。
 *
 * @contract
 * @pre ui 非空时已组合
 * @post 点「关闭」只回调 onConfirm；点「取消」只回调 onCancel
 * @err none
 * @inv 正文含「不可恢复」与 ui.displayName；未确认不发帧
 */
@Composable
fun CloseConfirmDialog(
    ui: WorkspaceViewModel.CloseConfirmUi,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val body = buildString {
        append("关闭「")
        append(ui.displayName)
        append("」不可恢复，未保存内容会丢失。")
        val err = ui.error
        if (!err.isNullOrEmpty()) {
            append('\n')
            append(err)
        }
    }
    AlertDialog(
        onDismissRequest = { if (!ui.inFlight) onCancel() },
        modifier = Modifier.testTag("close-confirm"),
        title = { Text("关闭会话") },
        text = { Text(body) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !ui.inFlight,
                modifier = Modifier.testTag("close-confirm-ok"),
            ) { Text("关闭") }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.testTag("close-confirm-cancel"),
            ) { Text("取消") }
        },
    )
}
