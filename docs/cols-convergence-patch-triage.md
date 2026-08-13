# 缺陷② 捡补丁报告：`horizontal-grid-convergence.patch`（Patch Triage）

> 日期：2026-08-14　预研席 w-cols-prep（只读，一行产品代码未改）
> 源补丁：`docs/reverted-to-v6/horizontal-grid-convergence.patch`（62630 字节，1101 行）
> 配套任务：fix-cols-grid-convergence（`.team/nodes/fix-cols-grid-convergence/`）
> 只读范围：`docs/` 内写本报告；`app/`、`server/`、`test/` 零改动；`git apply` 只跑了 `--check`。

## TL;DR（三句话给 leader / 开发席）

1. **`git apply --check` 全过、零冲突**——但这不是好消息意义上的"可直接 apply"：
   补丁基线 blob 与当前 v6 HEAD **逐字节一致**（`20e698d0b` / `3e82a908f`），所以 check 干净是必然；
   **真正的问题是语义冲突**——62630 字节里只有约 1/4 在修本缺陷，其余是已被用户裁定不可用而回退的
   D-38（回前台黑屏）、D-36（取证/分页）、捏合预览（重绘错乱）改动。
2. **真正要捡回的核心**：`measureCells()` 把实测 cellW 回写 presenter（`setMeasuredCellWidth`）+
   cellW 求法 `roundToInt→floor`。这是"两套栅格收敛"的唯一根治点，约 **2 个动点**（View 1 行 + presenter 1 个方法）。
3. **捡回不等于照抄**：回写与捏合（cellWidth 双写者）、首帧 seed（两次 resize）、Robolectric 测量 stub
   （cellW=1 污染 cols）三处交互必须重新设计，护栏还漏了字形侧收边。下面的最小修复面给了权衡，没有武断下结论。

---

## 1. 补丁与当前 HEAD 的关系（先讲清楚"零冲突"是什么）

### 1.1 事实核对

| 对象 | blob |
|---|---|
| 补丁基线 a 侧 `TermSurfaceView.kt` | `20e698d0b` |
| 补丁基线 a 侧 `TermViewPresenter.kt` | `3e82a908f` |
| 当前 HEAD 同文件 | **`20e698d0b` / `3e82a908f`（完全一致）** |
| 回退 commit `f89d47ec8^`（=回退前一版）同文件 | `20e698d0b` / `3e82a908f`（完全一致） |

当前 HEAD 的两个源文件 == 补丁 a 侧 == 回退前一版。`git apply --check` 全部 4 个文件 EXIT=0。

### 1.2 为什么是这样

`f89d47ec8`（全量回退 App 到 v6）的实际改动**只删了 14 个测试文件（2078 行），主源码在它之前已被
更早的 commit（`d53aba161` P0 回退脏行渲染 / `77ef17a9d` 回退整屏重写抑制 / `9c6727f45` 复盘）逐步
回退干净**。所以：

- w-dev-cols 导出补丁时，a 侧主源码**已经是 v6 状态** → 补丁 = `(v6) → (v6 + 全部尝试改动)`。
- 当前 HEAD 就是 v6 → 补丁当然能零冲突 apply。**check 干净 ≠ 补丁健康**，恰恰相反：
  它证明这份补丁是"从 v6 出发、把一堆当时在途改动打包在一起"的快照，混了好几个任务的东西。

### 1.3 与 leader 提示的差异

leader 提示"那版是在别的上下文里写的，不建议直接 apply"——**方向对，但理由要修正**：
不是"文本会冲突"，而是**语义会重演失败**。补丁里混入的捏合预览改动，回退 message 已明确定性其失败根因
（"预览语义做成了改字格重排而非整体缩放已画好的内容，字号一变网格几何就变……两边几何不一致，
这个交换是亏的"）。**若开发席按"check 过了"就直接 apply，会把用户否掉的 D-38/D-36/捏合预览一起带回来，重演 v5/v6 翻车。**

---

## 2. `git apply --check` 冲突清单

命令：`git apply --check --verbose docs/reverted-to-v6/horizontal-grid-convergence.patch`

