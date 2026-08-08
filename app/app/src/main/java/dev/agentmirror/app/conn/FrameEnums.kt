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
 * error 帧的机器可读 code 闭集（docs/protocol.md §7.2）。
 *
 * 客户端 switch 该值决定恢复策略；未识别的 code 视为 [UNKNOWN]，
 * 但不阻塞连接——协议级错误帧本身是合法的 S→C 消息。
 */
enum class ErrorCode(val wire: String) {
    /** 未认证即操作。 */
    UNAUTHORIZED("unauthorized"),

    /** 控制帧无法解析。 */
    BAD_FRAME("bad_frame"),

    /** 版本不匹配（随后关闭）。 */
    UNSUPPORTED_VERSION("unsupported_version"),

    /** 未知帧类型。 */
    UNSUPPORTED_TYPE("unsupported_type"),

    /** ref 无对应存活会话。 */
    SESSION_NOT_FOUND("session_not_found"),

    /** 服务端内部错误。 */
    INTERNAL("internal"),

    /** 未识别 code 的兜底值（非线上值）。 */
    UNKNOWN("unknown");

    companion object {
        /** 按线上字符串解析；未识别值返回 [UNKNOWN]。 */
        fun fromWire(value: String): ErrorCode =
            entries.firstOrNull { it.wire == value } ?: UNKNOWN
    }
}

/**
 * input_ack ok:false 时的机器可读 reason 闭集（docs/protocol.md §7.3）。
 *
 * reason 存在当且仅当 ok:false（一字段一义）；ok:true 时不得携带。
 */
enum class InputFailReason(val wire: String) {
    /** 目标会话已不存在。 */
    SESSION_NOT_FOUND("session_not_found"),

    /** 未订阅该会话即注入。 */
    NOT_SUBSCRIBED("not_subscribed"),

    /** tmux send-keys 被拒。 */
    INJECT_FAILED("inject_failed"),

    /** 文本超服务端大小上限。 */
    TOO_LARGE("too_large"),

    /** 服务端内部错误。 */
    INTERNAL("internal"),

    /** 未识别 reason 的兜底值（非线上值）。 */
    UNKNOWN("unknown");

    companion object {
        /** 按线上字符串解析；未识别值返回 [UNKNOWN]。 */
        fun fromWire(value: String): InputFailReason =
            entries.firstOrNull { it.wire == value } ?: UNKNOWN
    }
}
