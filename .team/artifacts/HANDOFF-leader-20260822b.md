# HANDOFF · leader · 2026-08-22（第二份，覆盖当日 15:xx 之后）

> 用户特别交代（原话，优先级高于我自己的判断）：**「把你现在手头的任务在 compact 之后做好。」**
> ⇒ 后继醒来的第一件事**不是**汇报历史，是把在跑的账本 `ledger.coreapp.v1` 推到收口。

---

## §0 compact 后先做什么

**一句话现状**：账本 `ledger.coreapp.v1` 正在跑（driver pid **24810**），7 格里 `t.path`/`t.pub`
已绿并经我复跑判据核过，`t.capp` 刚派给 pb-impl。
**最大风险不是技术，是额度**：pb-impl 的 grok **周额度只剩 3%**（15 分钟前还是 9%），
后面还有 `t.capp`（施工）与 `t.perf`（三夹具各 ≥10 次冷点开，最耗额度）没跑，**很可能跑不完就断额**。

**开口第一句**（对用户说）：
> 「coreapp-v1 跑到 t.capp，t.path/t.pub 已绿（我复跑判据核过）。但 pb-impl 的 grok 周额度只剩 3%，
> t.capp+t.perf 大概率撑不到收口。三个选项：①硬跑撞了再说 ②换 cursor 席位（restart=失忆，
> 但本链任务书够全、判据是机械的）③只跑到 t.pub 就停、把 t.capp/t.perf 留到额度恢复。
> 我倾向 ② 或 ③，等你定。」

**必读清单**（按优先级）：
1. 本文件
2. `/Volumes/nvme/Projects/远程Agent安卓/.team/tasks/coreapp/任务书.md`（本链六格的全部细则）
3. `/Volumes/nvme/Projects/远程Agent安卓/docs/基线-20260822-release.md`（当前稳定基线的单一事实来源）
4. `/Volumes/nvme/Projects/远程Agent安卓/CLAUDE.md`（置顶节「🔴 当前稳定基线」+ 席位与模型 + 安全红线）
5. `/Volumes/nvme/Projects/远程Agent安卓/.team/artifacts/ledger-trial-findings.md` 末节
   （2026-08-22 的 27 条编排优化点；**A6/A7/A8 是会咬人的坑**）
6. `/Users/alauda/.claude/skills/ledger-orchestration-trial/phases/03-返修.md`
   （两条固定处置：回环第二轮不自转、`frozen_no_new_case` 换钥匙）

### 恢复工作流程（照做，做完才算接上）

1. **先核对，后开口**（本文档写的是落笔那一刻，可能已过期）：
   ```
   cd /Volumes/nvme/Projects/远程Agent安卓
   pgrep -f "ledger-run --drive .team/ledgers/coreapp-v1.json"   # 期望 24810
   pgrep -f autopr.py                                            # 期望 38969
   pgrep -f stall-alert                                          # 期望 47198
   tail -6 .team/ledgers/coreapp-drive1.log
   /usr/bin/python3 -c "import json;d=json.load(open('.team/ledgers/coreapp-v1.json'));print(d['revision']);[print(k,v.get('state')) for k,v in sorted(d['tasks'].items())]"
   git log --oneline -1                                          # 期望 1f18f48f8 或更新
   ```
2. **先恢复守护，后推进**：心跳 cron 是**会话级的，compact/重启后不会自己跟过来**。
   接手第一件事就是重建它，周期 30 分钟，正文照抄本文档 §4 的「心跳应做什么」。
   若 driver/autopr/stall-alert 任一不在，按 §4 的重起命令补起来（**先看日志末尾的收工原因**，
   ⛔ 不要一上来就重启）。
3. **恢复期间禁令**（做完第 1、2 步之前一律不许）：⛔ 不重启驱动器、⛔ 不重派任何格、
   ⛔ 不移除/重建席位、⛔ 不改判据、⛔ 不开新账本、⛔ 不推远端。
4. **判「恢复完毕」**：三个进程都确认在跑（或已按收工原因正确处置并重起）、心跳 cron 已建、
   账本 revision 与各格状态已读过一遍。满足即可推进。
5. **现场与文档不符**：**以现场为准**，并在给用户的第一句话里说明差异。
   ⛔ 不要按文档去"纠正"现场。

---

## §1 身份与不变量（铁律，⛔ 不可弱化）

