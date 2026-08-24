# HANDOFF · 远程Agent安卓 leader · 2026-08-24

落笔时刻（UTC）：2026-08-24T14:16:07Z。后继只读本文件 + 它点名的路径即可接手。

## §0 compact 后先做什么

**一句话现状：** 你是本仓 Team Agent **leader**（当前会话是 Grok 接手的 Pi 会话）。产品链停在「实验室性能门」：r13 同批 A/B 已测完且 **fail**；针对回退的归因/修复账本 `ledger.perf-regress.v1` r4 因 **找不到金标准 A 包** 停在不可判。scan-filter 源码已进 main，**生产 daemon 没换**。心跳每 30 分钟会误报「没有存活 driver」。

**开口第一句：**  
「当前卡在缺精确 A 包 md5=`0907d6881bb1e034ef33a49f89afaa44`（体积 35044459 bytes，tag `baseline-20260822-release`）。有这份 APK 才能测新候选 B'（md5=`daca6170aa58a8054aa3d20537a61e64`）。r13 实验室门失败已记账，不改 1.10、不重跑旧 B `3ebc9c55703c780c842a2f410b85034e`。scan-filter 源码在 `2f4698e14`，线上 daemon 仍是 8 月 23 日二进制。」

**必读（按序）：**
1. 本文件。
2. `CLAUDE.md`（供给、全自动编排、耗上下文禁令、sol 写任务书/判据、凭据红线）。`AGENTS.md` 是它的符号链接。
3. `.team/nodes/input-full-auto/perf-design/CONTRACT.md`（可执行 A/B 门）。
4. `.worktrees/wt-input-perf/.team/nodes/input-full-auto/perf-measure/MEASURE.md` 的 r13 节 + 同目录 `perf-ab.json`。
5. `.team/nodes/spec-sol/perf-regress/任务书.md` 与 `.team/ledgers/acceptance/perf-regress.sh`。
6. `docs/输入透传契约.md`（输入链后续；§1 有节首更正与后文旧 mouse 说法冲突，未裁定）。

### 恢复工作流程（编号，照做）

1. **先核对，后开口。** 文档可能过期。至少跑：
   - `git rev-parse --short HEAD`（落笔是 `4c37abf9b`）
   - `python3 -c 'import json; d=json.load(open(".team/ledgers/perf-regress-v1.json")); print(d["revision"], {k:v["state"] for k,v in d["tasks"].items()})'`
   - `ps -p <lease pid> -o pid,etime,stat,comm`（只这四列，不要 `command/args`）
   - `lsof -nP -iTCP:9900 -sTCP:LISTEN -t` 再 `ps -p` 同样四列
   - `NODEPROBE_SOCK=/private/tmp/tmux-501/ta-b7cc1c640ccf` + tmux-node-activity 的 `nodes.sh` 判活（⛔ `worker_state`）
2. **先恢复守护，后推进。** 心跳已装：`python3 ~/.agents/skills/team-heartbeat/scripts/manage.py status --workspace "$PWD"`。label=`com.team-agent.heartbeat.834d7731010c`，周期 1800s。若 trial 哈希与 `heartbeat.sh` 不一致，按 team-heartbeat skill 重 trial 再 install。**不要**因为心跳写「没有存活 driver」就杀进程或重派。
3. **恢复期间禁令：** 不 `remove-agent`/`shutdown-team`；不 `team-agent collect` 抢 active ledger 的 result；不人肉 `team-agent send` 补投产品格；不重跑同一旧 B；不改 1.10；不把 `measurement: unjudgeable` 折成 pass；不凭账本 `AllSucceeded` 并线（必须看 `VERDICT.md` 是否 `verdict: pass`）；不读 `.team/current/profiles/*.env`、`tailnet-test.env`、Shadowrocket plist、`tailscale_keys.bin`、生产 daemon 明文日志；`ps` 只取 `pid,ppid,etime,stat,comm`。
4. **恢复完毕标准：** 上列命令与现场一致；心跳仍 loaded；你知道卡点是金标准 A 包；没有误杀 park 的 `ledger-run`。
5. **与文档不符：** **以现场为准**，在对话里改口，再补写本 HANDOFF。不要为了圆文档去重启团队。

---

## §1 身份与不变量

