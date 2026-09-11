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

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal data class TermDrawControlUpdate(val opt: Boolean? = null, val burst: Int = 0)

/** File I/O belongs to the worker, never to doFrame/onDraw. No periodic polling. */
internal class TermDrawControlFiles(private val directory: File) {
    fun read(): TermDrawControlUpdate {
        val opt = readSmall(File(directory, OPT))?.let { it != "0" }
        // Claim before reading: a replacement command cannot be deleted by the
        // reader of its predecessor. The process-wide watch has one worker.
        val command = File(directory, BURST)
        val claimed = File(directory, ".$BURST.reading")
        val burst = try {
            Files.move(command.toPath(), claimed.toPath(), StandardCopyOption.REPLACE_EXISTING)
            try {
                readSmall(claimed)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            } finally {
                claimed.delete()
            }
        } catch (_: IOException) {
            0
        } catch (_: SecurityException) {
            0
        }
        return TermDrawControlUpdate(opt, burst)
    }

    private fun readSmall(file: File): String? = try {
        file.inputStream().use { stream ->
            val bytes = ByteArray(65)
            var count = 0
            while (count < bytes.size) {
                val n = stream.read(bytes, count, bytes.size - count)
                if (n <= 0) break
                count += n
            }
            if (count > 64) null else String(bytes, 0, count, Charsets.UTF_8).trim()
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    companion object {
        const val OPT = "term_draw_opt"
        const val BURST = "term_draw_burst"
        fun accepts(path: String?): Boolean = path == OPT || path == BURST
    }
}

/**
 * Coalesces file events with at most one read/delivery in flight. close() and
 * delivery run on the owner/UI thread; request() may run on FileObserver's thread.
 * Retirement waits asynchronously for an in-flight delivery, while close() still
 * prevents a late result from reaching a replacement View/session.
 */
internal class TermDrawControlSession(
    private val read: () -> TermDrawControlUpdate,
    private val execute: (Runnable) -> Unit,
    private val dispatch: (Runnable) -> Unit,
    private val consume: (TermDrawControlUpdate) -> Unit,
) : Closeable {
    private val lock = Any()
    private var closed = false
    private var pending = false
    private var inFlight = false
    private var closeWhenIdleCallback: (() -> Unit)? = null

    fun request() {
        val start = synchronized(lock) {
            if (closed) return
            pending = true
            if (inFlight) false else {
                inFlight = true
                true
            }
        }
        if (start) execute(readTask)
    }

    private val readTask = Runnable { readOnce() }

    private fun readOnce() {
        synchronized(lock) {
            if (closed) {
                inFlight = false
                return
            }
            pending = false
        }
        val update = read()
        dispatch(Runnable {
            try {
                if (synchronized(lock) { !closed }) consume(update)
            } finally {
                var again = false
                var idle: (() -> Unit)? = null
                synchronized(lock) {
                    if (closed || !pending) {
                        inFlight = false
                        if (!closed) {
                            idle = closeWhenIdleCallback
                            closeWhenIdleCallback = null
                        }
                    } else {
                        again = true
                    }
                }
                idle?.invoke()
                if (again) execute(readTask)
            }
        })
    }

    /** Retire without dropping a read that already claimed a command. Never waits. */
    fun closeWhenIdle(onIdle: () -> Unit) {
        val now = synchronized(lock) {
            if (closed) {
                true
            } else if (!inFlight && !pending) {
                true
            } else {
                closeWhenIdleCallback = onIdle
                false
            }
        }
        if (now) onIdle()
    }

    /** A replacement listener keeps this session alive if retirement is still pending. */
    fun cancelCloseWhenIdle() {
        synchronized(lock) { closeWhenIdleCallback = null }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            pending = false
            closeWhenIdleCallback = null
        }
    }
}
