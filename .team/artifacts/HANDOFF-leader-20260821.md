# 交接 · 远程Agent安卓 leader · 2026-08-21

> 写给**刚接手、没看过过程**的人。代号首次出现都解释。
> **本次为同日第二次更新（覆盖同一份）**，落笔约 2026-08-21 15:00，`HEAD=ece92d605`。
> **文档写的是落笔那一刻，可能已过期，先按 §0.5 核对**。

---

## §0 compact 后先做什么

### 0.1 一句话现状
**三张账本 32 格全部跑完并 land，新 APK 已交付到桌面，服务端已换成配套的新二进制。
现在什么都没在跑，在等用户反馈这一版的问题。**

### 0.2 🔴 开口第一句（用户 2026-08-21 原话：「接下来会反馈此轮的问题，然后继续修复」）
> 「新包在桌面 `agentmirror-20260821-1324.apk`（md5 `e6cbe8d9e34769c4f3e3c58f2d069af4`），
> 服务端也已换成配套新二进制（pid 见现场，含 `close_session`/`create_session` 两个新帧）。
> **等你反馈这一版的问题**，你说一条我立一格修一条。
> 另外 D2 那两条（终端大块灰、灰块里字不可见）**本版没修，是预期内的**——
> 『字不可见』我已经能写判据了，『大块灰』还缺你导一次诊断日志。」

⚠️ **⛔ 不要泛泛汇报现状**。用户已经说了下一步是他反馈、我们修。
开口就是「等你反馈」+ 把「哪些没修是预期内的」讲清楚，免得他把已知未修当成新缺陷再报一遍。

### 0.3 必读清单（按优先级）
1. 本文件
2. `/Volumes/nvme/Projects/远程Agent安卓/docs/编排方法论.md` —— **10 节，本轮最重要的沉淀**。
   §10 是**开跑前必过的七问清单**；§4 是判据方法论；§9 是「判据只盖了半个仓」的事故。
   ⚠️ **写账本前必须过一遍 §10**，本轮同一个错误犯了三次就是因为只有原则没有清单。
3. `requirement-base/entries/087` ~ `091`（本轮的五份契约）
   - `089` §2.5/§2.6 —— **D2 两条的现场证据与判据方向**，下一步最可能派的格
   - `091` —— 配对页两条，入池未派
4. `.team/artifacts/ledger-trial-findings.md` —— 编排框架优化点清单（**⛔ 只收集不主动发**）
5. `/Users/alauda/.claude/CLAUDE.md` + `/Volumes/nvme/Projects/远程Agent安卓/CLAUDE.md`

### 0.4 恢复动作（协作环境）
**当前无需恢复**：team 活着、coordinator 是新版、生产 daemon 在跑。
若发现 team 死了：`team-agent restart . --team grok-l2`（⛔ `--allow-fresh` 需用户明确同意）。

### 0.5 🔴 恢复工作流程（照做，做完才算接上）

**第 1 步 · 先核对后开口**（文档可能已过期）：
```bash
cd /Volumes/nvme/Projects/远程Agent安卓
git log --oneline -3 && git rev-parse --short HEAD          # 期望 HEAD=ece92d605 或更新
lsof -nP -iTCP:9900 -sTCP:LISTEN                            # 期望有 agentmirrord 在听
ps -Ao comm | grep -c 'ledger-run$'                         # 期望 0（本轮已收口）
ls .team/nodes/_driver/                                     # 期望空
python3 -c "import json;from collections import Counter
for n in ('pr1-v1','pr2-v1','pr3-v1'):
    d=json.load(open('.team/ledgers/%s.json'%n,encoding='utf-8'))
    print(n,d['revision'],dict(Counter(v.get('state') for v in d['tasks'].values())))"
```
期望：`pr1-v1 rev26 {succeeded:12}` / `pr2-v1 rev37 {succeeded:18}` / `pr3-v1 rev4 {succeeded:2}`

