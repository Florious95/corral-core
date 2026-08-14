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

package dev.agentmirror.app.termview

/**
 * 终端渲染：快照/增量渲染 + 本地滚动视口（60fps，需求 006）。
 *
 * [TermViewPresenter] 纯 JVM 视口状态机（跟随/锁定历史、可见行窗口、字号→行列数换算、
 * 脏区合并），单测全部打在它上；[TermSurfaceView] 薄 Android 层（Canvas 画格、拖动手势、
 * Choreographer 帧调度）。内核为 :terminal 模块；resize 协议帧由上层接线（conn/session）。
 *
 * @consumes dev.agentmirror.app.diag
 * @consumes dev.agentmirror.terminal
 */
