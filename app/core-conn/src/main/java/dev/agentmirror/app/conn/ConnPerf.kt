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

package dev.agentmirror.app.conn

/**
 * 核模块 PerfTrace 出口：由 app 壳注入 [dev.agentmirror.app.perf.PerfTrace]。
 * 八事件名与格式仍由壳侧 PerfTrace 决定，本对象只转发。
 */
interface ConnPerfHooks {
    fun isEnabled(): Boolean = false
    fun emitWsBinaryRecv(frameRef: String, kind: String, bytes: Int) = Unit
    fun noteReflow(ref: String, src: String, rows: Int, cols: Int) = Unit
    fun onSubscribeResult(
        ref: String,
        rows: Int,
        cols: Int,
        sent: Boolean,
        replay: Boolean,
        ready: Boolean,
        hasConn: Boolean,
        reason: String,
    ) = Unit
    fun emitNoListener(
        frameRef: String,
        listenerNull: Int,
        kind: String,
        bytes: Int,
        listenerRef: String,
    ) = Unit
}

object ConnPerf {
    @Volatile
    var hooks: ConnPerfHooks = object : ConnPerfHooks {}

    fun isEnabled(): Boolean = hooks.isEnabled()
    fun emitWsBinaryRecv(frameRef: String, kind: String, bytes: Int) =
        hooks.emitWsBinaryRecv(frameRef, kind, bytes)
    fun noteReflow(ref: String, src: String, rows: Int, cols: Int) =
        hooks.noteReflow(ref, src, rows, cols)
    fun onSubscribeResult(
        ref: String,
        rows: Int,
        cols: Int,
        sent: Boolean,
        replay: Boolean,
        ready: Boolean,
        hasConn: Boolean,
        reason: String,
    ) = hooks.onSubscribeResult(ref, rows, cols, sent, replay, ready, hasConn, reason)
    fun emitNoListener(
        frameRef: String,
        listenerNull: Int,
        kind: String,
        bytes: Int,
        listenerRef: String,
    ) = hooks.emitNoListener(frameRef, listenerNull, kind, bytes, listenerRef)
}
