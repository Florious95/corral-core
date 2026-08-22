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

package dev.agentmirror.app.perf

/**
 * 打开会话全链路性能仪表。
 *
 * [PerfTrace] 是唯一入口：一行一事件，双出口（logcat tag `PerfTrace` + [dev.agentmirror.app.diag.DiagLog]）。
 * 调用方：列表/收藏/悬浮窗点开会话（tap）、[dev.agentmirror.app.session.SessionRoute]
 * （route_enter）、[dev.agentmirror.app.conn.ConnectionManager]（subscribe_sent / geom_seed /
 * layout_settled 的重排）、[dev.agentmirror.app.session.SessionViewModel]（first_frame_recv /
 * snapshot_applied）、[dev.agentmirror.app.termview.TermSurfaceView]（first_draw）。
 *
 * @consumes dev.agentmirror.app.diag
 */
object PackageDoc
