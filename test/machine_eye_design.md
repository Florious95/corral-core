# 机器眼 Layer 0 设计（算子 + 考卷）

> 状态：**设计稿，等 leader 批准后写代码**。
> 边界：只写 `test/`（独立测试工程），不碰产品/app/server。零 npm 依赖（Node 22）。
> 核心约束：**本席位通道读不了图片** —— 这是正确约束，算子必须输出数字而非图像。
> 方法：拿用户已报缺陷当考卷，语料用 `e2e/artifacts/` 真实录屏/截图/UI dump。

## 〇、为什么存在

用户报的 11 条缺陷**全是屏幕上随时间变化的现象**，但我们只有代码状态测试，没有东西看屏幕。
机器眼 = 把用户现象写成**可计算、可重放、可证伪**的数字判据。
已有先例：fix-ime-no-resize 的「254 帧仅 3 帧非零差分且均为纯滚动」——那就是一次机器判据，
当时当取证用，现在要当**方法**。

## 一、算子总览

三个算子，输入统一为「一段 adb 录屏 / 一组截图 / 一份 uiautomator UI dump」，输出纯数字。

| 算子 | 输入 | 输出（数字） | 判哪些用户现象 |
|---|---|---|---|
| **空间** `space` | 单帧 PNG | 内容边界 {top, bottom, left, right}、末行文字底边 y、最右非背景像素 x | 右列截断、底部留白 |
| **时间** `time` | 帧序列（PNG 组 / mp4） | 差分分布形态 {frame 索引, 非零差分帧号集合, 每帧差分区域, 形态分类} | 整屏刷、捏合闪烁、IME 上推 vs 重排 |
| **符号** `symbol` | UI dump XML + 可选截图 | 兜底槽字符集、控件 bounds、列表项文本集 | 字形缺省、会话列表陈旧、输入框位置 |

### 算子设计约束（通道决定）
- 我读不了 PNG，所以算子**不能依赖任何人眼看图**：像素读入用纯 JS PNG 解码器
  （`zlib.inflateSync` 解 IDAT + 手动解 filter，Node 内置 zlib，零 npm 依赖），灰度化后算数字。
- mp4 解码：ffmpeg 可用（系统已有）→ 算子接受「已抽帧 PNG 目录」或「mp4 + ffmpeg 抽帧」两种输入。

## 二、算子接口（Node，CommonJS，落 test/framework/machine_eye/）

```
test/framework/machine_eye/
├── png.js          # 纯 JS PNG 解码：readPng(path) → {width, height, rgba Uint8Array}
├── space.js        # 空间算子
├── time.js         # 时间算子
├── symbol.js       # 符号算子
└── index.js        # 统一导出 + 数字直方图工具
```

### 空间算子 `space.js`

```js
// 输入：PNG 路径 + 背景色阈值（默认终端深底 0x0D1626；可指定其他背景）
// 输出：
//   { width, height,
//     contentBounds: {top, bottom, left, right},  // 非背景像素的包围盒（排除状态栏/键条区可选）
//     lastTextBaselineY,                           // 最后一行文字的底边 y（扫描非背景像素最低行）
//     rightmostNonBgX,                             // 最右非背景像素 x
//     rightMarginPx,                               // = width - rightmostNonBgX（右缘余量）
//     bottomMarginPx,                              // 视口底 - lastTextBaselineY（底部余量）
//     backgroundRatio                              // 背景像素占比（留白量化）
//   }
function analyzeFrame(pngPath, opts) { ... }
```

**用途映射**：
- 右列截断：`rightMarginPx < 0`（内容右边界越过视口右缘）或 `rightmostNonBgX` 接近 width
  → 判「最右列被截」（对应 fix-cols-grid-convergence 横向）。
- 底部留白：`bottomMarginPx` 大（内容底边远在视口底之上）→ 判「终端内容只占顶部一部分、
  下方大片空黑」（对应 D-38 / 纵向）。
- 背景阈值需按主题区分（深底/浅底），opts 可配。

### 时间算子 `time.js`

```js
// 输入：帧目录（PNG 序列）或 mp4 + ffmpeg；可选「内容区裁剪」（y 范围，如 [254,2010]）
// 输出：
//   { frameCount,
//     nonZeroDiffFrames: [frameIndex...],          // 帧间差分非零的帧号集合
//     diffAreas: {frameIndex: {top,bottom,left,right,areaRatio}},  // 每帧差分区域
//     movementPattern: 'STATIC'|'BOTTOM_APPEND'|'SCROLL_DOWN'|'FULL_REFLOW'|'MIXED',
//     reflowSignal: boolean                         // 是否检测到"行内容位置改变"（重排）
//   }
function analyzeSequence(framesDirOrMp4, opts) { ... }
```

**四种形态分类（分布形态，非差分总量）**：
- `STATIC`：全部帧差分 0（或 ≤ 阈值）。
- `BOTTOM_APPEND`：差分只出现在**下方若干行**（区域底贴内容底，顶不高于前帧内容顶）。
- `SCROLL_DOWN`：差分区域随帧序号**单调下移**（整屏上推/滚动）。
- `FULL_REFLOW`：差分覆盖全屏 **且** 行内容位置改变（同一文本行出现在不同 y，说明重排）。
- `MIXED`：不属于以上单一形态。

**形态判别算法**（差分区域形态学）：
1. 逐相邻帧算 `mean_abs_diff`（内容区内，灰度）。
2. 差分 > 阈值 → 记非零帧 + 差分区域包围盒。
3. 对每个非零帧的包围盒：顶是否贴内容顶（上移）→ SCROLL_DOWN 信号；底是否贴内容底 →
   BOTTOM_APPEND 信号；覆盖全屏 → FULL_REFLOW 信号。