- 我是本工程 **leader**。⛔ **不亲写产品码**（含解冲突）；判据、裁定、并线（seal/land/推 PR）才是我的活。
- **探索性的活派席位**，不清楚就**追问**（用户 2026-08-22 令）。leader 的上下文留给判断，不留给素材。
- **席位不许主动给 leader 发消息**：唯一出口是 `report_result` + 落盘产物；只有**编排调整**才允许发一条。
- **自报不算完成**：席位说绿，我要么自己复跑判据写下退出码，要么标「待核」。本文档里凡写「已核」
  都是我亲手跑过的。
- **一事一 PR 一闭环**：每格 seal 之后、评审派单之前先推分支开远端 PR；land 后立刻推 main 让它显示
  **merged**。远端 PR 列表就是流程存在的证明。
- **判据四态**：通过(0)/不通过(1)/**不可判(2)**/不适用。⛔ 不可判与不适用不许折进通过或失败。
  **编译不过 ≠ 测试红**，一律不可判。
- **模型**：⛔⛔ 所有席位一律 `provider: grok` / `model: grok-4.6`；⛔ 不许再开 Opus 席位
  （用户 2026-08-22 令）。⛔⛔ 禁 Deepseek、禁 Fable 5。
  ⚠️ 只改角色文件不生效，必须 `remove-agent --force --confirm` + `add-agent` 重建并**读 pane 自证模型名**。
- ⛔ **重建/移除席位前先看驱动器在等谁** —— `remove-agent` 会连同**在飞的派单一起销毁**，
  驱动器会一直等到预算耗尽，**账本层零症状**（2026-08-22 实撞，见 §3-B）。
- **`ok: True` 不是送达**：投递回执成功 ≠ 席位收到/还活着。判活要读屏或看产物 mtime。
- **判活别只看提示符**：新席位的空提示符和干完活的空提示符长得一样。要看 pane 里**有没有派单正文**。

---

## §2 排期与封存令

**已闭环**（当日凌晨那条链 `ledger.perfbase.v1`，rev32，`desired_state=stopped`）：
- 任务一 打开链路 Debug 日志仪表 ✅
- 任务二 模拟器性能基线 ✅
- 任务三 corral-core 仓内三核切分 + 本地引用式构建暂存工程 ✅
- **额外收获**：白屏根因定位并修复（详见 §3-A），真机金标准已过

**当前稳定基线（封存令性质，⛔ 不许回退）**
> 用户原话：「现在这个 release 版本是新的基线，是新的稳定版本。**性能体验是最核心的体验，
> 这个不能回退。**」

- tag `baseline-20260822-release`（本地 / corral-core / corral-serve **三处同名**）
- corral-core 还有 GitHub Release 页，正文含完整地板数字
- APK md5 `0907d6881bb1e034ef33a49f89afaa44`，桌面副本
  `~/Desktop/agentmirror-20260822-perfbase-RELEASE.apk`（**别删**）
- 判据来源**不是模拟器**：用户在**蜂窝网络 + 广州中转打洞节点**（非直连、最苛刻路径）实测
  「秒开、没有空白」
- 单一事实来源：`docs/基线-20260822-release.md`

**冻结项**：`docs/优化点清单-1820.md` 的 18 个优化点**仍冻结**，
金标准门没过不许开；重启时「一次一条 + 用户真机不倒退」。

---

## §3 P0 / 重要既成事实

### A. 白屏根因（已闭环，写这里是因为后继会被问到）

`ConnectionManager` 只有**一个全局 listener 槽**，被列表页 `WorkspaceViewModel` 占着；
订阅首帧交给它、**就地丢弃**，会话页 VM 一帧拿不到。**18/18 零反例**。
铁证行：`ev=first_frame_recv emitted=0 reason=has_listener listener_null=0
listener_ref=dev.agentmirror.app.workspace.WorkspaceViewModel`
修复：改为**按 ref 分发**（`addBinaryListener`）。已 land 进 main、已进基线包、用户真机已验。

### B. 我当日犯的错（⛔ 后继别重犯，全部已记进 findings 与 skill）

1. **在一格在飞时拆了席位**（换模型）⇒ 派单随席位销毁，驱动器等到预算耗尽，**日志零症状**。
2. **账本形状错三条**：两棵树改同一批文件（land 时 add/add 冲突）、下游分支基于上游分支而非等它
   land、某格的树建在上游 land 之前（基点错、包里没仪表、取数 0 行）。
   ⇒ **`coreapp-v1` 已逐条改进：所有施工格共用 `wt-ca` 一棵树**。
3. **判据自己的坑**：全角括号紧跟 `$VAR` ⇒ bash 3.2 报 unbound variable ⇒ 退出 1
   ⇒ **判据坏了却被记成「判据不通过」**。13 处已全改 `${VAR}`。
   **规矩：判据里 `$VAR` 后接非 ASCII 一律写 `${VAR}`。**
