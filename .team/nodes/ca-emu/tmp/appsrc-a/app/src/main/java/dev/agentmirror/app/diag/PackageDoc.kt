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

package dev.agentmirror.app.diag

/**
 * 诊断日志（feat-diagnostic-log-export）：把用户真机复现一次变成一份可分析证据。
 *
 * 用户 2026-08-14 裁定「测试链路必须先抓到真实缺陷，抓不到就不许改代码；抓不到的时候
 * 出路是加日志」。本包承载那条取证链路：环形缓冲（内存有界）+ 落盘转储（磁盘有界）
 * + 写入点脱敏 + 设置页一键导出。判据：**光看导出的日志，就能算出「最右列超出屏幕
 * 几个像素」、看出 tsnet 状态停在 Up 而 SOCKS 拨号在失败**，不需要再问用户要截图。
 *
 * 设计红线：
 * - **凭据脱敏在写入点**（[DiagLog.record] 内），不是导出时过滤——导出时过滤会漏掉
 *   内存环形缓冲里的原文。配对 token / TS authkey / Bearer 头经 [DiagLog.registerSecret]
 *   注册后，任何一条记录在落缓冲前就被替换为 [REDACTED]。
 * - **静默经济**：本包零线程零定时器，[DiagLog.record] 由既有事件流驱动（tsnet 状态
 *   迁移 / SOCKS 拨号 / WS 连接 / 上传 / 栅格几何），空闲时零 CPU 零分配。
 * - **资源有界**：内存环形缓冲固定容量（默认 4096 条，写满覆盖最旧）；落盘文件字节
 *   上限（默认 1 MiB），超限截断最旧行。两者都可注入配置。
 * - **失败可见**：导出必须返回可判定的结果（条数/字节/错误），不许静默失败。
 *
 * 消费方（各自 KDoc 声明 @consumes dev.agentmirror.app.diag）：tsnet（状态迁移 +
 * SOCKS 拨号）、conn（WS 连接/断开）、session（上传）、termview（渲染栅格几何）、
 * ui/settings（导出入口）、app 根（前后台生命周期）。
 */
