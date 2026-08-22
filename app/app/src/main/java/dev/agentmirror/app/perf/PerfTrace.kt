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

import dev.agentmirror.app.conn.ConnPerf
import dev.agentmirror.app.conn.ConnPerfHooks
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

    init {
        ConnPerf.hooks = object : ConnPerfHooks {
            override fun isEnabled(): Boolean = this@PerfTrace.isEnabled()
            override fun emitWsBinaryRecv(frameRef: String, kind: String, bytes: Int) =
                this@PerfTrace.emitWsBinaryRecv(frameRef, kind, bytes)
            override fun noteReflow(ref: String, src: String, rows: Int, cols: Int) =
                this@PerfTrace.noteReflow(ref, src, rows, cols)
            override fun onSubscribeResult(
                ref: String,
                rows: Int,
                cols: Int,
                sent: Boolean,
                replay: Boolean,
                ready: Boolean,
                hasConn: Boolean,
                reason: String,
            ) = this@PerfTrace.onSubscribeResult(ref, rows, cols, sent, replay, ready, hasConn, reason)
            override fun emitNoListener(
                frameRef: String,
                listenerNull: Int,
                kind: String,
                bytes: Int,
                listenerRef: String,
            ) = this@PerfTrace.emitNoListener(frameRef, listenerNull, kind, bytes, listenerRef)
            override fun onKeySend(ref: String, char: String) =
                this@PerfTrace.keySend(ref, char)
        }
    }

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
    /** 按键回显量具：交给传输层。配对键 seq+char。 */
    const val EV_KEY_SEND = "key_send"
    /** 按键回显量具：仿真器消费到该字符且本帧绘制完成。 */
    const val EV_KEY_ECHO = "key_echo"

    /** WS 二进制读入口留痕（t.instr3，非八事件契约；emitted=0 不进基线）。 */
    const val EV_WS_BINARY_RECV = "ws_binary_recv"

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
        try {
            Log.d(tag, line)
        } catch (_: Throwable) {
            // 纯 JVM 单测没有 mock android.util.Log；DiagLog 仍落。
        }
        DiagLog.record(tag, line)
    }

    @Volatile
    private var enabled: Boolean = readProcessProp()

    /** 单调时钟；测试注入。生产读 [SystemClock.elapsedRealtime]。 */
    @Volatile
    private var clock: DiagLog.Clock = DiagLog.Clock {
        try {
            SystemClock.elapsedRealtime()
        } catch (_: Throwable) {
            0L
        }
    }

    @Volatile
    private var sink: Sink = productionSink

    private val nextSeq = AtomicLong(1L)
    private val opens = ConcurrentHashMap<String, Open>()
    private val settleRunnables = ConcurrentHashMap<String, Runnable>()
    /** 收帧 ref 不匹配：每对 (frame_ref, want_ref) 只留一行，避免 delta 热路径刷屏。 */
    private val refMismatchLogged = ConcurrentHashMap.newKeySet<String>()
    private val noOpenFirstLogged = ConcurrentHashMap.newKeySet<String>()
    private val noOpenSnapshotLogged = ConcurrentHashMap.newKeySet<String>()
    private val wsBinaryRecvLogged = ConcurrentHashMap.newKeySet<String>()
    private val noListenerLogged = ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var settleHandler: Handler? = null

    private val keySeq = AtomicLong(1L)
    private val keyLock = Any()
    private val pendingKeys = ArrayDeque<PendingKey>()
    private val consumedSinceDraw = ArrayDeque<Char>()

    private data class PendingKey(val seq: Long, val char: Char)

    private class Open(val openId: String) {
        @Volatile var routeEntered: Boolean = false
        @Volatile var subscribeSent: Boolean = false
        @Volatile var geomSeeded: Boolean = false
        @Volatile var firstFrame: Boolean = false
        @Volatile var snapshotApplied: Boolean = false
        @Volatile var firstDraw: Boolean = false
        @Volatile var emptyDrawLogged: Boolean = false
        @Volatile var settled: Boolean = false
        @Volatile var lastReflowSrc: String = "none"
        @Volatile var lastRows: Int = -1
        @Volatile var lastCols: Int = -1
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
        refMismatchLogged.clear()
        noOpenFirstLogged.clear()
        noOpenSnapshotLogged.clear()
        wsBinaryRecvLogged.clear()
        noListenerLogged.clear()
        synchronized(keyLock) {
            pendingKeys.clear()
            consumedSinceDraw.clear()
        }
        keySeq.set(1L)
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
     * 尚未 settled 时打 `layout_settled emitted=0 reason=unbind`（含 pending/行列），
     * 分得出「还在等 / 被离开取消 / 从未 noteReflow」。
     *
     * @contract @pre none @post 该 ref 不再持有打开态；未结算则一行取消原因 @err none
     */
    fun unbind(ref: String) {
        if (enabled) {
            val st = opens[ref]
            if (st != null && !st.settled) {
                val pending = if (settleRunnables.containsKey(ref)) 1 else 0
                emit(
                    st.openId,
                    EV_LAYOUT_SETTLED,
                    "emitted=0 reason=unbind settle_pending=$pending " +
                        "last_reflow_src=${st.lastReflowSrc} rows=${st.lastRows} cols=${st.lastCols} " +
                        "quiet_ms=$LAYOUT_SETTLED_QUIET_MS",
                )
            }
        }
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
    fun layoutSettled(
        openId: String,
        quietMs: Int,
        lastReflowSrc: String,
        rows: Int = -1,
        cols: Int = -1,
    ) {
        if (!enabled) return
        emit(
            openId,
            EV_LAYOUT_SETTLED,
            "quiet_ms=$quietMs last_reflow_src=$lastReflowSrc rows=$rows cols=$cols",
        )
    }

    /**
     * `key_send`：把一次 a–z 单字符交给传输层。带 seq+char 供与 [flushKeyEchoAfterDraw] 配对。
     * 非单字符 a–z（IME 整词等）不发事件，避免配不上对。
     */
    fun keySend(ref: String, char: String) {
        if (!enabled) return
        if (char.length != 1) return
        val c = char[0]
        if (c !in 'a'..'z') return
        val seq: Long
        synchronized(keyLock) {
            seq = keySeq.getAndIncrement()
            pendingKeys.addLast(PendingKey(seq, c))
        }
        val openId = idFor(ref) ?: "-"
        emit(openId, EV_KEY_SEND, "seq=$seq char=$c")
    }

    /** 仿真器把 a–z 写入网格。关路径：调用方不挂钩，本方法也不会被走到。 */
    fun notePrintableEcho(char: Char) {
        if (!enabled) return
        if (char !in 'a'..'z') return
        synchronized(keyLock) { consumedSinceDraw.addLast(char) }
    }

    /**
     * 本帧绘制完成后：把本帧消费到的字符与未配对的 key_send 按 FIFO 同字符配对，发 `key_echo`。
     * 配不上的消费（快照里的字母）丢掉，不发事件。
     */
    fun flushKeyEchoAfterDraw(ref: String) {
        if (!enabled) return
        val matched = ArrayList<PendingKey>(4)
        synchronized(keyLock) {
            while (consumedSinceDraw.isNotEmpty()) {
                val c = consumedSinceDraw.removeFirst()
                val it = pendingKeys.iterator()
                var hit: PendingKey? = null
                while (it.hasNext()) {
                    val p = it.next()
                    if (p.char == c) {
                        it.remove()
                        hit = p
                        break
                    }
                }
                if (hit != null) matched.add(hit)
            }
        }
        if (matched.isEmpty()) return
        val openId = idFor(ref) ?: "-"
        for (p in matched) {
            emit(openId, EV_KEY_ECHO, "seq=${p.seq} char=${p.char}")
        }
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
     * 跳过时仍打 `layout_settled emitted=0 reason=`（no_open / already_settled / no_handler）。
     *
     * @contract
     * @pre [isEnabled] 已在调用点短路；[src] 为最后一次重排来源
     * @post 500ms 内无新重排则发 layout_settled，带 quiet_ms / last_reflow_src / rows / cols
     * @err none
     * @inv 空闲零 CPU（无消息即无回调）
     */
    fun noteReflow(ref: String, src: String, rows: Int = -1, cols: Int = -1) {
        if (!enabled) return
        val st = opens[ref]
        if (st == null) {
            emit(
                "-",
                EV_LAYOUT_SETTLED,
                "emitted=0 reason=no_open last_reflow_src=$src rows=$rows cols=$cols " +
                    "quiet_ms=$LAYOUT_SETTLED_QUIET_MS",
            )
            return
        }
        if (rows >= 0) st.lastRows = rows
        if (cols >= 0) st.lastCols = cols
        if (st.settled) {
            emit(
                st.openId,
                EV_LAYOUT_SETTLED,
                "emitted=0 reason=already_settled last_reflow_src=$src " +
                    "rows=${st.lastRows} cols=${st.lastCols} quiet_ms=$LAYOUT_SETTLED_QUIET_MS",
            )
            return
        }
        st.lastReflowSrc = src
        val gen = synchronized(st) { ++st.settleGen }
        val h = mainHandler()
        if (h == null) {
            emit(
                st.openId,
                EV_LAYOUT_SETTLED,
                "emitted=0 reason=no_handler last_reflow_src=$src " +
                    "rows=${st.lastRows} cols=${st.lastCols} quiet_ms=$LAYOUT_SETTLED_QUIET_MS",
            )
            return
        }
        cancelSettle(ref)
        val r = Runnable {
            if (!enabled) return@Runnable
            val cur = opens[ref] ?: return@Runnable
            if (cur !== st) return@Runnable
            if (st.settleGen != gen) return@Runnable
            if (st.settled) return@Runnable
            st.settled = true
            layoutSettled(
                st.openId,
                LAYOUT_SETTLED_QUIET_MS,
                st.lastReflowSrc,
                st.lastRows,
                st.lastCols,
            )
        }
        settleRunnables[ref] = r
        h.postDelayed(r, LAYOUT_SETTLED_QUIET_MS.toLong())
    }

    /**
     * subscribe 守卫/发出的唯一打点口。未就绪、无 conn、send 失败、无 open_id、
     * 重连重放（take 已占用）都打同一 `ev=subscribe_sent`，用 `emitted=` `reason=`
     * 与 `ready=` `conn=` `ok=` `take_before=` `replay=` 两边操作数区分。
     *
     * @contract
     * @pre [isEnabled] 已在调用点短路
     * @post sent=true 必有一行 subscribe_sent emitted=1（重放不被 take 吞）；
     *       首次成功另打 geom_seed；否则 geom_seed emitted=0 reason=already_seeded
     */
    fun onSubscribeResult(
        ref: String,
        rows: Int,
        cols: Int,
        sent: Boolean,
        replay: Boolean,
        ready: Boolean,
        hasConn: Boolean,
        reason: String,
    ) {
        if (!enabled) return
        val st = opens[ref]
        val id = st?.openId ?: "-"
        val takeBefore = if (st?.subscribeSent == true) 1 else 0
        if (!sent) {
            emit(
                id,
                EV_SUBSCRIBE_SENT,
                "emitted=0 reason=$reason ready=${b(ready)} conn=${b(hasConn)} ok=0 " +
                    "take_before=$takeBefore replay=${b(replay)} rows=$rows cols=$cols",
            )
            return
        }
        if (st != null) st.subscribeSent = true
        emit(
            id,
            EV_SUBSCRIBE_SENT,
            "emitted=1 reason=$reason ready=${b(ready)} conn=${b(hasConn)} ok=1 " +
                "take_before=$takeBefore replay=${b(replay)} rows=$rows cols=$cols",
        )
        if (st == null) {
            emit(
                id,
                EV_GEOM_SEED,
                "emitted=0 reason=no_open rows=$rows cols=$cols",
            )
            return
        }
        if (!st.geomSeeded) {
            st.geomSeeded = true
            geomSeed(id, rows, cols)
            noteReflow(ref, "subscribe", rows, cols)
        } else {
            emit(
                id,
                EV_GEOM_SEED,
                "emitted=0 reason=already_seeded rows=$rows cols=$cols",
            )
        }
    }

    /**
     * 收帧口 `frame.ref != 本页 ref`：原先静默 return，帧到了和没到在日志里同形。
     * 每对 (frame_ref, want_ref) 一行 `first_frame_recv emitted=0 reason=ref_mismatch`。
     *
     * @contract
     * @pre [isEnabled] 已在调用点短路
     * @post 一行含 frame_ref= 与 want_ref= 两边操作数
     */
    fun emitFrameRefMismatch(frameRef: String, wantRef: String, kind: String, bytes: Int) {
        if (!enabled) return
        val key = "$frameRef\u0000$wantRef"
        if (!refMismatchLogged.add(key)) return
        val id = opens[wantRef]?.openId ?: "-"
        emit(
            id,
            EV_FIRST_FRAME_RECV,
            "emitted=0 reason=ref_mismatch frame_ref=$frameRef want_ref=$wantRef kind=$kind bytes=$bytes",
        )
    }

    /**
     * WS 二进制帧已解码（Connection.onBinary）。每 ref 一行，emitted=0 不进基线。
     *
     * @contract @pre [isEnabled] 已在调用点短路 @post 一行 ev=ws_binary_recv kind= bytes=
     */
    fun emitWsBinaryRecv(frameRef: String, kind: String, bytes: Int) {
        if (!enabled) return
        if (!wsBinaryRecvLogged.add(frameRef)) return
        val id = opens[frameRef]?.openId ?: "-"
        emit(
            id,
            EV_WS_BINARY_RECV,
            "emitted=0 kind=$kind bytes=$bytes frame_ref=$frameRef",
        )
    }

    /**
     * ConnectionManager 全局 listener 槽：每 ref 一行。
     * `listener_null=1` ⇒ reason=no_listener；`listener_null=0` ⇒ reason=has_listener。
     * `listener_ref=` 是接帧者自己的 ref 或实现类名，与 frame_ref= 并排。
     *
     * @contract @pre [isEnabled] 已在调用点短路 @post emitted=0 且含 frame_ref= listener_null= listener_ref=
     */
    fun emitNoListener(
        frameRef: String,
        listenerNull: Int,
        kind: String,
        bytes: Int,
        listenerRef: String,
    ) {
        if (!enabled) return
        if (!noListenerLogged.add(frameRef)) return
        val id = opens[frameRef]?.openId ?: "-"
        val reason = if (listenerNull == 1) "no_listener" else "has_listener"
        emit(
            id,
            EV_FIRST_FRAME_RECV,
            "emitted=0 reason=$reason frame_ref=$frameRef listener_null=$listenerNull " +
                "listener_ref=$listenerRef kind=$kind bytes=$bytes",
        )
    }

    /**
     * 本打开首帧。已发过则静默（delta 热路径）。
     * opens 落空：一行 `emitted=0 reason=no_open`（每 ref 一次），不再静默 return。
     */
    fun emitFirstFrameIfFirst(ref: String, kind: String, bytes: Int) {
        if (!enabled) return
        val st = opens[ref]
        if (st == null) {
            if (noOpenFirstLogged.add(ref)) {
                emit(
                    "-",
                    EV_FIRST_FRAME_RECV,
                    "emitted=0 reason=no_open kind=$kind bytes=$bytes want_ref=$ref",
                )
            }
            return
        }
        if (st.firstFrame) return
        st.firstFrame = true
        firstFrameRecv(st.openId, kind, bytes)
    }

    /**
     * 本打开首次 snapshot_applied + 记重排。已发过则静默（resize 补快照热路径）。
     * opens 落空：一行 `emitted=0 reason=no_open`（每 ref 一次）。
     */
    fun emitSnapshotIfFirst(ref: String, alt: Int, rows: Int, cols: Int) {
        if (!enabled) return
        val st = opens[ref]
        if (st == null) {
            if (noOpenSnapshotLogged.add(ref)) {
                emit(
                    "-",
                    EV_SNAPSHOT_APPLIED,
                    "emitted=0 reason=no_open alt=$alt rows=$rows cols=$cols want_ref=$ref",
                )
            }
            return
        }
        if (st.snapshotApplied) return
        st.snapshotApplied = true
        val seq = st.snapshotSeq.incrementAndGet()
        snapshotApplied(st.openId, seq, alt)
        noteReflow(ref, "snapshot", rows, cols)
    }

    /**
     * onDraw 首绘。glyphs=0 打一行 emitted=0（每打开一次），>0 才算 first_draw。
     * ⛔ 不扫网格：glyphs 由调用方从已有 cellsNonBlank 传入。
     */
    fun emitFirstDraw(ref: String, glyphs: Int) {
        if (!enabled) return
        val st = opens[ref] ?: return
        if (glyphs <= 0) {
            if (st.emptyDrawLogged) return
            st.emptyDrawLogged = true
            emit(st.openId, EV_FIRST_DRAW, "emitted=0 reason=glyphs_zero glyphs=0")
            return
        }
        if (st.firstDraw) return
        st.firstDraw = true
        firstDraw(st.openId, glyphs)
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
        mainHandler()?.removeCallbacks(r)
    }

    private fun cancelAllSettles() {
        val h = mainHandler()
        settleRunnables.values.forEach { r -> h?.removeCallbacks(r) }
        settleRunnables.clear()
    }

    /** 懒取主线程 Handler：对象 init 时可能还没有 looper（纯 JVM 单测），Robolectric 后再取。 */
    private fun mainHandler(): Handler? {
        settleHandler?.let { return it }
        val h = try {
            val looper = Looper.getMainLooper() ?: return null
            Handler(looper)
        } catch (_: Throwable) {
            null
        }
        settleHandler = h
        return h
    }

    private fun b(v: Boolean): Int = if (v) 1 else 0

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