4. **一夜没积攒优化点**（违反铁律⑧），事后补了 27 条。**后继每轮撞到坑就当场记**，别攒到最后。

### C. 环境侧的两条（用户当日实撞，⛔ 别再当成代码缺陷去查）

1. **「一直 TS 入网中最后失败」= tsnet 身份分裂**（**真缺陷，未修**）：
   `TsnetWire.stateDirForKey` 按 auth key 的 SHA-256 分状态目录，而 `sanitizeHostname` 恒为
   `agentmirror-<机型>` ⇒ 换一把 auth key 就在 tailnet 里注册出**同名新设备**，
   旧节点占位把 `tsnet.Up` 拖到 60s deadline。**绕过：到 tailnet 删掉旧的同名设备**（用户已实测有效）。
   修法待用户裁：①设备级单一 state dir ②或把 key 指纹并进 hostname。
   附带缺口：`GomobileTsnetBackend.start` **全程零日志**，要修 tsnet 前先补这段留痕。
2. **「重连很慢」= 手机 VPN 全局转发**，tailnet 流量被套一层；改成分应用转发并排除本 app 即恢复。
   当时被怀疑成服务端回退，**而服务端二进制一夜没动过**。
   ⇒ **规矩：先问网络路径，再疑代码；说「回退服务端」之前先确认它现在到底是哪个版本。**

---

## §4 在途任务（唯一一条，就是用户点名要做好的那件）

### 任务：账本 `ledger.coreapp.v1` —— core 变成被引用的发布产物，app 壳搬进 corral-app

**为什么做这件事**（用户原话）：
> 「我希望以后关于性能这方面的东西能够稳定地在 core，如无必要不要改这个代码仓库。
> 另外其他的改动可以和它隔离开，APP 可以稳定地在 APP 那个仓库去改，并且禁止改 core 相关的代码。」
> 「如果说 APP 那边也影响性能，那就从 APP 去入手解决。那核心至少可以保留绝大部分的变量。」

**已与用户对齐的关键判断**（⛔ 别推翻）：
- core **不是**「性能的保险箱」，是**把一大批变量钉死的地方**。八个性能打点里**六个在壳**、
  两个沾核 ⇒ 边界**不按仓划、按代码位置划**。
- 「禁止从 app 侧改核」靠**依赖形态**强制，不靠纪律：**app 仓里没有核源码 ⇒ 物理上改不了**。
- core 发布渠道 = **corral-core 仓的一个 `maven` 分支**，app 通过 raw URL 消费。
  ⚠️ 用户曾说成「本地建一个 Maven 客户端」，我已更正：**不是本地客户端，是远端 maven 分支**；
  本机 `mavenLocal` 只在联调时用。

**流水线信息**
| 项 | 值 |
|---|---|
| 驱动器 pid | **24810**（`ledger-run --drive .team/ledgers/coreapp-v1.json`） |
| 驱动器日志 | `.team/ledgers/coreapp-drive1.log`（硬链 `.team/nodes/_driver/coreapp-v1.out`） |
| pid 文件 | `.team/nodes/_driver/coreapp-v1.pid` |
| 收口机器人 | `tools/autopr.py` pid **38969**，日志 `.team/ledgers/autopr.log`，每 60s 一轮 |
| 停滞告警 | pid **47198**，间隔 180s × 连续 25 次判停 |
| 账本 revision | 3（落笔时） |
| 施工树 | **所有施工格共用 `.worktrees/wt-ca`**（判者/收账/升报各有自己的树） |

**各格进度（落笔时）**
| 格 | 状态 | 核实情况 |
|---|---|---|
| `t.path` 钉住性能关键路径 | **succeeded** | **已核**：我在 wt-ca 复跑 `judge-perfpath.sh` → `PASS`；产物 `docs/性能关键路径.md` 2903 bytes |
| `t.pub` 三核发布 maven 产物 | **succeeded** | **已核**：复跑 `judge-pub.sh` → `PASS`；`.team/staging/maven-repo/dev/agentmirror/core/{core-protocol,core-terminal,core-conn}/20260822.0/` 三份 pom + 6 个 jar/aar |
| `t.capp` corral-app 只引用产物 | **在飞**（06:17 派给 pb-impl） | 未产出 |
| `t.perf` 引用式 APK 复测性能门 | planned | — |
| `t.rv` 异源终审（pb-rv1） | planned | — |
| `t.close` 收账（pb-rv2） | planned | — |
| `t.esc` 升报（pb-rv2） | planned，只经转移边可达 | — |

