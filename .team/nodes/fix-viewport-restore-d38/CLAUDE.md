# 知识基底 · fix-viewport-restore-d38（tools/basegen.py 编译产物——手工编辑无效，重编请改素材源后重跑）

## 1. 任务信封（taskbook.yaml 原文，机械抽取）
```yaml
  - id: fix-viewport-restore-d38
    goal: >
      修复「切后台再回前台，终端只占屏幕一小块、下方大片空白」（D-38，用户已多次报告）。
      用户 2026-08-12 截图实证：终端内容仅占屏幕顶部约 1/4，中间大片空黑，键条与输入框在底部
      —— 即终端行数停留在一个更小的旧视口几何上，未随回前台恢复。
      ⚠️ **leader 引入的风险必须一并处理**：今晚 fix-ime-no-resize 把
      TermViewPresenter.onViewportSizeChanged 改成「首帧 seed 一次后不再 emit resize、
      只更新 visibleRows」。修复前视口变化会触发重算并纠正几何；修复后则不会，
      因此后台期间视口若变过（如 IME 收起），回前台会卡在旧的小几何上 —— 与本现象一致。
      **根子是同一个毛病：onViewportSizeChanged 分不清「输入框临时挤压」与「真实视口变化」。**
      「一律 emit」和「一律不 emit」都是错的。本任务要给出能区分两者的判据：
      输入框/IME 引起的挤压 → 视口上推、不重算 rows/cols、不发 resize；
      真实视口变化（回前台、旋转、分屏、窗口尺寸变更）→ 必须重算并按需发一次 resize。
      v5 曾用 TermSurfaceView.onWindowVisibilityChanged 处理回前台（QA PASS），
      代码在归档分支 v5-failed@2874c54 可参考，但该文件同时含 v5 闪烁回归元凶，**不得整文件捞回**。
    acceptance:
      - "bash -lc 'cd app && env -u TEAM_AGENT_* ./gradlew :app:testDebugUnitTest'"
      - "红测：IME 挤压序列不产生 resize 帧（保住 fix-ime-no-resize 成果）"
      - "红测：模拟切后台→视口变化→回前台，几何必须恢复到与当前 View 尺寸一致，且最多发一次 resize"
      - "强制门 TermSurfaceSessionBindingRegressionTest / TermSurfacePinchGestureTest / TermViewImeResizePresenterProbeTest 全绿"
    deps: []
    write_scope: ["app/app/src/main/java/dev/agentmirror/app/termview/"]
    evidence: ".team/evidence/fix-viewport-restore-d38.json"
    contention: none
```

## 2. 架构基（build_wiki.py 现算影响闭包）
- write_scope 包：dev.agentmirror.app.termview
- 正向依赖（你消费的契约，只读）：kt_dev_agentmirror_terminal
- **反向依赖（波及面=回归自查范围）**：kt_dev_agentmirror_app_session

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
- .team/nodes/fix-viewport-restore-d38/FIELD.md（先完整读；含真机实证/失败现场/裁定）
