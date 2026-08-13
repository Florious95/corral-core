# HANDOFF · leader 夜班交接 · 2026-08-12 23:40

> 本文是写给**刚接手、没看过过程**的人。所有代号首次出现即解释，路径/sha/进程号写全。
> 前一份交接是 `HANDOFF-leader-20260812.md`（18:11，上一届 leader 退役时写），**不要覆盖它**，两份都要读。

---

## §0 compact 后先做什么

### 一句话现状

今晚从 v2 基线重做缺陷修复，**已收口 13 条**（其中 4 条经用户真机亲验），3 条在途。
刚因协调器故障做了一次**错误的整队重启**，已收拾干净并重启为 5 席位精简团队。
**生产 daemon 当前是停的，用户手机连不上。**

### 开口第一句（对用户说）

> 「生产 daemon 在 team shutdown 时被一并停掉了，你手机现在连不上——要我立刻拉起来吗？
> 另外 `w-dev-d38`（切后台回前台视口不恢复）已完工在等我批准跑全量单测；
> 磁盘上还压着 13 个改动文件 + 12 个新增文件没打 git 锚点。」

### 必读清单（按优先级）

1. **本文**
2. `HANDOFF-leader-20260812.md` —— 上一届 leader 的退役交接（v5 失败全景）
3. `/Volumes/nvme/Projects/远程Agent安卓/CLAUDE.md` —— 工程铁律
4. `docs/web-vs-android-terminal-model.md` —— Web(xterm.js) vs 安卓自研内核的模型差异，**今晚最有价值的产出**
5. `docs/oss-terminal-solutions.md` —— herdr/ghostty/alacritty/wezterm 开源方案调研
6. `docs/d26-glyph-whitelist-refuted.md` —— 用户否掉「靠字符串判 agent 状态」的裁定
7. `docs/ops-coordinator-hot-restart.md` —— 协调器热重启（**别再用 team-agent restart 修它**）
8. `.team/evidence/*.json` —— 13 条收口证据；`.team/evidence/archive/*FALSIFIED*` —— **两份假 PASS 记录**

### 恢复动作

```bash
cd /Volumes/nvme/Projects/远程Agent安卓

# 1. 拉起生产 daemon（当前已停，用户连不上）
bash .team/prod-daemon-launch.sh -host 192.168.31.116
#    起来后核：lsof -iTCP:9900 -sTCP:LISTEN

# 2. team 已在运行（5 席位），核活：
.team/ta status --json | python3 -c "import json,sys;d=json.load(sys.stdin);[print(k,v.get('status'),v.get('worker_state')) for k,v in sorted(d['agents'].items())]"

# 3. 若 leader pane 掉了
.team/ta claim-leader --confirm --workspace .
```

---

## §1 身份与不变量（今晚被反复检验的铁律）

### 角色边界

- **leader 只编排不亲做**。今晚违规两次：连续 grep 代码查根因（用户当场纠正「你不要亲力亲为」）、
  写 for 循环自动删席位重试（用户拦下）。
- 跨 team 转交用**全名**：框架问题直投
  `/Users/alauda/Documents/code/agent前沿探索/多agent协作::refactor-maintainability/leader`

### 客观核对，不凭自报（今晚三次实证）

1. **两份假 PASS 记录**（都在 `.team/evidence/archive/`）：
   - `fix-upload-token-chain.FALSIFIED-20260812.json`：声称打通整条上传鉴权链、附具体行号，
     **代码里一个环节都不存在，git 全历史无任何提交**
   - `fix-scrollback-d36.FALSIFIED-20260812.json`：声称修好服务端 scrollback 坐标平移，
     **修复从未落地**
   - 成因相同：写码席位把改动留在**未提交工作区**后退役，验证席据此立 pass 证据，改动随后被 `git checkout` 抹除
   - **教训：未提交的工作不是交付物。** v4 的导航实现与上传鉴权链就是这么永久丢失的
2. **模拟器结论三次被真机推翻**：捏合报「完全无反应」→ 用户一捏就正常；
   上滑报「画面完全不动」→ 用户能滑一段；上传模拟器验过 → 用户在 TS 下 timeout。
   **共同点：我们验的场景和用户用的场景不是同一条路。**
   → **凡涉触控手势 / 传输通道的模拟器结论，不得单独作为判据。**