**合法阻塞 vs 卡死**：`t.perf` 一轮是三夹具各 ≥10 次冷点开，**半小时以上不长日志是正常的**，
判活看 `.worktrees/wt-ca/.team/perf/` 下产物的 mtime，⛔ 别看驱动器日志有没有增长就判卡死。

**下阶段第一个动作**（具体到命令，⛔ 不许写"继续推进"）：
1. 跑 §0 恢复流程第 1 步的六条命令；
2. 读 pb-impl 的 pane 看额度还剩多少：
   ```
   S=/private/tmp/tmux-501/ta-b7cc1c640ccf
   P=$(tmux -S $S list-panes -a -F "#{window_name} #{pane_id}"|awk '$1=="pb-impl"{print $2}')
   tmux -S $S capture-pane -p -t $P | grep -oE "Weekly limit left: [0-9]+%"
   ```
3. 按额度决定，并把 §0 那三个选项摆给用户。
**怎么算做完**：`t.close` 绿 + 收账文书落盘 + 我给用户写完一页 + cron 删掉。

**🔴 额度风险（本任务当前最大变量）**
- pb-impl 的 grok **周额度 15:xx 时只剩 3%**（十几分钟前还是 9%，下降很快）。
- 撞额度的表现**很难看**：席位半路死掉，**账本层零症状**（投递回执早已 ok），
  驱动器一直等到 `seat_wait_seconds`（本链 5400s）耗尽。**只有读屏能发现。**
- 三个选项（用户未定，**⛔ 不许自作主张换模型或降标准**）：
  ① 硬跑，撞了按老规矩读屏+换钥匙重派；
  ② 换 cursor 席位（有 `cursor-teammate` skill）。⚠️ **cursor restart = 失忆**，
     重要上下文只能靠任务书与落盘传承——本链任务书够全、判据是机械的，可行；
  ③ 只跑到 `t.pub` 就停（已经绿了），`t.capp`/`t.perf` 留到额度恢复。
- 我的倾向：**② 或 ③**。

**心跳应做什么**（30 分钟一次，正文要点）
1. 看 driver/autopr/stall-alert 三个 pid + 驱动器日志尾 + autopr 日志尾；
2. 驱动器不在 = **收工退出，不是崩了**，看「收工 finish 停机原因」对号入座：
   - **AwaitingHuman + 判者刚判 rework** ⇒ 引擎不复位 fix 格（框架已知欠账 P13）。处置：
     备份账本 → fix 格与判者格 `state=planned`、清 `status_record`/`blocking_reasons`
     → **把裁定书里的 rework 逐条理由并进 fix 格派单正文**（依赖边重派**不带**上游 case）
     → `revision+1` → 受管后台重起驱动器 → 写 pid 文件 + 硬链日志；
   - **`frozen_no_new_case`** ⇒ 判据红后没换钥匙。处置：清该格 `attempts[]` 里
     `failed`/`failed_retryable` 的条目 + `state=planned` + `revision+1`；
   - ⛔⛔ 两种都**不许清 `rounds` 与 `audit.route_hops`**（轮次上限靠它们，清了回环变无限返修）；
3. 判据红 ⇒ **先疑判据假红**（自己在**该格的 worktree 里**手跑一遍 ——
   ⚠️ **判据脚本住在各格 worktree 里，改 main 上的判据对已建好的树无效**）→ 再疑产品 → 最后才疑框架；
   ⛔ 不许改判据放行；
4. autopr 出现「并线 红(park…)」= 冲突 ⇒ 记 `.team/escalations/`，
   ⛔ 不自动解也**不手解产品码冲突**（铁律⑩），其余格继续；
5. 同一缺陷已投过就不再重复投递，**除非形状变准或带得出对照组**（那算补完案卷，该投）。

**收口时必须做的三件（席位做不了）**
1. 把 `.team/staging/maven-repo/` 推成 **corral-core 的 `maven` 分支**（席位禁 push）；
2. 把 `.team/staging/corral-app/` 推到 **https://github.com/Florious95/corral-app**（同上）；
3. 提醒用户**真机复验**引用式构建的包「秒开无空白」——**模拟器绿不能替代金标准**。
   ⚠️ 本仓 `app/app` 壳**还没删**；删壳是用户真机复验通过之后的下一步，**不在本链范围**。

---

## §5 运维与外部

- **生产 daemon**：9900，pid **16330**，二进制 `./agentmirrord-night-4120c0884`（= 基线构建）。
  **当日一夜未动**。重启无需用户确认（2026-08-14 裁定），但**席位禁碰**。
