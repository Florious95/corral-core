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

/**
 * 本地 scrollback 环形缓冲：容量可配，满则淘汰最老行；支持向头部插入历史分页（006）。
 *
 * 尾部追加来自屏幕滚出的行；头部插入来自 capture-pane -S 的按行区间补页。
 * 索引 0 恒为最老一行（最远历史），渲染层按 0..size-1 顺序衔接到屏幕上方。
 *
 * 线程语义（fix-term-render-debt 缺陷①连带）：数据驱动帧唤醒落地后，
 * 「WS 收件线程 feed→appendTail」与「主线程帧回调 line/size 读」是常态并发，
 * ArrayDeque 裸并发会撕裂（removeFirst 挪头时 line(index) 读错行/越界），
 * 全部操作经内部锁互斥；行内容是不可变 List<Cell>，出锁后可安全持有。
 */
class ScrollbackBuffer(val capacity: Int) {

    private val lines = ArrayDeque<List<Cell>>()

    /** [lines] 的跨线程互斥锁（WS 写 / 主线程帧读）。 */
    private val lock = Any()

    /** 当前缓冲行数。 */
    val size: Int get() = synchronized(lock) { lines.size }

    /** 取第 [index] 行（0 = 最老）。 */
    fun line(index: Int): List<Cell> = synchronized(lock) { lines[index] }

    /** 尾部追加一行（屏幕顶部滚出的行）；满则淘汰头部最老行。 */
    fun appendTail(line: List<Cell>) {
        if (capacity <= 0) return
        synchronized(lock) {
            if (lines.size >= capacity) lines.removeFirst()
            lines.addLast(line)
        }
    }

    /**
     * 头部插入一批更老的历史行（[older] 按时间正序：越靠前越老）。
     *
     * 只填剩余容量：环形缓冲已满时再塞更老历史没有意义（马上会被淘汰），
     * 超出部分丢弃最老端，保留与现有头部衔接的一段。
     */
    fun prependHead(older: List<List<Cell>>) {
        synchronized(lock) {
            val room = capacity - lines.size
            if (room <= 0) return
            val kept = if (older.size > room) older.subList(older.size - room, older.size) else older
            for (i in kept.indices.reversed()) {
                lines.addFirst(kept[i])
            }
        }
    }

    /** 清空全部历史（ED 3 或上层重建时用）。 */
    fun clear() {
        synchronized(lock) { lines.clear() }
    }
}