```
Checking patch .../TermSurfaceView.kt...  OK
Checking patch .../TermViewPresenter.kt... OK
Checking patch .../TermColsGridConvergenceDiscriminationTest.kt... OK
Checking patch .../TermMeasuredCellWritebackTest.kt... OK
EXIT=0
```

**冲突文件清单：无（文本层面）**。四个文件全部可干净套入当前 HEAD。

> ⚠️ 反面解读：正因为零文本冲突，开发席最大的风险是"误以为安全"。真正的冲突在语义层，见 §4/§5。
> 另外两个新测试文件（`TermColsGridConvergenceDiscriminationTest` / `TermMeasuredCellWritebackTest`）
> 在 `app/app/src/test/`，**不在任务 write_scope**（`.team/nodes/fix-cols-grid-convergence/CLAUDE.md` 的
> write_scope 只列 `app/app/src/main/java/dev/agentmirror/app/termview/`）——重新引入测试前需 leader 确认落点。

---

## 3. 逐 hunk 分类

### 3.1 `TermSurfaceView.kt`（13 个改动块，第 5–264 行）

| # | 位置（补丁行号） | 干什么 | 分类 | 建议 | 理由 |
|---|---|---|---|---|---|
| S1a | L9-10 新增 import `ViewCompat`/`WindowInsetsCompat` | D-38 从 WindowInsets 查 IME | 无关 | 丢弃 | 当前 HEAD 无 D-38，ImeInset 语义走缺陷③回炉 |
| S1b | L15 新增 import `kotlin.math.floor` | 修法 2 用 floor | **相关** | **捡回** | floor 是收敛手段之一（见 §4） |
| S2 | L69-137 新增 `imeInsetPx`/`stableWidthPx`/`stableHeightPx`/`imeVisible`/`imeVisibleKnown` + `setImeVisible()` + `recordStableHeightIfImeClosed()` | D-38 候选3 稳定视口 | 无关 | 丢弃 | 用户否掉、缺陷③回炉另行处理 |
| S3 | L183-187 `onScale` 加注释 | 捏合预览（纯注释） | 无关 | 丢弃 | 捏合预览语义已被否 |
| S4 | L196-199 `doFrame` 注释改（"整帧全窗口重绘 P0 回退"） | 注释 | 无关 | 丢弃 | 当前代码已是整帧重绘，注释与现状不匹配 |
| S5 | L245-247 `onSizeChanged` 加 `recordStableHeightIfImeClosed()` | D-38 | 无关 | 丢弃 | 同 S2 |
| S6 | L255-284 新增 `onWindowVisibilityChanged()` | D-38 回前台重放几何 | 无关 | 丢弃 | 缺陷③回炉时另议 |
| S7 | L318-325 `onTouchEvent` 加 ACTION_UP→`onPinchCommit()` | 捏合预览提交 | 无关 | 丢弃 | 被用户否掉的捏合预览（回退 message 已定性失败） |
| S8 | L371 注释改 | 注释 | 无关 | 丢弃 | — |
| S9 | L373-394 `drawLine` 加右缘护栏 `clipRight` 三段式 + `onClipGuardEngaged()` | 修法 3 护栏 | **相关** | **需重写** | 护栏思路有价值（兜底），但①只收背景矩形、**不收字形**（`drawTextRuns`/`drawCentered` 末列 CJK 字形仍越界）；②`width>0` guard 是为 `TermBgCjkAlignTest`（view.draw 无 layout）设的，重写要保留；③与 floor/回写配合后的触发条件要重定 |
| S10 | L516-521 `measureCells`：`cellW` roundToInt→floor + `p.setMeasuredCellWidth(cellW)` | **修法 1+2（本缺陷核心）** | **相关** | **捡回（语义重设计）** | 根治点。但 setMeasuredCellWidth 与捏合双写 cellWidth、首帧 seed 二次 emit、JVM 测量 stub 三处交互需重新设计（§4.1） |

### 3.2 `TermViewPresenter.kt`（13 个改动块，第 264–617 行）

