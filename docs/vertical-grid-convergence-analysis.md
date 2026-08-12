# 纵向栅格收敛分析（fix-cols-grid-convergence 遗留缺口 · 留给下一轮）

> 任务：分析纵向（rows / cellHeight）这条是否与横向存在同一类不收敛，不改代码。
> 背景：横向已修（修法 1：实测 cellW 回写 presenter.cellWidth；修法 2：round→floor）。
> 纵向**未修**：leader 裁定只回写 cellWidth、不回写 cellHeight（反馈环 + IME rows 锚定双重原因，
> 见 TermViewPresenter.setMeasuredCellWidth KDoc）。
> 本文档只做分析，为下一轮「纵向栅格收敛」任务留账。

## 一、纵向的两套栅格现状

与横向完全同构，两套栅格互不通：

| | 上报 rows 的栅格 | 绘制推进的栅格 |
|---|---|---|
| 计算处 | `TermViewPresenter.recomputeGeometry`：`rows = viewportHeightPx / cellHeight` | `TermSurfaceView.measureCells`：`cellH = (fontMetrics.descent - ascent).roundToInt()` |
| 取值 | `presenter.cellHeight`（默认 `DEFAULT_CELL_HEIGHT = 20`，仅捏合时改） | View 层局部变量 `cellH`（真实字体度量） |
| 纵向推进 | 决定上报给服务端的 rows（协议 resize 帧） | `drawLine` 行带高度 = `cellH`（TermSurfaceView.kt:293）、`rowY = (logical - win.first) * cellH`（:236） |

**不存在任何把实测 cellH 写回 presenter.cellHeight 的通路**（grep：`cellHeight` 仅出现在
presenter 定义/默认/onFontSizeChanged，以及 view 捏合缩放读它）。与横向修复前完全对称。

## 二、纵向不收敛的具体形态

真机字形度量典型值：`DEFAULT_CELL_HEIGHT = 20` 是名义值。实测 `cellH = (descent-ascent)` 通常
**略大于名义值**（例如 Roboto/DroidSansMono 在常用 textSize 下 descent-ascent ≈ 21-24px）。
若实测 cellH > 名义 20：

- 上报 rows = viewportHeight / 20（偏大）
- 绘制推进每行 cellH（偏大）→ rows 个单元格的绘制总高 = rows × cellH > viewportHeight
- **末行被画到 View 下缘之外** → 与横向「最右列被截」同构的**底部行被截**

## 三、当前为什么不暴露成用户可见的截断（三条现存缓冲）

与横向不同，纵向有**三条**已锚定机制恰好吸收了不收敛，所以当前没有用户报「底行被截」：

1. **visibleRows 抑制（fix-ime-no-resize / raw/019 裁定②）**：`onViewportSizeChanged` 首帧后
   **不再 recomputeGeometry**，rows 只在首帧算一次。首帧用的 cellHeight 是名义 20；若首帧后
   捏合改字号，`onFontSizeChanged` 才会重算 rows。IME 挤压只推 visibleRows。→ 大部分时间
   上报的 rows 是「名义栅格」的产物，而绘制用实测栅格，两者差值被 `window` 的贴底钳制吸收
   （`window` 底部钳到末逻辑行，多余像素高被空白填掉，不裁内容）。
2. **`onRealViewportChanged`（D-38）**：回前台/窗口尺寸变更时重算几何 → 若 rows 偏大，会 emit
   一次 resize 把内核 rows 改小（贴近实测），反而**修正**了不收敛。
3. **内核 `TerminalGrid` 行高固定 1 逻辑行**：绘制行带 `rowY = 逻辑行 × cellH`，窗口只覆盖
   `visibleRows` 行，最后一行 rowY+cellH ≤ viewportHeight 由 `visibleRows` 保证
   （`visibleRows = viewportHeight / cellHeight`，用名义高算 → 若名义 < 实测，窗口行数比
   viewportHeight 能装的少，**底部留空白**而非截断）。

## 四、什么条件下会暴露（下一轮的触发点）

纵向不收敛**潜伏**，遇到以下任一会显形：

- **捏合放大字号**：`onFontSizeChanged(newW, newH)` 把 `cellHeight` 设为捏合值。若捏合值用
  round（view 侧 `newH = (cellHeight * factor).roundToInt()`，TermSurfaceView.kt:113）→ 可能
  放大步进，rows = viewportHeight / 偏大的 cellHeight 偏小 → 上报 rows 比实测能装的少 →
  **底部大片留白**（用户感知：终端内容不占满屏，下缘空黑）。这是最可能的暴露。
- **服务端按上报 rows 换行**：若上报 rows 偏大（名义 < 实测），服务端 pane 高度 > 客户端画布
  能容纳 → 客户端只画前 rows 行、末行滚出视口不可见（需本地滚动才能看到）→ 用户感知
  「打开会话看不到最后几行」。
