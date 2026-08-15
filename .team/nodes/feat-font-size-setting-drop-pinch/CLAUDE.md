# 知识基底 · feat-font-size-setting-drop-pinch（tools/basegen.py 编译产物——手工编辑无效，重编请改素材源后重跑）

## 1. 任务信封（taskbook.yaml 原文，机械抽取）
```yaml
  - id: feat-font-size-setting-drop-pinch
    title: "移除捏合缩放，改为设置页字号选择；字号→单元尺寸走实测度量，进入前定尺寸"
    status: todo
    owner: null
    contention: contract
    origin: "2026-08-14 用户裁定（架构决策，非缺陷单）"
    context: |
      **用户原话**：「移除捏合相关代码，并行在设置加字体大小设置，字体大小和尺寸相关联，
      此后不再捏合调整……直接改字体大小，背后直接和尺寸关联，进入直接以对应尺寸加载，
      根本上避免上面问题」。

      **捏合关联的三个从未修好的问题**（用户列举）：
        1. 捏合后闪烁，且延迟明显
        2. 边缘在屏幕外（＝缺陷②）
        3. 捏合后大小未延续（不持久化）

      **用户的关键现场证据**：「我进入对话，这个问题是没有的」——
      ② 在进入会话时【不出现】，只在捏合之后出现。
      ⇒ 初始计算是对的，**坏的是重算路径**。leader 最初判断"删捏合不会自动修好②"，
      被这条证据推翻，已收回。

      **代码层的对应（已核实）**：
        TermViewPresenter.onFontSizeChanged(L275)  捏合路径：cellWidth = 名义值 → recomputeGeometry() → 按名义值上报 cols
        TermViewPresenter.setMeasuredCellWidth(L313) 实测回写：下一帧测得实测值 → 不同则再上报一次
        注释原文：「真机收敛序列 seed 名义 10 → 回写实测 11 → 停」
      ⇒ **每次几何变化都先向服务端上报一个错的列数再纠正。**
      进入会话时这次收敛发生在画面出现之前，所以用户看不到；捏合时发生在眼前，所以看得到。

      **另一个被本任务顺带拆掉的结构**（今日发现）：
        TermViewPresenter.kt:294 注释：「否则 cellH→textSize→cellH 反馈环」
        textSize = cellHeight × 0.85，而 cellHeight = descent - ascent
        ⇒ 回写 cellHeight 会成环，所以它被钉死在 DEFAULT_CELL_HEIGHT=20，从不实测。
      本方案把字号变成【用户选定的独立输入】，textSize 不再由 cellHeight 推导 ⇒ 环断开 ⇒
      cellWidth 与 cellHeight 都能用实测值。这使 `fix-cellheight-writeback` 一并消解。
    hard_requirements:
      - "字号 → 单元尺寸【必须走实测字形度量】（measureText / fontMetrics），禁止查表配常量——否则② 会在固定字号下原样活下来"
      - "禁止保留「先用名义值播种、再靠实测回写收敛」的模式：几何只算一次，且算的时候就用实测值，不许先上报一个错的 cols 再纠正"
      - "字号必须持久化（用户列举的问题 3：捏合后大小未延续）"
      - "进入会话前尺寸即已确定，首帧就是最终尺寸（用户列举的问题 1：闪烁与延迟的根治方式）"
    acceptance:
      - "红测：给定字号，断言上报的 cols 与画布实际能完整容纳的列数一致（先红后绿）"
      - "红测：全流程只上报一次 resize，不存在「名义值 → 实测值」两次上报（钉死播种-收敛模式已消失）"
      - "红测：字号变更后重启 App，断言仍是所选字号（持久化）"
      - "红测：纯 CJK 双宽内容下末列字形右缘不超出画布宽度（吸收 fix-cols-cjk-doublewidth）"
      - "红测：cellHeight 使用实测值而非 DEFAULT_CELL_HEIGHT（吸收 fix-cellheight-writeback）"
      - "眼见为实：模拟器上切换字号，截图证明无闪烁、首帧即最终尺寸、右列完整；改前需先复现捏合后的切字与闪烁作对照"
      - "不倒退：删除捏合后，既有 TermSurfacePinchGestureTest 等测试需一并清理，但终端渲染/输入/重连基本功能全绿"
      - "bash -lc 'cd app && env -u TEAM_AGENT_* ./gradlew :app:testDebugUnitTest'"
      - "bash -lc 'python3 tools/archwiki/build_wiki.py --check --strict-t3'"
    supersedes: ["fix-cellheight-writeback", "fix-cols-cjk-doublewidth"]
    deps: []
    write_scope: ["app/app/src/main/java/dev/agentmirror/app/termview/", "app/app/src/main/java/dev/agentmirror/app/session/", "app/app/src/main/java/dev/agentmirror/app/diag/SettingsScreen.kt", "app/app/src/test/"]
    evidence: ".team/evidence/feat-font-size-setting-drop-pinch.json"
```

## 2. 架构基（build_wiki.py 现算影响闭包）
- write_scope 包：dev.agentmirror.app.termview, dev.agentmirror.app.session, dev.agentmirror.app.diag.SettingsScreen.kt
- 正向依赖（你消费的契约，只读）：kt_dev_agentmirror_app_conn, kt_dev_agentmirror_app_service, kt_dev_agentmirror_app_tsnet, kt_dev_agentmirror_app_ui_theme, kt_dev_agentmirror_terminal
- **反向依赖（波及面=回归自查范围）**：kt_dev_agentmirror_app

