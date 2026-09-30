// machine_eye.test.js — 机器眼 Layer 0 考卷（三个算子的已知答案验收）。
//
// 硬考卷：时间算子必须在 ime-no-resize 254 帧上复现「恰 3 帧非零差分且纯滚动」。
// 其余考卷：空间算子内容边界、符号算子 bounds、版本对比。
// 全部读 e2e/artifacts/ 真实语料，输出确定数字，不依赖 daemon/tmux（纯本地文件分析）。

'use strict';

const path = require('path');
const { globalRegistry } = require('../framework/registry');
const { analyzeFrame } = require('../framework/machine_eye/space');
const { analyzeSequence } = require('../framework/machine_eye/time');
const { analyzeDump } = require('../framework/machine_eye/symbol');

// 语料根（相对本文件，repo 根/e2e/artifacts）。
const ART = path.resolve(__dirname, '../../e2e/artifacts');

function assert(cond, msg) {
  if (!cond) throw new Error(msg);
}

// ---- 时间算子考卷 ----

globalRegistry.define({
  name: 'machine-eye:T1-ime-254-frames-3-diff-no-reflow',
  tags: ['machine-eye', 'time'],
  localOnly: true,
  description: '硬考卷：ime frames2 254 帧 → 恰 3 帧非零差分、纯滚动、无重排',
  async fn() {
    const dir = path.join(ART, 'ime-no-resize/frames2');
    const r = analyzeSequence(dir, { region: { top: 254, bottom: 2010, left: 0, right: 1079 } });
    assert(r.status === 'OK', `时间算子判不出: ${r.reason}`);
    assert(r.frameCount === 254, `帧数应 254，实 ${r.frameCount}`);
    // 硬考卷：恰 3 帧非零差分（已知答案：2/3/4 行三次增高）。
    assert(
      r.nonZeroDiffFrames.length === 3,
      `硬考卷失败：非零差分帧应恰 3，实 ${JSON.stringify(r.nonZeroDiffFrames)}（已知答案 3）`,
    );
    // 纯滚动（整屏上推），无重排。
    assert(r.movementPattern === 'SCROLL_DOWN', `形态应 SCROLL_DOWN（纯滚动），实 ${r.movementPattern}`);
    assert(r.reflowSignal === false, '不得有重排信号（IME 挤压不触发重排）');
  },
});

globalRegistry.define({
  name: 'machine-eye:T2-send9-bottom-append-no-fullredraw',
  tags: ['machine-eye', 'time'],
  localOnly: true,
  description: '发消息增行：底部追加、无整屏重绘（reflowSignal=false）',
  async fn() {
    const dir = path.join(ART, 'ime-no-resize/frames-send9');
    const r = analyzeSequence(dir, { region: { top: 254, bottom: 2010, left: 0, right: 1079 } });
    assert(r.status === 'OK', `时间算子判不出: ${r.reason}`);
    assert(r.reflowSignal === false, '发消息不得整屏重绘（reflowSignal=false）');
    // 底部追加：差分区贴底（bottomRatio 高）。
    const bottomRatios = Object.values(r.diffAreas).map((d) => d.bottomRatio);
    assert(
      bottomRatios.length > 0 && bottomRatios.every((b) => b > 0.7),
      `发消息差分应贴底（bottomRatio>0.7），实 ${JSON.stringify(bottomRatios)}`,
    );
  },
});

// ---- 空间算子考卷 ----

globalRegistry.define({
  name: 'machine-eye:S1-final-screenshot-bottom-margin',
  tags: ['machine-eye', 'space'],
  localOnly: true,
  description: '四行输入框档位截图：内容底边在终端区内、底部留白可量化',
  async fn() {
    const png = path.join(ART, 'ime-no-resize/11-final-screenshot.png');
    const r = analyzeFrame(png);
    assert(r.status === 'OK', `空间算子判不出: ${r.reason}`);
    // 终端带识别（深底段，非全屏/非键条）。
    assert(r.terminalBand.top > 0 && r.terminalBand.bottom < 2400, `终端带越界: ${r.terminalBand}`);
    // 内容底边在终端带内（不越出终端）。
    assert(
      r.lastTextBaselineY <= r.terminalBand.bottom,
      `内容底边(${r.lastTextBaselineY}) 越过终端带底(${r.terminalBand.bottom})`,
    );
  },
});

