// d36_scrollback.test.js — D-36 红测（test/ 框架协议层半边）：scrollback 回复必须是「可用的一页历史」。
//
// D-36 goal（taskbook）：向上滑动无法查看历史消息。触摸滑动手势未转成 scrollback 请求。
// test/ 框架只测协议层（产品无感知，见 README），所以这里锁的是**服务端 scrollback 契约**——
// 客户端手势一旦接通（D-36 修复的 Kotlin 侧），消费的就是这个回复。协议依据
// docs/protocol.md §4.2 / §6.3：
//   C→S scrollback {req_id, ref, from_line, count}（from_line：0=当前屏顶，负值=屏上历史）
//   S→C [binary kind=3] scrollback：payload 头 12 字节元数据 {req_id, from_line, line_count} + ANSI 字节
//   §6.3：from_line/line_count 是**服务端收敛后的实际区间**，客户端据此锚定滚动视口；
//   无此元数据则客户端无法定位收敛后的页锚点，历史拼接会错位。
//
// 今日服务端缺陷（实测确认，非猜测）：
//   ws_handler.go handleScrollback 按「协议行 - pane.Height」翻译给 bridge，而 bridge.go
//   Scrollback 文档自述「-1 = 屏幕底部行」。但 tmux capture-pane 的坐标是**顶部相对**：
//   0 = 屏顶，负值 = 屏上历史（实测 tmux 3.6a：-S -1 返回屏顶上一行）。平移 pane.Height
//   把每一页都打偏：
//     * 当前屏请求 scrollback(0,6)：可见屏为 s-line-4..8，服务端却回 s-line-1..3 且
//       lineCount=6（数据仅 3 行）——内容错 + 元数据撒谎；
//     * 历史请求 scrollback(-30,5)：内容 h-line-1..5（正确收敛到最旧页），但 fromLine=-2
//       （真实应 -26，屏顶 h-line-27 为协议 0）——页锚点错位，客户端历史视口必然错。
//   修复方向（服务端，另拆案）：协议坐标**直接直传 tmux**，不做 paneHeight 平移；
//   bridge.go 的坐标语义注释同步更正为顶部相对；元数据必须与数据一致。
//   本文件两条用例今日红，修复后变绿。

const fs = require('fs');
const path = require('path');
const { globalRegistry } = require('../framework/registry');
const { AgentMirrorClient } = require('../framework/ws_client');
const { decodeScrollbackPayload, stripAnsi, splitLines } = require('../framework/binary');
const { expectListing, expect, expectEqual } = require('../framework/assertions');
const { getEnvironment } = require('../framework/context');

// 行号标记正则：解析 "MARK_N_<n>" 行里的 <n>（行号即内容，规避 tmux 坐标歧义）。
function markerNum(text, prefix) {
  const m = text.match(new RegExp(`${prefix}_(\\d+)`));
  return m ? parseInt(m[1], 10) : null;
}

// 建一个隔离 tmux 会话，输出 N 行唯一前缀的序号行后长眠。返回 sessionDir。
function createSession(tmuxInfo, workDir, marker, prefix, n) {
  const sessionDir = path.join(workDir, marker);
  fs.mkdirSync(sessionDir, { recursive: true });
  const scriptFile = path.join(sessionDir, 'd36.sh');
  const script = [
    '#!/bin/sh',
    `for i in $(seq 1 ${n}); do echo ${prefix}_$i; done`,
    'sleep 3600',
    '',
  ].join('\n');
  fs.writeFileSync(scriptFile, script);
  fs.chmodSync(scriptFile, 0o755);
  tmuxInfo.tmux(['new-session', '-d', '-s', marker, '-c', sessionDir, scriptFile]);
  return sessionDir;
}

// 连接 + auth + list，按 cwd（realpath 归一）定位目标会话 ref。
async function connectAndList(port, token, sessionDir) {
  const client = new AgentMirrorClient({ url: `ws://127.0.0.1:${port}/ws` });
  await client.connect();
  await client.auth(token);
  const listing = await client.list();
  const workspaces = expectListing(listing);
  const sessions = workspaces.flatMap((w) => w.sessions || []);
  const want = fs.realpathSync(sessionDir);
  const target = sessions.find((s) => {
    try { return s.cwd && fs.realpathSync(s.cwd) === want; } catch (e) { return false; }
  });
  expect(target && target.ref, `session for cwd ${sessionDir} not found in listing`);
  return { client, ref: target.ref };
}