**第 2 步 · 先恢复守护**：
⚠️ **当前不需要**——没有驱动器在跑，挂停滞告警只会立刻自触发。
**一旦开了新账本并起了驱动器，同一个动作里必须挂上**：
```bash
STALL_FRESH=900 STALL_NEED=8 STALL_EXCLUDE='^🟢 claude_code ' \
  ~/.claude/skills/ledger-orchestration-trial/scripts/stall-alert.sh --ws /Volumes/nvme/Projects/远程Agent安卓
```
（用**受管后台任务**起，⛔ `nohup &` 起的 shell 脚本活不过本次工具调用）
心跳：29 分钟一跳的 cron 已在会话里（session-only，**换会话就没了，接手后要自己重建**）。

**第 3 步 · 恢复期间的禁令**（做完第 1 步之前一律不许）：
⛔ 不重启 daemon、⛔ 不起驱动器、⛔ 不清席位、⛔ 不改账本、⛔ 不开新格。

**第 4 步 · 判「恢复完毕」**：第 1 步五条全部与期望相符，且已向用户说出 §0.2 那句。

**第 5 步 · 与文档不符怎么办**：
**以现场为准**，并在开口那句里明说哪一条对不上。
⛔ 不许按文档描述去「修正」现场——文档是旧的，现场是真的。

---

## §1 身份与不变量

- **我是 leader，⛔ 不亲手写产品代码**，包括⛔ 不手工解合并冲突（那是最容易悄悄改变语义的地方）。
- **判据红了先问「是不是我的判据错了」**：本轮 `t.recon` 返工 6 轮里 4 轮是我的判据假红；
  四次评审打回里三次的直接原因是我的账本或工具有错。
- **⛔ 判据红了不许改判据让它变绿**。判据本身写错可以改，**但必须明说是语义变更还是措辞修正**。
- **判活只认 `nodeprobe`**，⛔ 不许用 `team-agent` 的 `worker_state`/`last_output_at`；⛔ `unknown` 不能当 `idle`。
- **进度信号用账本 `revision`/mtime**，⛔ 不用驱动器日志 mtime（stdout 会缓冲，实测落后过 30 分钟）。
- **席位卡住先读屏**：`tmux -S /private/tmp/tmux-501/ta-b7cc1c640ccf capture-pane -p -t <pane>`。
  ⛔ 禁 `tmux send-keys`（绝对禁令），⛔ 给席位发消息只走 `team-agent send`。
- **⛔ 席位在途时不打针**（每次 `revision` 前进会把整个前沿重派）。
- ⚠️ **cursor 席位按「restart = 失忆」对待**（框架队 2026-08-21 装机通告3 的运营事实，三路手工定性实锤）：
  cursor vendor 的 `--resume` **不载入历史回合** ⇒ 该席位 restart 后上下文实际丢失。
  ⇒ 若将来用 cursor 做执行席，**重要上下文必须靠任务书与产物落盘**，⛔ 不许指望席位记得。
  **本工程当前未使用 cursor 席位**，属预防性记录（已写进 `CLAUDE.md`）。
- **`cd` 一律绝对路径且同一条命令内自洽** —— 2026-08-21 因此把生产 daemon 停了 10 分钟（见 §3.2）。

---

## §2 排期与封存令

### 已闭环（本轮，全部客观核过）
| 账本 | 格数 | 内容 |
|---|---|---|
| `pr1-v1` rev26 | 12 | D1 主题重着色收口 · D2 仪表 · E6 上传提示 · E17/E18 重连 · E19 几何 · 两份思路文档 |
| `pr2-v1` rev37 | 18 | E1 光标锚定回读 · E2–E5 输入区重做 · E7/E8 · E9/E16 · E10/E11/E15 · E12 · E13 · E14 |
| `pr3-v1` rev4 | 2 | P0：服务端编译回归修复 |

**14 条 PR 全部走完「提 PR → 测 PR → 审 PR → land」，13 份异源评审全 `VERDICT: supports`。**
核法：`git log --oneline --grep='^land pr/'`。

### 下阶段第一项（用户点名）
**接收用户对 `agentmirror-20260821-1324.apk` 的反馈，逐条立格修复。**
为什么是它：用户原话「接下来会反馈此轮的问题，然后继续修复」。