globalRegistry.define({
  name: 'machine-eye:S2-d38-pinch-baseline-vs-after',
  tags: ['machine-eye', 'space'],
  localOnly: true,
  description: 'd38 捏合前后：空间边界数字可对比（捏合放大改变内容右/底缘）',
  async fn() {
    const base = path.join(ART, 'd38-viewport-restore');
    const before = analyzeFrame(path.join(base, '10-pinchC-baseline.png'));
    const after = analyzeFrame(path.join(base, '11-pinchC-after-pinch.png'));
    assert(before.status === 'OK' && after.status === 'OK', '捏合前后帧需都可分析');
    // 输出数字（对比点：捏合改变字格 → 内容右缘/底缘可能变）。
    // 本考卷只断言「两帧都能算出数字」+ 记录对比，具体阈值由 w-base-v2 模拟器实测定。
    console.log(`[machine-eye S2] baseline rightmost=${before.rightmostNonBgX} bottom=${before.lastTextBaselineY} | ` +
      `after rightmost=${after.rightmostNonBgX} bottom=${after.lastTextBaselineY}`);
  },
});

// ---- 符号算子考卷 ----

globalRegistry.define({
  name: 'machine-eye:N1-ime-inputfield-top-monotonic',
  tags: ['machine-eye', 'symbol'],
  localOnly: true,
  description: 'IME 档位 XML：输入框 top 随档位单调变化（挤压）',
  async fn() {
    const base = path.join(ART, 'ime-no-resize');
    const stages = ['05-ime-focus', '07-stage2-3line', '08-stage3-4line'];
    const tops = [];
    for (const s of stages) {
      const r = analyzeDump(path.join(base, `${s}.xml`));
      assert(r.status === 'OK', `${s} 符号算子判不出: ${r.reason}`);
      tops.push(r.boundsByRole.inputField.top);
    }
    // 输入框 top 单调减小（输入框变高、上沿上移）：focus → 3行 → 4行。
    assert(
      tops[0] > tops[1] && tops[1] > tops[2],
      `输入框 top 应随档位单调减小（变高），实 ${JSON.stringify(tops)}`,
    );
  },
});

globalRegistry.define({
  name: 'machine-eye:N2-terminal-view-bounds-shift',
  tags: ['machine-eye', 'symbol'],
  localOnly: true,
  description: 'IME 挤压：终端 View bottom 随档位收缩（内容区变小）',
  async fn() {
    const base = path.join(ART, 'ime-no-resize');
    const stage2 = analyzeDump(path.join(base, '05-ime-focus.xml'));
    const stage4 = analyzeDump(path.join(base, '08-stage3-4line.xml'));
    assert(stage2.status === 'OK' && stage4.status === 'OK', '档位 XML 需可分析');
    const tv2 = stage2.boundsByRole.terminalView;
    const tv4 = stage4.boundsByRole.terminalView;
    assert(tv4.bottom < tv2.bottom, `终端 View bottom 应随档位收缩，实 ${tv2.bottom} → ${tv4.bottom}`);
  },
});

// ---- 版本对比考卷（abc-regression 三版本空间一致性）----

globalRegistry.define({
  name: 'machine-eye:T3-abc-versions-spatial-comparable',
  tags: ['machine-eye', 'space'],
  localOnly: true,
  description: 'abc-regression A/B/C 三版本同档位空间边界可对比（输出数字）',
  async fn() {
    const base = path.join(ART, 'abc-regression/keyframes');
    const versions = ['A-git-v2', 'B-d35fix', 'C-v4'];
    for (const ver of versions) {
      const png = path.join(base, `${ver}-03-two-lines.png`);
      const r = analyzeFrame(png);
      assert(r.status === 'OK', `${ver} 空间算子判不出: ${r.reason}`);
      // 记录（考卷验收点：三版本空间数字可对比）。
      console.log(`[machine-eye T3] ${ver} rightmost=${r.rightmostNonBgX} bottomY=${r.lastTextBaselineY}`);
    }
  },
});