- **你是 leader。** 团队 `remote-agent-android`，workspace `/Volumes/nvme/Projects/远程Agent安卓`。Pi 已接手，compact 后继续当这个 leader。
- **供给（2026-08-24 正本，CLAUDE.md）：** 执行席默认 Codex **`gpt-5.6-luna`**；**任务书与机械判据一律由唯一席 `spec-sol`（`gpt-5.6-sol`）撰写，leader 只审。** Claude 已登出不可调度。Grok **执行席**暂不开（等用户点名）。⛔ 不得把「Grok 额度耗尽」写成现行事实。角色文件 `dangerously_skip_permissions: true`。
- **leader 禁区：** 不写产品码、不解冲突、不写任务书/判据、不定位、不通读大树、不反复 grep/读大文件。探索/归因一律 TeamMate 或账本格。唯一允许直发 `spec-sol` 的是能力恢复与问询。产品格只走 `ledger-run`。
- **判据四态：** 0 通过 / 1 产品失败 / 2 不可判 / 不适用（XOR 未派）。不可判不准折成通过或失败。`measurement: unjudgeable` 必须 exit 2（已核 commit `4c37abf9b`）。
- **AllSucceeded ≠ 可并线。** 必须打开对应 `VERDICT.md` 看 `verdict: pass`。envcheck-emu / emu-own 都发生过机械门绿 + `verdict: fail`。
- **心跳误报：** `team-heartbeat` 常报「没有存活的 ledger-run driver」，而 park 的进程还在。以 `*.json.lease` 的 pid + `ps -p` 为准。
- **result 挂 message_id 不挂 ledger case_id：** waiter 可能空等。active ledger **禁止 collect**。
- **一格一分支；判据过了 leader 才并线。** 并线只拷任务书列明路径，不要顺带 `docs/wiki/t3-report.md`。

---

## §2 排期与封存令

用户令（原文精神，见 CLAUDE.md 与本会话）：

- 性能体验是核心，**不许回退**。金标准=真机蜂窝+广州中转「秒开、没有空白」。
- 输入透传链：**采样器 → 实验室 A/B 门 → 再改产品**（鼠标模式播种、双指滚轮、选区高亮、预测回显）。
- 实验室可执行门：三夹具四段、同批 A/B/A/B、每格 n≥10、nearest-rank p50/p95 **B/A≤1.10**。历史 `.team/perf/baseline-20260822.json` 是 INCONCLUSIVE/null，**不是**可执行门。
- **不改 1.10、不重跑同一 B 碰运气。**
- 用户真机称体验良好：记为金标准正向证据，**不能**把实验室 fail 改写成 pass。
- 全自动编排；断了要继续；耗上下文的活派 TeamMate。

已闭环（源码/工具，已核 git）：

| 项 | 核 |
|---|---|
| 四段 sampler | `ledger.perf-sampler.v1` r9 全 succeeded；脚本在 main（`53a311bde` 纳入） |
| scan-filter 源码 | `2f4698e14` 已核机械门 rc=0 后并线 |
| envcheck 两阶段闸 | `fb0945f35`，`VERDICT.md` 为 pass（第二轮终审） |
| emu-own runner | `55b84f8c5`，`VERDICT.md` 为 pass；emu-own.sh 与 envcheck-emu.sh 均 0 |
| perf-regress 判据分流 | `4c37abf9b`；r4 夹具实跑 **exit 2** |

未上线：scan-filter **未进生产二进制**。

封存/等待：输入链后续功能（modeseed/wheel/selection/echo）在实验室门对新 B' 全绿（或用户明文改口）之前不要开产品账本。

---

## §3 P0 / 插队

1. **用户真机卡**（本会话）：生产 daemon 空闲 CPU 曾到 24%。根因方向=扫描 `/private/tmp/tmux-501/` 下大量 socket。scan-filter 已合入源码。**止血：** 清了 5 个死 socket + 25 个仅 bash 的残留；留下 `default`、本队 `ta-b7cc1c640ccf`、有 node 的 `ta-e8878a711350`、两个仍跑 `team-agent` 的别人的队。**根治未上线：** 未换 `server/agentmirrord-night-4120c0884`。
2. **heartbeat「无 driver」假阴：** 不要当停机去杀/重派。
3. **P0 对原排期：** 换 daemon / 找 A 包 插在「继续测 B'」之前。输入功能被实验室门压住。

---

## §4 在途未收尾（可执行）

### 4.1 金标准 A 包（阻塞一切复测）— P0

- **做什么：** 拿到 **已核** md5=`0907d6881bb1e034ef33a49f89afaa44`、35044459 bytes 的 assembleRelease APK（debug keystore，仅本地对比不可分发）。
- **已核反例：** 按 tag 重建得到同体积但 md5=`2fda1fdec68f5aba9389b6a0a1e8598d`，**不能当 A**。仓内 `.team/`、`e2e/`、`app/.../outputs` 已搜过 **0 hit**（本 leader 核）。
- **谁：** 用户提供路径或文件；leader 不编造、不拿错包冒充。
- **有包之后：** 解挂 `ledger.perf-regress.v1`（impl `planned`、清 failed attempts、`revision++`），派 `sampler-dev-luna2` 对 B' md5=`daca6170aa58a8054aa3d20537a61e64` 跑 fresh A/B/A/B。判据 `.team/ledgers/acceptance/perf-regress.sh`：`unjudgeable`→2，有效样本超 1.10→1。
- **进程：** `perf-regress-v1.json.lease` pid **85754 ALIVE**（落笔 etime ~3h）。日志 `.team/nodes/_driver/perf-regress-v1.out`。park = 合法等 A 包，不是卡死。
- **禁：** 不重跑旧 B `3ebc9c55…`；不把 `measurement: unjudgeable` 当 fail 去返工产品。

### 4.2 实验室 r13 门失败（已记账，待 B' 复测）

