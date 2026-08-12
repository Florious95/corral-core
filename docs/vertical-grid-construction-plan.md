# 纵向栅格收敛（a+b 合一）施工准备：测试清单 + 守卫设计

> 状态：**准备文档，已批，等 w-base-v2 捏合实测结论**。
> 目标：横向（fix-cols-grid-convergence）已修；纵向（rows vs 实测 cellH）本轮不动，
> 本文列出 a+b 合一（见 vertical-grid-convergence-analysis.md §5.4）要新增/修改的测试
> 清单，每条写明测什么、修复前应当红在哪两个数字，供 leader 直接批。
> 以及一条守卫：防「rows 恒等于固定值」糊弄判据。

## ⚠️ 结论边界（leader 裁定，2026-08-13）

**T1-T7 全绿只证明结构收敛**（rows 是实测 cellH 的函数、上报与本地同源），
**不能证明用户看到的底部空白消失**。T2 里的「实测 cellH=26」是 JVM 里设定的值，不是测出的
物理量——与横向「JVM cellW=1px 与真机方向相反」是同一个边界（JVM 的 Robolectric 字形度量
是 stub，不反映真机字形物理宽度/高度）。

**引用纪律**：
- 任何 report_result / 交付汇报中，**不许出现「T2 绿 ⇒ 底部空白消失」这类推论**。
- 「底部空白是否消失」只能由模拟器/真机实测判（本工程眼见为实铁律）。
- 单测红测锁的是**结构不变量**（两栅格同源、无转移、防常量糊弄），不是用户可见症状。

**前提不变**：等 w-base-v2 捏合实测。若实测证明「捏合后底部空白」不成立，本计划**降级为潜伏账，
不施工**——不许因为计划已写好就找理由开工（v5 教训：方案做完就落地，不管是否真修用户的病）。

## 一、a+b 合一方案要点回顾（施工时对照）

1. **上报路径用实测 cellH**：首帧 + 真实视口变化（onRealViewportChanged 语义）+ 捏合改字号
   （005 契约允许 emit）时，用实测 cellH 算 rows 并 emit。
2. **本地路径恒用实测 cellH**：updateVisibleRows 用 measuredCellH（独立字段，不覆盖 cellHeight）。
3. **IME 挤压路径保持不 emit**（raw/019 裁定②）。
4. **捏合时序改造**：onScale 里先 measure 再 emit（否则 emit 一帧后才纠正本地，服务端已按
   非实测值发出）。

新增/修改的 presenter 接口（拟）：
- `private var measuredCellH: Int? = null`（实测行高，独立字段）
- `fun setMeasuredCellHeight(measuredCellH: Int)`：存字段 + updateVisibleRows + onFrameRequested
  （不 emit）
- `fun calibrateRows(measuredCellH: Int)`：首帧/真实视口变化用实测行高校准 rows（emit 一次）
- `onFontSizeChanged` 增加实测行高参与 rows 计算（或 view 侧先 measure 再传）
- view `measureCells` 末尾调 `setMeasuredCellHeight(cellH)`；onScale 里先量再 emit

## 二、测试清单（每条：测什么 / 修复前应红在哪两个数字）

### T1. 结构红测：reportedRows 与画布可容纳行数同源（对齐横向 hypothesisA）
- **测什么**：`reportedRows == floor(viewportHeight / 实测 cellH)`，且
  `reportedRows * cellH ≤ viewportHeight`。
- **修复前红在哪**：reportedRows = viewport/名义20（如 1080px → 54）；可容纳 = viewport/实测
  cellH（如 cellH=22 → 49）。**数字 54 vs 49 不相等** → 红。
- **改造**：presenter 首帧校准 emit rows 用实测 cellH。

### T2. 捏合放大后底部无空白红测（对应 D-38 假设，核心）
- **测什么**：捏合放大字号后，本地窗口行数 × 实测 cellH == viewportHeight（总绘制高恰好填满，
  无底部大片留白）。
- **修复前红在哪**：onFontSizeChanged 用捏合 cellHeight（如 24）算 rows = viewport/24；实测
  cellH 若 = 26（捏合后字体变大，实测行高随之略增）→ 窗口行数 × 实测 cellH = (viewport/24) × 26
  > viewportHeight → **底部多出约 2 行空白**。数字 = 绘制总高 > 画布高（如 1080 vs 1000）。
- **改造**：updateVisibleRows 用 measuredCellH（方向 b）。

### T3. 捏合放大后服务端 rows 与本地同源（防症状转移）
- **测什么**：捏合放大后，reportedRows（emit 的服务端值）== floor(viewport / 实测 cellH)，
  即服务端认知 == 本地可容纳行数。
