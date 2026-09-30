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

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.agentmirror.app.conn.BinaryFrame
import dev.agentmirror.app.conn.ConnectionManager
import dev.agentmirror.app.conn.ConnectionState
import dev.agentmirror.app.conn.FrameError
import dev.agentmirror.app.conn.FramePayload
import dev.agentmirror.app.conn.Level2Frame

/**
 * 二级菜单实时流条目（060）：服务端推的单个 pane 行。
 *
 * [ref] 是结构字段身份（socket+paneid），App 用它进三级终端——**永不来自 title**。
 * [name] 是展示名（window_name，fallback session_name）。[title] 是 pane_title **原样**
 * （一个字符都不解析/匹配/映射/美化），渲染时直接 Text(title)。
 */
data class Level2Entry(
    val ref: String,
    val name: String,
    val title: String,
    val rows: Int,
    val cols: Int,
)

/**
 * 二级菜单实时流 ViewModel（060 重建，不复活旧列表模型）。
 *
 * 消费服务端 [Level2Frame]（单个工作区会话全量快照）→ [sessions] 全量替换。标题零解析。
 * 生命周期：构造即 [ConnectionManager.subscribeLevel2]（进入二级菜单自动订阅）；[dispose]
 * 时 [ConnectionManager.unsubscribeLevel2]（离开即退订，不后台持续拉取）。
 *
 * @contract
 * @pre 构造时传入目标工作区 cwd（一级选中的工作区）
 * @post 构造即发 Level2Subscribe；收到 Level2Frame 全量替换 [sessions]；[dispose] 发
 *       Level2Unsubscribe 并停止消费
 * @err none（无关帧忽略；连接未就绪时订阅/退订静默幂等）
 * @inv [Level2Entry.title] 原样，不做任何字符串处理；[ref] 只来自结构字段
 */
class Level2ViewModel(
    private val manager: ConnectionManager,
    private val workspace: String,
) : ConnectionManager.Listener {

    /** 二级菜单会话行（服务端 Level2Frame 全量替换）。 */
    var sessions by mutableStateOf<List<Level2Entry>>(emptyList())
        private set

    init {
        manager.subscribeLevel2(workspace)
    }

    /** ConnectionManager.Listener：只消费 Level2Frame（本工作区实时流），其余帧忽略。 */
    override fun onFrame(frame: FramePayload) {
        if (frame !is Level2Frame) return
        if (frame.workspace != workspace) return // 只收本工作区流
        sessions = frame.sessions.map { s ->
            Level2Entry(
                ref = s.ref,
                name = s.name,
                title = s.title, // 原样，零加工
                rows = s.rows,
                cols = s.cols,
            )
        }
    }

    override fun onStateChanged(state: ConnectionState) = Unit
    override fun onBinary(frame: BinaryFrame) = Unit
    override fun onLocalDecodeError(code: FrameError, message: String) = Unit
    override fun onInputResult(reqId: Long, ok: Boolean, reason: String?) = Unit
    override fun onReconnect(attempt: Int, delayMs: Long) = Unit

    /** 离开二级菜单：退订实时流（不再后台拉取）。幂等。 */
    fun dispose() {
        manager.unsubscribeLevel2(workspace)
    }
}
