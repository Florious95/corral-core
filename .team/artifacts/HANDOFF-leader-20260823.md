# HANDOFF · 远程Agent安卓新 leader 接管 · 2026-08-23

> 写给刚接手、没看过本轮过程的新 leader。当前唯一重点是：**接管现有全自动账本，不得改成人肉编排；先恢复自动派单/结果消费，再继续性能基线链。**
>
> 落笔现场：仓根 `/Volumes/nvme/Projects/远程Agent安卓`，main HEAD `5d942d321`。

---

## §0 compact / 换手后先做什么

### 一句话现状

输入透传产品代码尚未开始新一轮修改；为守住 2026-08-22 性能基线，当前先造“四段 A/B/A/B 性能采样器”。`ledger.perf-sampler.v1` revision 2 的实现格实际完成并产生 durable result，但 `ledger-run` 因自动 `send` 固定 30 秒超时提前 park，未消费结果，也未派测试/探针/终审格；当前 5 个 Codex 席位全部 idle。

### 新 leader 对用户的开口第一句

> 「我已接上现有全自动账本：采样器实现席有一份尚未独立验收的产物，但自动驱动器在 send 回执 30 秒超时后错误 park，测试、探针、终审都没启动。我不会人肉补投；先恢复自动派单并消费已有 r2 result，性能门通过前不碰输入透传产品码。」

### 必读清单

1. 本文件：`/Volumes/nvme/Projects/远程Agent安卓/.team/artifacts/HANDOFF-leader-20260823.md`
2. P0 报告：`/Volumes/nvme/Projects/远程Agent安卓/.team/artifacts/ledger-p0-send-timeout-but-landed-20260823.md`
3. 性能契约：`/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/input-full-auto/perf-design/CONTRACT.md`
4. 当前账本：`/Volumes/nvme/Projects/远程Agent安卓/.team/ledgers/perf-sampler-v1.json`
5. 账本 DSL 源：`/Volumes/nvme/Projects/远程Agent安卓/.team/ledgers/src/perf-sampler-v1.py`
6. 驱动日志：`/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/_driver/perf-sampler-v1.out`
7. 工程规则：`/Volumes/nvme/Projects/远程Agent安卓/CLAUDE.md`
8. 全自动编排入口：`/Users/alauda/.agents/skills/ledger-orchestration-trial/SKILL.md`

### 恢复动作与工作流程

1. **先认领当前 team，不重启整队**：
   ```sh
   cd /Volumes/nvme/Projects/远程Agent安卓
   team-agent claim-leader --workspace . --team remote-agent-android --confirm --json
   ```
2. **核现场，不凭 handoff**：
   ```sh
   git log --oneline -5
   team-agent status --json --team remote-agent-android
   NODEPROBE_SOCK=/private/tmp/tmux-501/ta-b7cc1c640ccf \
     /Users/alauda/.agents/skills/tmux-node-activity/scripts/nodes.sh
   ps -p 28622 -o pid,ppid,etime,stat,comm
   python3 /Users/alauda/.agents/skills/team-heartbeat/scripts/manage.py status --workspace "$PWD"
   ```
3. **核已有结果，禁止重跑实现格**：
   ```sh
   team-agent results --case ledger_perf-sampler_v1__t.sampler.impl__r2 \
     --workspace . --team remote-agent-android
   git -C .worktrees/wt-charlie status --short
   ```
   现场已核：result id `res_78ffd6e637d8`；`wt-charlie` 有 3 个未提交文件，见 §4。
4. **先恢复守护**：30 分钟 heartbeat 已安装；若 status 显示 plist 未加载，按 skill 固定流程重新 trial 后 install，⛔ 直接 install。
5. **恢复期间禁令**：不重投 r2 case、不 reset/remove 当前 Codex 席位、不清 ledger attempts/lease、不修改产品码、不修改旧判据放行、不把实现席自报当验收通过。
6. **恢复完毕标准**：自动驱动器能在不重复执行 `t.sampler.impl` 的情况下消费 `res_78ffd6e637d8`，并由 ledger 自动派出 `t.sampler.test` 与 `t.sampler.probe`；nodeprobe 至少显示对应席位 working，日志出现 `dispatch-landed`。仅 PID 活着、消息 queued、inbox 有正文都不算。
7. 若现场与本文冲突，以 durable result、ledger revision、worktree 文件和最新日志为准；但任何冲突不得用人肉补投“修平”。

