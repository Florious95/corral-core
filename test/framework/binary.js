// binary.js — 二进制流帧编解码（docs/protocol.md §6）。
//
// 流帧与控制帧严格分离：WebSocket 的 binary 消息 = 一帧原始终端字节，
// 永不进入 JSON。帧布局（§6.1）：
//
//   偏移   长度   内容
//   0-1    2     magic "RA"（两字节）
//   2      1     version（= 1）
//   3      1     kind（1=snapshot 2=delta 3=scrollback）
//   4      1     reflen（0..255，会话 ref 字节长度；0 非法）
//   5      5+reflen  ref（UTF-8）
//   5+reflen  ...  payload（kind 相关）
//
// scrollback（kind=3）的 payload 头部另带 12 字节元数据头（§6.3）：
//   [req_id: 4BE 无符号][from_line: 4BE 有符号][line_count: 4BE 无符号][ANSI 字节流]
//
// 本模块是纯编解码 + 注解工具，不依赖任何测试框架；用例断言层（assertions.js）
// 消费它的解码结果。

// 版本字节（protocol.Version）。协议层面双方协商，客户端恒定发 1。
const PROTOCOL_VERSION = 1;

// kind 枚举（§6.2）。
const KIND_SNAPSHOT = 1;
const KIND_DELTA = 2;
const KIND_SCROLLBACK = 3;

const KIND_NAMES = { [KIND_SNAPSHOT]: 'snapshot', [KIND_DELTA]: 'delta', [KIND_SCROLLBACK]: 'scrollback' };

// decodeBinary 解码一个 ArrayBuffer 二进制流帧，返回结构化描述。
// @contract
// @pre buf 为完整一帧（magic 开头、长度自洽）
// @post 返回 { magic, version, kind, kindName, ref, payload }；payload 为 Uint8Array（kind=3 时含 12 字节头）
// @err 格式非法（magic/version/reflen 越界）时抛 Error
function decodeBinary(buf) {
  const bytes = new Uint8Array(buf);
  if (bytes.length < 5) {
    throw new Error(`binary frame too short: ${bytes.length} bytes`);
  }
  const magic = String.fromCharCode(bytes[0], bytes[1]);
  if (magic !== 'RA') {
    throw new Error(`bad binary magic: expected "RA", got ${JSON.stringify(magic)}`);
  }
  const version = bytes[2];
  if (version !== PROTOCOL_VERSION) {
    throw new Error(`unsupported binary version: ${version}`);
  }
  const kind = bytes[3];
  const reflen = bytes[4];
  if (reflen === 0) {
    throw new Error('binary frame with empty ref (reflen=0 is illegal)');
  }
  if (5 + reflen > bytes.length) {
    throw new Error(`binary frame ref overruns payload: reflen=${reflen} len=${bytes.length}`);
  }
  const ref = new TextDecoder().decode(bytes.subarray(5, 5 + reflen));
  const payload = bytes.subarray(5 + reflen);
  return { magic, version, kind, kindName: KIND_NAMES[kind] || `kind${kind}`, ref, payload };
}

// decodeScrollbackPayload 解析 kind=3 的 payload：12 字节元数据头 + ANSI 字节流（§6.3）。
// @contract
// @pre payload 为 decodeBinary 返回的 kind=3 payload
// @post 返回 { reqId, fromLine, lineCount, data:Uint8Array }
// @err payload 不足 12 字节时抛 Error
function decodeScrollbackPayload(payload) {
  if (payload.length < 12) {
    throw new Error(`scrollback payload too short for metadata header: ${payload.length}`);
  }
  const dv = new DataView(payload.buffer, payload.byteOffset, payload.length);
  return {
    reqId: dv.getUint32(0),
    fromLine: dv.getInt32(4),
    lineCount: dv.getUint32(8),
    data: payload.subarray(12),
  };
}

// stripAnsi 移除 ANSI/VT 转义序列，返回纯文本。用于终端文本断言。
// 覆盖 CSI 序列（ESC [ ... 字母）、OSC（ESC ] ... BEL/ST）、单字符 ESC（ESC 7/8/=/c 等）。
function stripAnsi(input) {
  // 逐字节扫描而非贪婪正则，避免吞掉属于显示内容的合法字符。
  const out = [];
  let i = 0;
  const bytes = typeof input === 'string' ? input : new TextDecoder().decode(input);
  while (i < bytes.length) {
    const c = bytes[i];
    if (c === '\x1b') {
      // 后续必须有东西才构成转义；孤立的 ESC 保留。
      if (i + 1 >= bytes.length) { out.push(c); i++; continue; }
      const next = bytes[i + 1];
      if (next === '[') {
        // CSI：读到 @..~ 结束。
        let j = i + 2;
        while (j < bytes.length && !(bytes[j] >= '@' && bytes[j] <= '~')) j++;
        i = j < bytes.length ? j + 1 : j;
      } else if (next === ']') {
        // OSC：读到 BEL 或 ST（ESC \）。
        let j = i + 2;
        while (j < bytes.length) {
          if (bytes[j] === '\x07') { j++; break; }
          if (bytes[j] === '\x1b' && bytes[j + 1] === '\\') { j += 2; break; }
          j++;
        }
        i = j;
      } else {
        // 单字符 ESC 序列（如 ESC 7 存屏），跳过两字节。
        i += 2;
      }
      continue;
    }
    out.push(c);
    i++;
  }
  return out.join('');
}

// splitLines 把终端字节按行切分（含行尾 CR/LF 归一行结尾），去掉末尾空行。
// 与 tmux capture-pane 的“裁去尾部空行”语义对齐，供行级断言使用。
function splitLines(text) {
  const lines = text.split(/\r?\n/);
  while (lines.length > 0 && lines[lines.length - 1] === '') lines.pop();
  return lines;
}

module.exports = {
  PROTOCOL_VERSION,
  KIND_SNAPSHOT,
  KIND_DELTA,
  KIND_SCROLLBACK,
  KIND_NAMES,
  decodeBinary,
  decodeScrollbackPayload,
  stripAnsi,
  splitLines,
};