| # | 位置（补丁行号） | 干什么 | 分类 | 建议 | 理由 |
|---|---|---|---|---|---|
| P1 | L272 删空行 | 无关 | — | 丢弃 | — |
| P2 | L280-295 `stableWidthPx`/`stableHeightPx` | D-38 | 无关 | 丢弃 | 缺陷③回炉 |
| P3 | L310-331 `geometryCorrectionCount`/`maxReportedRows`/`emitResize()` | D-38 几何自愈 | 无关 | 丢弃 | `emitResize` 是自愈出口，当前不需要 maxReportedRows 语义 |
| P4 | L339-363 `forensicsSnapshot()` | D-36 取证钩子 | 无关 | 丢弃 | 取证层已回退 |
| P5 | L373-379 `onScrollBy` 空 buffer 上滑锁定（maxTop==0 分支） | D-36 补页 | 无关 | 丢弃 | 缺陷④/分页另行立案 |
| P6 | L384-399 `onHistoryPrepend()` | D-36 头插平移 | 无关 | 丢弃 | 同 P5 |
| P7 | L408-455 `onRealViewportChanged()` | D-38 回前台重放 | 无关 | 丢弃 | 缺陷③回炉 |
| P8 | L460-489 `onViewportSizeChanged` 加稳定基准 + 几何自愈分支 | D-38 | 无关 | 丢弃 | 同 P7 |
| P9 | L491-537 `onFontSizeChanged` 改预览语义 + `onPinchCommit()` | 捏合预览 | 无关 | 丢弃 | 被用户否掉的捏合预览 |
| P10 | L540-569 `setMeasuredCellWidth()` | **修法 1（本缺陷核心）** | **相关** | **捡回（语义重设计）** | 根治点。幂等回写思路对（同值 no-op），但需重审首帧/捏合/IME 交互（§4.1） |
| P11 | L571-585 `clipGuardEngageCount` + `onClipGuardEngaged()` | 护栏可观测 | **相关** | **需重写** | 与 S9 配套；护栏不许静默是对的（约束三），重写时保留可观测性 |
| P12 | L587-596 `recomputeGeometry` 改 `emitResize()` | D-38 配合 | 无关 | 丢弃 | 当前无 maxReportedRows，直接 `onResizeRequest` 即可 |
| P13 | L603-617 `ForensicsSnapshot` data class | D-36 | 无关 | 丢弃 | 同 P4 |

### 3.3 两个新测试文件

| 文件 | 干什么 | 分类 | 建议 | 理由 |
|---|---|---|---|---|
| `TermColsGridConvergenceDiscriminationTest.kt`（293 行） | 判别红测：A（两栅格不收敛，结构+真机参数化）/ B（宽字符越界机制）/ 护栏 engage / 回写对照 | **相关（判别红测正是 FIELD.md 要求的开工第一产出）** | **捡回（改写）** | ①断言依赖 `setMeasuredCellWidth` 回写（S10/P10）——重设计后断言要对齐；②JVM 测量 stub 的坑处理得不错（cellW=1，用真机参数化判别 ASCII 越界）；③测试文件落点需 leader 确认（write_scope 外） |
| `TermMeasuredCellWritebackTest.kt`（179 行） | 约束测试：反馈环收敛（至多一次 emit）/ IME 挤压不误伤 / D-38 顺序无关 | **部分相关** | **部分捡回** | 前两个测试（反馈环收敛、IME 不误伤）有直接价值；第三个依赖 `onRealViewportChanged`（P7，D-38 符号，当前 HEAD 没有）→ 删除或改写，否则编译不过 |

### 3.4 汇总数字

| 分类 | 块数 | 内容 |
|---|---|---|
| **真修本缺陷（相关）** | S1b、S9、S10、P10、P11 + 2 测试 | floor、回写、护栏（需重设计语义/补字形侧） |
| **无关（丢弃）** | S1a、S2-S8、P1-P9、P12、P13 | D-38 稳定视口、D-36 取证/分页、捏合预览、注释 |
| **判不出** | — | 无（全部可归入上两类） |

**核心结论：62630 字节 ≈ 1/4 是本缺陷，3/4 是被回退的其他任务。直接 apply = 把 v6 翻车的三件事原样带回来。**

---