---

## §1 身份与不变量

- 新 leader 只定判据、裁定、并线；不亲写产品码、不替席位解冲突。
- 用户最新硬令：**必须走全自动编排，禁止人肉编排。** 直接 `team-agent send` 补投具体任务属于违规；本轮曾犯过，旧收件席已 remove 作废，见 §3。
- 所有可用席位必须是 Codex subscription，模型精确 `gpt-5.6-luna`。Grok 额度耗尽；Claude 已登出；非 Codex 席位已经全部 remove。
- 判据四态：0 通过、1 产品不通过、2 不可判、不适用；不可判不折色。
- 测试禁缓存：Go `-count=1`，Gradle `--rerun-tasks`。
- 性能前先 `sh tools/perfbase/envcheck.sh --gate`；不达标只能不可判。
- 当前性能标准：同一时段 A/B/A/B，三夹具、四段、每包每夹具 n≥10，p50/p95 的 B/A 均≤1.10；最终仍以用户真机“秒开无空白”为金标准。
- worker 自报、pane 存在、PID 活着都不是完成证据；必须独立机械判据与终审。
- 不许 `git add -A`；本仓有大量历史 worktree/runtime 脏项，只 stage 精确文件。
- 不写 `Co-Authored-By: Claude`。

---

## §2 排期与封存令

### 已完成并已客观核对

- Leader takeover 与 Codex 往返：message `msg_97a8ad8494b8`，result `res_73ef689b6ce0`。
- 当前目录非 Codex 席位清零：nodeprobe 最新只列 5 个 Codex。
- 30 分钟 heartbeat 已 trial + install：
  - label `com.team-agent.heartbeat.834d7731010c`
  - interval 1800 秒
  - `heartbeat.sh` 与 `trial.json` 哈希匹配
  - commit `5d942d321`
- 性能历史基线冲突已归因：tag 同路径 JSON 也是 null/INCONCLUSIVE，不能作可执行门；改用用户裁定的稳定 tag/参考 md5 + fresh same-batch A/B 契约，已落 `CONTRACT.md`。

### 自报完成、尚未验收

- `t.sampler.impl`：实现席报告完成；result `res_78ffd6e637d8`。这是**席位自报 + 自跑测试**，账本未消费、独立 test/probe/verify 均未运行，⛔ 不得并线或称完成。

### 封存 / 不得提前做

- 输入透传剩余产品任务（模式播种、双指滚轮、选区高亮、预测回显）必须等性能采样器验收并完成 fresh 基线后再动。
- 2026-08-22 稳定基线 tag `baseline-20260822-release` 不得回退；用户真机参考 APK md5 `0907d6881bb1e034ef33a49f89afaa44`。

---

## §3 P0 / 插队项

### P0：ledger-run 自动 send 固定 30 秒超时后 park，但消息实际落地

**未闭环，当前阻塞全自动编排。**

- revision 1：`t.perf.design` 自动 send 超时，任务实际落 inbox 并完成 blocked contract。
- revision 2：fresh Codex seats、fresh revision、preflight/dry-run 全绿；`t.sampler.impl` 仍在 30 秒精确超时后 park，但消息实际执行并产生 result。
- 机器日志：`.team/nodes/_driver/perf-sampler-v1.out`
- 报告：`.team/artifacts/ledger-p0-send-timeout-but-landed-20260823.md`
- 已投框架队 `/Volumes/nvme/Projects/讨论team-agent::wiki/leader`：
  - 首次 `msg_f346b60d6990`
  - 补充 fresh seat 对照 `msg_826eaa8a9aa8`
- ledger-run 量具身份：`/Users/alauda/.cargo/bin/ledger-run`，md5 `8c1c850bec4c86d230480b99fd6cd671`，mtime `2026-08-20T15:06:13+0800`。
- 原因边界：只确认“send 外层超时≠外部动作未发生”；未读框架源码，不判断卡在 CLI/coordinator/回执哪层。