- **远端仓**：`corral-core`（app/文档面）与 `corral-serve`（server 面）都是本 monorepo 的
  **过滤镜像**。推送**只用** `bash tools/mirror-pr.sh [分支...]` / `bash tools/mirror-pr-serve.sh [分支...]`；
  ⛔ **不要用 `mirror-push.sh` 推 main**（strip 实现不同源，会撞祖先闸重写远端历史）。
  两仓现在都**只剩 main + tag `baseline-20260822-release`**，OPEN PR 为 0（当日清干净的）。
- **对外直报通道**：ledger-orchestration / ledger-run 相关**一律投**
  `/Volumes/nvme/Projects/讨论team-agent::wiki/leader`。
  ⚠️ `讨论team-agent::team` 是**死队**（全行「错误」），双方已证实，**不必再验第二个地址**。
  当日与 wiki 已 **4 个往返**（日上限 10）。
- **待投未投**：`ledger-trial-findings.md` 末节 A4/A5/A6/A7/A8/A9/A10/A11/A12 共 9 条只积攒未投
  （按铁律「优化类只积攒」）。**A6「重建席位会连带销毁在飞派单且零告警」价值最高。**
  用户曾问过要不要投——**等下一条链跑完一次性成篇给他们**，或用户点头即投。
- **资源**：宿主 load 当日在 5–17 之间波动。⚠️ **同一构建在 load 9.16 与 14.15 两批之间实测差过 3 倍**，
  所以**性能复测必须带 load 读数**，换负载区间的数不可直接比较。
- **额度**：见 §4「额度风险」。这是当前最大变量。

---

## §6 安全约束（原文保留，⛔ 不可弱化）

- 密钥只存在于 `.team/current/profiles/*.env`，**任何席位禁止读其原文**。
- **`.team/current/profiles/tailnet-test.env` 全员禁读（含 leader）**。里面是用户 tailnet 的
  auth key，只能通过 `TS_AUTHKEY` 环境变量注入测试节点，**任何形式的 cat/grep/plist/Read 都禁止**。
  取值只用 `set -a; . <file>; set +a` 注入子进程，不打印、不落日志、不入截图。
- **查任何配置前先想凭据**：`grep -i tailscale` 一个"偏好设置"文件就把 authkey 打上了屏（实发）。
  同类禁令：**无过滤 `ps aux`**（暴露席位 API key）、`tail .team/logs/agentmirrord-prod.log`
  （daemon 明文打配对 token）。**Shadowrocket 的偏好 plist 与 `tailscale_keys.bin` 列入禁读。**
  进程只取 `ps -o pid,ppid,etime,stat,comm`，⛔ 不取 argv。
- **凭据已泄露 ≠ 停工**：只做三件——**一行上报（⛔ 不复述泄露的值）、就地收紧做法、继续干活**。
  ⛔ 禁止因此停工、禁止等新 key、禁止把删本地产物当成风险处置。轮换与否是用户的事。
- **起隔离 tmux 后必须自检「我在自己的 socket 上」**：`tmux` 建 socket 失败时**不报错，
  静默回退到默认 socket**（= 用户的真实 tmux）。已实证两条回退路径：`TMUX_TMPDIR` 路径过长、
  目录未预先存在。**唯一可靠的不变量是自检**：`mkdir -p /tmp/e2e-<名>`（短路径且预建）→
  `unset TMUX` → `tmux -S <sock> new-session -d` → **`tmux -S <sock> list-sessions` 自检**，
  会话必须在自己的 socket 上，否则立刻停手。
- ⛔ **席位不许写 `/tmp` 或任何项目外路径**，临时文件写 `.team/nodes/<格>/tmp/`
  （隔离 tmux 的 socket 目录因长度上限例外，必须短路径且预建）。
- ⛔ **不许碰用户真实 tmux**（leader 只读 nodeprobe / list-panes 例外）；
  测试 daemon 会扫真实舰队 ⇒ app 里 ⛔ 不许点开真实会话。
- 给席位发消息**只走 `team-agent send`**，⛔ 禁 tmux `send-keys`。
- ⛔ **不写 `Co-Authored-By: Claude`**（用户裁定：Contributor 应该是他）。
- ⛔ **禁写 memory**；⛔ **禁用 AskUserQuestion**，要问就直接在对话里一两句话问。
- ⛔ 席位不许 `git commit` / `git push` / `git checkout` / `git restore` / `git worktree add`；
  封版与并线是 leader 的独立动作。
