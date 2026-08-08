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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.agentmirror.app.ui.theme.AgentMirrorTheme

/**
 * Compose 应用根组合。
 *
 * 骨架期仅渲染占位文案；正式导航（两级导航：舰队 → 会话，见需求 001）
 * 由 workspace 任务在 [AgentMirrorTheme] 内接管。
 */
@Composable
fun AgentMirrorApp() {
    AgentMirrorTheme {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Agent Mirror 骨架就绪",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
