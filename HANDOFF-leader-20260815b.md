# 交接：远程Agent安卓 leader → 下一代 leader（2026-08-15 夜）

> 上一份是 `HANDOFF-leader-20260815.md`（同日早些时候，内容已被本轮大幅推进，读本份即可）。
> 本份记录的是**从「人肉编排」转向「账本驱动全自动编排」的那一轮**，以及夜里的停工令。

---

## §0 compact 后先做什么

### 一句话现状

**全队处于用户亲口下达的停工令中。** 账本 v1（状态判定第四轮）**已全部完成并客观验过**；
账本 v2（刷新模型）4 条任务完成 1 条（契约条目 059），其余 3 条**未派单，也不许派**——
因为 `team-agent send` 存在**概率性静默失败**（消息注入席位输入框但 Enter 未生效，
`send` 却返回 queued 无报错），在框架队修好前，**任何派单都可能没送到，而外部表现与「席位卡死」完全相同**。

### 开口第一句（对用户说）

> 「昨晚的停工令我执行了：驱动器已停、席位不再派单。**停工前账本 v1 全部闭环，
> 状态判定第四轮已落地并客观验过**——对照席定点变异证明判据真的会红（基线 exit 0 /
> 变异 exit 1 / 恢复 exit 0），顾问席独立复现了哈希与红绿分布，裁定 supports。
> ①直通输入的契约条目 059 也已入库，显式取代 003 第 1 条。
> **现在等 team-agent 框架队修好消息投递 P0 并给版本号**，他说修好后需要重启角色换基础设施。
> 在那之前我不派任何单。要我先做点不依赖派单的事吗（比如真机验收状态判定第四轮）？」

### 必读清单（按优先级，全是绝对路径）

1. **本文件**
2. `/Volumes/nvme/Projects/远程Agent安卓/docs/orchestration/编排方法论.md`
   ——**8 节，全是这一轮踩出来的**。§1 最终形态、§7 wait 只是省电、§8 没跑过就别说。
3. `/Volumes/nvme/Projects/远程Agent安卓/docs/rulings/20260815-用户裁定原文-直通输入与刷新模型.md`
   ——**用户原话逐字转录**，并区分了「用户裁的」与「leader 判的」。立条目/写判据以它为准。
4. `/Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/058-状态检测先归档回退再重建.md`
   `/Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/059-直通输入显式取代一次性注入.md`
5. `/Volumes/nvme/Projects/远程Agent安卓/docs/bugs/` 下 4 份（全部已投递给对应框架方，见 §5）
6. `/Volumes/nvme/Projects/远程Agent安卓/CLAUDE.md` + `/Users/alauda/.claude/CLAUDE.md`

### 恢复动作（协作环境塌了怎么复活）

```bash
cd /Volumes/nvme/Projects/远程Agent安卓
.team/ta status --json            # 看 coordinator 与席位

# 若 coordinator 不活 / tmux session missing：
#   ⚠️ 团队 0 席位时 restart 必失败（报 tmux session created: no，
#      而 coordinator.log 是 0 字节，查日志查不出原因）。根因=没 worker 可拉就建不出 worker session。
.team/ta add-agent librarian --role-file .team/current/agents/librarian.md --workspace .
.team/ta restart .

# 驱动器（停工令解除后才启）——必须用 harness 托管的后台进程，见 §1
python3 tools/ledger-driver.py .team/ledgers/refresh-and-contract-v2.json >> .team/ledgers/driver.stdout 2>&1
```

---

## §1 身份与不变量（这一轮新确立的铁律）

### 角色边界

- **leader 只编排，不做 hands-on**。这一轮我违反过：自己 grep 了六次代码。以后进席位。
- **驱动器是 owner pane 的子进程，不能做成席位**（框架裁定：owner gate 是设计如此，
  worker pane 建席会被 `not_owner` 拒）。`tools/ledger-driver.py`，日志 `.team/ledgers/driver.log`。
- 我只在**五种情况**出场：裁定改路线 / 同一任务连挂 2 轮 / 撞已裁定条目冲突 /
  `ledger-eval` 非 0 改不动 / 框架故障。

### 起长进程的三条（每条都是实发）

| 禁忌 | 后果 |
|---|---|
| `\| tee` | 管道吃掉退出码，**崩溃被报成 exit 0** |
| `&` 或 `nohup &` | 脱离 harness 托管 ⇒ 退出时**没人被唤醒**；而席位回报带 `casefile` 不打屏 ⇒ **全队静默停滞** |
| 只 `ast.parse` 验改动 | 抓不到「语句被注释吞掉」（我把 `LOG = ...` 拼进注释行尾，语法合法、运行即 NameError） |

