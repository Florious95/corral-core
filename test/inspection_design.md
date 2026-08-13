# 机器眼 Layer 1 设计：巡检（一个场景，多指标同时检）

> 状态：**设计稿，等 leader 批准后写**。
> 边界：只写 `test/`。**动作注入（adb/模拟器）部分写脚本但不执行**——执行由 w-base-v2 做
> （本席位通道禁止碰模拟器）。接口设计成「可执行的场景脚本 + 指标计算」，w-base-v2 负责跑。
> 与 Layer 0 区别：Layer 0 给定图算数字（被动）；Layer 1 给定场景自动跑一遍、一次吐全部指标（主动）。
> 用户要的「第一轮结束就收到一个都解决的 APK」，缺的就是这一层——每轮都量，不靠用户发现问题。

## 〇、一句话

Layer 0 让我们**能**量。Layer 1 决定我们**每轮都量**——跑一遍标准场景，把所有指标吐出来，
问题自己掉出来。数字存基线，任何一个变差就红（棘轮）。

## 一、核心设计：场景（Scenario）→ 指标集（Metrics）

```
场景 = 前置状态 + 动作序列 + 采集点 + 指标集
```

一次巡检 = 跑一个场景 → 在采集点采图/录屏/UI dump → 对每个采集点应用 Layer 0 算子 →
输出**数字表**（每指标一个数字）+ 与基线棘轮比对。

### 场景定义（S1-S5）

| 场景 | 前置状态 | 动作序列 | 采集点 |
|---|---|---|---|
| **S1 打开会话+发消息** | 隔离 tmux 会话，内容填充量固定（如 20 行 LINE-N + CJK + Powerline，对齐 R3） | 打开会话 → 等待快照 → 发一条消息 → 等待回显 | ①打开后稳定帧 ②发消息后稳定帧 ③发消息过程录屏 |
| **S2 捏合放大/缩小** | 同上 | 双指捏合放大 → 稳定 → 缩小 → 稳定 | ①基线 ②放大后 ③缩小后 |
| **S3 切后台→回前台** | 同上 | 切后台（Home）→ 停留 2s → 回前台 | ①回前台稳定帧 ②IME 在屏变体：回前台前先唤起 IME |
| **S4 上滑看历史** | scrollback 有历史（>1 屏） | 上滑 → 停留 → 回底 | ①上滑后稳定帧 ②回底后 |
| **S5 退出再进入** | 同上 | 退出会话 → 重新打开 | ①重开后稳定帧 |

### 指标集（每场景同时检多项）

| 指标 | 算子 | 对应缺陷 | 基线 |
|---|---|---|---|
| `rightEdgeGapPx` = 视口右缘 - 内容右缘 | space | 右列截断 | >100（有右侧空白） |
| `bottomMarginPx` = 终端底 - 内容底 | space | D-38 底部空黑 | <50（内容占满） |
| `lastRowVisible` = 末行底边 vs 输入框上沿 | space+symbol | D-20 末行被遮 | 末行底 < 输入框上沿 |
| `inputTop` = 输入框 y | symbol | 输入框跑到中间 | 固定（如 >2000） |
| `diffPattern` = 帧差分形态 | time | 发消息整屏刷 / 捏合闪烁 | 见各场景 |
| `reflowSignal` = 是否重排 | time | IME 挤压重排 | false |
| `paneRows` / `paneCols` = 主机 tmux pane 尺寸 | （脚本读 tmux） | 主机 pane 记账 | = 预期 |

### 采集点 → 指标的对应（哪个采集点出哪个指标）

- **稳定帧**（截图 PNG）→ space 算子全部（rightEdgeGap/bottomMargin/lastRowVisible）
- **UI dump**（uiautomator XML）→ symbol 算子（inputTop/lastRowVisible 交叉）
- **过程录屏**（mp4）→ time 算子（diffPattern/reflowSignal）
- **tmux 查询** → paneRows/paneCols

## 二、输出：数字表 + 棘轮

### 数字表格式（一次巡检输出）

```json
{
  "scenario": "S1-open-session-send",
  "runId": "2026-08-13T...",
  "metrics": {
    "rightEdgeGapPx": { "value": 143, "status": "OK" },
    "bottomMarginPx": { "value": 6, "status": "OK" },
    "lastRowVisible": { "value": true, "status": "OK" },
    "inputTop": { "value": 2060, "status": "OK" },
    "diffPattern": { "value": "BOTTOM_APPEND", "status": "OK" },
    "reflowSignal": { "value": false, "status": "OK" },
    "paneRows": { "value": 24, "status": "OK" }
  },
  "baseline": "2026-08-13T00:00:00Z",
  "regressions": []
}
```

