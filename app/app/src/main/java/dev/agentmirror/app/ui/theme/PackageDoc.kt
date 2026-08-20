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

package dev.agentmirror.app.ui.theme

/**
 * 终端主题色板与 APP 视觉 token（M3 深浅套 + 终端 Scheme 目录 + 重着色漏斗）。
 *
 * 历史品牌深蓝见 [brandPrimary]。终端 16+fg/bg 与用户块底由 [TermPalette] 从当前
 * 主题现算（契约 089 §1）；诊断写入走 [DiagLog]，ANSI/真彩类型来自 terminal 包。
 *
 * @consumes dev.agentmirror.app.diag
 * @consumes dev.agentmirror.terminal
 */