3. **两个席位对同一现象给出互斥根因**（最右列截断）。处置：不拍板选边，
   要求先写**判别红测**分辨，再动手。

### 通道纪律（今晚踩过）

- **deepseek(worker-api) 席位禁止 Read 任何图片**。图片进入历史后每次请求 400，上下文永久报废，
  已有一席因此死亡。所有 deepseek 角色文件已加「⛔ 通道硬限制」一节。
- **视觉验收一律交 Sonnet 席位 `w-base-v2`**。
- 当前通道：`w-base-v2` = `claude-sonnet-5[1m]` 订阅；其余 = deepseek `worker-api`（Codex 订阅已耗尽）。

### 并发纪律

- **全量 `:app:testDebugUnitTest` 串行**：定向 `--tests` 随便跑；全量只由开发席收工前独占跑一次，**跑前问 leader 排队**。
- **模拟器独占**：`emulator-5554` 与 `emulator-5556` 各由一席独占，adb 命令必须显式 `-s <设备>`。
- **同文件不并行**：`termview/TermViewPresenter.kt` 现由 `w-dev-d38` 持有。

### 安全（见 §6，原文照抄，不可弱化）

---

## §2 排期与封存令

**用户裁定（沿用）**：只修缺陷零新功能，F-01~F-06 封存。

**今晚新增的裁定**：

1. **「靠字符串/字形判 agent 工作状态」路线被用户直接否掉**（原文见 §3-C）
2. **不许加防抖来降低缺陷复现概率**——用户原话「那真的就是偏抖偏到姥姥家了」
3. **主场景是 Tailscale (TS)，不是局域网**。用户原话「最主要的场景还是 TS」。
   局域网会掩盖延迟相关缺陷，**不得用局域网下「看不出来」当作修好**。

---

## §3 P0 / 插队项

### A. 【当前最紧急】生产 daemon 已停，用户连不上

- `agentmirrord` 进程不存在，`:9900` 无监听。**在 `.team/ta shutdown` 时被一并带走**。
- 拉起：`bash .team/prod-daemon-launch.sh -host 192.168.31.116`
- ⚠️ **拉起后请注意**：daemon 会把**配对 token 明文打进 stdout**，而 stdout 被重定向到
  `.team/logs/agentmirrord-prod.log`。今晚 leader `tail` 该日志时把 token 带上屏了。
  **这本身是一条待修缺陷**（见 §4 队列）。建议拉起后换新 token。

### B. 协调器故障与 leader 的错误处置（已闭合，但教训要带走）

- **现象**：所有 `.team/ta send` 返回 `ok: False / coordinator_unavailable`，
  `diagnose` 报 `message_store_schema_version_mismatch` + `caller_newer_than_daemon`。
- **根因**（框架 team 已确认）：team-agent 全局升级到 0.5.65，同机各 workspace 的
  **旧 coordinator(0.5.64) 守护进程仍在跑**，schema 对不上。与本 workspace 无关。
- **leader 的错误处置**：照 `diagnose` 的 `suggested_repairs` 执行了 `team-agent restart`。
  该命令语义是「按团队状态重建**所有**席位」，结果把团队状态里累积的
  **111 个历史席位全部复活成活窗口**（tmux 里瞬间 29 个窗口），用户直接看到「角色被翻了好几倍」。
- **正解**（框架 team 提供，已记入 `docs/ops-coordinator-hot-restart.md`）：
  **只热重启协调器，两条命令，worker pane 与 tmux socket 完全不动。**
- **收拾结果**：整队 shutdown → 手工 remove 104 个历史席位 → 重启为 5 席位精简团队。
  协调器已用 0.5.65 重新拉起（pid 76087），`diagnose ok: True`，投递恢复。
- 框架 team 已把「diagnose hint 未告知 restart 会 kill 全 worker」记为其 A-52。
  leader 已补充告知「还会复活所有 stopped 席位」这一更大代价。

### C. 【契约级】agent 状态检测路线被用户否掉

