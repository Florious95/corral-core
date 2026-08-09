# 知识基底 · fix-term-residuals（tools/basegen.py 编译产物——手工编辑无效，重编请改素材源后重跑）

## 1. 任务信封（taskbook.yaml 原文，机械抽取）
```yaml
  - id: fix-term-residuals
    goal: >
      P1（w-term-debt 界外两条，2026-08-09）：①resize 后服务端不补快照——SIGWINCH 重排后客户端
      留提示符残影（协议/服务端 resize 语义补快照或增量重放，方案席位裁量报 leader）；②会话页
      首行被 translate(0,-lineHeightPx) 裁切半行（A-snapshot-align.png 可见）。红测先行。
    acceptance: ["bash -lc 'cd app && ./gradlew -q :terminal:test :app:testDebugUnitTest'", "bash -lc 'cd server && go test ./internal/api/... ./internal/bridge/...'"]
    deps: ["fix-term-render-debt"]
    write_scope: ["app/terminal/", "app/app/src/main/java/dev/agentmirror/app/termview/", "server/internal/api/", "server/internal/bridge/", "app/app/src/test/"]
    evidence: ".team/evidence/fix-term-residuals.json"
    contention: impl
```

## 2. 架构基（build_wiki.py 现算影响闭包）
- write_scope 包：dev.agentmirror.app.termview, internal/api, internal/bridge
- 正向依赖（你消费的契约，只读）：go_internal_agentstate, go_internal_discovery, go_internal_protocol
- **反向依赖（波及面=回归自查范围）**：go_cmd_agentmirrord, kt_dev_agentmirror_app_session

### 闭包架构卡内联（职责/导出面/依赖边）

### Kotlin · dev.agentmirror.app.termview

- **职责**：终端渲染：快照/增量渲染 + 本地滚动视口（60fps，需求 006）。
- **导出面**：ANSI_COLORS, TermSurfaceView, TermViewPresenter
- **依赖边**：（无）
- **doc 全文**：终端渲染：快照/增量渲染 + 本地滚动视口（60fps，需求 006）。 [TermViewPresenter] 纯 JVM 视口状态机（跟随/锁定历史、可见行窗口、捏合行列数换算、 脏区合并），单测全部打在它上；[TermSurfaceView] 薄 Android 层（Canvas 画格、拖动/捏合 手势、Choreographer 帧调度）。内核为 :terminal 模块；resize 协议帧由上层接线（conn/session）。

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

### Kotlin · dev.agentmirror.app.session

- **职责**：会话页：单个 tmux 会话的交互界面。
- **导出面**：Attachment, Failed, Failure, HttpUrlConnectionUploader, SessionRoute, SessionScreen, SessionViewModel, Success
- **依赖边**：dev.agentmirror.app.conn, dev.agentmirror.app.service, dev.agentmirror.app.termview
- **doc 全文**：会话页：单个 tmux 会话的交互界面。 组合终端渲染（termview）与输入下发（conn），承载缩放、手势与快捷输入条。 本包为占位骨架，由 session 任务落位实现。

## 3. 需求基
- goal 引用条目：（goal 无编号引用）
- librarian 撞库回执：.team/nodes/fix-term-residuals/LIBRARIAN.md（先完整读）
- 修订记录 requirement-base/REVISIONS.md 必读（被推翻结论不回改条目）

## 4. 经验基（通用纪律+先例）
- 红测先行；每次落盘保持整模块可编译（共享编译单元互阻三次实案）；编译被他人半成品阻断→直接 send 文件主人（附文件+行号+错误原文），主人最高优先修复回执，不经 leader
- 测试净化前缀 env -u TEAM_AGENT_*；tmux 只用自建隔离 socket；杀进程只 scoped kill 自己命名空间（w-fix-statewire 险案）
- 代码必须带注释（设计决策写为什么）；禁止 git push；本地不 commit；report_result 恰好一次带 tests

## 5. 现场基（leader 手填取证素材——唯一手填合法区）
- .team/nodes/fix-term-residuals/FIELD.md（先完整读；含真机实证/失败现场/裁定）
