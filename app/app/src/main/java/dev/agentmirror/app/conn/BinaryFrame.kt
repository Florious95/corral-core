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

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * 二进制流帧 kind（docs/protocol.md §6.2）。
 *
 * kind 是闭集；新增 kind 是增量变更，但需两端共享常量。
 */
enum class BinaryKind(val wire: Int) {
    /** 订阅生效后首帧全屏快照（capture-pane -e，含颜色转义）。 */
    SNAPSHOT(1),

    /** 一段增量终端字节（pipe-pane），追加到当前屏。 */
    DELTA(2),

    /** 一页历史（capture-pane -S），回答 scrollback 请求。 */
    SCROLLBACK(3);

    companion object {
        /** 按线上字节解析；未知 kind 返回 null（解码器需报 UNKNOWN_KIND）。 */
        fun fromWire(b: Int): BinaryKind? = entries.firstOrNull { it.wire == b }
    }
}

/**
 * 一条二进制流帧的解码结果（docs/protocol.md §6）。
 *
 * 载荷为原始 ANSI/VT 字节，**不经过任何 JSON 转义**。[kind] 为 [BinaryKind.SCROLLBACK]
 * 时，[reqId]/[fromLine]/[lineCount] 携带**服务端收敛后的实际行区间**元数据头，
 * 供客户端锚定本地滚动视口；其余 kind 下这些字段为 0。
 */
data class BinaryFrame(
    val kind: BinaryKind,
    val ref: String,
    val data: ByteArray,
    val reqId: Long = 0,
    val fromLine: Int = 0,
    val lineCount: Long = 0,
) {
    /** 相等比较含载荷字节内容（ByteArray 的 == 是引用相等，需显式比较）。 */
    override fun equals(other: Any?): Boolean =
        other is BinaryFrame &&
            kind == other.kind &&
            ref == other.ref &&
            reqId == other.reqId &&
            fromLine == other.fromLine &&
            lineCount == other.lineCount &&
            data.contentEquals(other.data)

    override fun hashCode(): Int {
        var h = kind.hashCode() * 31 + ref.hashCode()
        h = h * 31 + reqId.hashCode()
        h = h * 31 + fromLine
        h = h * 31 + lineCount.hashCode()
        return h * 31 + data.contentHashCode()
    }
}

/**
 * 二进制流帧编解码（docs/protocol.md §6）。一条 binary WS 消息 = 一帧。
 *
 * 布局：
 * ```
 * 偏移   长度   内容
 * 0-1    2      magic "RA"（两字节）
 * 2      1      version（= ProtocolVersion.BINARY）
 * 3      1      kind（见 §6.2）
 * 4      1      reflen（0..255）
 * 5      5+reflen  ref（UTF-8）
 * 5+reflen ...    payload（kind 相关）
 * ```
 * kind=SCROLLBACK 时 payload 头部为 12 字节元数据头：
 * `[req_id:4BE 无符号][from_line:4BE 有符号][line_count:4BE 无符号][ANSI 字节]`。
 *
 * magic 与 version 在最外层：解码器先验 magic/version，再信任何字节。
 * 解码严格默认：坏 magic/版本、未知 kind、截断、超限、空 ref 一律报错
 * （畸形镜像流必须显式浮出，不得污染终端网格）。
 */
object BinaryFrameCodec {
    private const val HEADER_LEN = 5
    private const val SCROLLBACK_HEADER_LEN = 12