### 无封存令
2026-08-19 起模拟器可用；2026-08-18 起编排出问题「投完报告就继续，不停工」。

---

## §3 P0 / 插队项

### 3.1 P0 · 服务端编译回归（**已闭环，已核**）
- **现象**：两波全绿并 land 之后，全量门在 main 上报
  `server cases 353→248`、`cmd/agentmirrord` 与 `internal/api` package failure、`go vet` 红。
- **根因**：`server/internal/api/lifecycle.go:252` 的**生产代码**调用了
  只定义在 `server/internal/api/tmux_test.go:157` 的 `scrubbedEnv()`
  ⇒ `go build` 失败，`go test` 却能过（测试构建包含 `_test.go`）。
- **为什么三层全漏**：我的每格判据只跑 `./gradlew :app:testDebugUnitTest` + archwiki 棘轮 + Android Lint 棘轮，
  **没有任何一条编译过 Go**；四位评审席审 diff 与说明、**不跑构建**；席位跑 `go test` 是绿的。
  **唯一接住的是全量门的用例数棘轮**（编译失败的包贡献 0 用例），**而它是 land 之后才跑的**。
- **根治**：`pr3-v1` 一格修复 + 一格审 PR，判据**首次同时含 `go build`/`go vet`/`go test`/`gofmt`**。
- **已核**：`cd server && go build ./... → rc=0`、`go vet ./... → rc=0`；
  `scrubbedEnv` 现定义在 `server/internal/api/lifecycle.go:259`（生产文件）；
  全量门复跑 `issues: []`、`server cases 382`（**超过事故前 353**）、真单测失败 0。
- **教训已立**：`docs/编排方法论.md` §9 + §10 七问清单。

### 3.2 🔴 我造成的一次断服（**已恢复，但必须记**）
- **现象**：2026-08-21 13:35 前后，9900 无人监听约 10 分钟，用户手机连不上。
- **原因**：我在一条命令里 `cd /Volumes/nvme/Projects/远程Agent安卓` 之后跑 `./agentmirrord`，
  **而该文件在 `server/` 下** ⇒ 启动失败；我的 `until lsof` 循环又死等到 10 分钟超时才暴露。
- **正确做法**：`cd /Volumes/nvme/Projects/远程Agent安卓/server && nohup ./agentmirrord > <绝对路径日志> 2>&1 &`
- **已恢复并自证**：pid 99365 在听 9900，二进制含 `close_session`×14 / `create_session`×15（旧包均为 0）。

### 3.3 P0 插队对原排期的扰动
**无漂移**：P0 发生在两波全部 land 之后，`pr1/pr2` 已收口，没有在途格被压。

---

## §4 在途未收尾任务

**当前无在途格、无驱动器、无后台流水线**（`ledger-run` 进程数 0，`.team/nodes/_driver/` 为空）。
⇒ **靠什么判断进度：没有活进程可查，一切等用户反馈。**

### 4.1 🔴 下阶段第一个动作（具体到命令与完成判据）
1. **等用户报问题**。他每报一条，先判它是不是**已知未修**（见 §4.2），⛔ 不要把已知的当新缺陷再查一遍。
2. **是新缺陷** ⇒ 写进对应契约（`requirement-base/entries/`），再立格。
   **立格前必须过 `docs/编排方法论.md` §10 的七问**，尤其第 2 条：
   **每条判据先在 main 上跑一次；main 上就红 ⇒ 它是绝对判据，必须改成增量棘轮。**
3. **怎么算做完**：该缺陷的格判据全绿 + 异源评审 `VERDICT: supports` + `land-pr.sh` 并线成功
   + 全量门 `真单测失败 0` 且棘轮无下行 + **用户真机确认**。