### 本轮 leader 违规：曾人肉补投

用户第一次指出无人运行后，旧 leader 曾直接给实现/红测/探针席发“账本救援”消息。这违反“禁止人肉编排”。处置：收到这些人工消息的 `input-dev-luna`、`input-test-luna`、`input-review-luna` 已全部 remove；新建 `sampler-*-luna2` 三席，角色文件明确只接受 ledger-run 自动派单。当前 r2 实现结果来自 ledger 自动派出的新席，不是那三条已作废人工消息。

P0 插队导致：性能采样器 test/probe/verify 未开始；后续输入透传产品排期全部顺延。

---

## §4 在途未收尾任务

### A. 四段 A/B/A/B 性能采样器（当前唯一 active 链）

- 账本：`.team/ledgers/perf-sampler-v1.json`
- ledger id：`ledger.perf-sampler.v1`
- revision：2
- driver PID：`28622`
- driver 日志：`.team/nodes/_driver/perf-sampler-v1.out`
- driver 状态：进程存活但账本 `parked/AwaitingHuman`；不是推进中。
- heartbeat ledger 兼容 symlink：`.team/nodes/_driver/账本-perf-sampler-v1.json` → `.team/ledgers/perf-sampler-v1.json`

#### t.sampler.impl

- 席位：`sampler-dev-luna2`（Codex gpt-5.6-luna）
- worktree：`.worktrees/wt-charlie`
- durable result：`res_78ffd6e637d8`
- case：`ledger_perf-sampler_v1__t.sampler.impl__r2`
- 自报测试：
  - `bash tools/perfbase/run-input-ab.sh --self-test` rc=0
  - `python3 -m py_compile tools/perfbase/parse-input-ab.py` rc=0
  - `sh -n tools/perfbase/run-input-ab.sh` rc=0
  - `sh .team/ledgers/acceptance/sampler-impl.sh` rc=0
- 客观文件现场（已核存在，未核语义）：
  - `?? tools/perfbase/run-input-ab.sh`
  - `?? tools/perfbase/parse-input-ab.py`
  - `?? .team/nodes/input-full-auto/sampler-impl/IMPL.md`
- 状态：**自报完成、未验收、未提交、未并线**。

#### t.sampler.test

- 席位：`sampler-test-luna2`
- worktree：`.worktrees/wt-alpha`
- case：`ledger_perf-sampler_v1__t.sampler.test__r2`
- durable results：空
- 当前 ledger state：planned
- 应交：`RED.md` + `cases.json`

#### t.sampler.probe

- 席位：`sampler-review-luna2`
- worktree：`.worktrees/wt-bravo`
- case：`ledger_perf-sampler_v1__t.sampler.probe__r2`
- durable results：空
- 当前 ledger state：planned
- 应交：`PROBE.md`

#### t.sampler.verify

- 席位：`sampler-review-luna2`
- worktree：与 impl 共用 `.worktrees/wt-charlie`，依赖 test/probe/impl 全成功后才可运行。
- 当前 state：planned
- 应交：`VERDICT.md`，必须重跑 self-test、做破坏齿，末行 `verdict: pass` 才可收口。

### B. 后续产品任务（全部排队，禁止现在施工）

1. 订阅时鼠标模式播种：旧 `.worktrees/wt-modeseed` 有一份未验收 Opus 时代中间态，账本 `ledger.modeseed.v1` FailedRetryable；不得直接并线，未来从已验证基线重新走红测/实现/终审。
2. 双指滚轮：旧 `ledger.wheel.v1` 驱动器已停，worktree 无有效交付；目标是双指上下滑=滚轮、单指拖保留、物理鼠标/桌面通路不动。
3. 选区高亮：只有归因，未实现。
4. 预测性本地回显：未开始。

顺序：采样器验收 → fresh 性能基线 → 模式播种单改动+A/B → 用户真机 → 双指滚轮单改动+A/B → 用户真机 → 其余。

---

## §5 运维与外部

### Team

当前官方 team：`remote-agent-android`。现有 5 席全部 Codex：

- `pi-codex-bridge`
- `input-advisor-luna`
- `sampler-dev-luna2`
- `sampler-test-luna2`
- `sampler-review-luna2`

