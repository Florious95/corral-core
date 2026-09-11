/*
 * Copyright 2026 AgentMirror Project Authors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.agentmirror.app.termview

import android.content.Context
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One event source/reader per process, shared by briefly overlapping visible Views. */
internal object TermDrawControlWatch {
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val worker by lazy {
        ThreadPoolExecutor(0, 1, 30L, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>()) { task ->
            Thread(task, "term-draw-controls").apply { isDaemon = true }
        }
    }
    // Accessed only on the main thread; no Activity/Context retained.
    private var monitor: Monitor? = null

    fun subscribe(context: Context, onChanged: () -> Unit): Closeable {
        check(Looper.myLooper() === Looper.getMainLooper())
        val group = monitor ?: Monitor(context.applicationContext.filesDir).also { monitor = it }
        group.cancelRetirement()
        val token = Any()
        group.listeners[token] = onChanged
        if (group.listeners.size == 1) group.start()
        return Closeable {
            check(Looper.myLooper() === Looper.getMainLooper())
            group.listeners.remove(token)
            if (group.listeners.isEmpty() && monitor === group) {
                group.retire()
            }
        }
    }

    private class Monitor(directory: File) : Closeable {
        val listeners = LinkedHashMap<Any, () -> Unit>()
        private val files = TermDrawControlFiles(directory)
        private val session = TermDrawControlSession(
            read = files::read,
            execute = { worker.execute(it) },
            dispatch = { main.post(it) },
            consume = { update ->
                val changed = update.opt != null && update.opt != TermDrawMeter.optEnabled
                update.opt?.let { TermDrawMeter.optEnabled = it }
                if (update.burst > 0) TermDrawMeter.armBurst(update.burst)
                if (changed || update.burst > 0) {
                    // Copy tokens, then check membership: a callback may detach another View.
                    for (token in listeners.keys.toList()) listeners[token]?.invoke()
                }
            },
        )
        @Suppress("DEPRECATION") // String constructor supports the existing minSdk 26.
        private val observer = object : FileObserver(
            directory.absolutePath, FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO,
        ) {
            override fun onEvent(event: Int, path: String?) {
                if (event and (FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO) != 0 && TermDrawControlFiles.accepts(path)) {
                    session.request()
                }
            }
        }

        fun cancelRetirement() {
            session.cancelCloseWhenIdle()
        }

        fun retire() {
            session.closeWhenIdle {
                if (listeners.isEmpty() && monitor === this) {
                    monitor = null
                    close()
                }
            }
        }

        fun start() {
            observer.startWatching()
            session.request() // File may predate attach. Observe first to avoid a read/watch gap.
        }

        override fun close() {
            session.close()
            observer.stopWatching()
        }
    }
}
