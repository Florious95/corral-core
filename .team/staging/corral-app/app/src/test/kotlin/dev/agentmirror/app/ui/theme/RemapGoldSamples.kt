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

package dev.agentmirror.app.ui.theme

import dev.agentmirror.terminal.TerminalColor

internal object RemapGoldSamples {
    val RGB: List<Triple<Int, Int, Int>> = listOf(
        Triple(0, 0, 0),
        Triple(255, 255, 255),
        Triple(255, 0, 0),
        Triple(0, 255, 0),
        Triple(0, 0, 255),
        Triple(255, 255, 0),
        Triple(0, 255, 255),
        Triple(255, 0, 255),
        Triple(128, 128, 128),
        Triple(32, 32, 32),
        Triple(220, 220, 220),
        Triple(255, 175, 0),
        Triple(255, 255, 200),
        Triple(12, 12, 40),
        Triple(1, 2, 3),
        Triple(254, 253, 252),
        Triple(90, 40, 200),
        Triple(8, 8, 8),
        Triple(16, 16, 16),
        Triple(240, 240, 240),
        Triple(196, 0, 0),
        Triple(0, 196, 0),
        Triple(0, 0, 196),
        Triple(255, 128, 64),
        Triple(64, 64, 255),
        Triple(160, 82, 45),
        Triple(255, 255, 224),
        Triple(47, 79, 79),
        Triple(218, 165, 32),
        Triple(75, 0, 130),
    )

    fun throughputWorkload(): List<Triple<TerminalColor, Boolean, Boolean>> {
        val out = ArrayList<Triple<TerminalColor, Boolean, Boolean>>(512)
        for (i in 16..255) {
            out += Triple(TerminalColor.Indexed(i), false, true)
            out += Triple(TerminalColor.Indexed(i), true, false)
        }
        for ((r, g, b) in RGB) {
            out += Triple(TerminalColor.Rgb(r, g, b), false, true)
            out += Triple(TerminalColor.Rgb(r, g, b), true, false)
        }
        return out
    }
}