- 账本 `ledger.input-full-auto.v1` **revision 13**，`t.perf.measure=failed_retryable`（当时判据把不可判也打成 1 的历史形态；r13 本身是 **有效样本 + 超 1.10**）。
- 证据（已核跑判据）：  
  - A `0907d6881bb1e034ef33a49f89afaa44` vs B `3ebc9c55703c780c842a2f410b85034e` rev `565542972`  
  - `redraw_tui.tap_to_route_enter` p50 ≈ **1.37**  
  - `real_claude_idle.first_frame_to_first_draw` p95 ≈ **1.14**  
  - 脚本另见 `real_claude_idle.tap_to_route_enter` p95 ≈ **1.11**  
  - `big_scrollback` 全过  
- 产物：`.worktrees/wt-input-perf/.team/nodes/input-full-auto/perf-measure/MEASURE.md`、`perf-ab.json`。
- 归因修复在途：`ledger.perf-regress.v1`（见 4.1）。实现席已做 keyecho-disabled fast path + 三类聚焦测试绿（WT 内 Gradle 已能跑）；**未**完成新 B' 的 fresh 测量。
- lease pid **96139 ALIVE**（input-full-auto r13 park）。不要重派同一 B。

### 4.3 生产 daemon 未换 scan-filter

- 源码已核并线：`2f4698e14`（2026-08-24 05:04 +0800）。
- 线上：**pid 72639**，`./agentmirrord-night-4120c0884`，二进制 mtime **2026-08-23 15:37**，落笔仍在听 `:9900`，cwd=`/Volumes/nvme/Projects/远程Agent安卓/server`。
- **不要瞎重启：** 启动参数可能含凭据，禁 `ps args`。用户说「按仓内惯例编好替换并听 9900」再备份、替换、核端口。备份目录曾用 `.team/nodes/_driver/daemon-backup/`。

### 4.4 spec-sol / 执行席

- `spec-sol`：Codex `gpt-5.6-sol`，角色 `.team/dynamic-role-files/spec-sol.md`。能力自证 `.team/nodes/spec-sol/能力自证.md`，问询 `.team/nodes/spec-sol/问询答复.md`。
- 执行席 luna：`sampler-dev-luna2` / `sampler-test-luna2` / `sampler-review-luna2`。已删的 `input-test-luna` 等不要再写进账本。
- `report_result` 缺 `tests.log_path` 会被框架拒（UnsupportedTestEvidenceSchema）；sol 曾 exact-once 不重试，leader 按盘面产物审。

### 4.5 心跳假阴

- 后继不要为「无 driver」去 `kill` 85754/96139。
- pid 文件：`.team/nodes/_driver/<ledger>.pid` 内容为 `pid=<n>`。stall-alert 认这个目录。

---

## §5 运维与外部

- 心跳：用户目录 LaunchAgent `com.team-agent.heartbeat.834d7731010c`。trial 绑定 SHA，改 `heartbeat.sh` 必须重 trial。
- 框架问题投 `/Volumes/nvme/Projects/讨论team-agent::wiki/leader`（`::team` 是死队）。
- `ledger-run send` 曾 30s 超时后 park，但消息可能已落地（P0 已报过）。`ok: True` 不是送达。
- 模拟器：测性能必须先 `envcheck --gate`；**本次自建模拟器**允许 load>12（`--measurement PID SERIAL`）；死 socket / daemon idle CPU>5% / 别人的 qemu / 无 adb 仍是 2。
- 清 socket 后 load 可能短暂升高。

---

## §6 安全约束（原文级，不可弱化）

- 密钥只在 `.team/current/profiles/*.env`，任何席位禁止读原文。
- **`.team/current/profiles/tailnet-test.env` 全员禁读（含 leader）**。只许 `set -a; . <file>; set +a` 注入子进程，不打印、不落日志、不入截图。
- 禁：无过滤 `ps aux`、`tail .team/logs/agentmirrord-prod.log`、Shadowrocket 偏好 plist、`tailscale_keys.bin`。
- `ps` 只取 `pid,ppid,etime,stat,comm`。
- 席位临时文件写 `.team/nodes/<格>/`，不写 `/tmp`。
- 给席位发消息只走 `team-agent send`；产品格由 `ledger-run` 发。
- 凭据已泄露 ≠ 停工：一行上报（不复述值）、收紧、继续。
- 隔离 tmux 必须自检自己的 socket，失败会静默回退到用户真实 tmux。

---

## 后继下阶段第一个动作

用户把金标准 APK 放到可核对路径后：

1. `md5` 必须等于 `0907d6881bb1e034ef33a49f89afaa44`。
2. 停 `perf-regress` park 驱动器（先 `ps -p 85754`），lease 文件删掉，impl `state=planned`、清 failed attempts、`revision++`。
3. `ledger-run --preflight/--dry-run` 后 `--drive --resident`，日志接到 `.team/nodes/_driver/perf-regress-v1.out`，pid 写成 `pid=<n>`。
4. 派单写明：A=该包，B=md5 `daca6170aa58a8054aa3d20537a61e64`，fresh A/B/A/B，不得用 r13 raw。

没有这份 APK：**不要**为了让心跳安静去重派或开 modeseed。
