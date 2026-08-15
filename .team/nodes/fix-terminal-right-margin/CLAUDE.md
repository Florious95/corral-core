# 知识基底 · fix-terminal-right-margin（tools/basegen.py 编译产物——手工编辑无效，重编请改素材源后重跑）

## 1. 任务信封（taskbook.yaml 原文，机械抽取）
```yaml
  - id: fix-terminal-right-margin
    title: "右边缘留白偏大：修完溢出后变成用不满宽度，与 Mac 上 CLI 的边距不一致"
    status: todo
    owner: null
    contention: none
    discovered: "2026-08-14 用户真机反馈：「边缘还是可以往外扩一扩……我的屏幕截图右边还是缩了一点点，它右边再往外扩一点点就好了，就和 Mac 电脑上看到的那个边缘那个空隙是一致的了」"
    context: |
      **这是 feat-font-size-setting-drop-pinch 之后出现的新状态，方向是对的、过头了一点。**
      修复前：上报的 cols 偏大 ⇒ 末列字形被挤出屏幕（缺陷②）。
      修复后：几何一次算对 ⇒ 不再溢出，但右侧留白比 Mac 上的 CLI 更宽，
      即**现在偏保守，宽度没用满**。

      候选成因（未验，禁止先入为主）：
      - cols 用了 floor 除法，余数全部变成右侧留白；余数接近一整列时浪费明显
      - 画布本身可能有额外 padding / 边距
      - 与 fix-font-size-scale-unit 耦合：字号偏大时列数少，同样的余数占比更显眼
        ⇒ **可能字号量纲修完后本条自动缓解，先修那条再看**
    acceptance:
      - "先取数：用户真机 grid 记录里的 viewport_width_px / cell_width_measured / reported_cols / canvas_capacity_cols，算出右侧实际留白像素"
      - "与 Mac 上 CLI 的等效留白做对照（用户可给截图，按字宽折算）"
      - "红测：给定视口宽与实测字宽，断言 cols 取满且右侧留白 < 一个字宽"
      - "不倒退：不许为了用满宽度把末列又挤出屏幕——缺陷② 的红测必须保持绿"
      - "眼见为实：用户真机截图确认右边距与 Mac 观感一致"
    deps: ["fix-font-size-scale-unit"]
    write_scope: ["app/app/src/main/java/dev/agentmirror/app/termview/", "app/app/src/test/"]
    evidence: ".team/evidence/fix-terminal-right-margin.json"
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
- 无现场素材文件