### 棘轮（Ratchet）—— 硬要求 1 + leader 补的三机制

**棘轮是单向的：只允许往好的方向走。**

- 每指标存**基线值**（首次巡检确立）。
- 本轮值 vs 基线比较，**任何一个数字变差就红**（`regressions` 数组列出）。
- 「变差」定义按指标方向：`rightEdgeGapPx` 变小（右缘逼近）、`bottomMarginPx` 变大（底部空白）、
  `reflowSignal` false→true、`lastRowVisible` true→false、`paneRows` 偏离预期。

**机制 A：改善必须收紧基线（leader 裁定，2026-08-13）**
- 若基线是从**有缺陷状态**确立的（如 D-38 失败态 bottomMarginPx=1123），棘轮会锁住缺陷本身
  （将来回归到 100 也比 1123 好，不红 → 缺陷合法化）。
- 因此：**指标改善时，基线自动收紧到新值**。
  - bottomMarginPx 从 1123 改善到 6 → 基线立刻变成 6
  - 之后任何 > 6 的值都红
- 单向棘轮：只允许往好的方向走，坏方向立即红。

**机制 B：基线必须带产地，产地不同不可比（leader 裁定）**
- 每条基线记录：**构建 sha**、**设备**（`agentmirror_geo_1260x2800` vs `1080x2400` 数字根本不可比）、
  **延迟条件**（0ms / 200ms-each-way）、**场景前置状态**（内容填充量）。
- 产地不同 → 直接判 `NOT_COMPARABLE`，不许硬比（和「未测到默认不通过」同纪律）。

**机制 C：初始基线分级（leader 裁定，别用当前状态）**
- **已知健康值优先**：有明确健康数字直接用（如 bottomMarginPx 健康 ≈6）。
- **已知失败值绝不作基线**：1123 标 `KNOWN_BAD`，作为**必须被改善**的目标，不是「不许变差」的下限。
- **没有已知值的**：首轮标 `PROVISIONAL`，明确它还没有权威性，**不许用它红别人**。

棘轮是「不倒退由机器判」——v5 那次「五个修复三个 QA PASS 却引入闪烁回归」，在棘轮下当场红。

### 未测到（Not-Measured）—— 硬要求 2

- 每个指标必须能声明「这轮没测到」，**不许缺失的指标默认算通过**。
- 实现：指标对象加 `measured: false` + `reason`，默认**未测到 = 不通过**（`status: 'NOT_MEASURED'`）。
- 例：S1 录屏失败 → `diffPattern` 标 `NOT_MEASURED: "录屏失败"`，不进 OK 集，棘轮也标红/待补。
- **沉默的缺失比红灯危险**——缺失必须显式喊出来。

### 注延迟（Latency-injected）—— 硬要求 3

- 场景必须能在**注延迟条件**下跑（用户主场景是 Tailscale，局域网掩盖延迟缺陷）。
- 实现：场景脚本接受 `latencyMs` 参数，执行时在关键动作间注入 sleep（如 `networkDelay 200ms`）。
- S1/S3/S4 默认带 `latencyMs: 200`（对齐用户 tailscale 典型 RTT）变体。
- 这是本工程明文纪律，整轮调查都违反过它。

## 三、接口设计（w-base-v2 执行，我写脚本）

### 目录

```
test/framework/inspection/
├── scenario.js        # 场景定义：注册 + 驱动（采集→算子→数字表→棘轮）
├── metrics.js         # 指标计算：采集点文件 → Layer 0 算子 → 数字
├── ratchet.js         # 棘轮：基线存取 + 变差检测
├── exec.js            # 动作注入执行器（adb/模拟器命令，w-base-v2 跑；本席不执行）
├── scenarios/
│   ├── s1_open_send.js
│   ├── s2_pinch.js
│   ├── s3_back_foreground.js
│   ├── s4_scroll_history.js
│   └── s5_exit_reenter.js
└── index.js
```

### 场景脚本接口（w-base-v2 执行）