⇒ 正确做法：**harness 托管的后台 Bash（`run_in_background: true`）+ 重定向**，
改完常量**跑一次真加载打印关键常量**。

### 凭据纪律（违反就会拿假状态做决策）

1. **`send` 返回 `queued`/`ok: True` 不是送达凭据。** 凭据只有两种：**席位转 BUSY，或落盘物出现。**
   （夜里证实还有更坏的：`send` 返回 ok 而消息根本没注入活会话——见 §3。）
2. **`report_result` 不是完成凭据。** 席位报全绿，**自己跑一遍**。
3. **`ledger-eval` exit 0 不是「跑完了」。** 实发：5/5 succeeded + exit 0，
   而 `workflow_state: state_error:terminalproofincomplete`。
4. **判席位活没活，以落盘物为准，不以框架状态字段为准。**
   实发：`reset_proof: weak` + `transcript_missing` 看起来像席位死了，
   我发一个「写一个文件就停」的最小探针，它立刻写出来了——**差点把一个健康席位拆了。**

### 判据自检（这一轮最贵的一课，我犯了三次）

> **写完每条机械判据问一句：「如果被测对象是坏的，这条命令会不会仍然返回 0？」会，就还不是判据。**

三次实发：
- `./gradlew :app:testDebugUnitTest` **从缓存返回 0**（`UP-TO-DATE`）⇒ 必须加 `--rerun-tasks`
- `t.archive` 只验了 `go build`，漏了 `go test` ⇒ 一条「强制旧设计存在」的化石测试留在树里
- `t.impl` 只验 `go test`=0 + 无 `spinnerFrames` ⇒ **一个永远返回 unknown 的空壳也能通过**

**规律：我倾向于验「没变坏」，而不是验「真的做成了」。** 兜底是对照席的定点变异，别省掉它。

### 顾问架构（我原来的理解是错的）

| 席别 | 上下文 | 为什么 |
|---|---|---|
| **顾问席** | **要有** | 价值就在于读过东西；零上下文顾问没有可兜的底 |
| **工作席** | 读顾问产出的摘要文档 | 省加载 |
| **对照席（至少一个）** | **零上下文，不读摘要** | 读过标准的席位会从记忆里把标准没写的补上，**标准的缺口就永远不响** |

`compatible_api` 只能 `clone` 不能 `fork` ⇒ **顾问的知识必须落成文档**。
**上下文是缓存，文本才是可继承的介质。**

---

## §2 排期与封存令

### 用户 2026-08-15 的优先级（原话）

> 「**你的优先级最高的，那就是完成我上面提到那些修改点。全自动编排的优先级是他们的。**」
> 「**绝对不要人肉编排。**」「无人值守驱动任务完成，全自动编排流畅运行为最高优先级。」

四个修改点：①直通输入 ②键条顺序 ③状态判定 ④刷新模型。

### 🔴 停工令（用户亲口，经 team-agent 框架 leader 转达，2026-08-15 夜）

> **今晚两队都停下来，等我方把消息投递 P0 修好；没有用户的新安排不要再开工。**
> **停的范围**：全部。手上任务做完当前这一步就停，不要开新的，不要再派单给席位。
> 修好后通知版本号，届时**需要重启角色以更换基础设施**。
> **在那之前不要自己改注入相关代码，避免两边分叉。**

**已执行**：驱动器已 kill（`pgrep -f ledger-driver.py` 无输出），未再派任何单。

### 另一条仍然生效的封存令

> 编排基础设施**归账本编排框架统一做一次，不该每个 team 各造一遍**。
> ⇒ 本工程**不自造 runner / 事件面**，只用已发布 CLI + 上游 reference 驱动器。

### ⛔ 仍然未解除：不许启动安卓模拟器

用户 2026-08-14 指令，**未通知解除**。第 2 层测试不可用，视觉验收唯一权威是**用户真机截图**。

---

## §3 P0 / 插队项

### P0-1：`team-agent send` 概率性静默失败（框架队在修，**当前停工原因**）

- **现象**：消息注入席位 composer 但 **Enter 未生效**，文本停在输入框，`send` 返回 queued 无任何报错。
  已知四个现场，跨三个 workspace。
- **机制（框架队已定位）**：席位 busy 流式输出 → `pre_submit_token_visible` 轮询 375ms 内 paste 未稳定
  → 兜底盲发 Enter → Enter 未生效 → 消息滞留 → 结果被 `delivery.rs:920` **映射成成功**。
  红测已红（`p0_enter_sent_without_placeholder_check_is_not_delivered`）。