## 4. 最小修复面建议（leader 最想要的一项：权衡摆出来，不武断下结论）

### 4.0 问题重述

`presenter.cellWidth` 与绘制推进 `cellW` 两个源：`recomputeGeometry()` 的 cols 用前者（默认 10，只有捏合改），
绘制 `drawLine`/`drawTextRuns` 的 x 推进用后者（每帧 `measureCells` 实测，**从不回写**）。两栅格不收敛。

### 4.1 候选动点（从小到大）

**X1 — 一行缓解：`cellW = max(1, floor(textW))`**（S10 的 floor 半）
- 效果：当**实测 ≤ 名义 10** 时，cols(名义)×floor ≤ W，末列整格可见。
- 不根治：FIELD.md 说真机实测 11 > 名义 10。textW≈10.5–11.5 时 floor→10 或 11：
  floor=10 → cols×10 ≤ W ✓（但字形实际 10.5+ 每格右溢 ~0.5px，连续字形轻微重叠）；
  floor=11 > 名义 10 → cols=W/10、末列右缘=11×(W/10)=1.1W **仍越界**。
- 结论：X1 只覆盖"实测 ≤ 名义"的形态，**缓解不根治**。但 1 行成本低，作为防舍入放大的配套可取。

**X2 — 根治：实测 cellW 回写 presenter（`setMeasuredCellWidth`）**（S10 + P10）
- cols 与绘制同源，两栅格收敛。与 Web 端 xterm.js 同一模型（一套度量决定列数与推进）。
- 三个真实权衡（必须由开发席定，我摆出来）：

  **(a) 回写 vs 捏合：cellWidth 有两个写者。**
  当前唯一写者是捏合 `onFontSizeChanged(newW,newH)`（+默认 10）。回写是第二个写者。
  关键事实：`measureCells` 的 cellW 是 cellHeight 的函数（`textSize = cellHeight*0.85` → 测"W"），
  所以捏合设的 **newW 必然被下次测量值覆盖**（测量值由 newH 决定，与 newW 无关）。
  两条路：
  - 路 1：测量值胜——捏合对宽度失去直接控制，宽度由高度间接定。缩放视觉仍生效（newH 变了），
    但宽高比不再自由。语义简单（一个 cellWidth 槽）。
  - 路 2：分开槽位——cellWidth 保留捏合语义，另加 `measuredCellW` 只供 recomputeGeometry 算 cols。
    presenter 两个宽度源，语义复杂，但捏合控制不被架空。
  - 额外风险：当前 v6 捏合**每步 emit** resize（`onFontSizeChanged` 内 `recomputeGeometry()`）。
    回写若也在手势中途每帧触发（onDraw 每帧 measureCells），会叠加 emit。**收敛性要靠"值稳定"
    兜住**：cellHeight 不变 → 测量 cellW 不变 → 同值 no-op → 至多一次 emit。但捏合过程中 cellHeight
    每步变 → 每步测量变 → 每步 emit。需要定义：捏合中回写是否应抑制，还是只在手势结束（或值稳定）才生效。

  **(b) 首帧两次 resize。**
  时序：`onSizeChanged`（seed）→ `recomputeGeometry` 用名义 10 先 emit 一次（如 96×108）→
  首次 `onDraw` 的 `measureCells` 回写实测（如 11）→ cols 变 → **再 emit 一次**（如 96×90）。
  进入会话 = 两次 resize + 服务端两次重排 + 快照重放。TS 慢速下是往返成本；首帧内容按名义 cols
  来、画布按实测排，存在内容错位窗口。w-dev-cols 那版"黑屏/错位"虽混了 D-38 无法归因，
  但**这是嫌疑点之一**，必须设计"首帧回写后一次收敛，内容随 resize 重放对齐"。
  一个思路：seed 时**先测后报**——onSizeChanged 里如果还没测过，用当前 cellWidth 首帧；首次 draw
  回写后明确只允许这一次追加 emit，之后幂等。

  **(c) Robolectric 测量 stub 污染。**
  JVM 下 `measureText` 返回字符数 → cellW=1 → 回写后 `presenter.cellWidth=1` → cols=W/1 爆炸。
  凡走 `view.draw`（onDraw）的既有测试都会受影响（见 §5 清单）。补丁的 discrimination test 用
  "JVM cellW=1、用真机参数化判别 ASCII"绕开——开发席要沿用这个思路，或对测量做注入/隔离。

  **上报 cols 用哪个值**（leader 直接问的）：
  必须用**实测（与绘制同源）**，`DEFAULT_CELL_WIDTH=10` 是错误源，弃用。
  取 round 还是 floor 有真实权衡：round 让格宽 ≥ 字形（字形不出格，但 cols 偏少、内容稀疏）；
  floor 让格宽 ≤ 字形（cols 偏多、内容密，但字形可能右溢重叠）。**两种都能保证 cols×格宽 ≤ W**
  （整除），差别只在字形相对格宽。真机等宽字宽通常是整数值或很稳定，两者差异小；xterm.js 用 floor。
  建议开发席按真机实测决定，别盲从补丁的 floor（那是为"宁可少一列"的语义，不是唯一正确解）。