// ── 用例 A（红测本体）：当前屏请求 scrollback(0, count) 必须返回「当前可见屏」。 ──
// 客户端上滑第一步拉的正是当前屏：若服务端把它打成历史最旧行（s-line-1..3）而非
// 可见屏（s-line-4..8），手势接通后看到的也是错页——D-36 的目标在协议层即被阻断。
// 断言（今日全红，修复后全绿）：
//   [1] 帧型：kind=3（scrollback）、ref 匹配、元数据可解码；
//   [2] 数据行数 === 元数据 line_count（§6.3「实际区间」自洽）；
//   [3] 页包含可见屏末行（最末输出标记）——证明是真屏幕，不是被裁切的片断；
//   [4] 页包含可见屏首行标记——证明是整屏，不是历史尾巴。
globalRegistry.define({
  name: 'd36:scrollback-current-screen-returns-visible-page',
  tags: ['d36', 'scrollback'],
  description: 'D-36 红测：scrollback(0,count) 必须返回当前可见屏（内容=屏、元数据自洽）',
  async fn(ctx, helpers) {
    const { port, token, workDir, tmuxInfo } = getEnvironment();
    const marker = `d36a_${Date.now().toString(36)}`;
    const prefix = 'D36SCREEN';
    const totalLines = 8;
    const sessionDir = createSession(tmuxInfo, workDir, marker, prefix, totalLines);
    await sleep(700); // 输出落定后再订阅
    const { client, ref } = await connectAndList(port, token, sessionDir);
    try {
      // 订阅 6 行屏：可见屏 = 底部 6 行（s-line-3..8 或 s-line-4..8，tmux 裁尾空行）。
      const snap = await client.subscribe(ref, { rows: 6, cols: 60 });
      await sleep(400);
      const visibleText = stripAnsi(snap.payload);
      const visibleLines = splitLines(visibleText);
      helpers.log(`visible screen (snapshot, ${visibleLines.length} lines): ${JSON.stringify(visibleText)}`);
      expect(visibleLines.length >= 2, `subscribe snapshot should be a real screen, got ${visibleLines.length} line(s)`);
      const bottomNum = markerNum(visibleLines[visibleLines.length - 1], prefix);
      const topNum = markerNum(visibleLines[0], prefix);
      expect(bottomNum != null && topNum != null, `visible screen lines must be ${prefix}_<n> markers`);

      // 当前屏请求：from_line=0 count=6。
      const bin = await client.scrollback(ref, 0, 6);
      // [1] 帧型与元数据存在（今日已满足——证明测试链路通，后续断言的红是内容真红）。
      expectEqual(bin.kind, 3, 'scrollback kind');
      expectEqual(bin.ref, ref, 'scrollback ref');
      const meta = decodeScrollbackPayload(bin.payload);
      helpers.log(`scrollback(0,6) meta: fromLine=${meta.fromLine} lineCount=${meta.lineCount} dataBytes=${meta.data.length}`);

      const pageLines = splitLines(stripAnsi(meta.data));
      helpers.log(`scrollback page (${pageLines.length} lines): ${JSON.stringify(stripAnsi(meta.data))}`);

      // [2] 数据行数 === 元数据 line_count（协议自洽）。
      expectEqual(
        pageLines.length, meta.lineCount,
        `D-36: scrollback data line count must equal metadata line_count (§6.3)`,
      );
      // [3] 页必须包含可见屏末行标记（最末输出）——今日服务端回 s-line-1..3，缺末行 → 红。
      const lastNum = markerNum(pageLines[pageLines.length - 1], prefix);
      expect(
        lastNum != null && lastNum >= bottomNum,
        `D-36: current-screen page must reach the visible bottom (last=${lastNum}, bottom=${bottomNum})`,
      );
      // [4] 页必须包含可见屏首行标记——整屏而非历史尾巴。
      const firstNum = markerNum(pageLines[0], prefix);
      expect(
        firstNum != null && firstNum >= topNum - 1,
        `D-36: current-screen page must start at/near the visible top (first=${firstNum}, top=${topNum})`,
      );
      helpers.log(`d36 current-screen page OK: ${pageLines.length} lines ${firstNum}..${lastNum}`);
    } finally {
      client.close();
    }
  },
});

