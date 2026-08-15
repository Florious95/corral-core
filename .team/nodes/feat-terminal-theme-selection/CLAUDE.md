# 知识基底 · feat-terminal-theme-selection（tools/basegen.py 编译产物——手工编辑无效，重编请改素材源后重跑）

## 1. 任务信封（taskbook.yaml 原文，机械抽取）
```yaml
  - id: feat-terminal-theme-selection
    title: "终端主题可选（黑/白等 CLI 常见主题）；并查清用户消息为何渲染成白底"
    status: todo
    owner: null
    contention: contract
    discovered: "2026-08-14 用户反馈：「我发的是白色的底，这不太符合它的，这显示不太舒服」「你要把 CLI 常见的一些主题在设置里面列出来，然后都可选……就像 Claude Code 它自身 CLI 也有多个主题」"
    context: |
      **这是两件事，只做后一件不会让白底消失——这一点必须先讲清楚，否则会白做一轮。**

      **(a) 白底的成因（leader 已初查，未定案）**：
      那个浅色块**不是我们画的，是 Claude Code 自己画的**。它有明暗主题，
      会探测终端背景色来决定用哪套。现在渲染出浅色块，说明**它以为自己跑在浅色终端里**。
      探测途径通常是 OSC 11 背景色查询或 `COLORFGBG` 环境变量。
      leader grep 实证：**服务端与 App 侧都没有任何 COLORFGBG / OSC 11 背景查询的处理**
      （`server/internal/` 与 `app/.../termview/` 全无命中）。
      ⇒ 假设：我们不回答这个查询，Claude Code 猜错。**这是假设不是结论，要实测证否。**

      **(a-补) 2026-08-14 19:36 用户第二张截图把根因收窄了，且证明这不是 Claude Code 专属问题**：
      那个 pane 跑的是 **Cursor Grok**（不是 Claude Code），同样出现浅底块，
      **而且浅底上的字几乎完全看不见**（「你有目标模式吗？」浅灰底浅灰字、
      「→ Add a follow-up」白底极浅字）。
      leader 查出两个硬事实：
        TermSurfaceView.kt:485   DEFAULT_FG = 0xFFE8E8E8   ← 接近白
        TermSurfaceView.kt:486   DEFAULT_BG = 0xFF0D1626   ← 深蓝黑，非标准黑
        反显 SGR 7 的处理：全 App grep **零命中**
      ⇒ **统一解释**：那些 CLI 以为自己在浅色终端，于是画输入框时显式设浅色背景、
        而**前景留给"默认值"**（它期望默认是深色）。我们的默认前景是接近白的 ⇒ 浅底浅字 ⇒ 看不见。
      ⇒ **白底、以及白底上看不见字，是同一个根因的两面**，不是两个 bug。
      ⇒ 同时印证用户那句「和日常看到的黑底主题、白色主题都不一样」：
        我们的底色 `#0D1626` 是自造的深蓝黑，不是标准黑。
      **另外要顺手查的**：反显（SGR 7）零命中意味着可能压根没实现。若某些 CLI 用反显画输入框，
      现在会渲染成普通字——**这是独立于上面那条的第二个候选缺陷，实测确认后再决定是否并入本条。**

      **(b) App 自己的主题调色板（用户要的功能）**：
      设置页列出 CLI 常见主题供选。注意：**这不能替代 (a)**——
      若 Claude Code 发的是 truecolor 背景，调色板改不了它；若是索引色，
      改索引映射又会波及其他用途。**先做 (a) 定案，再决定 (b) 的形态。**
    acceptance:
      - "(a) 先证否：实测 Claude Code 在本 App 终端里如何选择明暗主题（OSC 11 / COLORFGBG / 其他），给出观测证据而非推断"
      - "(a) 若确因未回答背景查询：补上回答/设置 COLORFGBG，实测用户消息块变为深色"
      - "(b) 主题选择：设置页可选，持久化；至少覆盖深色/浅色两套完整 ANSI 16 色 + 前景/背景"
      - "红测：切换主题后调色板实际生效（断言渲染取色，不断言'调用了 setTheme'）"
      - "眼见为实：用户真机截图确认用户消息块不再是刺眼白底"
      - "不倒退：GlyphFallbackPolicy / 双宽 / BCE 背景等既有渲染行为不变"
    deps: []
    write_scope: ["app/app/src/main/java/dev/agentmirror/app/termview/", "app/app/src/main/java/dev/agentmirror/app/SettingsScreen.kt", "server/internal/", "app/app/src/test/"]
    evidence: ".team/evidence/feat-terminal-theme-selection.json"
```

## 2. 架构基（build_wiki.py 现算影响闭包）
- write_scope 包：dev.agentmirror.app.termview, dev.agentmirror.app.SettingsScreen.kt, internal/
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
- 无现场素材文件
