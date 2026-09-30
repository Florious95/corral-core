# 知识基底 · feat-remote-scroll-mouse-wheel（tools/basegen.py 编译产物——手工编辑无效，重编请改素材源后重跑）

## 1. 任务信封（taskbook.yaml 原文，机械抽取）
```yaml
  - id: feat-remote-scroll-mouse-wheel
    title: "④ 重开的方向已由用户定死：把上滑手势变成【给窗口发鼠标滚轮指令】"
    status: todo
    owner: null
    contention: contract
    origin: "2026-08-14 用户裁定（取代 feat-remote-scroll-forward 前十轮的全部方向）"
    context: |
      **用户原话（这是唯一权威，前十轮的方向全部作废）**：
      「我在 Mac 上用鼠标滚轮往上滑，你可以看到它是可以往上滑的。要不然的话，
      它也不会出现那个 Jump to Bottom。那实际上你要做的不是，你要加上滑这个动作，
      实际上就是要把鼠标上滑的这个行为改成滚轮往上滑，给它给这个窗口发这样的指令。」

      **用户附了证据**：Mac 上滚轮上滑，Claude Code 出现 "Jump to Bottom" 提示
      ——证明 **Claude Code 自己就有滚动能力，只是没人给它发滚轮事件**。

      **这与 leader 2026-08-14 的实测吻合**（在自己跑着 Claude Code 的 tmux pane 上）：
        alternate_on = 1     alt-screen TUI
        history_size = 0     tmux 里零行 scrollback
        mouse_any_flag = 1   **它自己已经开着鼠标上报**
      ⇒ 第 8~10 轮做的「读 tmux scrollback 推快照」对它读的是空历史，已全轮归档
        （docs/archive/scroll-rounds-8-10/）。
      ⇒ 而更早那条「SGR 鼠标字节注入是死路」的结论**也是错的**：
        当时测的 less/vim 是 mouse=0，没开鼠标上报当然没反应。

      **所以新方向**：手势 → 鼠标滚轮事件 → 送到 pane 里那个开着 mouse 上报的程序。

      **从归档里可以直接复用、不要重造的**：
      - 手势层符号错（上滑发正值＝协议的"向下"）与手势层丢帧（不足一行的位移被丢弃、
        无累加器，700px 拖动只送达 3 行）——两个真 bug，与架构无关，补丁在
        docs/archive/scroll-rounds-8-10/diffs/rounds-8-10.patch，红测在同目录 tests/
      - copy-mode 滚动的结果进不了 pipe-pane 推流（字节级实证）——任何新方案都不能依赖它送画面
    open_questions:
      - "leader 问过但用户尚未回答：除 Claude Code 外是否还需要在别的 TUI（vim/htop/lazygit）里上滑？这决定要不要做 mouse_any_flag 判别与降级路径"
      - "鼠标事件怎么送达：tmux send-keys -M 无法外部合成（已实证）；候选是把 SGR 1006 序列写进 pane 的 pty。需实测在 mouse_any_flag=1 的程序上是否被解析"
      - "非 mouse 上报的程序（裸 shell）怎么办：不发、还是回落 tmux copy-mode？"
    acceptance:
      - "开工前先消掉部署分歧：生产 daemon pid 86755 跑的是已回退代码编出的二进制（见 docs/archive/scroll-rounds-8-10/README.md §四）"
      - "先实测：在 mouse_any_flag=1 的真实 Claude Code pane 上注入 SGR 滚轮序列，观测它是否真的滚动（禁止靠推断定案，前十轮全栽在这）"
      - "判据只认用户可见结果：画面里的字真的往上走、能看到上文；不许断言'调用了 InjectScroll'或'tmux 里滚了'"
      - "复用归档里的手势层符号与守恒红测，不要重写"
      - "眼见为实：用户真机验收（模拟器暂停中，且模拟器曾在裸 shell 上给出错误的绿）"
    deps: []
    write_scope: ["app/app/src/main/java/dev/agentmirror/app/termview/", "app/app/src/main/java/dev/agentmirror/app/session/", "server/internal/", "app/app/src/test/"]
    evidence: ".team/evidence/feat-remote-scroll-mouse-wheel.json"
```

## 2. 架构基（build_wiki.py 现算影响闭包）
- write_scope 包：dev.agentmirror.app.termview, dev.agentmirror.app.session, internal/
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
