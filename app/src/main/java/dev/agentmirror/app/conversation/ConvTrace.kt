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

package dev.agentmirror.app.conversation

import android.util.Log

/**
 * Conversation geometry/scroll trace, off by default and free when off:
 * `adb shell setprop debug.agentmirror.convtrace 1`, read once per process.
 * Lines carry the operands of every decision (anchor key/offset, paddings, insets, source).
 */
internal object ConvTrace {
    val enabled: Boolean = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java)
            .invoke(null, "debug.agentmirror.convtrace", "") == "1"
    }.getOrDefault(false)

    fun log(line: String) {
        if (enabled) Log.d("ConvTrace", "t=${android.os.SystemClock.uptimeMillis()} $line")
    }
}