- **D-38 修正被 IME 屏蔽**：`onRealViewportChanged` 只在回前台/窗口变更触发；若一直停留
  前台、仅 IME 挤压复原，不触发 → 名义 cellHeight 的不收敛持续累积。

## 五、纵向修复方向（下一轮参考，本轮不动）——施工级方案 + 逐方向推演

### 5.1 前提：三条已锚定机制的时序约束（必须先摆清）

| 事件 | 时序 | 影响 |
|---|---|---|
| 捏合 `onScale` | view 侧 `newH = (cellHeight * factor).roundToInt()`（TermSurfaceView.kt:113）→ `presenter.onFontSizeChanged(newW, newH)` | **emit 先于 measureCells**：onFontSizeChanged 用新 cellHeight 算 rows 并 emit，此时实测 cellH 还是旧值 |
| `onFontSizeChanged` | `cellHeight = newH` → `recomputeGeometry()`（rows = viewport/cellHeight）→ `updateVisibleRows()` | 上报 rows 与窗口行数都用**捏合值**，非实测 |
| `measureCells` | draw 时才跑：`textSize = cellHeight*0.85` → `cellH = (descent-ascent).roundToInt()` | 实测 cellH **晚于** emit 一帧 |

→ **任何「用实测 cellH」的方案都必须处理这个时序错位**：emit 发生在 measureCells 之前，
除非 view 侧在 onScale 里先 measure 再 emit，否则首帧/捏合的 emit 用的是上一帧的 cellH。

### 5.2 方向 a：首帧/真实视口变化时用实测 cellH 校准一次 rows 并 emit

**施工点**：
- presenter 加 `private var rowsCalibrated = false`；
- 加 `fun calibrateRows(measuredCellH: Int)`：仅当 `!rowsCalibrated && measuredCellH > 0 &&
  viewportHeightPx > 0` 时，算 `rows = viewportHeightPx / measuredCellH`，与内核不一致则
  `onResizeRequest(rows, cols)` 一次；置 `rowsCalibrated = true`；
- view `measureCells` 末尾调 `p.calibrateRows(cellH)`（首帧 draw 即校准）。

**能否让「捏合放大后底部大片空白」消失？——不能（单独做）。**
逐个推：方向 a 只在首帧/真实视口变化校准一次。捏合放大走 `onFontSizeChanged`，它用新
cellHeight（捏合值）算 rows/emit，**不经过校准点**（calibrateRows 已 `rowsCalibrated=true`）。
捏合后 rows = viewport/捏合cellHeight，仍非实测 cellH 栅格 → 若捏合 cellHeight 与实测 cellH
不等，rows 仍偏，底部空白**复现**。方向 a 只修「首帧名义→实测」，不修「捏合→实测」。

**反馈环边界如何断开**：校准点一次性使用实测 cellH，**不写回 cellHeight 字段** → textSize
(= cellHeight×0.85) 不变 → 下一次 cellH 不变 → 无循环。断开方式 = 「读一次，不持久化」。

### 5.3 方向 b：updateVisibleRows 改用实测 cellH（本地可见行数与绘制同源，不 emit）

**施工点**：
- presenter 加 `private var measuredCellH: Int? = null`；
- 加 `fun setMeasuredCellHeight(measuredCellH: Int)`：只存到该字段 + `updateVisibleRows()` +
  `onFrameRequested`（**不 emit resize、不改 cellHeight**）；
- `updateVisibleRows` 改：`visibleRowsOverride = measuredCellH?.let { viewportHeightPx / it }
  ?: (viewportHeightPx / cellHeight)`；
- view `measureCells` 末尾调 `p.setMeasuredCellHeight(cellH)`。

**能否让「捏合放大后底部大片空白」消失？——能（本地画布层面）。**
逐个推：捏合放大 → onFontSizeChanged 用捏合 cellHeight emit + 窗口行数（旧）→ 下一帧
measureCells 测出实测 cellH → setMeasuredCellHeight 更新 visibleRows = viewport/实测 cellH →
窗口覆盖的行数 × 实测行高 ≈ viewport → **总绘制高恰好填满画布，底部空白消失**。

**方向 b 不 emit resize 的本地/服务端不一致（leader 关键问题）——会产生新问题，症状转移**：
- 服务端 rows 仍 = viewport/cellHeight（名义/捏合值），本地 visibleRows = viewport/实测 cellH。
- **若实测 cellH > cellHeight**（典型，字体行高略大于名义）：本地 visibleRows < 服务端 rows →
  服务端按偏大 rows 送内容 → 本地画布只装得下前 visibleRows 行 → **底部内容被裁 / 需滚动才能
  看到**。空白消失，但变成「底行看不到」——从「底部留白」变成「底部截断」，症状转移不消失。