- **临时止血（框架队给的，必须发一条时才用）**：**双发**——正文一条 + 一个 `.` 一条。
  后一条的回车把积压的一起提交，代价是两条粘成一条，忽略末尾的 `.`。
  （我收到停工令那封信本身就是这么发来的：第二条消息只有一个 `.`。）
- **对原排期的扰动**：账本 v2 的 `t.refresh-oracle` / `t.refresh-impl` / `t.refresh-verify`
  **全部未派、且不许派**。见 §4。
- **本工程可能受影响的历史结论**：**没有。** v1 的每一条都建立在
  **落盘物 + 我或对照席实跑的退出码**上，不建立在投递成功上——这正是「判据是唯一凭据」的价值。

### P0-2：advisor 席位一度连收 3 次派单零动作（已定性，非本工程可修）

- `inbox` 显示 `status=submitted_unverified` / `error=transcript_missing`，而那个 rollout jsonl
  **文件是存在的**（1 MB，mtime 就在报错前后）。
- `start-agent` → `Noop`；`reset-agent --discard-session` → 新 session id 生成但
  `capture_state: transcript_missing`、`reset_proof: weak` 依旧。
- **最小落盘探针证明席位是活的**（写 `/tmp/advisor-alive.txt` 成功）。
- 已投报告，见 §5。**与 P0-1 很可能是同一根因的两个面。**

---

## §4 在途未收尾任务（逐条可执行）

### 账本 v1 `ledger.state-detection.v1` — **全部完成，但有一个引擎级尾巴**

路径 `.team/ledgers/state-detection-v1.json`。5/5 `succeeded`，`ledger-eval` exit 0。

| 任务 | 干了什么 | 验证方式（**全部客观核过，非自报**） |
|---|---|---|
| `t.keybar` | 键条改 `Esc / Tab / ↑↓←→ / Ctrl-C` + `KeyBarOrderTest` | **leader 亲自定点变异**：基线 exit 0 / 把 Ctrl-C 挪回第二位 exit 1（`KeyBarOrderTest.kt:75` FAIL） |
| `t.archive` | `server/internal/agentstate/` 1699 行整体归档到 `docs/archive/agentstate-round4/`；删 `StateDone`；归档 3 个「断言静态字形→状态」的化石测试 | **leader 重跑 5 条判据全 0**（含 `go test ./...`、`! grep spinnerFrames`） |
| `t.oracle` | 顾问产出 `.team/nodes/state-oracle/判据基底摘要.md` + `probe-red.log` + `run-probe.sh` | leader 实跑 2 条判据 exit 0 |
| `t.impl` | 重建为**导数判据**（变=working / 不变=idle），零字形白名单 | 驱动器跑 2 条判据 exit 0；**更强的证据在 t.verify** |
| `t.verify` | 对照席零上下文定点变异 | **基线 exit 0 / 变异（`track.go` 导数分支永远返回 idle）exit 1，R1/R2 红而 G1/G2 保持绿 / 恢复 exit 0，SHA-256 回基线** |

**顾问裁定**：`supports`（`.team/nodes/state-oracle/verdict-A-ctl-verdict.md`）。
顾问**自己重做了一遍变异**，哈希与红绿分布独立复现，并诚实标了一处留白
（只做单点变异、未做对称变异），没有把它夸大成否决。

**⚠️ 引擎级尾巴（不影响代码正确性，别被它吓到）**：
`workflow_state: state_error:terminalproofincomplete`，因为 `required` 里的 judgment 判据
`A-ctl-verdict` 在账本里**无处记录**。我实验证实 **schema 根本没有这个字段**
（`tasks.*.evidence` / `audit.*` 都被拒，`desired_state` 只有 running/stopped）。
⇒ **只要 required 含 judgment 判据，这个状态就不可达。** 已投报告给账本编排 leader。
**本工程据此定的纪律：上游修好前，账本里不放 judgment 判据，全部用 mechanical；
人裁验收走账本之外，由 leader 直接对用户。**

**未做**：状态判定第四轮**没有真机验收**。模拟器停用，第 3 层要用户看。**这是最该先补的一件事。**

### 账本 v2 `ledger.refresh-and-contract.v2` — 4 条完成 1 条，**其余因停工令冻结**

路径 `.team/ledgers/refresh-and-contract-v2.json`，`ledger-eval` exit 0，`workflow_state: active`。

