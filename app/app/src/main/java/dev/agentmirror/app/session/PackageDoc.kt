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

package dev.agentmirror.app.session

/**
 * 会话页：单个 tmux 会话的交互界面。
 *
 * 组合终端渲染（termview）与输入下发（conn），承载缩放、手势与快捷输入条；
 * 图片附件走 multipart HTTP 上传（上传基地址由 service 装配的 ServiceWire 统一注入），
 * 跨层共享连接经 service 的 ServiceWire.uiConnector 扇出订阅。会话页已完整落位：
 * 镜像流（snapshot/delta/scrollback 本地滚动补页）、发送必达回执、附件路径注入光标处。
 *
 * @consumes dev.agentmirror.app.conn
 * @consumes dev.agentmirror.app.service
 * @consumes dev.agentmirror.app.termview
 * @consumes dev.agentmirror.app.tsnet
 * @consumes dev.agentmirror.app.ui.components
 * @consumes dev.agentmirror.app.ui.model
 * @consumes dev.agentmirror.app.ui.screens
 * @consumes dev.agentmirror.app.ui.theme
 * @consumes dev.agentmirror.app.workspace
 * @consumes dev.agentmirror.terminal
 * @consumes dev.agentmirror.app.diag
 */