### 4.2 已知未修（⛔ 用户再报时不要当新缺陷）
| 编号 | 内容 | 卡在哪 | 下一步 |
|---|---|---|---|
| **D2-a** | 终端里**大块灰**（实测 `#464646` = 该主题 ANSI 索引 8） | **需要用户导一次诊断日志** | 让用户从设置导出，搜 `term-remap`，读出命中哪条分支、输入 rgb 多少，再写修复判据。⛔ 拿到读数前不入账本 |
| **D2-b** | **灰块里的字看不见** | **判据现在就能写** | `TermSurfaceView.kt:823` 调 `TermPalette.colorFor` 时**不传 `againstBg`**，而 `TermPalette.kt:242` 注释白纸黑字写着「绘制层未传入时对照纸色」⇒ 对比度修补永远拿 `defaultBg` 当参照。判据见 `089` §2.6（含两条守卫：默认底路径输出逐一不变 + 热路径仍走表，`colorFor` 已优化到 87.4ns ⛔ 不许倒退） |
| **E20/E21** | 配对页旧 UI；配对页能被侧滑到达 | 入池未派 | 契约 `091` 已写全判据，可直接立格 |
| **E22** | 存量坏味道 33 条（app lint 16 / server gofmt 5 + staticcheck 13） | 入池未派 | ⛔ 不摊派给缺陷格；单独一格清理，⛔ 不许降规则、⛔ 不许 `--freeze` |
| **079** | 触摸点击转发 SGR 鼠标事件 | 用户裁定「之后的目标」 | 可延后 |

### 4.3 与用户反馈无关、可延后
- market 调研（`.team/nodes/market-scan/调研.md`，383 行）是否回写 `requirement-base/` —— **问过用户，未答复，⛔ 不自作主张**。
- P0 报告投递（见 §5）。

---

## §5 运维与外部

### 5.1 现场坐标
- 生产 daemon：`server/agentmirrord`，日志 `.team/logs/agentmirrord-prod.log`
  （⛔ **只 grep 明确要的那一行，不 tail** —— daemon 明文打配对 token）
- team tmux socket：`/private/tmp/tmux-501/ta-b7cc1c640ccf`
- 席位（清理后剩 6 个）：`grok-l2`: `advisor` `dev-app` `pr3-gofix`；
  `remote-agent-android`: `advisor` `dev-app` `pr3-judge-go`
- 工具：`tools/gate/run.sh`（全量门）· `smell-ratchet.py` · `wiki-ratchet.py` · `seal-pr.sh` · `land-pr.sh`
- 镜像：`tools/mirror-push.sh`（远端 `corral-core`/`corral-serve`，**有祖先闸**；
  规则变更需 `MIRROR_REBASELINE=1` 才能整体重写）

### 5.2 资源
- **grok 周额度会耗尽**（本轮撞过一次 `Weekly limit left: 0%`，实现席全停）。开工前看一眼各席底部那行。
- **评审席固定 Opus 5**（⛔ 禁 Fable 5、⛔ 禁 Deepseek，2026-08-21 用户令）。

### 5.3 外部通道（**当前不可达**）
- 框架队：`/Volumes/nvme/Projects/讨论team-agent::team/leader` 与 `::wiki/leader`
- 🔴 **投前必须 `team-agent status --workspace ... --team ...` 验活**：
  **每一行都是「错误」＝死队，投过去照样返回 `ok: True` 但无人读取。**
  **2026-08-21 实测两个 team 均为死队** ⇒ 两份 P0 报告已落盘**未投递**：
  - `.team/artifacts/ledger-p0-派单硬约束禁止commit导致PR链断裂-20260821.md`
  - `.team/artifacts/ledger-p0-驱动器活着但零进展零派单-20260821.md`（含第二次复现与三条排除项）
- **优化点⛔ 只收集不主动发**（2026-08-19 用户令），出口 `.team/artifacts/ledger-trial-findings.md`。
- **coordinator 已三次热替换**，当前 inode **`365334801`**（md5 `b81c70816ff504d44f1d4a041373c84f`，集成线 1f47c099）。
  换装历史：`363660027` → `364985898` → **`365334801`**（三版都叫 0.5.66）。
  **按 inode 自证，⛔ 不按版本号**（两版都叫 0.5.66）。旧目录 `.0.5.66.previous-*` **⛔ 勿清理**（kalloc 纪律）。