- **修复前红在哪**：onFontSizeChanged 用捏合 cellHeight emit rows（如 viewport/24=45）；实测
  可容纳 = viewport/26=41。**数字 45 vs 41** → 服务端偏大 → 本地画不下 → 症状转移（截断）。
  修复后两数相等。
- **改造**：onScale 先 measure 再 emit（时序改造），emit rows 用实测 cellH。

### T4. 方向 b 不 emit 约束（本地路径不得扰动服务端）
- **测什么**：setMeasuredCellHeight 只更新 visibleRows，**绝不 emit resize**（同值 no-op）。
- **修复前红在哪**：若实现错误地走了 recomputeGeometry → emit 额外 (rows,cols)。红 =
  resizeCalls 出现非预期的二次 emit（如首帧校准外又 emit）。
- **改造**：setMeasuredCellHeight 只存字段 + updateVisibleRows，不经 recomputeGeometry。

### T5. IME 挤压不误伤（复用 TermViewImeResizePresenterProbeTest 序列）
- **测什么**：首帧校准 emit 一次后，IME 挤压/复原不额外 emit（raw/019 裁定②保持）。
- **修复前红在哪**：若纵向校准挂在 onViewportSizeChanged 的错误位置 → IME 挤压触发额外 emit。
  红 = IME 收缩后 resizeCalls 多出条目（当前探针断言 `[96 to 108]` 不变）。
- **改造**：校准只挂首帧/真实视口变化入口，不碰挤压路径。

### T6. 反馈环收敛红测
- **测什么**：setMeasuredCellHeight / calibrateRows 后同值重测 no-op（至多一次 emit）。
- **修复前红在哪**：若误写回 cellHeight → cellH→textSize→cellH 循环 → 每帧 emit。红 =
  连续两次 calibrate 产生两次 emit（应一次）。
- **改造**：measuredCellH 独立字段，不覆盖 cellHeight。

### T7. 守卫红测（防「rows 恒等于固定值」糊弄，见下节）
- **测什么**：rows 必须随实测 cellH 变化（不是常数）。固定 rows 会被此测试抓红。
- **修复前红在哪**：若有人把 rows 硬编码（如恒 45），不同 viewportHeight/cellH 下 reportedRows
  不变 → 结构红测 T1 的「两数相等」对任意 viewport/cellH 都应成立，固定值只在单一尺寸巧合相等，
  多尺寸参数化下红。
- **改造**：T1 参数化多组 (viewportHeight, cellH)。

## 三、守卫设计：防「rows 恒等于固定值」糊弄判据

### 问题
a+b 合一的核心判据是「reportedRows == floor(viewport / 实测 cellH)」。若有人在施工时偷懒，
让 rows **恒等于某个固定值**（如 45），T1 在单一测试尺寸下可能「碰巧」相等（45 == 45），
测试绿但实际没修。这是 v5「改完现象消失又引入新现象」的变体——判据被常量糊弄。

### 守卫三件套（都放进测试，不改生产）

1. **多尺寸参数化**（T1 参数化）：T1 对多组 `(viewportHeight, cellH)` 跑，如
   `(1080, 20), (1080, 22), (1920, 20), (1920, 24), (500, 18)`。固定值 rows 只可能在恰好一组
   相等，其余必红。→ 判据对任意视口/字格成立，不是单点巧合。

2. **变化响应红测**（新测试）：先设 viewport/cellH 得 reportedRows；改 cellH（模拟捏合放大）
   再得 reportedRows。断言 **reportedRows 变了**（rows 必须对字格变化有响应）。
   - 红在哪：若 rows 恒固定值，两次 reportedRows 相同 → 红（断言「第二次 != 第一次」失败）。
   - 且断言变化方向正确：cellH 增大 → rows 减小（floor 语义）。

3. **服务端-本地一致性红测**（T3 强化）：reportedRows（服务端）== 本地窗口行数（visibleRows）。
   固定值 rows 若 != 本地可见行数 → 红（本地画布与服务端认知脱节，正是症状转移的探测）。
   - 红在哪：rows 固定为 45，本地 visibleRows = viewport/cellH（如 41）→ 45 != 41 → 红。

### 守卫的验收判据
- T1 + T7（参数化）全绿 ⇒ rows 是「实测 cellH 的函数」，不是常量。
- T3 绿 ⇒ 服务端与本地同源，无转移。
- T2 + T5 绿 ⇒ 本地无空白、IME 不扰动。
- T6 绿 ⇒ 反馈环收敛。

## 四、施工时序（等 leader 批准 + w-base-v2 实测确认后）

1. 若 w-base-v2 确认「捏合放大后底部空白」= D-38 真根因 → 按 a+b 合一施工。
2. 先写红测（T1-T7 全红）再改生产（红测先行）。
3. 落盘不打包，等 leader 放行模拟器。