| 任务 | 负责人（席位全名） | 状态 | 卡在哪 / 下一步 |
|---|---|---|---|
| `t.contract` | `advisor` | ✅ **succeeded** | 已完成：`requirement-base/entries/059-直通输入显式取代一次性注入.md` + INDEX 行。leader 重跑 3 条判据全 0，已提交 `d20a51e34` |
| `t.refresh-oracle` | `advisor` | ⏸ planned | **未派单，停工令冻结。** 解除后由驱动器自动派 |
| `t.refresh-impl` | `dev-keybar` | ⏸ planned | 依赖 `t.refresh-oracle` |
| `t.refresh-verify` | `control` | ⏸ planned | 依赖 `t.refresh-impl` |

**顺序约束**：`t.refresh-oracle → t.refresh-impl → t.refresh-verify` 串行（`requires_success`）。
`t.refresh-impl` 写 `app/`，与将来的 ①直通输入实现**同 Gradle 模块**，
按 CLAUDE.md「同一模块同一时刻只放一席施工」必须串行——用同一个 `worktree_id: wt-app` 让引擎强制。

**怎么恢复（停工令解除后）**：

```bash
cd /Volumes/nvme/Projects/远程Agent安卓
ledger-eval .team/ledgers/refresh-and-contract-v2.json    # 先看前沿，必须 exit 0
# 然后用 harness 托管的后台进程起驱动器（禁 tee、禁 &）：
python3 tools/ledger-driver.py .team/ledgers/refresh-and-contract-v2.json >> .team/ledgers/driver.stdout 2>&1
```

**合法阻塞 vs 卡死怎么分**：驱动器在 `wait` 上最长各等 `WAIT_EACH=1800s`（两个候选 id），
**等不到也不当失败**，会落到机械判据去判——所以「长时间没动静」是合法的，
**真卡死的信号是驱动器进程退出**（harness 会唤醒你）。

### 还没开工的：①直通输入的实现（v3）

契约已就位（059）。**这是四个修改点里最大的一个**，且判据大量落在手感上。
建议拆法（未成账本）：
- 键入直达（每键一帧）／删除键映射 `\x7f`
- **中文输入法边界**：组合期归输入法本地、上屏那一刻直通整串
  ⚠️ **这是 leader 的判断，不是用户裁定**（`docs/rulings/…` 文末已标注），施工时若有更好做法应上报
- 本地回显：**leader 倾向不做**（App 是 pane 的镜子，多一份本地状态就多一类不一致缺陷）——同上，未经用户裁定
- 手感判据只能上升到用户真机

---

## §5 运维与外部

### 进程 / 席位现状（写交接时核实）

```
驱动器      已停（pgrep -f ledger-driver.py 无输出）
席位        advisor / control / dev-keybar / dev-state / librarian —— 5 个，不再派单
coordinator 活
HEAD        d20a51e34   工作区仅剩 .team/ledgers/driver.log|.stdout 与 docs/wiki/t3-report.md 三个无关改动
```

### 跨团队直报通道（**归属别搞错**）

| 症状 | 投递给 |
|---|---|
| 席位起不来 / 投递失败 / clone-fork / 状态异常 | `/Users/alauda/Documents/code/agent前沿探索/多agent协作::refactor-maintainability/leader` |
| 账本写不出来 / 报错没说清 / 规范与实现对不上 / 席位供给失败 | `/Volumes/nvme/Projects/讨论team-agent::team/leader` |

**投递纪律（用户 2026-08-15）**：先写 bug 分析报告（现象 / 日志支撑 / 原因分析）落 `docs/bugs/`，
**消息里只给文档路径，不长篇大论**。
**往返硬上限：同一对方一天 ≤10 个往返。** 发之前问：这封信有没有一个信外的对象？没有就不发。

### 已投递的 4 份报告（对方都在等/已处理）

| 报告 | 归属 | 对方状态 |
|---|---|---|
| `docs/bugs/20260815-无结果事件面导致leader被迫人肉编排.md` | team-agent | **前提被推翻，我已在文内标注作废**（DS-01 `wait --task` 0.5.65 就有；我提的 `results.jsonl` 是伪需求） |
| `docs/bugs/20260815-reference-driver-wait键错误.md` | ledger-orchestration | **已修，reference 已同步** |
| `docs/bugs/20260815-驱动器漏判judgment判据.md` | ledger-orchestration | **已修（`check_required`）**；schema 无处记裁定那条**仍未解** |
| `docs/bugs/20260815-transcript_missing-席位活着但被判不可验证.md` | team-agent | 已投，未回；很可能与 P0-1 同根因 |

