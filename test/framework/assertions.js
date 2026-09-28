// assertions.js — 通用断言库。
//
// 覆盖三类断言（FIELD.md 核心能力 1）：
//   1. WebSocket 帧断言：控制帧结构 / 载荷字段 / 二进制流帧头与元数据
//   2. 终端文本断言：ANSI 剥离后的文本包含 / 行 / 非空
//   3. 尺寸断言：图片尺寸（预留 PNG 头解析；截图尺寸断言入口）
//
// 设计：断言函数返回布尔 + 提供 expectXxx 断言器（失败抛 AssertionError，携带
// 可读信息）。用例在断言失败时由 runner 捕获并记 FAIL。所有断言纯函数、无副作用。
//
// 约定：本库不 import 测试框架，可被任意用例直接 require。

const { stripAnsi, splitLines, decodeScrollbackPayload, KIND_SNAPSHOT, KIND_DELTA } = require('./binary');

// --- 断言器基类 -----------------------------------------------------------

class AssertionError extends Error {
  constructor(message, { expected, actual } = {}) {
    super(message);
    this.name = 'AssertionError';
    this.expected = expected;
    this.actual = actual;
  }
}

// expect(cond, message) — 通用断言；失败抛 AssertionError。
function expect(cond, message) {
  if (!cond) throw new AssertionError(message);
  return true;
}

// expectEqual / expectTruthy / expectFalsy — 便捷断言器。
function expectEqual(actual, expected, label = 'value') {
  if (actual !== expected) {
    throw new AssertionError(`${label}: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`, { expected, actual });
  }
  return true;
}

function expectTruthy(actual, label = 'value') {
  if (!actual) throw new AssertionError(`${label}: expected truthy, got ${JSON.stringify(actual)}`, { expected: 'truthy', actual });
  return true;
}

function expectFalsy(actual, label = 'value') {
  if (actual) throw new AssertionError(`${label}: expected falsy, got ${JSON.stringify(actual)}`, { expected: 'falsy', actual });
  return true;
}

// --- 帧断言 ---------------------------------------------------------------

// expectFrame 断言一个控制帧的 type 与 v。
function expectFrame(frame, type, { version = 1, label = 'control frame' } = {}) {
  expect(frame && typeof frame === 'object', `${label}: frame is not an object`);
  expectEqual(frame.v, version, `${label}.v`);
  expectEqual(frame.type, type, `${label}.type`);
  return frame;
}

// expectFramePayloadField 断言控制帧 payload 存在且某字段等于期望值。
function expectFramePayloadField(frame, field, expected, { label = 'payload' } = {}) {
  expect(frame.payload && typeof frame.payload === 'object', `${label}: payload missing`);
  expectEqual(frame.payload[field], expected, `${label}.${field}`);
  return frame;
}

// expectListing 断言 listing 帧并返回 workspaces 数组。
function expectListing(frame, { label = 'listing' } = {}) {
  expectFrame(frame, 'listing', { label });
  expect(frame.payload && Array.isArray(frame.payload.workspaces), `${label}: payload.workspaces must be an array`);
  return frame.payload.workspaces;
}

// expectAuthAck 断言 auth_ack 帧 ok 语义。
function expectAuthAck(frame, ok, { label = 'auth_ack' } = {}) {
  expectFrame(frame, 'auth_ack', { label });
  expectEqual(frame.payload.ok, ok, `${label}.ok`);
  return frame;
}

// expectBinarySnapshot 断言一个二进制帧是 snapshot，并校验 ref。
function expectBinarySnapshot(bin, ref, { label = 'binary snapshot' } = {}) {
  expect(bin && typeof bin === 'object', `${label}: binary frame missing`);
  expectEqual(bin.kind, KIND_SNAPSHOT, `${label}.kind`);
  if (ref !== undefined) expectEqual(bin.ref, ref, `${label}.ref`);
  return bin;
}

// expectBinaryDelta 断言一个二进制帧是 delta。
function expectBinaryDelta(bin, ref, { label = 'binary delta' } = {}) {
  expect(bin && typeof bin === 'object', `${label}: binary frame missing`);
  expectEqual(bin.kind, KIND_DELTA, `${label}.kind`);
  if (ref !== undefined) expectEqual(bin.ref, ref, `${label}.ref`);
  return bin;
}

// expectScrollback 断言 scrollback 二进制帧的元数据头（§6.3）。
function expectScrollback(bin, { fromLine, count, reqId, label = 'scrollback' } = {}) {
  expect(bin && typeof bin === 'object', `${label}: binary frame missing`);
  expectEqual(bin.kind, 3, `${label}.kind`);
  const meta = decodeScrollbackPayload(bin.payload);
  if (reqId !== undefined) expectEqual(meta.reqId, reqId, `${label}.reqId`);
  if (fromLine !== undefined) expectEqual(meta.fromLine, fromLine, `${label}.fromLine`);
  if (count !== undefined) expectEqual(meta.lineCount, count, `${label}.lineCount`);
  return meta;
}

// --- 终端文本断言 ---------------------------------------------------------

