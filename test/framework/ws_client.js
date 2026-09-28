// ws_client.js — WebSocket 协议客户端（docs/protocol.md §3–§4）。
//
// 单条连接同时承载：text 消息 = JSON 控制帧；binary 消息 = 原始终端流帧（§1）。
// 客户端只负责按协议收发并做逐帧类型检查；断言（assertions.js）与用例（cases/）
// 消费它的事件，不做协议外的智能。所有方法均返回 Promise，超时显式抛错
// （红线5：失败必可见，绝不允许无限等待）。
//
// 事件模型（基于 EventTarget，Node 22 内置，零依赖）：
//   - 'control'  每收到一个合法控制帧，detail.frames = 全部未消费控制帧数组
//   - 'binary'   每收到一个二进制流帧，detail = decodeBinary 的结果
//   - 'close'    连接关闭，detail = { code, reason }
// 请求-应答的等待由 waitFor() / waitForType() 实现；消费过的帧从队列剔除，
// 未被消费的帧保留，避免快帧在两次等待之间丢失（协议是异步流，不保证一一对应）。

const { decodeBinary } = require('./binary');

const VERSION = 1;
// 每帧请求的默认超时（ms）。重连语义下 S 不保存状态，超时即显式失败可重试。
const DEFAULT_TIMEOUT_MS = 5000;

class AgentMirrorClient {
  constructor({ url, timeoutMs = DEFAULT_TIMEOUT_MS } = {}) {
    if (!url) throw new Error('AgentMirrorClient requires a ws:// url');
    this.url = url;
    this.timeoutMs = timeoutMs;
    this.ws = null;
    this.queue = [];        // 未消费的控制帧（保持到达顺序）
    this.nextReqId = 1;
    this.authed = false;
    this.closed = false;
    this.closeInfo = null;
    this._events = { control: [], binary: [], close: [] };
    this._errorHandler = null;
    this._recentBins = [];  // 最近二进制帧环形缓冲（防历史残留误判）
    this._binSeq = 0;
    this._lastBin = null;
  }

  // connect 建立 WebSocket 连接。不自动发 auth —— 认证是显式的（便于错误 token 用例）。
  // @contract
  // @pre 未连接（重复 connect 会抛错）
  // @post 连接已打开；此后 receive 帧进入队列并触发事件
  // @err 拨号失败/超时/非 101 响应时 reject
  async connect() {
    if (this.ws) throw new Error('already connected');
    return new Promise((resolve, reject) => {
      let settled = false;
      const timer = setTimeout(() => {
        if (!settled) { settled = true; reject(new Error(`connect timeout: ${this.url}`)); }
        try { this.ws && this.ws.close(); } catch { /* ignore */ }
      }, this.timeoutMs);

      let ws;
      try {
        ws = new WebSocket(this.url);
      } catch (err) {
        clearTimeout(timer);
        reject(new Error(`cannot open WebSocket ${this.url}: ${err.message}`));
        return;
      }
      this.ws = ws;
      ws.binaryType = 'arraybuffer';

      const onError = (ev) => {
        if (!settled) { settled = true; clearTimeout(timer); reject(new Error(`websocket error: ${ev.message || 'unknown'}`)); }
      };
      this._errorHandler = onError;
      ws.addEventListener('error', onError);

      ws.addEventListener('open', () => {
        if (!settled) { settled = true; clearTimeout(timer); resolve(); }
      });

      ws.addEventListener('message', (ev) => this._onMessage(ev.data));
      ws.addEventListener('close', (ev) => {
        this.closed = true;
        this.closeInfo = { code: ev.code, reason: ev.reason || '' };
        for (const fn of this._events.close) fn({ code: ev.code, reason: ev.reason || '' });
        if (!settled) { settled = true; clearTimeout(timer); reject(new Error(`connection closed before open: code=${ev.code} reason=${ev.reason}`)); }
      });
    });
  }