- **若实测 cellH < cellHeight**：本地 visibleRows > 服务端 rows → 本地显示多于服务端 → 底部
  仍是空白（服务端没那么多内容可送）。
- 结论：**方向 b 单独做，本地/服务端永远不同栅格**，必选其一（空白或截断）。它只解决「本地
  画布填满」这个表象，不解决「服务端认知」这个根。

**反馈环边界如何断开**：把实测 cellH 存到**独立字段 `measuredCellH`**，不覆盖 `cellHeight`
→ textSize (= cellHeight×0.85) 不变 → cellH 不变 → 无循环。断开方式 = 「两个字段并存：
cellHeight 供服务端 rows + textSize，measuredCellH 供本地可见行数」。

### 5.4 真正的收敛 = a + b 合一（推荐，下一轮施工）

方向 a 单做不够、方向 b 单做症状转移，**合一方能既消空白又不转移**：

- **上报路径（服务端认知）用实测 cellH**：首帧 + 真实视口变化（对齐 D-38 的
  `onRealViewportChanged` 语义）+ 捏合改字号（005 契约允许 emit）时，用实测 cellH 算 rows 并
  emit。→ 服务端 rows 与本地可容纳行数同源，无空白无截断。
- **本地路径（可见行数）恒用实测 cellH**：updateVisibleRows 用 measuredCellH。→ 本地窗口与
  绘制同源，无空白。
- **IME 挤压路径保持不 emit**（raw/019 裁定②）：挤压/复原只推 visibleRows，不碰服务端。
  → 不扰动 fix-ime-no-resize 成果。

**捏合路径的时序改造**（关键，方向 a+b 合一后仍要处理）：onScale → `onFontSizeChanged`
emit 用的是新 cellHeight（非实测）。要在 emit 前用实测 cellH，需 view 侧在 onScale 里先
`measureCells()`（或至少先量 cellH）再传 `onFontSizeChanged`。这是 view 层的小改造：
`onScale` 里 `val measured = measureCellHeightForScale(); presenter.onFontSizeChanged(newW, newH)`。
否则 emit 后一帧 setMeasuredCellHeight 才纠正本地，服务端 rows 已按非实测值发出。

### 5.5 方向 a/b 逐方向回答「是否让现象消失」汇总

| 方案 | 本地空白消失? | 服务端认知正确? | 症状转移? | 反馈环断开 |
|---|---|---|---|---|
| 方向 a 单做 | 否（只修首帧） | 首帧是，捏合否 | 无转移（没修到点） | 一次性读，不持久化 |
| 方向 b 单做 | 是 | 否（永远不一致） | 是：空白→底行被裁 | 双字段并存 |
| a+b 合一 | 是 | 是 | 否 | 双字段并存 + 一次性校准 |

## 六、与本轮已锚定改动的关系

- **fix-ime-no-resize**：纵向修复的「首帧校准」方向必须保持「首帧后不再 emit」约束（raw/019）。
  方向 b 的本地 visibleRows 更新**不 emit**，天然不违反；方向 a 只在首帧 emit 一次，也不违反。
- **D-38**：`onRealViewportChanged` 已提供「真实视口变化才重算」的独立入口，方向 a 的校准 emit
  应挂在这个入口的语义下（首帧 + 真实视口变化），不碰 IME 挤压路径。**且 leader 已命中的关键**：
  D-38 用户「内容占顶部 1/4、中间空黑」可能与「捏合放大后 rows 用非实测 cellH 算、底部留白」
  同根——回前台只是让用户重新注意到，病在捏合路径。若 w-base-v2 实测确认，方向 a+b 合一正是
  D-38 的根治（而非回前台补丁）。
- **本轮横向修复**：横向只回写 cellWidth 是**正确**的（收敛性 + 不动 IME rows 锚定）；纵向
  不能照抄横向的 setMeasuredCellWidth（反馈环 + 时序），需按 5.4 的 a+b 合一设计。

## 七、验证建议（下一轮红测形态）

- 结构红测：`reportedRows == floor(viewportHeight / 实测 cellH)`，且 `reportedRows * cellH ≤
  viewportHeight`（对齐本轮横向的 hypothesisA_reportedColsAndCanvasCapacityMustShareSource）。
- 不扰动红测：首帧校准 emit 一次后，IME 挤压/复原不额外 emit（复用 TermViewImeResizePresenterProbeTest
  的锚定序列）。
- 收敛红测：校准后同值重测 no-op（至多一次）。
- **捏合红测（对应 D-38 假设）**：捏合放大后，本地窗口行数 × 实测 cellH ≤ viewportHeight
  （底部无空白），且服务端 rows 与本地可容纳行数一致（无转移截断）。