```js
// scenarios/s1_open_send.js
module.exports = {
  id: 'S1-open-session-send',
  tags: ['inspection', 's1'],
  // 动作序列（exec.js 执行，命令由 w-base-v2 环境提供）
  actions: [
    { type: 'adb', args: ['shell', 'am', 'start', '...'] },   // 打开会话
    { type: 'wait', ms: 1500 },                                // 等快照稳定
    { type: 'capture', name: 'open-stable', kind: 'png' },     // 采集点①
    { type: 'capture', name: 'open-dump', kind: 'uiautomator' },
    { type: 'input', text: 'echo hello' },                     // 发消息
    { type: 'wait', ms: 1000 },
    { type: 'capture', name: 'send-stable', kind: 'png' },     // 采集点②
    { type: 'screenrecord', name: 'send-rec', durationMs: 3000 }, // 录屏
  ],
  // 采集点 → 指标
  metrics: [
    { name: 'rightEdgeGapPx', capture: 'open-stable', fn: 'space.analyzeFrame', target: 'rightEdgeGapPx' },
    { name: 'bottomMarginPx', capture: 'open-stable', fn: 'space.analyzeFrame', target: 'bottomMarginPx' },
    { name: 'lastRowVisible', capture: ['open-stable', 'open-dump'], fn: 'space+symbol', target: 'lastRowVisible' },
    { name: 'inputTop', capture: 'open-dump', fn: 'symbol.analyzeDump', target: 'inputTop' },
    { name: 'diffPattern', capture: 'send-rec', fn: 'time.analyzeSequence', target: 'movementPattern' },
    { name: 'reflowSignal', capture: 'send-rec', fn: 'time.analyzeSequence', target: 'reflowSignal' },
  ],
  // 基线（首跑确立，之后棘轮）
  baseline: { /* 数字表 */ },
  // 注延迟变体
  latencyVariants: [{ name: 'tailscale-200ms', latencyMs: 200 }],
};
```

### w-base-v2 执行方式

```bash
cd test && node runner.js --tag=inspection        # 全部场景
node runner.js --tag=inspection --name=S1          # 单场景
node runner.js --tag=inspection --latency=200      # 注延迟变体
```

runner 起隔离 daemon/tmux（复用 fixtures），w-base-v2 补 adb/模拟器连接，动作注入走 exec.js。

## 四、棘轮基线存取

- 基线落 `test/baselines/<scenario>.json`（首跑写，之后读）。
- 每指标：`{ baseline: <值>, direction: 'lower-better'|'higher-better'|'equals', tolerance,
  provenance: { buildSha, device, latencyMs, fixture }, origin: 'HEALTHY'|'KNOWN_BAD'|'PROVISIONAL' }`。
- 变差判定：`direction=lower-better` 且 `cur > baseline + tolerance` → 红。
- **改善收紧**：`cur` 比基线好（lower-better 且 cur < baseline）→ 基线更新为 cur。
- **产地比较**：buildSha/device/latencyMs/fixture 任一不同 → `NOT_COMPARABLE`，不硬比。
- **初始分级**：HEALTHY（健康值）/ KNOWN_BAD（必须改善目标）/ PROVISIONAL（首轮未确认，不红别人）。
- `NOT_MEASURED` 指标：棘轮标记「待补」，不默认通过。

## 五、验证考卷（Layer 1 自身的验收，不自证）

1. **棘轮能抓回归**：喂一个「模拟变差」的指标（如 rightEdgeGapPx 从 143 → 30），棘轮必须红。
2. **未测到能显式喊**：让某指标缺采集文件，输出必须 `NOT_MEASURED` 且状态非 OK。
3. **场景定义可执行**：S1 场景动作序列能在 w-base-v2 环境跑通（w-base-v2 负责模拟器部分）。
4. **注延迟生效**：`latencyMs=200` 时关键 wait 拉长，数字表标记 `latency: 200`。
5. **改善收紧生效**（leader 裁定）：喂一个改善值（如 bottomMarginPx 1123→6）→ 基线必须更新到 6；
   再喂一个介于新旧之间的值（如 100）→ **必须红**（100 > 6）。此考卷不绿，棘轮是摆设。
6. **产地不可比**：同指标不同 device → NOT_COMPARABLE 不硬比。

## 六、与 Layer 0 的关系

- Layer 1 复用 Layer 0 算子（space/time/symbol），不重写。
- Layer 1 新增：场景驱动 + 指标编排 + 棘轮 + 未测到声明 + 注延迟。
- 用户 11 条缺陷里有 4 条会在 S1 一个场景同时暴露（右列截断/整屏刷/底部空黑/输入框）——主动发现。

## 七、边界

- 动作注入脚本**不执行**（本席禁碰模拟器），w-base-v2 跑。
- 不做 UI、不做报告美化、不做配置系统。
- 判不出 INDETERMINATE，缺指标 NOT_MEASURED。