    /**
     * 编码一条二进制帧为完整字节序列（WS binary 载荷）。
     * 编码前校验 ref 长度与载荷大小，坏帧不跨线。
     */
    fun encode(frame: BinaryFrame): ByteArray {
        // 编码侧边界校验（对齐 Go validateBinaryPayload）。
        if (frame.ref.isEmpty()) {
            throw FrameEncodeException(FrameError.INVALID_REF, "empty ref")
        }
        val refBytes = frame.ref.toByteArray(StandardCharsets.UTF_8)
        if (refBytes.size > ProtocolVersion.MAX_REF_LEN) {
            throw FrameEncodeException(FrameError.REF_TOO_LONG, "ref exceeds 255 bytes")
        }
        if (frame.data.size > ProtocolVersion.MAX_BINARY_PAYLOAD) {
            throw FrameEncodeException(FrameError.INVALID_FIELD, "payload exceeds 1 MiB")
        }
        if (frame.kind == BinaryKind.SCROLLBACK) {
            if (frame.reqId <= 0) {
                throw FrameEncodeException(FrameError.INVALID_FIELD, "scrollback req_id must be >= 1")
            }
            if (frame.lineCount <= 0) {
                throw FrameEncodeException(FrameError.INVALID_FIELD, "scrollback line_count must be >= 1")
            }
        }

        // 可变长：header 5 + ref + 可能的 12 字节头 + 载荷。
        val buf = ByteBuffer.allocate(
            HEADER_LEN + refBytes.size + frame.data.size +
                (if (frame.kind == BinaryKind.SCROLLBACK) SCROLLBACK_HEADER_LEN else 0),
        )
        buf.put('R'.code.toByte())
        buf.put('A'.code.toByte())
        buf.put(ProtocolVersion.BINARY.toByte())
        buf.put(frame.kind.wire.toByte())
        buf.put(refBytes.size.toByte())
        buf.put(refBytes)
        if (frame.kind == BinaryKind.SCROLLBACK) {
            // 12 字节元数据头：服务端收敛后的**实际**行区间（请求越界时收敛）。
            buf.putInt(frame.reqId.toInt())
            buf.putInt(frame.fromLine)
            buf.putInt(frame.lineCount.toInt())
        }
        buf.put(frame.data)
        return buf.array()
    }

    /**
     * 解码一条二进制 WS 消息为 [BinaryFrame]。
     *
     * @throws FrameDecodeException 分类码见 [FrameError]：
     *   - 帧太短 / 截断 ⇒ TRUNCATED
     *   - magic 不符 ⇒ BAD_MAGIC；版本字节不符 ⇒ UNSUPPORTED_VERSION
     *   - kind 未知 ⇒ UNKNOWN_KIND；reflen=0 ⇒ INVALID_REF
     *   - scrollback 头缺 / req_id=0 / line_count=0 ⇒ TRUNCATED / INVALID_FIELD
     */
    fun decode(bytes: ByteArray): BinaryFrame {
        if (bytes.size < HEADER_LEN) {
            throw FrameDecodeException(FrameError.TRUNCATED, "frame shorter than 5-byte header")
        }
        if (bytes[0] != 'R'.code.toByte() || bytes[1] != 'A'.code.toByte()) {
            throw FrameDecodeException(
                FrameError.BAD_MAGIC,
                "bad magic: ${bytes[0].toInt()} ${bytes[1].toInt()}",
            )
        }
        val version = bytes[2].toInt() and 0xff
        if (version != ProtocolVersion.BINARY) {
            throw FrameDecodeException(
                FrameError.UNSUPPORTED_VERSION,
                "binary version $version, want ${ProtocolVersion.BINARY}",
            )
        }
        val kind = BinaryKind.fromWire(bytes[3].toInt() and 0xff)
            ?: throw FrameDecodeException(FrameError.UNKNOWN_KIND, "unknown kind: ${bytes[3]}")
        val reflen = bytes[4].toInt() and 0xff
        if (bytes.size < HEADER_LEN + reflen) {
            throw FrameDecodeException(FrameError.TRUNCATED, "ref of $reflen bytes but frame has ${bytes.size}")
        }
        if (reflen == 0) {
            throw FrameDecodeException(FrameError.INVALID_REF, "empty ref")
        }
        val ref = String(bytes, HEADER_LEN, reflen, StandardCharsets.UTF_8)
        var offset = HEADER_LEN + reflen
        val body = bytes

        if (kind == BinaryKind.SCROLLBACK) {
            if (body.size - offset < SCROLLBACK_HEADER_LEN) {
                throw FrameDecodeException(FrameError.TRUNCATED, "scrollback metadata header")
            }
            val reqId = ByteBuffer.wrap(body, offset, 4).int.toLong() and 0xffffffffL
            val fromLine = ByteBuffer.wrap(body, offset + 4, 4).int
            val lineCount = ByteBuffer.wrap(body, offset + 8, 4).int.toLong() and 0xffffffffL
            offset += SCROLLBACK_HEADER_LEN
            if (reqId == 0L) {
                throw FrameDecodeException(FrameError.INVALID_FIELD, "scrollback req_id must be >= 1")
            }
            if (lineCount == 0L) {
                throw FrameDecodeException(FrameError.INVALID_FIELD, "scrollback line_count must be >= 1")
            }
            return BinaryFrame(
                kind = kind,
                ref = ref,
                data = body.copyOfRange(offset, body.size),
                reqId = reqId,
                fromLine = fromLine,
                lineCount = lineCount,
            )
        }
        return BinaryFrame(kind = kind, ref = ref, data = body.copyOfRange(offset, body.size))
    }
}