4. 重排判定：差分帧中，同一行文本的 y 坐标是否改变（行位置移动）→ reflowSignal。

**已知答案硬考卷**：
- `ime-no-resize` 的 `frames2/`（254 帧）→ 必须输出 `nonZeroDiffFrames = 3 个帧号`
  （对应 2/3/4 行增高），`movementPattern` 为纯滚动（SCROLL_DOWN / 混合但每帧差分
  纯平移），`reflowSignal = false`。**复现不出 = 算子错。**

### 符号算子 `symbol.js`

```js
// 输入：uiautomator dump XML 路径 + 可选截图 PNG
// 输出：
//   { controls: [{text, bounds:{left,top,right,bottom}, className}],
//     textSet: [去重文本...],
//     fallbackChars: [...],            // 渲染结果里检测到的兜底槽字符（'?' / 形近映射目标）
//     boundsByRole: {terminalView: {...}, inputField: {...}, keyBar: {...}}
//   }
function analyzeDump(xmlPath, pngOpts) { ... }
```

**用途映射**：
- 兜底槽（D-35）：渲染结果是否把缺字码点改成 '?' 或形近等价 → `fallbackChars` 含对应码点。
- 会话列表陈旧：`textSet` 与期望会话名集合比对，缺失/多余即「列表不刷新」。
- 输入框位置（IME 挤压）：`boundsByRole.inputField` 的 top/bottom 随档位变化 → 与 bounds 判据核对。

## 三、考卷清单（已知答案，语料 = e2e/artifacts/ 真实数据）

### 空间算子考卷

| # | 语料 | 已知答案 | 判的用户现象 |
|---|---|---|---|
| S1 | `ime-no-resize/11-final-screenshot.png`（四行输入框档位） | `boundsByRole` 之外：终端内容底边 y 在输入框上方，`bottomMarginPx` ≥ 0 | D-20 末行可见（IME 弹起末行不遮） |
| S2 | `d38-viewport-restore/10-pinchC-baseline.png` vs `11-pinchC-after-pinch.png` | 捏合前后内容右缘/底缘数字对比：捏合后 `rightMarginPx` 或 `bottomMarginPx` 显著变化 | 捏合放大后底部留白 / 右缘变化 |
| S3 | 右列截断语料（若已有对应截图） | `rightMarginPx < 0` 或接近 0 → 截断 | 最右列被截（fix-cols-grid-convergence） |

### 时间算子考卷（核心，必须复现已知答案）

| # | 语料 | 已知答案 | 判的用户现象 |
|---|---|---|---|
| T1 | `ime-no-resize/frames2/`（254 帧） | **`nonZeroDiffFrames` 恰 3 个**，`reflowSignal=false`，形态=纯滚动 | IME/输入框挤压不重排、不闪烁 |
| T2 | `ime-no-resize/frames-send9/`（发送增行） | 底部平滑追加，形态=`BOTTOM_APPEND`，无整屏重绘 | 发消息只追加不整屏刷 |
| T3 | `abc-regression/keyframes/`（A/B/C 三版本同名档位） | 修复前后同档位 diff：C 比 A 差分区域小 / 形态从 FULL_REFLOW→BOTTOM_APPEND | 输入框增高是否重排（版本对比） |
| T4 | 捏合闪烁语料（`pinch-harness/` 若有录屏） | 捏合期间差分形态：无持续整屏重绘 | 捏合闪烁 |

### 符号算子考卷

| # | 语料 | 已知答案 | 判的用户现象 |
|---|---|---|---|
| N1 | `ime-no-resize/05-ime-focus.xml` / `07-stage2-3line.xml` 等 | `boundsByRole.inputField.top` 随档位单调变化（聚焦→2行→3行） | 输入框位置/挤压 |
| N2 | D-35 缺字语料（`d35-real-symbol/` 若有 dump） | `fallbackChars` 含预期兜底码点 | 字形缺省兜底 |
| N3 | 会话列表语料（`03-workspace-list.xml`） | `textSet` 含预期会话名 | 列表正确性 |

## 四、验收标准（考卷式，不自证）

1. **时间算子必须在 T1 上复现「254 帧仅 3 帧非零差分」**——这是硬考卷，复现不出算子错。
2. 每个算子在对应考卷语料上输出**确定的数字**，数字与已知答案吻合（或与用户现象方向一致）。
3. 算子跑完输出 JSON（`{operator, input, output, expected, pass}`），不输出图片、不做 UI。
4. 新增：`test/cases/machine_eye.test.js` 注册为测试用例，纳入 runner。

## 五、实现顺序（批后执行）

1. `png.js`（纯 JS PNG 解码）→ 用 `ime-no-resize/11-final-screenshot.png` 自测（尺寸=1080×2400）。
2. `space.js` 空间算子 → S1/S2 考卷。
3. `time.js` 时间算子 → **T1 硬考卷**（ime frames2 254 帧复现 3）。
4. `symbol.js` 符号算子 → N1/N2/N3。
5. `test/cases/machine_eye.test.js` 注册 + 全部考卷跑绿。

## 六、判不出停下问（边界）

- PNG 解码若遇 Robolectric/模拟器 PNG 的非常规色深/滤镜，停下来问 leader，不猜。
- ffmpeg 抽帧的帧率/时间戳与已知答案（254 帧 25.436s）对不上时，以已抽好的 `frames2/` 为准。
- 背景色阈值识别：若某截图背景非终端深底，opts 需显式给阈值；不给默认判不出来就报错不猜。