### 闭包架构卡内联（职责/导出面/依赖边）

### Kotlin · dev.agentmirror.app.termview

- **职责**：终端渲染：快照/增量渲染 + 本地滚动视口（60fps，需求 006）。
- **导出面**：ANSI_COLORS, DRAWABLE_EQUIVALENTS, GlyphFallbackPolicy, GlyphFontProvider, GlyphRunBuilder, GlyphSegment, GlyphSlot, TermSurfaceView, TermViewPresenter, XTERM_256
- **依赖边**：dev.agentmirror.terminal
- **doc 全文**：终端渲染：快照/增量渲染 + 本地滚动视口（60fps，需求 006）。 [TermViewPresenter] 纯 JVM 视口状态机（跟随/锁定历史、可见行窗口、捏合行列数换算、 脏区合并），单测全部打在它上；[TermSurfaceView] 薄 Android 层（Canvas 画格、拖动/捏合 手势、Choreographer 帧调度）。内核为 :terminal 模块；resize 协议帧由上层接线（conn/session）。 @consumes dev.agentmirror.terminal

### Kotlin · dev.agentmirror.app.session

- **职责**：会话页：单个 tmux 会话的交互界面。
- **导出面**：Attachment, Failed, Failure, HttpUrlConnectionUploader, SessionRoute, SessionScreen, SessionViewModel, Success
- **依赖边**：dev.agentmirror.app.conn, dev.agentmirror.app.service, dev.agentmirror.app.termview, dev.agentmirror.app.tsnet, dev.agentmirror.app.ui.theme, dev.agentmirror.terminal
- **doc 全文**：会话页：单个 tmux 会话的交互界面。 组合终端渲染（termview）与输入下发（conn），承载缩放、手势与快捷输入条； 图片附件走 multipart HTTP 上传（上传基地址由 service 装配的 ServiceWire 统一注入）， 跨层共享连接经 service 的 ServiceWire.uiConnector 扇出订阅。会话页已完整落位： 镜像流（snapshot/delta/scrollback 本地滚动补页）、发送必达回执、附件路径注入光标处。 @consumes dev.agentmirror.app.conn @consumes dev.agentmirror.app.service @consumes dev.agentmirror.app.termview @consumes dev.agentmirror.app.tsnet @consumes dev.agentmirror.app.ui.theme @consumes dev.agentmirror.terminal

### Kotlin · dev.agentmirror.app

- **职责**：Compose 应用根组合。
- **导出面**：AgentMirrorApp, MainActivity, MainNavState, Session
- **依赖边**：dev.agentmirror.app.pairing, dev.agentmirror.app.service, dev.agentmirror.app.session, dev.agentmirror.app.ui.theme, dev.agentmirror.app.workspace
- **doc 全文**：Compose 应用根组合。 依需求 004「客户端无状态」，本组件只做路由，不持有任何业务状态。 首启路由（pairing-ui 知识基底 §1）： - 无配对配置 → 配对页（扫码/手填，可跳过进空工作区）； - 有配对配置 → 直进工作区列表； - 配对页可从设置/重配入口重进（重新配对）。 会话页跳转沿用 session-ui 挂载的 [SessionRoute]。 导航态由 [navState]（D-3 修复）注入：MainActivity 在 onSaveInstanceState 持久化、重建恢复，深链/旋转都不丢导航位置（审计 D-2/D-3）。 工作区 VM（[workspaceViewModel]）由 MainActivity 持有（fix-workspace-wiring 修复， navState 同模式提升），本组件只负责在工作区分支用 [DisposableEffect] 把它接入 [ServiceWire.uiConnector]——配对成功后列表能渲染（此前 VM 裸建从未接线，uiConnector 全仓无调用点，见 fix-workspace-wiring 知识基底）。 @consumes dev.agentmirror.app.pairing @consumes dev.agentmirror.app.service @consumes dev.agentmirror.app.session @consumes dev.agentmirror.app.ui.theme @consumes dev.agentmirror.app.workspace

## 3. 需求基
- goal 引用条目：（goal 无编号引用）
- librarian 撞库：无回执文件（leader 未查或无命中）
- 修订记录 requirement-base/REVISIONS.md 必读（被推翻结论不回改条目）

## 4. 经验基（通用纪律+先例）
- 红测先行；每次落盘保持整模块可编译（共享编译单元互阻三次实案）；编译被他人半成品阻断→直接 send 文件主人（附文件+行号+错误原文），主人最高优先修复回执，不经 leader
- 测试净化前缀 env -u TEAM_AGENT_*；tmux 只用自建隔离 socket；杀进程只 scoped kill 自己命名空间（w-fix-statewire 险案）
- 派单通道净化：所有 Team Agent CLI 调用统一走仓库包装器 .team/ta，尤其 add-agent/start-agent/reset-agent；禁止手写 env -u 前缀或直接调用 team-agent，否则 Codex 托管代理会被快照进新席启动串，形成零 token 假 BUSY
- A-31 开工核真：Codex 新席必须在对应 ~/.codex/sessions 当日 JSONL 出现 reasoning 或 custom_tool_call；Working/BUSY、pane 存在、命令 exit 0 均不算真活性
- 代码必须带注释（设计决策写为什么）；禁止 git push；本地不 commit；report_result 恰好一次带 tests

## 5. 现场基（leader 手填取证素材——唯一手填合法区）
- 无现场素材文件