### 5.4 交付物
- **APK**：`~/Desktop/agentmirror-20260821-1324.apk`
  39,978,904 bytes，md5 `e6cbe8d9e34769c4f3e3c58f2d069af4`
  **已核 dex**：`ProviderLaunch`/`CloseConfirmDialog`/`InputLineExtract`/`SessionSwitchSheet`/
  `ProviderLaunchStore`/`NewAgentDialog` 六个类全部命中，且**在上一版 `20260820-2227` 里全部不存在**。
- 🔴 **APK 与服务端必须配套**：本版的「关闭」「+新建 Agent」依赖服务端新增的
  `close_session`/`create_session` 帧。**旧 daemon 不认这两个帧，功能必然失败。**
  已于 2026-08-21 13:4x 换新并自证。

---

## §6 安全约束（原文保留，不可弱化）

- 密钥只存在于 `.team/current/profiles/*.env`，**任何席位禁止读其原文**。
- **`.team/current/profiles/tailnet-test.env` 全员禁读（含 leader）**。里面是用户 tailnet 的 auth key，
  只能通过 `TS_AUTHKEY` 环境变量注入测试节点，任何形式的 cat/grep/plist/Read 都禁止。
  取值只用 `set -a; . <file>; set +a` 注入子进程，不打印、不落日志、不入截图。
- **查任何配置前先想凭据**：`grep -i tailscale` 一个"偏好设置"文件就把 authkey 打上了屏。
  同类禁令：无过滤 `ps aux`、`tail .team/logs/agentmirrord-prod.log`。
  **Shadowrocket 的偏好 plist 与 `tailscale_keys.bin` 列入禁读。**
- ⛔⛔ **遍历进程树只能取 `comm`，禁止取 argv**。`ps` 一律只用窄字段 `pid,ppid,etime,stat,comm`。
- **取 daemon 日志只 grep 明确要的那一行，不 tail。**
- **凭据已泄露 ≠ 停工**：只做三件——一行上报（不复述泄露的值）、就地收紧做法、继续干活。
  **截图前先关输入法**（`adb shell input keyevent 111`）。
- **起隔离 tmux 后必须自检"我在自己的 socket 上"**：`mkdir -p /tmp/<短名>` → `unset TMUX` →
  `tmux -S <sock> new-session -d` → `tmux -S <sock> list-sessions`。
  **tmux 建 socket 失败时不报错，会静默回退到默认 socket。**
- ⛔ **绝不触碰用户真实 tmux**（默认 socket），席位只读也不行。
  例外：leader 可跑**只读**的 `nodeprobe` / `list-panes`。
- ⛔⛔ **不要 `git checkout` / `git restore` 任何文件**。⛔⛔ **不要 `git worktree add`**。
- **不写 `Co-Authored-By: Claude`**。**禁止写 memory**；**禁止用 AskUserQuestion 工具问用户**。
- 给席位发消息**只走 `team-agent send`**，⛔ 禁 tmux `send-keys`。
- ⛔ **禁止为框架队取证**（例外：现成材料可给）。**跨 agent 往返一天硬上限 10 个**。
- 🔴 **`ok: True` 不是送达**，投前必须验活。
- 🔴 **对外只收集不主动发**，出口是 `.team/artifacts/ledger-trial-findings.md`。
- ⛔ **不代按 Cursor 的 `Workspace Trust` 提示**。
- ⛔ **席位不许写 `/tmp` 或任何项目外路径**，临时文件写 `.team/nodes/<格>/tmp/`。

---

## §7 用户特别交代（原文，来自本日**第一次** `/handoff` 的参数；**本次 `/handoff` 未带参数，此条仍然有效**）

> **「接下来会反馈此轮的问题，然后继续修复」**

落实：§0.2 开口第一句指向它；§2 列为下阶段第一项；§4.1 给出第一个动作与完成判据；
§4.2 列出**已知未修**清单，防止用户再报时被当成新缺陷重查一遍。
