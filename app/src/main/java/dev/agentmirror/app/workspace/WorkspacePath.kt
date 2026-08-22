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

/**
 * 工作区路径的展示名：取最后一段目录名做主标题（018 §一.3 信息层级——
 * 列表行主信息一眼可辨：用户扫读认的是「项目名」，完整路径降为辅信息单行中段省略，
 * 替代旧版整路径换四行撑爆行高的缺陷，图28 实锤）。
 *
 * 纯函数（无 Android 依赖），边界语义：
 * - 常规路径取末段：`/home/a/proj` → `proj`；
 * - 尾随斜杠不产生空段：`/home/a/proj/` → `proj`；
 * - 根目录 `/`（全斜杠）与空串无可取段 → 原样返回（不造假名）。
 */
internal fun cwdDisplayName(cwd: String): String {
    val segment = cwd.split('/').lastOrNull { it.isNotEmpty() }
    return segment ?: cwd
}
