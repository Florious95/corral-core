# 知识基底 · fix-term-glyph-render（终端豆腐块——字形回退链）

## 0. 任务（taskbook.yaml#fix-term-glyph-render）
- 真机实锤（图 29/30）：终端画面大片 ■ 黑块，Claude Code TUI 的界面元素（旋转指示、进度块、框线）整段变豆腐。根因方向：终端画布用单一等宽 Typeface 绘制，Android 默认 monospace 覆盖面窄——盲文点阵 U+2800-28FF（spinner 常用）、块元素 U+2580-259F、框线 U+2500-257F 部分、Powerline 私有区 U+E0B0+、部分符号无字形。Canvas drawText 不做系统级字体回退（TextView 会，裸 Paint 不会）→ 缺字即豆腐。
- 修复：字形回退链——绘制前按字符（codepoint）用 `paint.hasGlyph()` 检测，缺字回退到候选 Typeface 序列（系统 sans/symbol 字体等，实测哪些系统字体有这些区段；Typeface.create 缓存防抖）；宽度强制单元格对齐（回退字形宽度≠等宽单元格时居中裁剪/缩放绘制，禁止破坏列对齐）。双宽（CJK/emoji）测量既有逻辑不回退（已有 wcwidth 处理，验证不破坏）。
- 红测先行：夹具字符串覆盖 盲文轮转符/块元素/框线/Powerline/CJK/emoji 混排——逐字符断言「hasGlyph(主字体) 为假的字符经回退链后有字形可绘」+列对齐测量断言。JVM 层（:terminal 纯 JVM 不涉 Android Paint）测网格/测量语义；termview 层（app 模块，Robolectric 有 Paint）测回退链选择。
- 验收：`cd app && ./gradlew -q :terminal:test :app:testDebugUnitTest` 全绿；模拟器会话页截图（真实 claude 会话画面）对照留档 e2e/artifacts/ui-review/term-glyph-after.png。
- 红线：禁止整体换字体牺牲等宽（终端本体必须等宽栅格）；:terminal 纯 JVM 模块禁止引 Android 依赖（008 隔离，回退链属 termview 渲染层）。

## 1. 现场基
- 渲染画布：app/app/src/main/java/dev/agentmirror/app/termview/（Canvas 栅格绘制、Typeface 选择处 grep Typeface/drawText）；:terminal 模块=纯 JVM 引擎（feed/网格/damage），字形无关——大概率只动 termview。
- 双宽处理：termview 测量逻辑（wcwidth 类）现状先读。
- **并行环境**：ui-redesign 席位动 Screen 层视觉（不进 termview 画布内部）；你只动 termview/ 与 :terminal（如需）+测试。每次落盘保持可编译。
- 真实样本：Claude Code TUI 常用字符实测（✳✻✽ 及 U+28xx 盲文轮转、▁▂▃ 块、╭─╮ 圆角框线）——从 e2e 隔离会话 capture-pane 取真实字节做夹具最稳。

## 2. 需求基（指针）
1. requirement-base/entries/018 标准7（终端页专项：字形完整/等宽对齐）
2. requirement-base/entries/006（秒开与本地滚动——渲染性能不得回退，回退链要有缓存）
3. docs/scenario-coverage.md C 矩阵（终端内容保真）

## 3. 经验基
- hasGlyph 逐字符调用有开销：按 codepoint 结果缓存（LRU/数组），滚动热路径零新增分配；红测先行；截图自检后再交件；净化前缀 env -u TEAM_AGENT_*。


## 4. 架构基（build_wiki.py 现算，2026-08-09，18 包 22 边；全卡见 docs/wiki/README.md）
- 本案 write_scope 包：app_termview
- 正向依赖（你消费的契约，只读）：无
- **反向依赖（改动波及面，回归自查范围）**：kt_dev_agentmirror_app_session
- 各包职责/导出面/依赖边以 docs/wiki/README.md 对应架构卡为准（现算产物，与代码同步）。

## 5. 需求基增补（librarian 撞库，2026-08-09）
- 006/011/R-002：自研最小 VT 引擎裁定与首帧 p90 50.6ms 实证（015）——回退链不得回退此性能
- 005 内容保真由 CLI 重画保证——你只管「有字形可绘」，不管语义
- 「字形/等宽」需求库无沉淀，018 标准7 是唯一判定权威

## 6. 影响闭包架构卡内联（契约级，build_wiki.py 现算）

### Kotlin · dev.agentmirror.app.termview

- **职责**：终端渲染：快照/增量渲染 + 本地滚动视口（60fps，需求 006）。
- **导出面**：ANSI_COLORS, TermSurfaceView, TermViewPresenter
- **依赖边**：（无）
- **doc 全文**：终端渲染：快照/增量渲染 + 本地滚动视口（60fps，需求 006）。 [TermViewPresenter] 纯 JVM 视口状态机（跟随/锁定历史、可见行窗口、捏合行列数换算、 脏区合并），单测全部打在它上；[TermSurfaceView] 薄 Android 层（Canvas 画格、拖动/捏合 手势、Choreographer 帧调度）。内核为 :terminal 模块；resize 协议帧由上层接线（conn/session）。

### Kotlin · dev.agentmirror.app.session

- **职责**：会话页：单个 tmux 会话的交互界面。
- **导出面**：Attachment, Failed, Failure, HttpUrlConnectionUploader, SessionRoute, SessionScreen, SessionViewModel, Success
- **依赖边**：dev.agentmirror.app.conn, dev.agentmirror.app.service, dev.agentmirror.app.termview
- **doc 全文**：会话页：单个 tmux 会话的交互界面。 组合终端渲染（termview）与输入下发（conn），承载缩放、手势与快捷输入条。 本包为占位骨架，由 session 任务落位实现。