**X3 — 兜底护栏：`drawLine` 右缘收边 + `clipGuardEngageCount` 可观测**（S9 + P11）
- 防"回写没覆盖到"的异常（服务端主动送超宽内容 / rotate 瞬间 / 首帧窗口）。
- 护栏不许静默（约束三）→ 保留可观测计数器，测试断言正常路径恒 0。
- **重写必补**：补丁只收背景矩形，`drawTextRuns`/`drawCentered` 的字形（尤其末列 CJK 主格字形）
  没收——用户"『它』的一半"正是字形被 Canvas 裁，护栏只收底色不解决字形越界。
  要么字形侧也收边/钳制，要么保证 cols 正确后字形天然在格内（X2 治本，护栏兜异常）。
- 保留 `width>0` guard（`TermBgCjkAlignTest` 走 view.draw 无 layout，width=0 时护栏必须失效）。

### 4.2 最小修复面（我倾向的组合，但把决策权留给开发席）

| 层级 | 动点 | 必/选 |
|---|---|---|
| 根治 | X2 回写（View 1 行 `p.setMeasuredCellWidth(cellW)` + presenter 1 个幂等方法），先解决 (a)(b)(c) 三个权衡 | 必 |
| 配套 | X1 floor 1 行（防舍入放大，配合回写后 cols×cellW ≤ W 更稳） | 必（低成本） |
| 兜底 | X3 护栏（重写，补字形侧收边 + 保留 width guard + 可观测计数） | 选（推荐） |
| 测试 | 判别红测（改写对齐新语义）+ 写回约束测试（去 D-38 依赖） | 必 |

**一句话**：真正绕不开的只有 X2 的"回写 + 幂等收敛"，X1 是 1 行白捡，X3 和测试是安全网。
**比补丁小的点**：补丁把 D-38/D-36/捏合预览一起带上，最小修复面**一个都不要带**。

---

## 5. 不倒退清单

回写/floor/护栏改动会碰到这一带的既有测试与锚定（v5/v6 已在这一带翻车三次，逐条列）：

### 5.1 既有测试（app/app/src/test/.../termview/）

| 测试 | 与改动的接触面 | 风险 |
|---|---|---|
| `TermViewImeResizePresenterProbeTest`（锚定 `96 to 108` 首帧 resize） | presenter 直接单测，**不经 onDraw** | 低（回写在 onDraw 里，不污染它；但若 seed 时序改成"先测后报"要重验锚定） |
| `SessionImeResizeProtocolRegressionTest`（`96 to 108`） | session 层协议锚定 | 中（首帧两次 resize 若改变协议时序，此测试断言 96→108 一次，可能 red） |
| `TermBgCjkAlignTest` | 走 `view.draw`（onDraw）→ measureCells 回写 cellW=1；floor 改变格宽；护栏 width guard | **高**（回写污染 cellWidth、格宽数字变化都会 red；护栏必须 width>0 guard） |
| `TermSurfacePinchGestureTest` | 捏合手势 + resize 次数 | 高（若回写与捏合每步 emit 叠加，resize 次数断言会 red；**不得改动捏合语义**——捏合预览已被用户否掉） |
| `TermSurfaceSessionBindingRegressionTest` | 绑定 + 帧循环 + draw | 中（onDraw 改动、回写触发帧请求） |
| `TermFirstRowVisibleTest` / `TermViewFrameWakeTest` | 首帧渲染 / 帧唤醒 | 中（回写若触发额外帧请求/额外 resize，时序断言可能 red） |
| `TermGestureDirectionTest` / `TermViewPresenterTest` | 滚动 / presenter 状态机 | 低（不碰 cols） |