- **用户裁定原文**：
  > 「你假如说认为这两种，一个是完成态，一个是工作中态，还在通过这样的**字符串形式**
  > 去确定它的工作状态，那就**完全走偏了**。并且**这两个实际上都是完成的状态**。
  > 你可以通过 **herdr** 这个仓库去确定如何正确的检测 Agent CLI 的状态。」
- **背景**：Claude Code 输出 `Brewed for 42m 3s` 与 `Churned for 3m 37s`，
  **两者都是完成态**，前导为同类星号字形。leader 一度误判为「一个工作一个完成」，
  并让席位把 `✳ ◐◑◒◓◔◕` 补进「工作中」字形白名单——**方向错误**。
- **后果**：若重编 daemon 上线该改动，所有跑完的会话都会因 `✳ Brewed for` 被判「工作中」，
  使用户报告的「已停止工作却显示工作中」误检**更严重**。
- **处置**：
  - **生产 daemon 暂不重编**（见 §4 依赖约束）
  - 已立案 `study-herdr-agent-state`，标 `contention: contract`，**定夺前相关模块不施工**
  - `server/internal/agentstate/*` 的改动（`rules.go`/`activity.go`/`sample.go`/`adapters.go`）
    **方向已被否决**，打锚点时应单独成一个提交并标明，或直接回退

### D. P0 对原排期的扰动

- 捏合族（`fix-pinch-preview-commit`，同时解 D-29 闪烁 + D-31 字号持久化）**因同文件冲突被压后**，
  等 `w-dev-d38` 释放 `TermViewPresenter.kt`
- D-26 后续（Layer ② 活性判据接线）**因 §3-C 裁定暂停**，等 herdr 调研结论
- 所有需要重编 daemon 的验证（D-36 服务端坐标修复）**被 §3-C 阻塞**

---

## §4 在途未收尾任务（逐条可执行）

**基线**：`main` @ `9653be07f`。归档分支 `v5-failed` @ `2874c54`（v5 全部失败改动，**不删**）。
tag `v2-baseline` = `7c5635364`。

### 4.1 `fix-viewport-restore-d38` —— 切后台回前台，终端只占屏幕一小块

- **负责人**：`w-dev-d38`（deepseek worker-api）
- **状态**：**代码已完工，卡在「等 leader 批准跑全量单测」**。席位已连发三次请示，
  其中一次因协调器故障未送达。
- **改动**（均在 write_scope 内，磁盘上）：
  - `app/.../termview/TermViewPresenter.kt` +`onRealViewportChanged`（68 行）
  - `app/.../termview/TermSurfaceView.kt` +`onWindowVisibilityChanged`（39 行）
  - 新增 `TermViewViewportRestorePresenterProbeTest`（4 用例）、`TermSurfaceViewportRestoreTest`（3 用例）
- **设计**（leader 已认可）：两个入口**语义正交**——
  `onViewportSizeChanged` = IME/输入框挤压（只推 visibleRows 不 emit）；
  `onRealViewportChanged` = 回前台/旋转/分屏/窗口变更（重算 + 按需 emit 一次）；
  由 View 层按事件源分派，因为「回前台那一刻 IME 在不在屏」只有 View 层知道。
- **下一步**：批准它独占跑全量 `:app:testDebugUnitTest` → 收工 → 立证据 JSON
- ⚠️ **但有一个更深的发现尚未纳入**（见 4.4），可能说明客户端修复只解决一半

### 4.2 `fix-cols-grid-convergence` —— 最右列文字被截断（用户第 4 次报告）

- **负责人**：`w-dev-cols`（deepseek worker-api），状态 BUSY
- **关键**：两个席位给出**互斥根因**，leader 未拍板，要求**先写判别红测**：
  - 假说 A（`docs/web-vs-android-terminal-model.md`）：cols 用**名义字格宽(默认10px)**算、
    绘制用**实测字形宽**算，两栅格永不收敛 → 预测「ASCII 也会被截」
  - 假说 B（`docs/oss-terminal-solutions.md`）：内核 `TerminalGrid.write` 已防宽字符占末列，
    问题在渲染层 `TermSurfaceView.drawLine/drawCentered` 把**宽字符**画过右缘被 Canvas 裁半
    → 预测「只有 CJK 被截，ASCII 不会」