// toText 把二进制帧 payload 或字符串剥 ANSI 后转为纯文本。
function toText(input) {
  if (input == null) return '';
  if (input instanceof Uint8Array) return stripAnsi(input);
  if (input && input.payload && input.payload instanceof Uint8Array) return stripAnsi(input.payload);
  return String(input);
}

// expectTerminalContains 断言终端文本（ANSI 已剥离）包含给定子串。
function expectTerminalContains(terminalInput, substring, { label = 'terminal' } = {}) {
  const text = toText(terminalInput);
  expect(typeof text === 'string', `${label}: expected string, got ${typeof text}`);
  if (!text.includes(substring)) {
    throw new AssertionError(`${label}: expected text to contain ${JSON.stringify(substring)}\n--- actual (first 500 chars) ---\n${text.slice(0, 500)}`, { expected: substring, actual: text.slice(0, 500) });
  }
  return text;
}

// expectTerminalLineContains 断言终端文本的某一行包含子串。
function expectTerminalLineContains(terminalInput, lineSubstring, { line = null, label = 'terminal' } = {}) {
  const text = toText(terminalInput);
  const lines = splitLines(text);
  if (line !== null) {
    expect(line < lines.length, `${label}: no line ${line} (text has ${lines.length} lines)`);
    expect(lines[line].includes(lineSubstring), `${label}: line ${line} ${JSON.stringify(lines[line])} does not contain ${JSON.stringify(lineSubstring)}`);
    return lines[line];
  }
  for (const l of lines) {
    if (l.includes(lineSubstring)) return l;
  }
  throw new AssertionError(`${label}: no line contains ${JSON.stringify(lineSubstring)}\n--- actual (first 500 chars) ---\n${text.slice(0, 500)}`, { expected: lineSubstring, actual: text.slice(0, 500) });
}

// expectTerminalNotContain 断言终端文本不包含子串。
function expectTerminalNotContain(terminalInput, substring, { label = 'terminal' } = {}) {
  const text = toText(terminalInput);
  if (text.includes(substring)) {
    throw new AssertionError(`${label}: expected text NOT to contain ${JSON.stringify(substring)}`, { expected: `not ${substring}`, actual: substring });
  }
  return text;
}

// expectTerminalNonEmpty 断言终端文本非空（剥 ANSI 后仍有内容）。
function expectTerminalNonEmpty(terminalInput, { label = 'terminal' } = {}) {
  const text = toText(terminalInput);
  if (!text || !text.trim()) {
    throw new AssertionError(`${label}: expected non-empty terminal text, got empty`, { expected: 'non-empty', actual: '' });
  }
  return text;
}

// expectTerminalHeight 断言终端文本的行数（capture-pane 语义：尾部空行已裁）。
function expectTerminalHeight(terminalInput, minLines, { label = 'terminal' } = {}) {
  const text = toText(terminalInput);
  const lines = splitLines(text);
  if (lines.length < minLines) {
    throw new AssertionError(`${label}: expected at least ${minLines} lines, got ${lines.length}`, { expected: minLines, actual: lines.length });
  }
  return lines;
}

// --- 尺寸断言（截图尺寸）-------------------------------------------------

// parsePngSize 解析 PNG 头的宽高（bytes 12–16 为宽，16–20 为高，均 4BE）。
// 非 PNG 或不足 24 字节返回 null —— 尺寸断言需先用它探测。
function parsePngSize(buf) {
  if (!buf || buf.byteLength < 24) return null;
  const bytes = buf instanceof Uint8Array ? buf : new Uint8Array(buf);
  const sig = [137, 80, 78, 71, 13, 10, 26, 10];
  for (let i = 0; i < 8; i++) {
    if (bytes[i] !== sig[i]) return null;
  }
  const dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  return { width: dv.getUint32(16), height: dv.getUint32(20) };
}

// expectImageSize 断言截图尺寸在给定范围（width 精确或 range）。用于截图尺寸断言。
function expectImageSize(buf, { width, height, minWidth, minHeight, label = 'image' } = {}) {
  const size = parsePngSize(buf);
  expect(size, `${label}: not a decodable PNG`);
  if (width !== undefined) expectEqual(size.width, width, `${label}.width`);
  if (height !== undefined) expectEqual(size.height, height, `${label}.height`);
  if (minWidth !== undefined) expect(size.width >= minWidth, `${label}.width ${size.width} < minWidth ${minWidth}`);
  if (minHeight !== undefined) expect(size.height >= minHeight, `${label}.height ${size.height} < minHeight ${minHeight}`);
  return size;
}

module.exports = {
  AssertionError,
  expect,
  expectEqual,
  expectTruthy,
  expectFalsy,
  expectFrame,
  expectFramePayloadField,
  expectListing,
  expectAuthAck,
  expectBinarySnapshot,
  expectBinaryDelta,
  expectScrollback,
  expectTerminalContains,
  expectTerminalLineContains,
  expectTerminalNotContain,
  expectTerminalNonEmpty,
  expectTerminalHeight,
  expectImageSize,
  parsePngSize,
  toText,
};
