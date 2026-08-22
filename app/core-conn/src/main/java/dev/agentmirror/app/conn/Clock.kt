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
 * 时间源抽象：重连退避与输入超时的唯一时间依据。
 *
 * 生产用 [Real]（System.currentTimeMillis）；JVM 单测注入假时钟推进时间
 * （conn 知识基底 §1：测试用假时钟），使退避序列确定性可断言。
 */
interface Clock {
    /** 当前时间毫秒（Real 为墙钟 System.currentTimeMillis；仅用于相对差值与期限比较）。 */
    fun nowMs(): Long

    /** 真实时钟（墙钟；宿主在期限比较期间不应回拨系统时间）。 */
    object Real : Clock {
        override fun nowMs(): Long = System.currentTimeMillis()
    }
}
