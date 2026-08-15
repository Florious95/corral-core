# 知识基底 · fix-image-upload-input-box（tools/basegen.py 编译产物——手工编辑无效，重编请改素材源后重跑）

## 1. 任务信封（taskbook.yaml 原文，机械抽取）
```yaml
  - id: fix-image-upload-input-box
    title: "发图后文件名落进输入框；且需 leader 额外 Read 一次，每次多烧一整轮 LLM 请求"
    status: todo
    owner: null
    contention: contract
    discovered: "2026-08-14 用户反馈 + 截图（upload-20260814T112827）"
    context: |
      **用户原话**：「要解决一下我发一张图，它出现在输入框的问题，它不应该出现在输入框。
      并且……我在节点上给你发张图的话，你在那条消息直接就把图片给读了。但是我给你发图的话，
      你需要还有一个额外的读的动作，那这样的话就有额外的费用，因为它都是一次完整的 LM 请求，
      它会把全面的内容全部都加载一遍。」

      **两个子问题，成本那条更值钱**：
      1. **UI**：上传后文件名 `upload-20260814T112812-1000022709.jpg` 被塞进了输入框，
         用户还得自己删。截图可见输入框里就是这个文件名。
      2. **成本**：App 发图 ⇒ pane 里出现一个**路径字符串** ⇒ leader 必须再调一次 Read
         才能看到图。而用户在 Mac 上直接拖图给 Claude Code 时，图是**内联附件**，零额外往返。
         **每多一次 Read＝一次完整 LLM 请求，把整个上下文重新加载一遍**——
         用户当前上下文已达 1M 的 62%，这个代价很实。

      **一个待验的候选修法（假设，未测）**：App 插入的不是裸路径，而是 Claude Code 的
      文件引用语法（如 `@/path/to/image.jpg`）。若 Claude Code 对 `@` 引用的图片会内联附上，
      则一次往返即可，成本问题消失。**必须实测确认 Claude Code 是否对图片支持这个语法**，
      不要假定。若不支持，则本条只能优化 UI，成本那半需另想办法（或明确告知用户不可解）。

      **注意 PTY 的结构性限制**：App 与 Claude Code 之间隔着 tmux/PTY，
      没有传输"图片内容块"的通道，只能传字节。所以"像 Mac 上那样内联"未必可达——
      **如果实测证明不可达，如实告诉用户不可解，不要造一个假的解法。**
    acceptance:
      - "先实测：Claude Code 对 `@<图片路径>` 是否内联附图（给出观测证据）"
      - "若可行：App 改为插入该语法，实测 leader 无需额外 Read 即可看到图"
      - "若不可行：如实结论化，并至少修掉 UI 问题（文件名不落输入框）"
      - "UI：上传完成后输入框保持干净，用户不需要手动删除文件名"
      - "眼见为实：用户真机发一张图，截图证明输入框干净 + leader 侧一次往返内看到图"
    deps: []
    write_scope: ["app/app/src/main/java/dev/agentmirror/app/session/", "server/internal/", "app/app/src/test/"]
    evidence: ".team/evidence/fix-image-upload-input-box.json"
```

## 2. 架构基（build_wiki.py 现算影响闭包）
- write_scope 包：dev.agentmirror.app.session, internal/
- 正向依赖（你消费的契约，只读）：kt_dev_agentmirror_app_conn, kt_dev_agentmirror_app_service, kt_dev_agentmirror_app_termview, kt_dev_agentmirror_app_tsnet, kt_dev_agentmirror_app_ui_theme, kt_dev_agentmirror_terminal
- **反向依赖（波及面=回归自查范围）**：kt_dev_agentmirror_app

### 闭包架构卡内联（职责/导出面/依赖边）

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
