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

import dev.agentmirror.app.diag.DiagLog

/**
 * 打开会话全链路 PerfTrace 仪表（任务一）——**本文件仅 API 骨架**。
 *
 * 红测席只给出方法签名与测试注入钩子；方法体**不发日志、不拼事件行**。
 * 施工席负责：默认开、关时最外层短路、一行一事件 `key=value`、双出口
 * (`Log.d("PerfTrace", line)` + `DiagLog.record("PerfTrace", line)`)、
 * `open_id` 贯穿且不串、`t` 取 elapsedRealtime（⛔ 不用墙钟）。
 *
 * 八事件名即契约（顺序即链路，⛔ 不许改名）：
 * `tap` `route_enter` `subscribe_sent` `geom_seed` `first_frame_recv`
 * `snapshot_applied` `first_draw` `layout_settled`。
 *
 * @contract
 * @pre 调用方在 `isEnabled()==false` 时于最外层短路（参数不求值、字符串不拼接、
 *      不分配 lambda）。骨架本身不强制这一点。
 * @post 骨架：八个 ev 方法与 [beginOpen] 均不写出口；[isEnabled] 反映
 *       [setEnabledForTest] 写入的开关。实现后：开时一行一事件，关时零行。
 * @err none（公开方法不抛）
 * @inv 骨架不分配常驻线程/定时器；实现亦 ⛔ 不许为 layout_settled 起常驻定时器
 */
object PerfTrace {

    const val TAG = "PerfTrace"

    /** 进程启动读取的系统属性：`0` = 关。默认开（未设或非 0）。 */
    const val PROP_ENABLED = "debug.agentmirror.perftrace"

    const val EV_TAP = "tap"
    const val EV_ROUTE_ENTER = "route_enter"
    const val EV_SUBSCRIBE_SENT = "subscribe_sent"
    const val EV_GEOM_SEED = "geom_seed"
    const val EV_FIRST_FRAME_RECV = "first_frame_recv"
    const val EV_SNAPSHOT_APPLIED = "snapshot_applied"
    const val EV_FIRST_DRAW = "first_draw"
    const val EV_LAYOUT_SETTLED = "layout_settled"

    /** `layout_settled` 静默窗口（ms）：最后一次重排后再无重排才结算。 */
    const val LAYOUT_SETTLED_QUIET_MS = 500

    /**
     * 假出口（去 Android Log / 真机 DiagLog）。测试注入；生产由施工席接到双出口。
     * @contract
     * @pre tag 非空；line 为已格式化的一行（骨架不会调用）
     * @post 实现后每条事件恰好一次 emit
     * @err none
     * @inv 无
     */
    fun interface Sink {
        fun emit(tag: String, line: String)
    }

    @Volatile
    private var enabled: Boolean = true

    /** 单调时钟；测试注入。生产实现应读 [android.os.SystemClock.elapsedRealtime]。 */
    @Volatile
    private var clock: DiagLog.Clock = DiagLog.Clock { 0L }

    @Volatile
    private var sink: Sink = Sink { _, _ -> }

    /**
     * 开关查询（调用点最外层短路用）。
     *
     * @contract
     * @pre none
     * @post 返回当前缓存的开关（默认 true；[setEnabledForTest] 可改）
     * @err none
     * @inv 不读系统属性、不 I/O（进程启动读一次是施工席的事）
     */
    fun isEnabled(): Boolean = enabled

    /**
     * 测试注入开关（替代 `adb shell setprop debug.agentmirror.perftrace 0`）。
     *
     * @contract
     * @pre none
     * @post [isEnabled] 等于 [value]
     * @err none
     * @inv 不发日志
     */
    fun setEnabledForTest(value: Boolean) {
        enabled = value
    }

    /**
     * 测试注入假时钟（[DiagLog.Clock.nowMs] 在此表示 elapsedRealtime ms，不是墙钟）。
     *
     * @contract
     * @pre none
     * @post 后续事件的 `t=` 必须取自该时钟（骨架不读它）
     * @err none
     * @inv 不发日志
     */
    fun setClockForTest(c: DiagLog.Clock) {
        clock = c
    }

    /**
     * 测试注入假出口。
     *
     * @contract
     * @pre none
     * @post 实现后每条事件走该出口；骨架不调用
     * @err none
     * @inv 不发日志
     */
    fun setSinkForTest(s: Sink) {
        sink = s
    }

    /**
     * 测试复位：默认开、时钟归零、出口变空操作。不泄漏单例状态到下一用例。
     *
     * @contract
     * @pre none
     * @post enabled=true；clock 恒 0；sink 空操作
     * @err none
     * @inv 不发日志
     */
    fun resetForTest() {
        enabled = true
        clock = DiagLog.Clock { 0L }
        sink = Sink { _, _ -> }
    }

    /**
     * 一次打开生成一个 `open_id`，贯穿后续 1..8 事件。
     *
     * @contract
     * @pre none
     * @post 骨架返回空串且不发日志。实现后：短随机 id，两次调用不重复。
     * @err none
     * @inv 骨架不写出口
     */
    fun beginOpen(): String = ""

    /**
     * 事件 `tap`（用户点击会话行）。
     * @contract @pre [openId] 来自同一次 [beginOpen] @post 骨架不发日志 @err none @inv 无
     */
    fun tap(openId: String) {}

    /**
     * 事件 `route_enter`（会话页路由进入）。
     * @contract @pre [openId] 来自同一次 [beginOpen] @post 骨架不发日志 @err none @inv 无
     */
    fun routeEnter(openId: String) {}

    /**
     * 事件 `subscribe_sent`（订阅帧发出）。
     * @contract @pre [openId] 来自同一次 [beginOpen] @post 骨架不发日志 @err none @inv 无
     */
    fun subscribeSent(openId: String) {}

    /**
     * 事件 `geom_seed`，必须带 `rows=` `cols=`。
     * @contract @pre [openId] 来自同一次 [beginOpen] @post 骨架不发日志 @err none @inv 无
     */
    fun geomSeed(openId: String, rows: Int, cols: Int) {}

    /**
     * 事件 `first_frame_recv`，必须带 `kind=` `bytes=`。
     * @contract @pre [openId] 来自同一次 [beginOpen] @post 骨架不发日志 @err none @inv 无
     */
    fun firstFrameRecv(openId: String, kind: String, bytes: Int) {}

    /**
     * 事件 `snapshot_applied`，必须带 `seq=` `alt=`。
     * @contract @pre [openId] 来自同一次 [beginOpen] @post 骨架不发日志 @err none @inv 无
     */
    fun snapshotApplied(openId: String, seq: Long, alt: Int) {}

    /**
     * 事件 `first_draw`，必须带 `glyphs=`（>0 才算首绘）。
     * @contract @pre [openId] 来自同一次 [beginOpen] @post 骨架不发日志 @err none @inv 无
     */
    fun firstDraw(openId: String, glyphs: Int) {}

    /**
     * 事件 `layout_settled`，必须带 `quiet_ms=` 与 `last_reflow_src=`。
     * @contract @pre [openId] 来自同一次 [beginOpen] @post 骨架不发日志 @err none @inv 无
     */
    fun layoutSettled(openId: String, quietMs: Int, lastReflowSrc: String) {}

    /**
     * 骨架保留对注入字段的读取，避免编译器把测试钩子判成死存储。
     * ⛔ 不构成发日志；施工席实现 ev 方法时走这里取 t 与出口。
     */
    @Suppress("unused")
    internal fun clockNowMsForImpl(): Long = clock.nowMs()

    @Suppress("unused")
    internal fun sinkForImpl(): Sink = sink
}