- **进度**：判别红测文件已在磁盘 `TermColsGridConvergenceDiscriminationTest.kt`
- **下一步**：等它报判别结果 → **先看结果再批准动手修**
- **根治参考**：alacritty/xterm.js 的 `LEADING_WIDE_CHAR_SPACER` 边界模型（MIT/Apache-2.0 可引用）

### 4.3 `fix-input-send-fullrepaint` —— 发消息后整屏自上而下逐行刷，看不到底部最新消息

- **负责人**：`w-dev-repaint`（deepseek worker-api），状态 BUSY
- **用户给的决定性对照组**：同样的内容变化，**teammate 从其他进程写入从不出问题，只有用户从 App 发送才出**
- **leader 假设被席位用代码证据推翻**：leader 认为「发送路径触发 snapshot 重取」，
  席位逐环走完 `sendDraft → sendInput → handleInput → relay → onBinary`，
  证明发送路径**无任何** snapshot 重取/重订阅/resize 副作用
- **席位给出的机制**（leader 已认可）：
  - 触发器 = **外部 CLI 自己的 recap**（用户发消息 → Claude Code 清屏 + 自上而下重画整屏），
    作为大 delta 流式回传
  - 放大器 = **客户端每帧全窗口重绘**（`TermSurfaceView.onDraw` 每帧清屏重画可见窗口全部行）
  - teammate 写入是小增量 delta → 整帧重绘看不出；用户发送触发整屏 recap → 看得一清二楚
  - TS 高 RTT 下分片被节流 → 帧更多 → 刷得更久（用户实测 LAN 约 1 秒、TS 更久）
- **已批准的 5 步计划**：
  1. 红测 A（回归锁）：`sendDraft` 只产生 `InputFrame`，无 Subscribe/Unsubscribe/Resize
  2. 红测 B（现红）：Robolectric RecordingCanvas 断言只画脏行；两条路径重绘范围一致
  3. 修复：`TermSurfaceView` 按 `takeDamage` 局部重绘（ghostty `collect_dirty_patch` 模式，MIT）
  4. **红测 C（leader 追加，必做）**：模拟整屏 recap 分片到达，断言**用户看不到中间态**
  5. 回归门 + 全量
- ⚠️ **红测 C 是关键**：只做脏行渲染只解决「整屏乱刷」，**解决不了「看不到底部最新消息」**——
  后者需要「recap 期间不展示中间态」（Web/xterm 用约 120ms 合并）。
  **必须向席位/用户讲清这不是被否掉的那种防抖**：
  ❌ 被否掉的 = 加延迟让 bug 更难撞见；✅ 要做的 = 屏幕正在被重写时不展示写了一半的样子。
- **席位另报的服务端残留**：`.team/evidence/fix-d27-v3.json` 声称的服务端「no-op resize skip」
  **实际不存在**（`handleResize` ws_handler.go:267-310 无条件补发 snapshot），
  这是**第三份记录与代码不符**。按边界该席位未动 server/，需另立案。

### 4.4 【新发现，尚未立案】主机侧 tmux pane 尺寸被改小且不恢复

- **用户实证**（截图）：切回 App 后对话框在屏幕中间；**去 Mac 上看同一个 CLI，底部也是被截断的**
- **含义**：这不是客户端渲染问题，**App 真的把主机 tmux pane 尺寸改小了，且没有人负责恢复**
- **它同时解释三条缺陷**：D-38（回前台只剩一小块）、D-21（退出会话不恢复终端尺寸）、
  以及用户在 Mac 上也能看到「半截」
- **状态**：leader 已向用户说明，**尚未写进现场基、尚未立案**（用户当时叫停派活）
- **下一步**：立案，且需重新评估 4.1 的客户端修复是否只解决一半

### 4.5 已立案未开工（按优先级）

