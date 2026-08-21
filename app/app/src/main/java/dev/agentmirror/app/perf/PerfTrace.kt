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

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.agentmirror.app.diag.DiagLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 打开会话全链路 PerfTrace 仪表（任务一）。
 *
 * 一行一事件，`key=value` 空格分隔，首三字段固定
 * `open_id=<短随机id> ev=<事件名> t=<elapsedRealtime ms>`（⛔ 不用墙钟）。
 * 双出口：`Log.d("PerfTrace", line)` + `DiagLog.record("PerfTrace", line)`。
 *
 * 关：`adb shell setprop debug.agentmirror.perftrace 0`，进程启动读一次。
 * 调用方须在最外层 `if (PerfTrace.isEnabled())` 短路——参数不求值、不拼串、不分配 lambda。
 *
 * 八事件名即契约：`tap` `route_enter` `subscribe_sent` `geom_seed`
 * `first_frame_recv` `snapshot_applied` `first_draw` `layout_settled`。
 *
 * @contract
 * @pre 调用方在 `isEnabled()==false` 时于最外层短路
 * @post 开：一行一事件走双出口；关：零行零分配（方法体首行 return）
 * @err none（公开方法不抛）
 * @inv 不为 layout_settled 起常驻定时器/协程；重排取消未发出的一次性延迟消息
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
     * 假出口（去 Android Log / 真机 DiagLog）。测试注入；生产接到双出口。
     * @contract
     * @pre tag 非空；line 为已格式化的一行
     * @post 每条事件恰好一次 emit
     * @err none
     * @inv 无
     */
    fun interface Sink {
        fun emit(tag: String, line: String)
    }

    private val productionSink = Sink { tag, line ->
        Log.d(tag, line)
        DiagLog.record(tag, line)
    }

    @Volatile
    private var enabled: Boolean = readProcessProp()

    /** 单调时钟；测试注入。生产读 [SystemClock.elapsedRealtime]。 */
    @Volatile
    private var clock: DiagLog.Clock = DiagLog.Clock { SystemClock.elapsedRealtime() }

    @Volatile
    private var sink: Sink = productionSink

    private val nextSeq = AtomicLong(1L)
    private val opens = ConcurrentHashMap<String, Open>()
    private val settleRunnables = ConcurrentHashMap<String, Runnable>()
    private val settleHandler: Handler? = try {
        Looper.getMainLooper()?.let { Handler(it) }
    } catch (_: Throwable) {
        null
    }

    private class Open(val openId: String) {
        @Volatile var routeEntered: Boolean = false
        @Volatile var subscribeSent: Boolean = false
        @Volatile var geomSeeded: Boolean = false
        @Volatile var firstFrame: Boolean = false
        @Volatile var snapshotApplied: Boolean = false
        @Volatile var firstDraw: Boolean = false
        @Volatile var settled: Boolean = false
        @Volatile var lastReflowSrc: String = "none"
        @Volatile var settleGen: Int = 0
        val snapshotSeq = AtomicLong(0L)
    }

    /**
     * 开关查询（调用点最外层短路用）。
     *
     * @contract
     * @pre none
     * @post 返回当前缓存的开关（默认 true；prop=0 或 [setEnabledForTest] 可关）
     * @err none
     * @inv 不读系统属性、不 I/O
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
     * @post 后续事件的 `t=` 必须取自该时钟
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
     * @post 每条事件走该出口
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
     * @post enabled=true；clock 恒 0；sink 空操作；打开簿记清空
     * @err none
     * @inv 不发日志
     */
    fun resetForTest() {
        cancelAllSettles()
        opens.clear()
        enabled = true
        clock = DiagLog.Clock { 0L }
        sink = Sink { _, _ -> }
        nextSeq.set(1L)
    }

    /**
     * 一次打开生成一个 `open_id`，贯穿后续 1..8 事件。
     *
     * @contract
     * @pre none
     * @post 关：空串且不发日志。开：短随机 id，两次调用不重复。
     * @err none
     * @inv 关路径不分配
     */
    fun beginOpen(): String {
        if (!enabled) return ""
        // 不用注入时钟：红测要求第一条 ev 的 t= 取时钟初值，beginOpen 读时钟会吃掉那一拍。
        val n = nextSeq.getAndIncrement()
        return java.lang.Long.toUnsignedString(n, 36)
    }

    /**
     * 把 [openId] 绑到会话 [ref]（收藏/列表/悬浮窗/旋转重进共用）。
     * @contract @pre [isEnabled] 已在调用点短路 @post 该 ref 后续事件用同一 open_id @err none @inv 关路径立即返回
     */
    fun bind(ref: String, openId: String) {
        if (!enabled) return
        cancelSettle(ref)
        opens[ref] = Open(openId)
    }

    /**
     * 会话页进入：承接尚未 route_enter 的 tap；否则（旋转/重连后重进）新开一条。
     *
     * @contract
     * @pre [isEnabled] 已在调用点短路
     * @post 返回本组合应贯穿的 open_id，且该打开已记 routeEntered
     * @err none
     * @inv 关路径返回空串；快速连点两个 ref 不串
     */
    fun beginRoute(ref: String): String {
        if (!enabled) return ""
        val existing = opens[ref]
        if (existing != null && !existing.routeEntered) {
            existing.routeEntered = true
            return existing.openId
        }
        val id = beginOpen()
        bind(ref, id)
        opens[ref]?.routeEntered = true
        return id
    }

    /** 当前 ref 的 open_id；关或未 bind 返回 null（调用点据此跳过，零拼串）。 */
    fun idFor(ref: String): String? {
        if (!enabled) return null
        return opens[ref]?.openId
    }

    /**
     * 会话页离开：取消未发出的 layout_settled 延迟消息，解绑。
     * @contract @pre none @post 该 ref 不再持有打开态 @err none @inv 不发日志
     */
    fun unbind(ref: String) {
        cancelSettle(ref)
        opens.remove(ref)
    }

    /**
     * 用户点击会话行（列表 / 收藏 / 悬浮窗切换共用）。
     * @contract @pre [isEnabled] 已短路 @post 绑定 + 发 tap @err none @inv 关路径零分配
     */
    fun onUserOpen(ref: String) {
        if (!enabled) return
        val id = beginOpen()
        bind(ref, id)
        tap(id)
    }

    /**
     * 事件 `tap`（用户点击会话行）。
     * @contract @pre [openId] 来自同一次 [beginOpen] @post 开时一行 @err none @inv 关路径立即返回
     */
    fun tap(openId: String) {
        if (!enabled) return
        emit(openId, EV_TAP)
    }

    /**
     * 事件 `route_enter`（会话页路由进入）。
     * @contract @pre [openId] 来自同一次 [beginOpen] @post 开时一行 @err none @inv 关路径立即返回
     */
    fun routeEnter(openId: String) {
        if (!enabled) return
        emit(openId, EV_ROUTE_ENTER)
    }

    /**
     * 事件 `subscribe_sent`（订阅帧发出）。
     * @contract @pre [openId] 来自同一次 [beginOpen] @post 开时一行 @err none @inv 关路径立即返回
     */
    fun subscribeSent(openId: String) {
        if (!enabled) return
        emit(openId, EV_SUBSCRIBE_SENT)
    }

    /**
     * 事件 `geom_seed`，必须带 `rows=` `cols=`。
     * @contract @pre [openId] 来自同一次 [beginOpen] @post 开时一行含操作数 @err none @inv 关路径立即返回
     */
    fun geomSeed(openId: String, rows: Int, cols: Int) {
        if (!enabled) return
        emit(openId, EV_GEOM_SEED, "rows=$rows cols=$cols")
    }

    /**
     * 事件 `first_frame_recv`，必须带 `kind=` `bytes=`。
     * @contract @pre [openId] 来自同一次 [beginOpen] @post 开时一行含操作数 @err none @inv 关路径立即返回
     */
    fun firstFrameRecv(openId: String, kind: String, bytes: Int) {
        if (!enabled) return
        emit(openId, EV_FIRST_FRAME_RECV, "kind=$kind bytes=$bytes")
    }

    /**
     * 事件 `snapshot_applied`，必须带 `seq=` `alt=`。
     * @contract @pre [openId] 来自同一次 [beginOpen] @post 开时一行含操作数 @err none @inv 关路径立即返回
     */
    fun snapshotApplied(openId: String, seq: Long, alt: Int) {
        if (!enabled) return
        emit(openId, EV_SNAPSHOT_APPLIED, "seq=$seq alt=$alt")
    }

    /**
     * 事件 `first_draw`，必须带 `glyphs=`（>0 才算首绘）。
     * @contract @pre [openId] 来自同一次 [beginOpen] @post glyphs≤0 不发；否则一行 @err none @inv 关路径立即返回
     */
    fun firstDraw(openId: String, glyphs: Int) {
        if (!enabled) return
        if (glyphs <= 0) return
        emit(openId, EV_FIRST_DRAW, "glyphs=$glyphs")
    }

    /**
     * 事件 `layout_settled`，必须带 `quiet_ms=` 与 `last_reflow_src=`。
     * @contract @pre [openId] 来自同一次 [beginOpen] @post 开时一行含操作数 @err none @inv 关路径立即返回
     */
    fun layoutSettled(openId: String, quietMs: Int, lastReflowSrc: String) {
        if (!enabled) return
        emit(openId, EV_LAYOUT_SETTLED, "quiet_ms=$quietMs last_reflow_src=$lastReflowSrc")
    }

    /** 产品链：本打开是否尚未发过 subscribe_sent。 */
    fun takeSubscribeSent(ref: String): Boolean = takeFlag(ref) { o ->
        if (o.subscribeSent) false else { o.subscribeSent = true; true }
    }

    /** 产品链：本打开是否尚未发过 geom_seed。 */
    fun takeGeomSeed(ref: String): Boolean = takeFlag(ref) { o ->
        if (o.geomSeeded) false else { o.geomSeeded = true; true }
    }

    /** 产品链：本打开是否尚未发过 first_frame_recv。 */
    fun takeFirstFrame(ref: String): Boolean = takeFlag(ref) { o ->
        if (o.firstFrame) false else { o.firstFrame = true; true }
    }

    /** 产品链：本打开是否尚未发过 snapshot_applied。 */
    fun takeSnapshotApplied(ref: String): Boolean = takeFlag(ref) { o ->
        if (o.snapshotApplied) false else { o.snapshotApplied = true; true }
    }

    /** 产品链：本打开是否尚未发过 first_draw。 */
    fun takeFirstDraw(ref: String): Boolean = takeFlag(ref) { o ->
        if (o.firstDraw) false else { o.firstDraw = true; true }
    }

    /** 本打开下一次快照序号（从 1）。未 bind 返回 0。 */
    fun nextSnapshotSeq(ref: String): Long {
        if (!enabled) return 0L
        return opens[ref]?.snapshotSeq?.incrementAndGet() ?: 0L
    }

    /**
     * 记下一次 resize/reflow/全量重绘，并（重新）安排 500ms 一次性延迟消息。
     * ⛔ 不常驻定时器：每次重排 cancel 旧消息再 post 一条。
     *
     * @contract
     * @pre [isEnabled] 已在调用点短路；[src] 为最后一次重排来源（resize/snapshot/subscribe/rotate）
     * @post 500ms 内无新重排则发 layout_settled，带 quiet_ms 与 last_reflow_src
     * @err none
     * @inv 空闲零 CPU（无消息即无回调）
     */
    fun noteReflow(ref: String, src: String) {
        if (!enabled) return
        val st = opens[ref] ?: return
        if (st.settled) return
        st.lastReflowSrc = src
        val gen = synchronized(st) { ++st.settleGen }
        val h = settleHandler ?: return
        cancelSettle(ref)
        val r = Runnable {
            if (!enabled) return@Runnable
            val cur = opens[ref] ?: return@Runnable
            if (cur !== st) return@Runnable
            if (st.settleGen != gen) return@Runnable
            if (st.settled) return@Runnable
            st.settled = true
            layoutSettled(st.openId, LAYOUT_SETTLED_QUIET_MS, st.lastReflowSrc)
        }
        settleRunnables[ref] = r
        h.postDelayed(r, LAYOUT_SETTLED_QUIET_MS.toLong())
    }

    /**
     * 骨架保留对注入字段的读取，避免编译器把测试钩子判成死存储。
     */
    internal fun clockNowMsForImpl(): Long = clock.nowMs()

    internal fun sinkForImpl(): Sink = sink

    private fun emit(openId: String, ev: String, extra: String = "") {
        val t = clockNowMsForImpl()
        val line = if (extra.isEmpty()) {
            "open_id=$openId ev=$ev t=$t"
        } else {
            "open_id=$openId ev=$ev t=$t $extra"
        }
        sinkForImpl().emit(TAG, line)
    }

    private inline fun takeFlag(ref: String, crossinline take: (Open) -> Boolean): Boolean {
        if (!enabled) return false
        val st = opens[ref] ?: return false
        return take(st)
    }

    private fun cancelSettle(ref: String) {
        val r = settleRunnables.remove(ref) ?: return
        settleHandler?.removeCallbacks(r)
    }

    private fun cancelAllSettles() {
        val h = settleHandler
        settleRunnables.values.forEach { r -> h?.removeCallbacks(r) }
        settleRunnables.clear()
    }

    /**
     * 进程启动读一次 `debug.agentmirror.perftrace`。`0` = 关；缺省/其它 = 开。
     * @contract @pre none @post 不抛；读不到当开 @err none @inv 只在对象初始化调用一次
     */
    private fun readProcessProp(): Boolean {
        val v = try {
            val clz = Class.forName("android.os.SystemProperties")
            val m = clz.getMethod("get", String::class.java, String::class.java)
            m.invoke(null, PROP_ENABLED, "") as? String
        } catch (_: Throwable) {
            null
        }
        return v != "0"
    }
}