### 对方给的一条通则（他写进了 skill，值得我们照用）

> 「为异常留后路但不留信号」——判据是一句话：**出了异常，谁会知道？**
> 四个实例并列：judgment 静默跳过 / `task_id=manual` 静默作废 / `state_error` 仍 exit 0 /
> **`submitted_unverified` 长得像成功**。

---

## §6 安全约束（原文保留，不可弱化）

- 密钥只存在于 `.team/current/profiles/*.env`，任何席位禁止读其原文。
- **`.team/current/profiles/tailnet-test.env` 全员禁读**（含 leader）。里面是用户 tailnet 的
  auth key，只能通过 `TS_AUTHKEY` 环境变量注入测试节点，任何形式的 cat/grep/plist/Read 都禁止。
  取值只用 `set -a; . <file>; set +a` 注入子进程，不打印、不落日志、不入截图。
- **查任何配置前先想凭据**：`grep -i tailscale` 一个"偏好设置"文件就把 authkey 打上了屏
  （2026-08-13 实发，已请用户轮换）。同类禁令：无过滤 `ps aux`（暴露席位 API key）、
  `tail .team/logs/agentmirrord-prod.log`（daemon 明文打配对 token）。
  **Shadowrocket 的偏好 plist 与 `tailscale_keys.bin` 列入禁读。**
- **凭据已泄露 ≠ 停工**（2026-08-13 用户裁定，2026-08-14 重申并批评过一次违反）：
  用户对 `tailnet-test.env` 的长期决定是「既然泄露了，就写进文件，接下来就用它去测」。
  再次泄露时**只做三件事：一行上报（不复述泄露的值）、就地收紧做法、继续干活**。
  **禁止**因此停工、禁止等新 key、禁止把删本地产物当成风险处置——
  片段一旦进入上下文就擦不掉，删截图减少的是执行者的不适而非真实风险。
  轮换与否是用户的事，不是开工前置条件。
- **起隔离 tmux 后必须自检"我在自己的 socket 上"**：`tmux` 在建 socket 失败时**不报错，
  静默回退到默认 socket** —— 也就是用户的真实 tmux。已实证两条回退路径：
  ① `TMUX_TMPDIR` 路径过长（unix socket 上限 ~104 字节）；② `TMUX_TMPDIR` 目录**未预先存在**。
  **唯一可靠的不变量是自检**：
  ```
  mkdir -p /tmp/e2e-<席位名>          # 短路径，且预建
  unset TMUX
  tmux -S <sock> new-session -d ...
  tmux -S <sock> list-sessions         # ← 自检：会话必须在自己的 socket 上，否则立刻停手
  ```
- 给席位发消息只走 `.team/ta send`（净化包装器），**禁 tmux `send-keys`**。
  不走包装器派出的席位第一个请求就发不出去、全生命周期零 token，而屏幕上显示 Working 是假活
  （已实证白跑 60 分钟）。
- ⛔ **绝不触碰生产 daemon（pid 4140）与用户真实 tmux**，席位只读也不行。
  （leader 重启 daemon 已获常驻授权：先备份现二进制、换、起完核 `9900` 在听，无需确认。）
- ⛔ **不许启动安卓模拟器 / emulator / qemu**，用户 2026-08-14 指令，**未解除**。
- **不写 `Co-Authored-By: Claude`**（用户 2026-08-14 裁定「Contributor 应该是我」）。
  历史上已有的带该署名的 commit 不动本地，由 `tools/mirror-push.sh` 在推远端时摘掉。
- **取 daemon 日志只 grep 明确要的那一行，不 tail。**

---

## §7 这一轮最值得带走的三句

1. **「没跑过就别说。」** 一天之内两个方向各撞一次：我把「`--help` 没列 `--task`」当成
   「参数不存在」并发给对方，害他撤回了一个本来正确的修复（它在 `cli/emit.rs:864` 一直都在）；
   他把「`send` 报错」当成「`send` 没有 `--task`」，实际是他自己的 `$REPO` 变量是空的。
   **拿一个真实的失败信号，去回答它没在回答的那个问题。**
2. **「判据是唯一凭据，wait 只是省电。」** 编排的可靠性不来自任何单点正确，来自
   **三条互不依赖的兜底同时在场**：wait 猜错、席位不守约、判据漏查，任意一条单独都不致命。
3. **「停不是缺陷，是把决定权交回给 leader。」** 驱动器的设计就是判据不过就停、
   wait 非零就停、前沿为空就返回 0。**但「停了没人知道」等于没停**——所以它必须跑在
   harness 托管的后台进程里。