| task_id | 内容 | 阻塞原因 |
|---|---|---|
| `study-herdr-agent-state` | herdr 如何正确检测 agent 状态 | 契约级，等派 |
| `fix-pinch-preview-commit` | 捏合只本地缩放、松手发一次 resize（解 D-29+D-31） | 等 `w-dev-d38` 释放 `TermViewPresenter.kt` |
| `fix-session-list-stale` | 已消失的会话仍留在 App 列表，点进去 `session_not_found` | 等派 |
| `study-image-inline-context` | 图片直接进 agent 上下文而非只注入路径（用户报过 4-5 轮） | 方向待用户认可 |
| （未立案）D-27 服务端 no-op resize skip | 见 4.3 席位报告 | 等立案 |
| （未立案）D-26 Layer ② 接线 | 见 §3-C | 等 herdr 结论 |
| （未立案）daemon 日志明文记 token | 见 §3-A | 等立案 |
| `fix-terminal-resize-cluster` 中的 D-21 | 退出会话恢复终端尺寸 | 归档 `v5-failed` 有代码可参考 |
| D-34 缩放字体堆叠 | 依赖捏合先修好 | — |
| D-28 捏合后右侧溢出 | 可能与 4.2 同源 | — |

### 4.6 git 锚点状态（**必须尽快打**）

磁盘上未提交：**13 个改动文件 + 12 个新增文件**（含 7 个新测试、4 份 docs）。
今晚已实证「未提交的工作会归零」两次。建议分组：

1. 上传 tsnet 通道（`HttpUrlConnectionUploader.kt` + `HttpUrlConnectionUploaderTsnetRouteTest.kt`）—— 已收口
2. D-36 完整历史（`SessionViewModel.kt`/`ws_handler.go`/`bridge.go`/`test/cases/` + 3 个新测试）—— 已收口
3. D-38 视口恢复（`TermViewPresenter.kt`/`TermSurfaceView.kt` + 2 个新测试）—— 待全量绿
4. **D-26 字形白名单（`server/internal/agentstate/*`）—— 方向已被否决，单独提交并标明，或回退**
5. 调研文档 4 份（`docs/*.md`）
6. `docs/wiki/README.md` + `t3-report.md` —— archwiki 重生成物

---

## §5 运维与外部

### 进程与设备（已核实，2026-08-12 23:40）

| 项 | 状态 |
|---|---|
| 生产 daemon | ❌ **已停**，`:9900` 无监听。拉起：`bash .team/prod-daemon-launch.sh -host 192.168.31.116` |
| team | ✅ 运行中，5 席位，session `team-remote-agent-android` |
| coordinator | ✅ pid 76087，0.5.65，`diagnose ok: True` |
| `emulator-5554` | ✅ device（AVD `wedding_user_a_api35`） |
| `emulator-5556` | ✅ device（AVD `agentmirror_test_b`，今晚新建，用户授权开第二台） |
| 看门狗 | ⚠️ 未复核，整队重启后状态未知，**接手后请核** |

### 用户当前环境

- 手机经 **LAN** 连（`ws://192.168.31.116:9900/ws`）；**Tailscale 已不可用**
  （`Tailscale.app` 不存在、无 `tailscaled`、无 100.x 接口）
- 用户装的包：`~/Desktop/agentmirror-v6-9653be0.apk`（38MB，SHA-256 `6d114f07…`，21:38 构建）
- v4 参考包已归档到 `~/Library/Application Support/agentmirror/apk-archive/agentmirror-debug-20260812-v4.apk`
  —— **其源码已永久丢失，此包是 v4 那份实现唯一残存物证，不要删**

### 外部通道

- 框架问题直投：
  `team-agent send '/Users/alauda/Documents/code/agent前沿探索/多agent协作::refactor-maintainability/leader' '...'`
- 今晚已就协调器 schema 问题往返两次，对方已确认并给出热重启方案（见 `docs/ops-coordinator-hot-restart.md`）

---

## §6 安全约束（原文保留，不可弱化）

- **密钥只存在于 `.team/current/profiles/*.env`，任何席位禁止读其原文。**
  只能用 `team-agent profile show <name> --workspace . --json` 看脱敏诊断。
- **配对 token 与 TS authkey 同级——不落日志、不上屏明文、不入截图，QR 是唯一合法出口。**
  TS authkey 传入只经 `TS_AUTHKEY` 环境变量。
- **凭据分两级**（今晚 leader 裁定）：
  - 用户**生产凭据**（`/Users/alauda/Library/Application Support/agentmirror/token`）：
    绝不进 argv / 日志 / 截图 / report_result
  - 测试席**自建隔离 daemon 的一次性 token**：丢弃品，进 argv 属可接受风险
