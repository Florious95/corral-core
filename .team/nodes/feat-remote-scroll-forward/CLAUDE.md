# 知识基底 · feat-remote-scroll-forward（tools/basegen.py 编译产物——手工编辑无效，重编请改素材源后重跑）

## 1. 任务信封（taskbook.yaml 原文，机械抽取）
```yaml
  - id: feat-remote-scroll-forward
    goal: >
      D-36 用户 2026-08-14 重新定义（原话）：「我在屏幕里面向上滑的时候，我向上滑的这个行为
      没有投放到这个界面。也就是说我向上滑，要类似于我在这个界面鼠标滚轮也向上滑，
      它才能配合看到上面的内容。」「之前所有的假的复现、假的修改正确，全部都是它本身
      从上往下加载了大量的 CLI 的内容，因此我才能上上滑。」
      **这个定义推翻了 fix-scrollback-history-d36 的全部前提**：我们一直在修「App 本地缓冲
      怎么滚」和「服务端 scrollback 分页怎么对齐」，而用户要的是**把滚动手势送到远端终端**，
      等价于在该 TUI 里滚鼠标滚轮（Claude Code / vim / less 等自己处理滚轮，App 侧滚本地缓冲
      永远看不到它们的历史）。
      现状已 grep 实证：App 从未向服务端发过滚轮/鼠标事件；服务端 input 路径只有 send-keys；
      bridge 无 copy-mode/滚动。**整条链路上不存在这个能力**，属于补一条从未有过的能力，
      跨「手势 → 事件 → 协议 → 服务端 → tmux」四层。
      范围张力：与「只修缺陷零新功能」封存令冲突，用户 2026-08-14 裁定「核心就是把这四个
      缺陷开发好」，本条即其中之一。
    acceptance:
      - "关卡1（契约，必须先过）：产出协议方案书交 leader 定夺——滚轮事件怎么上线（复用 send-keys 发 SGR/正常鼠标序列，还是新增 wire kind），谁判断远端是否处于 alt-screen/mouse-tracking 模式，非 TUI 场景（裸 shell）如何降级到 tmux copy-mode。leader 未裁定前不得改协议与 app/"
      - "关卡2（红测）：手势 → 事件的单测；远端 mouse-tracking 开/关两种模式各有一条期望字节序列的红测"
      - "关卡3：cd server && env -u TEAM_AGENT_* go test ./... 全绿"
      - "关卡4：python3 tools/archwiki/build_wiki.py --check --strict-t3 exit 0"
      - "关卡5（眼见为实）：真机在 Claude Code 会话里上滑，能看到该 TUI 自己的上文，改前录到不动、改后录到动，且不倒退"
    deps: []
    write_scope: ["app/app/src/main/java/dev/agentmirror/app/session/", "app/app/src/main/java/dev/agentmirror/app/termview/", "server/internal/api/", "server/internal/bridge/", "test/cases/"]
    evidence: ".team/evidence/feat-remote-scroll-forward.json"
    contention: contract
```

## 2. 架构基（build_wiki.py 现算影响闭包）
- write_scope 包：dev.agentmirror.app.session, dev.agentmirror.app.termview, internal/api, internal/bridge
- 正向依赖（你消费的契约，只读）：go_internal_agentstate, go_internal_discovery, go_internal_protocol, kt_dev_agentmirror_app_conn, kt_dev_agentmirror_app_service, kt_dev_agentmirror_app_tsnet, kt_dev_agentmirror_app_ui_theme, kt_dev_agentmirror_terminal
- **反向依赖（波及面=回归自查范围）**：go_cmd_agentmirrord, kt_dev_agentmirror_app

### 闭包架构卡内联（职责/导出面/依赖边）

### Kotlin · dev.agentmirror.app.session

- **职责**：会话页：单个 tmux 会话的交互界面。
- **导出面**：Attachment, Failed, Failure, HttpUrlConnectionUploader, SessionRoute, SessionScreen, SessionViewModel, Success
- **依赖边**：dev.agentmirror.app.conn, dev.agentmirror.app.service, dev.agentmirror.app.termview, dev.agentmirror.app.tsnet, dev.agentmirror.app.ui.theme, dev.agentmirror.terminal
- **doc 全文**：会话页：单个 tmux 会话的交互界面。 组合终端渲染（termview）与输入下发（conn），承载缩放、手势与快捷输入条； 图片附件走 multipart HTTP 上传（上传基地址由 service 装配的 ServiceWire 统一注入）， 跨层共享连接经 service 的 ServiceWire.uiConnector 扇出订阅。会话页已完整落位： 镜像流（snapshot/delta/scrollback 本地滚动补页）、发送必达回执、附件路径注入光标处。 @consumes dev.agentmirror.app.conn @consumes dev.agentmirror.app.service @consumes dev.agentmirror.app.termview @consumes dev.agentmirror.app.tsnet @consumes dev.agentmirror.app.ui.theme @consumes dev.agentmirror.terminal

### Kotlin · dev.agentmirror.app.termview

- **职责**：终端渲染：快照/增量渲染 + 本地滚动视口（60fps，需求 006）。
- **导出面**：ANSI_COLORS, DRAWABLE_EQUIVALENTS, GlyphFallbackPolicy, GlyphFontProvider, GlyphRunBuilder, GlyphSegment, GlyphSlot, TermSurfaceView, TermViewPresenter, XTERM_256
- **依赖边**：dev.agentmirror.terminal
- **doc 全文**：终端渲染：快照/增量渲染 + 本地滚动视口（60fps，需求 006）。 [TermViewPresenter] 纯 JVM 视口状态机（跟随/锁定历史、可见行窗口、捏合行列数换算、 脏区合并），单测全部打在它上；[TermSurfaceView] 薄 Android 层（Canvas 画格、拖动/捏合 手势、Choreographer 帧调度）。内核为 :terminal 模块；resize 协议帧由上层接线（conn/session）。 @consumes dev.agentmirror.terminal

### Go · internal/api

- **职责**：Package api implements the service-side WebSocket API and the image upload endpoint, wiring together discovery and bridge (task ws-api).
- **导出面**：Discoverer, NewServer, NewStateProvider, Options, Server, StateProvider, TokenValidator
- **依赖边**：internal/agentstate, internal/bridge, internal/discovery, internal/protocol

### Go · internal/bridge

- **职责**：Package bridge exposes a single tmux pane as a terminal bridge: first-frame snapshot, incremental output stream, whole-message input injection with a decidable ack, resize, and scrollback paging.
- **导出面**：ErrInvalidKey, ErrPaneNotFound, ErrServerUnreachable, ErrTmuxTimeout, NewPane, Pane
- **依赖边**：（无）

### Go · cmd/agentmirrord

- **职责**：Command agentmirrord is the service-side daemon of AgentMirror (product github.com/agentmirror/agentmirror): a sidecar that mirrors the user's existing tmux sessions to the Android app over WebSocket.
- **导出面**：main
- **依赖边**：internal/api, internal/config, internal/pairing, internal/tsnetd

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
