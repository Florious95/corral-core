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

package dev.agentmirror.app.service

/**
 * 前台服务：常驻连接 + 通知栏（需求 004 Android 前台服务路线）。
 *
 * 承载与主机 sidecar 的长连接生命周期，系统杀进程后由 Activity 重连恢复
 * （客户端无状态，冷启动 1 秒内恢复画面）。本包为占位骨架，由 service 任务落位实现。
 */