- **不许手改 App 的 SharedPreferences 来绕过配对流程**（既泄露凭据又制造无法复现的状态）
- **绝不触碰生产 daemon 与用户真实 tmux，只读也不行**，测试一律自建隔离环境。
  起隔离 daemon **必须**用 `AGENTMIRROR_E2E_DISCOVERY_SOCKET_DIRS` 收窄扫描
  （定义在 `server/internal/api/discoverer.go:21`；不设=生产全扫，设空=一个都不扫，设了=fail-closed 白名单），
  否则 discovery 会扫到宿主真实 socket。今晚已有 4 次越界记录（均只读、均自报）。
- **席位禁止 git push。**
- **GPL 隔离**：终端内核自研，依赖须 Apache-2.0 兼容。
  Termux 系 GPLv3 **不可用**；herdr-remote AGPL、mosh GPLv3 **只借鉴算法不复制代码**；
  herdr/ghostty/xterm.js/alacritty/wezterm/libvterm 为 Apache-2.0 或 MIT，**可引用**。
- **测试净化前缀** `env -u TEAM_AGENT_*`。
- **⚠️ 已知泄露风险**：daemon 把配对 token 明文打进 stdout → 落进
  `.team/logs/agentmirrord-prod.log`。leader 今晚 `tail` 该日志时已把 token 带上屏一次。
  **这是一条待修缺陷**，且建议重启 daemon 时换新 token。

---

## §7 今晚已收口清单（13 条，均有证据 JSON）

| task_id | 覆盖缺陷 | 状态 | 用户亲验 |
|---|---|---|---|
| `fix-rendering-d34-d35` | D-35 bypass 符号缺省 | pass | ✅「左下角那个透明红框解决了」 |
| `fix-upload-token-chain` | D-22 图片上传 401 | pass_user_confirmed | ✅ 成功传图两张 |
| `fix-back-gesture` | D-23 侧滑退出 + D-32 返回跳级 | pass_user_confirmed | ✅「侧滑的几个问题都解决了」 |
| `fix-ime-no-resize` | 输入框变高致终端重排 | pass | ⏳ 未专门确认 |
| `fix-upload-transport-tsnet` | 上传在 TS 下 timeout | pass_pending_tailnet | ⏳ TS 不可用无法验 |
| `fix-scrollback-history-d36` | D-36 向上滑看历史 | pass_pending_daemon_rebuild | ⏳ 需重编 daemon |
| `fix-agentstate-detection-d26` | D-26 状态检测 | **partial**（Layer② 休眠，方向已被否） | ❌ 用户报误检仍在 |
| `rootcause-flicker-v5` | v5 输入框闪烁根因 + 常驻守门探针 | pass | — |
| `base-v2-gate` | v2 基线门禁 + 回归基准 | pass | — |
| `test-pinch-harness` | 捏合测试能力 + 判定 | pass_with_instrumented_red | — |
| `study-web-terminal-model` | Web vs 安卓终端模型差异 | pass | — |
| `research-oss-terminal-solutions` | herdr/ghostty 等开源方案 | pass | — |
| （运维）协调器热重启手册 | — | 已归档 | — |

**用户今晚报的 11 条缺陷里，4 条已亲验修好，1 条已确认仍在（D-38），其余在修或排队。**

---

## §8 给后继的三条提醒

1. **用户的判断多次胜过我的推理。** 今晚被用户纠正的关键点：
   「透明红框 vs 符号缺省不是两种口径」「侧滑是回退造成的倒退」「别被概率性带偏」
   「向上滑是完全失效不是部分失效」「Brewed 和 Churned 都是完成态」「别亲力亲为」。
   **每一条都改变了工作方向。用户报的现象优先于任何内部推理。**
2. **凡「修了 N 轮还在」的缺陷，先怀疑定义错了。** D-36 四轮没修好，
   根因之一是定义被改过两次，每轮都在修一个用户没报的问题。
3. **账面会说谎。** 今晚抓到两份假 PASS + 一份记录与代码不符（D-27）。
   `taskbook.yaml` + `.team/evidence/` 是本工程的「唯一权威」，而它至少有三处不可信。
   **接手任何「已修」的结论前，先去代码里核一遍。**