### 5.2 行为锚定（非测试，改坏即用户可见回归）

1. **fix-ime-no-resize 的 viewportSeeded 锚定**：`onViewportSizeChanged` 首帧只 emit 一次，
   此后 IME 挤压/复原只推 visibleRows、不 emit。回写在 IME 挤压期间若 emit，**不得绕过这条约束**
   （补丁的 `writebackDuringImeShrinkDoesNotEmitResize` 锁"恰好一次"，但那是含 D-38 的版本，重设计后要重验）。
2. **首帧渲染**：进入会话 → seed emit → draw 回写 → 二次 emit → 服务端重排 + 快照重放。
   首帧不得黑屏/错位（w-dev-cols 那版就在这附近翻车）。
3. **捏合**：当前 v6 是"每步 emit"（`onFontSizeChanged`→`recomputeGeometry`）。**缺陷②不许改成
   "预览不重排"语义**——那是被用户否掉的捏合预览（回退 message 已定性：改字格重排 vs 整体缩放，
   预览期间两边几何不一致 = 错行错位）。回写若与捏合交互，只能叠加、不能替换捏合语义。
4. **R3 基准**：CJK 与 Powerline 渲染正常（`e2e/artifacts/baseline-v2/R3-cjk-powerline.png`）。
   护栏收边/floor 改格宽直接碰 CJK 渲染，视觉验收走 Claude 订阅席 + 真机。
5. **D-35 已修**：形近等价映射 + `'?'` 兜底槽不得回退（不涉及，列在这里提醒纪律）。
6. **上报 cols 变化 → 服务端 pane 重排**：真机实测必须"改前复现 + 改后看到修复 + 不倒退"三件齐
   （眼见为实铁律；捏合注入器在新 AVD 上是坏的 → **必须真机**）。

### 5.3 流程红线

- 施工时 `app/app` 同一时刻只放一席（缺陷②排在缺陷①之后，当前只做预研）。
- 只动 write_scope 内文件；测试落点（`app/app/src/test` 不在 write_scope）需 leader 确认。
- 判别红测与修复后红测必须 JVM 可重复跑（FIELD.md 收工门）。

---

## 6. 给开发席的接手指引（不用从零读那份 62630 字节）

1. **只读这些**：本报告 §3 的分类表（快速定位）+ 补丁的 S10/P10/S9/P11 四处（本缺陷相关，
   补丁行号 L516-521 / L540-569 / L571-585 / L373-394）+ 当前 HEAD 的 `TermSurfaceView.kt`/`TermViewPresenter.kt`
   （与补丁 a 侧逐字节一致，读当前文件 = 读补丁 a 侧）。
2. **忽略补丁的**：S2-S8、P2-P9、P12-P13（D-38/D-36/捏合预览/注释）——它们已被用户裁定不可用而回退，
   缺陷③（IME 回前台）走回炉流程另立案，缺陷④（上滑滚轮）等用户确认，都**不归本缺陷**。
3. **施工起点**：X2 回写（先定 §4.1(a) 的 cellWidth 单槽/双槽取舍，再定 (b) 首帧二次 emit 收敛，
   再处理 (c) JVM 测量隔离），加 X1 floor，X3 护栏可选。
4. **验收**：判别红测转绿（cols 上报 == 画布可容纳列数，cols×格宽 ≤ View 宽）+ 既有 §5.1 测试不 red
   + 真机"改前复现/改后见修复/不倒退"三件齐。

---

*本报告由预研席 w-cols-prep 在纯只读模式下产出；`git apply` 仅执行 `--check`；未 commit、未 push。*