落笔时 nodeprobe：working=0、idle=5、unknown=0。Team status 的 `session_name` 仍显示历史 `team-grok-l2`，这是拓扑遗留显示；寻址必须使用完整逻辑地址 `/Volumes/nvme/Projects/远程Agent安卓::remote-agent-android/<seat>`，但用户已禁止 leader 人工派任务。

### Heartbeat

- 配置：`.team/heartbeat/heartbeat.sh`
- 周期：1800 秒
- explicit team：`remote-agent-android`
- trial：`.team/heartbeat/trial.json`
- state：`.team/heartbeat/state.json`
- log：`.team/heartbeat/heartbeat.log`
- LaunchAgent：`~/Library/LaunchAgents/com.team-agent.heartbeat.834d7731010c.plist`
- `manage.py status` 落笔时显示 loaded、`state = not running`；该 LaunchAgent 是定时触发而非常驻进程，间隔之间 not running 本身不等于卸载。用下一轮 heartbeat.log/launchctl 现场复核。
- 心跳只报告，按安全契约不重启、不重投、不改账本。

### Git

main 最新关键提交：

- `5d942d321` 安装输入链30分钟团队心跳
- `43d25b755` 切换采样器账本到纯自动新席位
- `d6bc54b06` 立四段性能采样器全自动并行链
- `7bce762bd` 编排输入透传性能基线全自动首链

仓根有大量历史 runtime/ledger/worktree 脏项；不要清理、不要 `git add -A`、不要把它们误归当前链。当前链产品实现只在 `wt-charlie` 三个未跟踪文件中。

### 外部通道

框架/ledger-run 问题只投 `/Volumes/nvme/Projects/讨论team-agent::wiki/leader`。同一根因且无新信息不重复投；fresh seat/revision 2 对照已经补投。禁止为框架队做复现阶梯；等待他们发布新基础设施时才换用。

---

## §6 安全约束（不可弱化）

- 密钥只存在于 `.team/current/profiles/*.env`，任何席位禁止读其原文。
- `.team/current/profiles/tailnet-test.env` 全员禁读（含 leader）；只能 `set -a; . <file>; set +a` 注入子进程，不打印、不落日志、不入截图。
- 禁读 Shadowrocket 偏好 plist、`tailscale_keys.bin`、生产 daemon 明文配对日志。
- 进程只取 `pid,ppid,etime,stat,comm`，禁止无过滤 `ps aux`、禁止 argv。
- 凭据泄露不等于停工：一行上报、不复述值、收紧做法、继续；不得删证据冒充处置。
- 不碰用户真实 tmux；隔离 tmux 必须短 socket、预建目录并 `list-sessions` 自检；测试结束只清自己创建的资源。
- 不碰生产 9900 daemon；不在 app 中打开真实会话。
- 席位不写 `/tmp` 或项目外路径；临时文件进本格 `.team/nodes/.../tmp/`。隔离 tmux 的短 socket 例外按任务书严格自检。
- 不读 provider profile 原文；只用 `team-agent profile show/doctor` 的脱敏输出。
- 禁 Deepseek、Fable、Opus、Grok；当前只用 Codex subscription `gpt-5.6-luna`。
- 禁止人肉编排：leader 不用 direct send 补投、催单或替 ledger 调度；自动编排坏了只能留 P0、使用对方发布的新基础设施，不能改他们引擎。
- leader 不亲写产品码、不改旧判据放行、不把不可判染色。
- 席位不得 commit/push/checkout/restore/worktree add；leader 只在客观验收后精确 stage/commit。
- 不写 `Co-Authored-By: Claude`。

---

## 后继第一个合法动作

**不是重投实现格。** 先让框架侧/新基础设施恢复“自动 send 超时后对账并继续”的能力，或取得能由 ledger-run 自动消费现有 r2 durable result 的正式路径。完成标准：账本写回 `t.sampler.impl=succeeded` 且证据绑定 `res_78ffd6e637d8`，随后自动派出 test/probe，日志有 `dispatch-landed`，nodeprobe 看到对应席位 working。任何 direct leader send 都不合法。
