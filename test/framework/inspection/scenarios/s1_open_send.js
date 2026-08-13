/*
 * Layer 1 场景 S1：打开会话 + 发一条消息。
 *
 * 一个场景同时检多项指标（用户 11 条缺陷里 4 条会在本场景暴露）：
 * - rightEdgeGapPx（右列截断）
 * - bottomMarginPx（D-38 底部空黑）
 * - lastRowVisible（D-20 末行被遮）
 * - inputTop（输入框跑到中间）
 * - diffPattern/reflowSignal（发消息整屏刷）
 * - paneRows/paneCols（主机 pane 记账）
 *
 * 动作注入由 w-base-v2 执行（本席禁碰模拟器）；本文件只声明场景，不执行。
 * 依赖：隔离 daemon/tmux（fixtures 起）、w-base-v2 的 adb/模拟器连接。
 */

'use strict';

const { defineScenario } = require('../scenario');

defineScenario({
  id: 'S1-open-session-send',
  tags: ['inspection', 's1'],

  // 前置状态：隔离 tmux 会话，内容填充量固定（20 行 LINE-N + CJK + Powerline，对齐 R3）。
  // 动作序列（w-base-v2 执行，命令由 exec.js 提供）。
  actions: [
    { type: 'fixture', fn: 'prepareSession', fixture: '20-line-cjk' }, // 填充隔离会话
    { type: 'adb', args: ['shell', 'am', 'start', '-n', 'dev.agentmirror.app/.SessionActivity'] },
    { type: 'wait', ms: 1500 },                                        // 等快照稳定
    { type: 'capture', name: 'open-stable', kind: 'png' },             // 采集点① 打开后稳定帧
    { type: 'capture', name: 'open-dump', kind: 'uiautomator' },       // 采集点① dump
    { type: 'input', text: 'echo hello' },                             // 发消息
    { type: 'wait', ms: 1000 },
    { type: 'capture', name: 'send-stable', kind: 'png' },             // 采集点② 发后稳定帧
    { type: 'screenrecord', name: 'send-rec', durationMs: 3000 },      // 采集点② 过程录屏
    { type: 'tmux', cmd: 'display-message', args: ['-p', '#{pane_width}x#{pane_height}'] }, // pane 记账
  ],

  // 采集点 → 指标。
  metrics: [
    { name: 'rightEdgeGapPx', capture: 'open-stable', fn: 'space.analyzeFrame', target: 'rightMarginPx', direction: 'higher-better', healthValue: 100, knownBadValue: 0 },
    { name: 'bottomMarginPx', capture: 'open-stable', fn: 'space.analyzeFrame', target: 'bottomMarginPx', direction: 'lower-better', healthValue: 6, knownBadValue: 1123 },
    { name: 'lastRowVisible', capture: ['open-stable', 'open-dump'], fn: 'space+symbol', target: 'lastRowVisible', direction: 'equals', healthValue: true },
    { name: 'inputTop', capture: 'open-dump', fn: 'symbol.analyzeDump', target: 'boundsByRole.inputField.top', direction: 'higher-better', healthValue: 2000 },
    { name: 'diffPattern', capture: 'send-rec', fn: 'time.analyzeSequence', target: 'movementPattern', direction: 'equals', healthValue: 'BOTTOM_APPEND' },
    { name: 'reflowSignal', capture: 'send-rec', fn: 'time.analyzeSequence', target: 'reflowSignal', direction: 'equals', healthValue: false },
    { name: 'paneRows', capture: 'tmux', fn: 'tmux.size', target: 'rows', direction: 'equals' },
    { name: 'paneCols', capture: 'tmux', fn: 'tmux.size', target: 'cols', direction: 'equals' },
  ],

  // 注延迟变体（用户主场景 Tailscale，局域网会掩盖延迟缺陷）。
  latencyVariants: [
    { name: 'default', latencyMs: 0 },
    { name: 'tailscale-200ms', latencyMs: 200 },
  ],
});