// ── 用例 B（红测本体）：历史请求的元数据 from_line 必须与页内容一致。 ──
// 客户端上滑的第二段拉屏上历史；§6.3 元数据是客户端锚定滚动视口的唯一依据。
// 今日服务端把 fromLine 报错（-30,5 → fromLine=-2，真实应 -26），页锚点错位，
// 历史拼接必然错——即使内容是对的（收敛到最旧页）。
// 断言：由可见屏顶行号 T 与页首行号 F 推导协议坐标（协议 0=T，F 的协议行=F−T），
// 断言服务端报告的 fromLine === F − T。今日 -2 ≠ 1−27=−26 → 红；修复后变绿。
globalRegistry.define({
  name: 'd36:scrollback-history-meta-matches-page',
  tags: ['d36', 'scrollback'],
  description: 'D-36 红测：scrollback 历史页元数据 from_line 必须与页内容一致（页锚点正确）',
  async fn(ctx, helpers) {
    const { port, token, workDir, tmuxInfo } = getEnvironment();
    const marker = `d36b_${Date.now().toString(36)}`;
    const prefix = 'D36HIST';
    const totalLines = 30;
    const sessionDir = createSession(tmuxInfo, workDir, marker, prefix, totalLines);
    await sleep(700);
    const { client, ref } = await connectAndList(port, token, sessionDir);
    try {
      // 订阅 5 行屏：可见屏 = h-line-27..30（协议 0 处）。
      const snap = await client.subscribe(ref, { rows: 5, cols: 60 });
      await sleep(400);
      const visibleLines = splitLines(stripAnsi(snap.payload));
      const topNum = markerNum(visibleLines[0], prefix);
      helpers.log(`visible screen top = ${prefix}_${topNum} (protocol 0)`);
      expect(topNum != null, `visible screen top must be ${prefix}_<n>`);

      // 拉屏上历史：from_line=-30 count=5 → 完全在历史之上，收敛到最旧页。
      const bin = await client.scrollback(ref, -30, 5);
      expectEqual(bin.kind, 3, 'scrollback kind');
      expectEqual(bin.ref, ref, 'scrollback ref');
      const meta = decodeScrollbackPayload(bin.payload);
      const pageLines = splitLines(stripAnsi(meta.data));
      helpers.log(`scrollback(-30,5) meta: fromLine=${meta.fromLine} lineCount=${meta.lineCount} dataLines=${pageLines.length}`);
      helpers.log(`  page=${JSON.stringify(stripAnsi(meta.data))}`);

      // 数据行数 === 元数据 line_count（今日满足：5==5）。
      expectEqual(pageLines.length, meta.lineCount, 'D-36: history data line count must equal metadata line_count');

      // 页非空且首行可解析。
      expect(pageLines.length >= 1, 'D-36: history page must be non-empty');
      const firstNum = markerNum(pageLines[0], prefix);
      expect(firstNum != null, `history page first line must be ${prefix}_<n>`);

      // 协议坐标推导：协议 0 = 可见屏顶 topNum，协议 (firstNum − topNum) = 页首行 firstNum。
      // 服务端报告的 fromLine 必须等于该推导值 —— 今日 -2 ≠ -26 → 红。
      const expectedFromLine = firstNum - topNum;
      expectEqual(
        meta.fromLine, expectedFromLine,
        `D-36: history page meta.fromLine must match its content (first=${firstNum}, screenTop=${topNum})`,
      );
      helpers.log(`d36 history page OK: fromLine=${meta.fromLine} == ${expectedFromLine} (${pageLines.length} lines)`);
    } finally {
      client.close();
    }
  },
});

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}