  _onMessage(data) {
    if (typeof data === 'string') {
      // 控制帧：JSON 信封 {v,type,payload}。未知字段忽略（§4.1 前向兼容），
      // 未知 type 由服务端回 error，客户端只转发。
      let frame;
      try { frame = JSON.parse(data); } catch (err) { return; }
      this.queue.push(frame);
      for (const fn of this._events.control) fn(frame);
    } else {
      // 二进制流帧：直接解码并触发 binary 事件。
      let bin;
      try { bin = decodeBinary(data); } catch (err) { return; }
      bin._seq = ++this._binSeq; // 单调序号，用于区分“新帧”与历史残留
      this._recentBins.push(bin);
      if (this._recentBins.length > 64) this._recentBins.shift();
      this._lastBin = bin;
      for (const fn of this._events.binary) fn(bin);
    }
  }

  // --- 请求-应答原语 ------------------------------------------------------

  _sendEnvelope(type, payload) {
    if (this.closed) throw new Error('connection closed');
    this.ws.send(JSON.stringify({ v: VERSION, type, payload }));
  }

  // waitForType 等待队列中出现指定 type 的控制帧（消费它）。
  // timeoutMs 内未到即抛错；服务端 error 帧按映射表转为可读错误。
  // @contract
  // @pre 无
  // @post 返回匹配帧；已从队列移除
  // @err 超时抛 Error；先收到 error 帧时按其 code 抛 Error（unsupported_version 关闭后也抛）
  async waitForType(type, { timeoutMs = this.timeoutMs } = {}) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      const idx = this.queue.findIndex((f) => f.type === type);
      if (idx >= 0) return this.queue.splice(idx, 1)[0];
      const errIdx = this.queue.findIndex((f) => f.type === 'error');
      if (errIdx >= 0) {
        const err = this.queue.splice(errIdx, 1)[0];
        throw new Error(`protocol error: ${err.payload.code}: ${err.payload.reason || ''}`.trim());
      }
      await sleep(25);
    }
    throw new Error(`timeout waiting for frame type "${type}" (${timeoutMs}ms)`);
  }

  // waitForControl 等待任意新控制帧（不消费），用于主动推送类（list_delta）。
  // 返回该帧；不保证是最新一帧。
  async waitForControl({ timeoutMs = this.timeoutMs } = {}) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      if (this.queue.length > 0) return this.queue[0];
      await sleep(25);
    }
    throw new Error(`timeout waiting for any control frame (${timeoutMs}ms)`);
  }

  // waitForBinary 等待一个满足条件的二进制流帧（不消费——流帧是镜像数据，
  // 语义上是“正在发生”，不做请求-应答配对）。
  // options: { kind, ref, after }  —— after 为 seq 下界（排除历史残留帧）。
  // @contract
  // @pre 无
  // @post 返回匹配的 decodeBinary 结果（带 _seq）
  // @err 超时抛 Error
  async waitForBinary({ kind = null, ref = null, after = 0, timeoutMs = this.timeoutMs } = {}) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      const match = this._recentBins.find((b) =>
        b._seq > after &&
        (kind === null || b.kind === kind) &&
        (ref === null || b.ref === ref)
      );
      if (match) return match;
      await sleep(25);
    }
    throw new Error(`timeout waiting for binary frame kind=${kind ?? 'any'} ref=${ref ?? 'any'} (${timeoutMs}ms)`);
  }

  // --- 协议操作（§3 生命周期） -------------------------------------------

  // auth 配对握手。成功置 authed；失败（ok:false，随后服务端关闭）抛 Error。
  // token 只上行一次，不回显不落日志（§9）。
  // @contract
  // @pre 已 connect
  // @post ok:true → authed=true；ok:false → 抛 Error
  // @err 拒绝 / 超时 / 连接先关 → Error
  async auth(token) {
    this._sendEnvelope('auth', { token });
    const ack = await this.waitForType('auth_ack');
    if (ack.payload.ok === true) {
      this.authed = true;
      return ack;
    }
    throw new Error(`auth rejected: ${ack.payload.reason || 'no reason'}`);
  }

  // list 拉取全量两级列表（§5.1）。返回 listing 帧。
  async list() {
    const reqId = this.nextReqId++;
    this._sendEnvelope('list', { req_id: reqId });
    return this.waitForType('listing');
  }

  // subscribe 订阅一个会话镜像：S 先回二进制 snapshot，再流 delta（§4.2）。
  // 返回订阅首帧 snapshot（解码结果）。snapshot 的 seq 会先于订阅到达的
  // 历史 delta 序列，用 after 下界排除。
  // @contract
  // @pre ref 来自 listing
  // @post 订阅成功；返回 kind=1 snapshot 帧
  // @err 会话不存在 → 服务端 error: session_not_found → Error
  async subscribe(ref, { rows = 24, cols = 80 } = {}) {
    const after = this._binSeq;
    this._sendEnvelope('subscribe', { ref, rows, cols });
    return this._waitForBinaryOrError({ kind: 1, after, timeoutMs: this.timeoutMs });
  }

  // input 注入整条文本（send-keys 语义，注入+回车，§4.2）。回执必达（input_ack）。
  // @contract
  // @pre ref 已订阅
  // @post 返回 input_ack 帧；ok:false 抛 Error（带 reason）
  // @err 超时 / ok:false → Error
  async input(ref, text, { keys } = {}) {
    const reqId = this.nextReqId++;
    const payload = { req_id: reqId, ref };
    // text 与 keys 互斥（§4.2 R-1）；两者都缺 = 仅回车。
    if (keys !== undefined) payload.keys = keys;
    else payload.text = text === undefined ? '' : text;
    this._sendEnvelope('input', payload);
    const ack = await this.waitForType('input_ack');
    if (ack.payload.ok === true) return ack;
    throw new Error(`input failed: ${ack.payload.reason || 'no reason'}`);
  }

  // scrollback 按行区间拉历史（§4.2 / §6.3）。返回二进制帧的 scrollback 载荷。
  // from_line: 0=当前屏顶行，负值=屏上历史；count>=1。
  async scrollback(ref, fromLine, count) {
    const reqId = this.nextReqId++;
    const after = this._binSeq;
    this._sendEnvelope('scrollback', { req_id: reqId, ref, from_line: fromLine, count });
    // scrollback 回复是二进制 kind=3；但服务端可能先回 error（ref 不存在）。
    return this._waitForBinaryOrError({ kind: 3, after, timeoutMs: this.timeoutMs });
  }

  // unsubscribe 停止镜像（幂等，§4.2）。
  async unsubscribe(ref) {
    this._sendEnvelope('unsubscribe', { ref });
    return true;
  }

  // resize 上报手机行列数；成功应用后 S 补发一帧 snapshot（§4.2）。
  async resize(ref, { rows, cols } = {}) {
    this._sendEnvelope('resize', { ref, rows, cols });
    return true;
  }

  // close 关闭连接。关闭即隐式退订全部会话（§3）。
  close() {
    try { this.ws && this.ws.close(1000, 'client close'); } catch { /* ignore */ }
  }

  // _waitForBinaryOrError 等一个 kind 的二进制帧；若期间先到 error 帧则抛错。
  // 支持 after 下界，避免返回历史残留帧。
  async _waitForBinaryOrError({ kind, after = 0, timeoutMs }) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      const errIdx = this.queue.findIndex((f) => f.type === 'error');
      if (errIdx >= 0) {
        const err = this.queue.splice(errIdx, 1)[0];
        throw new Error(`protocol error: ${err.payload.code}: ${err.payload.reason || ''}`.trim());
      }
      const match = this._recentBins.find((b) => b._seq > after && b.kind === kind);
      if (match) return match;
      await sleep(25);
    }
    throw new Error(`timeout waiting for binary frame kind=${kind} (${timeoutMs}ms)`);
  }

  // --- 事件监听 -----------------------------------------------------------
  on(event, fn) {
    if (!this._events[event]) throw new Error(`unknown event: ${event}`);
    this._events[event].push(fn);
    return this;
  }

  // collectBinaries 把二进制帧追加到给定数组（供用例累积镜像数据做文本断言）。
  collectBinaries(arr) {
    return this.on('binary', (bin) => arr.push(bin));
  }
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

module.exports = { AgentMirrorClient, VERSION, DEFAULT_TIMEOUT_MS };
