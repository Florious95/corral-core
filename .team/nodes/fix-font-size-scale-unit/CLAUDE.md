# 知识基底 · fix-font-size-scale-unit（tools/basegen.py 编译产物——手工编辑无效，重编请改素材源后重跑）

## 1. 任务信封（taskbook.yaml 原文，机械抽取）
```yaml
  - id: fix-font-size-scale-unit
    title: "字号最小档仍比旧字号大一倍多：预设用 sp，旧世界用物理像素，量纲选错"
    status: todo
    owner: null
    contention: none
    discovered: "2026-08-14 用户真机反馈：「我把字体调到最小，还是比之前的字体大很多」「最小的字体都不够特别小，导致我看的内容很少」"
    context: |
      **前提：架构方向是对的，别推翻。** 用户同一条反馈的前半句是
      「尺寸这个问题就相当于已经完全把上面几个问题解决了——第一个是闪烁，第二个是部分跑到
      屏幕外面，第三个是一致性」。feat-font-size-setting-drop-pinch 的三条主症状全消。
      本条只是刻度盘的量纲错了，不是方案错了。

      **根因（leader 已核代码）**：
        TermSurfaceView.kt:446   val sizePx = fontSizeSp * resources.displayMetrics.scaledDensity
        旧世界（已删的捏合时代）  textSize = DEFAULT_CELL_HEIGHT(20) × 0.85 = 17【物理像素】
      预设 12/14/16/18/20 用的是 **sp**（受系统字体缩放影响），旧世界用的是**物理像素**。
      用户手机若为 3.0x 密度，12sp = 36px ≈ 旧字号的 2.1 倍。
      **所以"最小档"比他一直在用的字号还大一倍——不是选项不够多，是整个刻度盘错了量纲。**

      **⛔ 未闭合的取证，动手前必须先拿到**：用户手机真实的 px/sp 比值。
      取法：让用户在 App 里 设置 → 查看诊断日志 → 复制全部，找 `grid` 那条记录里的
      `cell_width_measured`（那是当前字号下实测的字形推进宽，单位物理像素）。
      配合当时选中的档位（sp 值）即可反算 scaledDensity，据此定准确档位。
      **不许拍脑袋改数字**——本工程 2026-08-14 已七次栽在"没测量就下结论"上。

      **一个待定的设计问题（拿到数后一并裁）**：终端字号是否应该受系统 sp 缩放影响？
      终端用户要的是"一屏能看多少内容"，而 sp 会被系统无障碍设置放大——两者诉求冲突。
      候选：改用 dp/px 直接控制；或保留 sp 但把范围下探到覆盖旧字号。此问需 leader 裁定。
    acceptance:
      - "先取数：拿到用户真机 grid 记录里的 cell_width_measured + 当时档位，反算 px/sp 比值（禁止推断）"
      - "最小档必须 ≤ 旧世界的等效字号（textSize 17 物理像素），用户诉求是'看到更多内容'"
      - "红测：给定档位与 px/sp 比值，断言算出的 textSize 落在预期物理像素区间"
      - "眼见为实：用户真机切到最小档，截图证明一屏内容量明显多于当前"
      - "不倒退：feat-font-size-setting-drop-pinch 的 11 个用例与 5 处定点变异全部保持有效"
    deps: ["feat-font-size-setting-drop-pinch"]
    write_scope: ["app/app/src/main/java/dev/agentmirror/app/termview/", "app/app/src/main/java/dev/agentmirror/app/SettingsScreen.kt", "app/app/src/test/"]
    evidence: ".team/evidence/fix-font-size-scale-unit.json"
```

## 2. 架构基（build_wiki.py 现算影响闭包）
- write_scope 包：dev.agentmirror.app.termview, dev.agentmirror.app.SettingsScreen.kt
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
