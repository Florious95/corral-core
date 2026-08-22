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

package dev.agentmirror.terminal

/** 测试用 ESC 字符常量。 */
const val E = "\u001b"

/** 取屏幕第 [y] 行的可见文本（拼接单元格文本并去尾部空白）。 */
fun TerminalEmulator.rowText(y: Int): String =
    snapshot().lines[y].joinToString("") { it.text }.trimEnd()

/** 取屏幕第 [y] 行第 [x] 格。 */
fun TerminalEmulator.cellAt(x: Int, y: Int): Cell = snapshot().lines[y][x]

/** 取 scrollback 第 [i] 行（0=最老）的可见文本。 */
fun TerminalEmulator.scrollbackText(i: Int): String =
    scrollback.line(i).joinToString("") { it.text }.trimEnd()
