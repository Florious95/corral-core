# 交接：远程Agent安卓 team leader · 2026-08-19

工作区 `/Volumes/nvme/Projects/远程Agent安卓`（下文所有相对路径都相对它）。
落笔时 HEAD = `271434362`。**文档写的是落笔那一刻的状态，可能已过期——先按 §0.5 核对再开口。**

---

## §0 compact 后先做什么

### 一句话现状

**下阶段唯一重点：把会话内右上角那个「查看」（悬浮窗）弄好。** 它当前**是坏的**，
用户真机实测三个缺陷（socket 取错并把观测装置自己列了进去 / ANSI 控制序列被当文本画出来 /
画面不断向下追加不替换）。修复账本 `ledger.overlay-fix.v1` 正在跑，第一格 `t.probe`（写加强版探针）在 advisor 手上。

### 开口第一句（对用户说这句，不要泛泛报现状）

> 悬浮窗修复在跑（账本 `overlay-fix-v1`，第一格是先把加强版探针写出来并在当前坏实现上验红）。
> 按你的新裁定，我准备把它**先在 web 外部端做成 MVP、调好样式给你看过，再进安卓**（契约 066）。
> 要我现在就把 web 端 MVP 那一格加进账本吗？

### 必读清单（按顺序）

1. 本文件
2. `requirement-base/entries/065-悬浮窗三缺陷与判据加强.md` —— 三缺陷 + 判据为什么上一版没拦住
3. `requirement-base/entries/066-悬浮窗先在外部端做MVP与展示要求.md` —— **用户新方法论**：先外部端 MVP
4. `requirement-base/entries/067-收藏与三栏布局.md` —— 下阶段第二项
5. `requirement-base/entries/064-会话内悬浮窗展示节点总览实时流.md` —— 形态与「尺寸零影响」硬约束
6. `.team/nodes/ov-design/可行性结论.md` —— **抓屏机制的实测结论，推翻了 leader 两处推测，必读**
7. `.team/artifacts/ledger-trial-findings.md` —— 试用期优化清单 F-01…F-07（**攒批未发，待用户会签**）
8. `/Users/alauda/.claude/skills/ledger-orchestration-trial/SKILL.md` 与 `ledger-orchestration`
9. `CLAUDE.md`（项目）与 `/Users/alauda/.claude/CLAUDE.md`（全局）

### §0.5 恢复工作流程（照编号做，做完才算接上）

1. **先核对，后开口**
   ```bash
   cd /Volumes/nvme/Projects/远程Agent安卓
   python3 .team/artifacts/heartbeat-check.py     # 驱动器 pid/etime、账本各格状态、每席工作态
   git log --oneline -1 && git status --porcelain | grep -vc '^??'
   lsof -nP -iTCP:9900 -sTCP:LISTEN -t            # 生产 daemon 是否在听
   ```
2. **先恢复守护，后推进**：**心跳是会话级的，不会跟过来。** 接手第一件事就是重开 30 分钟心跳
   （`CronCreate`，cron `13,43 * * * *`），内容只跑 `python3 .team/artifacts/heartbeat-check.py`
   加 `tail -c 500 .team/ledgers/ov2-drive.log`。
   ⛔ **不要在心跳文案里手写 `ps` / `pgrep` / `find`** —— 量具全在那个脚本里，理由见 §3 F-05。
3. **恢复期间禁令**（判"恢复完毕"之前一律不许）：不重启驱动器、不重置席位、不清 worktree、
   不改账本、不出 APK、不给编排队/框架队发信。
4. **判"恢复完毕"的标准**：①上面三条命令的输出与本文档 §4 一致（或已看懂差异）；②心跳已重开；
   ③读完必读清单前 6 项。三条齐了才可以推进。
5. **发现与文档不符**：**以现场为准**，并在开口第一句里补一句差异。
   若差异涉及"任务状态从 succ 变回 plan"或"驱动器不在了"，先看 `.team/ledgers/ov2-drive.log` 尾部
   的停机原因，**先查自己（判据/任务书）再判断是不是框架的问题**。

### 协作环境复活命令（若 team 塌了）

```bash
cd /Volumes/nvme/Projects/远程Agent安卓
.team/ta status --json | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('ready'),list(d.get('agents',{})))"
# 全员 DEAD 时：
.team/ta restart . --team grok-l2
# 仍失败且确认无上下文可失（jsonl 不存在）时才允许：
.team/ta restart . --team grok-l2 --allow-fresh
```

---

## §1 身份与不变量

- 我是 **leader，只编排不施工**：写契约、写账本、跑判据核对、提交、部署、出包。产品代码由席位写。
- **team**：`grok-l2`（workspace `/Volumes/nvme/Projects/远程Agent安卓`）。四席全 **grok**，
  角色文件里**必须显式写 `model: grok-4.6`** —— 不写框架会**静默**填内置默认 `grok-4`，
  「席位悄悄拿到一个不在角色文件里的模型和上下文窗口，而 argv 看起来还很正常」。
- 给席位发消息只走 `.team/ta send`（净化包装器），**禁 tmux `send-keys`**。
- **完成凭据只认机械判据退出码**。席位自报、`report_result` 都只是唤醒信号，不可信也不需要可信。
- **判据要断言「世界变了」**，不是「东西在那儿」。本项目头号复发形状。
  实例：上一版悬浮窗探针断言「两帧非空且内容不同」——**一堆 ANSI 乱码完全满足它**，于是放行了一个坏实现。
- **每格判据一过就立刻提交**。这不只是防事故（2026-08-12 有过整条修复以未提交状态被回退抹掉），
  它同时是**下一格判据的前置条件**：基线不干净，`git status` 类的"干净判据"必然误红（已连撞三次）。
- **改账本前必须先停驱动器**（F-07）。驱动器发现内存与盘上不一致会停机并拒绝合并——那是对的行为。
- **`worktree_id` 只是并发互斥标签，不是 git worktree**。席位会误读成"给我建一棵树"，
  六棵 `.worktrees/wt.*` 就是这么来的。任务书里必须每次写明。
- **判活不要用 team-agent 的 `worker_state`** —— 已实测证伪。用 `nodeprobe`（见 §5）。

---

## §2 排期与封存令

### 下阶段第一项：把右上角「查看」（悬浮窗）弄好 ← 用户明确指定

用户原话：「下阶段的重点，那就是把右上角这个查看弄好。」

**为什么是它**：它是本轮唯一一个**已交付但用户实测不可用**的功能。二级菜单列表+三态状态
（契约 061/062）用户已确认「准确率非常高、APP 装了没什么问题」，悬浮窗是唯一的欠账。

**方法上有新裁定（契约 066）：先在 web 外部端做 MVP，调好样式给用户看过，再进安卓。**
理由是实测代价——悬浮窗第一版那三个缺陷在安卓上才发现，而它们**在 web 端一眼可见**；
安卓一轮验证要出包、装机、人工看图，几十分钟。

### 下阶段第二项：收藏 + 三栏布局（契约 067）

**排在悬浮窗之后**，理由：它是新功能，而悬浮窗是欠账；且它有三处需要用户先裁定的留白
（窄屏怎么呈现三栏 / 收藏排序 / 会话消失后怎么显示）。

### 无封存令

2026-08-15 那条「编排被阻断就停工等修」已于 2026-08-18 被用户**取代**：
现在是**投完报告就继续，不停工**（`CLAUDE.md` 已改，旧条留档作废）。

### ⛔ 仍然生效的硬禁令

- **不许启动安卓模拟器**（用户 2026-08-14 令，**未解除**）。第 2 层测试暂停，
  真机是唯一渲染验收路径 ⇒ 这也是为什么 066 的「先 web 端」特别有价值。

---

## §3 P0 与插队项

### P0-A：驱动器停机通知投不出去（**已报，对方已立案，未修**）

- 报告：`.team/artifacts/ledger-p0-停机通知投不出去-20260818.md`，投递 `msg_a7661cb3ed80`
- 根因（编排队核到，比我推测更实）：`cli/src/lib.rs` 停机通知处**收件人写死 `let recipient = "leader";`**，
  team key 取自 config 而**不取自账本**。所以同一次运行里派单投得中、通知投不中。
- 现象每次都长这样（**已复现 5 次以上**）：
  ```
  "通知未送达：team key `annot` is not a runtime key in target workspace
   `/Volumes/nvme/Projects/远程Agent安卓`"
  ```
  `annot` 是**对方自己工作区**的 team 名；我账本里 `grep -c annot` = **0**。
- **对后继的实际影响**：**驱动器停了不会有人告诉你。** 只能靠 §0.5 第 2 步那个心跳发现。
  ⚠️ 这就是为什么"接手第一件事是重开心跳"不是可选项。
- 对方排期：已立案 `ledger.p0notify`，硬判据两条（通知 team 必须从账本取；通知未送达必须是响的失败）。

### P0-B：投递记 delivered、席位活着，但消息从未成为一个回合（**已报，归属已拆**）

- 报告：`.team/artifacts/ledger-p0-投递delivered席位从不消费-20260818.md`，投递 `msg_cc5b1b548fd8`；
  复现补充 `msg_ec06c367bd48`
- 已发生 **2 次，两次都是 `dev-server` 这同一个席位**（不同账本的 t.srv）。这条已递给对方缩小范围。
- 排除项（我已核，别重查）：`pane_in_mode=0`（不是 copy-mode 吃 Enter）；正文完整（不是尾部截断丢 token）；
  席位进程活着（子进程 comm=node，grok CLI 是 node 程序）。
- **归属**：编排队判定拆两半 —— **注入丢失那半归 team-agent 框架队**（他们已同步）；
  **送达凭据那半归编排队**（「真正的凭据是它成为了席位的一个回合，不是写进了收件箱」，他们原样收下）。
- **止血办法（后继遇到照做）**：
  ```bash
  .team/ta reset-agent dev-server --discard-session --workspace .
  # 然后停驱动器 → bump 账本 revision（换 case_id）→ 重启驱动器
  ```
  ⚠️ 旧那条消息已被标 delivered，不会再被消费，**必须换 revision 重派**。

### 插队扰动说明

P0-A/B 都**没有打断排期**（新裁定：投完报告就继续）。**在途任务没有因此漂移。**

### F-05：我自己的量具一天坏了五次（**这条比两个 P0 更容易让后继栽**）

判断"有没有节点在工作"，我用错了五把尺：

| 错法 | 为什么错 |
|---|---|
| team-agent `worker_state` / `last_output_at` | 对 grok 席位**失灵**：持续干活期间一律报 `PROBABLY_IDLE`、时间戳冻住 |
| `find -newermt '-30 minutes'` | macOS 的 **BSD find 不认相对时间，恒返回 0** ⇒ 把健康运行判成卡死 |
| `pgrep -x ledger-run \| head -1` | **全机匹配**。本机同时有 3 个工作区在跑驱动器，取到的常是别人的 |
| `pane_current_command` | grok/claude 是 TUI、走**备用屏**，这里显示 `bash`，看着像进程没了 |
| `~/.grok/sessions` 的 mtime | 那个目录**全机共享**，别的工作区的 grok 席位也在写，与本 team 无关 |

**通用形状**：**「判据自己坏了」和「被测对象坏了」在输出上完全同形**，都是一个不好看的读数。
唯一能分开的办法是**用第二种独立方法量同一件事**。
⇒ 已收紧：量具固化进 `.team/artifacts/heartbeat-check.py`（驱动器按 **cwd 认领**、判活用 `nodeprobe`
读 pane 标题、并**排除 leader 自己那一窗**——我跑心跳时必然 working，算进去会让"全空闲"永远看着健康）。

---

## §4 在途未收尾任务

### 任务 1（**下阶段重点**）：悬浮窗修复 —— 账本 `ledger.overlay-fix.v1`

- 账本文件：`.team/ledgers/overlay-fix-v1.json`
- 日志：`.team/ledgers/ov2-drive.log`
- 驱动器：落笔时 **pid 43721**（`ps -o pid=,etime=,stat= -p 43721`；**pid 会变，用心跳脚本认 cwd**）
- 契约：`065`（三缺陷 + 判据加强）、`064`（形态与尺寸零影响）、**`066`（先外部端 MVP）**
- 五格与负责席位：

  | 格 | 席位 | 干什么 | 落笔时状态 |
  |---|---|---|---|
  | `t.probe` | **advisor** | 写加强版探针，**现在必须验红** | **在跑** |
  | `t.srv` | **dev-server** | 修 socket 作用域 + 排除 scratch 自我映照 | planned |
  | `t.app` | **dev-app** | 修 ANSI 渲染 + 替换而非追加 + 订阅带当前 socket | planned |
  | `t.ver` | **control** | 探针转绿 + 尺寸不变仍绿 + 定点变异 | planned |
  | `t.pkg` | **dev-app** | 出 APK | planned |

- **三条新判据（这次要咬到点上）**：
  1. **无裸控制序列**：渲染后文本不得出现 `ESC[` / `[?1049` / `[K` / `(B[m` 字面量
  2. **socket 正确且无自我映照**：必须出现目标 socket 下真实会话名；**不得出现** `am-overlay` / `tree` / `sleep` / `ov-spin`
  3. **替换而非追加**：连续刷新后行数**有界**，不出现同一棵树重复多份
- **回归红线（不可改）**：`.team/nodes/ov-design/size-invariance.sh` 必须**仍然绿**
  （打开/关闭悬浮窗，用户所有真实会话窗口行列数一个都不能变；064 已实证过一次）。
- **合法阻塞 vs 卡死**：advisor 在跑探针属正常，单格十几分钟很常见；
  判"卡死"要三样对齐：驱动器 etime 不涨 + `nodeprobe` 显示该席位 idle + 日志无新行。
- **下一步第一个动作（具体到命令）**：
  ```bash
  cd /Volumes/nvme/Projects/远程Agent安卓
  pkill -f "overlay-fix-v1.json"                     # 改账本前必须先停驱动器（F-07）
  # 在 .team/ledgers/overlay-fix-v1.json 里插入一格 t.web-mvp（owner r.app 或新席位）：
  #   在 web/ 下做悬浮窗外部端 MVP，走真实服务端流（⛔ 不许假数据），
  #   判据：无裸控制序列 / 树全展开 / CJK 不错位 / 状态可见变化
  #   依赖：t.probe → t.web-mvp → t.srv、t.app
  python3 -c "..."                                    # bump revision
  ledger-run --preflight --json .team/ledgers/overlay-fix-v1.json   # 必须 rejected:false
  nohup ledger-run --drive --json .team/ledgers/overlay-fix-v1.json > .team/ledgers/ov2-drive.log 2>&1 &
  ```
  **怎么算做完**：web 端 MVP 上用户亲眼看过、认可样式（简洁 / 中英文不乱 / 全展开 / 状态动起来），
  **然后**安卓侧三条判据全绿 + 尺寸不变仍绿 + 出包用户真机确认。

### 任务 2：收藏 + 三栏布局（契约 `067`）—— **未开工，可延后**

- **开工前必须先让用户裁定三处留白**（契约 067 §四）：三栏在窄屏怎么呈现 / 收藏排序 /
  已收藏但会话消失了怎么显示。**不要自行决定。**
- 已写死的一条红线：**收藏的身份只能用 tmux 结构字段**（`session_name` / `window_index` / `window_name`），
  **不得用标题字符串当键** —— 标题随任务摘要变化，用它当键会导致收藏丢失或错位。

### 任务 3：试用期优化清单交付 —— **攒批未发，等用户会签**

- 文件：`.team/artifacts/ledger-trial-findings.md`，已记 **F-01 … F-07**
- 纪律（试用期 skill §1）：**非阻塞项只记不投**，阶段任务收口后**交用户会签，统一发一次**。
  ⛔ 不许因为攒多了就升级成阻塞去投。
- **退出条件**：当前阶段任务收口 + 清单交用户会签并发出 ⇒ 试用期纪律作废或改版。**到点了主动提。**

### 任务 4（低优先）：`.worktrees/` 残留 —— **等用户许可再清**

- `git status` 里那 4 项"未提交"就是它们（git 当子模块报）。**不是产品改动。**
- 六棵：`wt.app` `wt.design` `wt.package` `wt.rollback` `wt.server` `wt.verify`
- ⚠️ `wt.app` 里那份 stale 基线是「整棵搬回来会把已回退的 v1 装回去」这个教训的**实物证据**，
  清之前问用户。清法：逐个 `git worktree remove` + 删目录 + `.gitignore` 加一行。

---

## §5 运维与外部

### 生产服务端（**leader 常驻授权，无需确认**）

- 现役：pid **48612**，起于 8月19 00:52，监听 `9900`，`-host 192.168.31.116`
- 二进制：`server/agentmirrord`；备份在 `/tmp/am.bk-*`
- 换法（三步，一步不能省）：
  ```bash
  cd server && go build -o /tmp/am.new ./cmd/agentmirrord
  cp agentmirrord /tmp/am.bk-$(date +%H%M%S)
  kill $(lsof -nP -iTCP:9900 -sTCP:LISTEN -t | head -1); sleep 2
  cp /tmp/am.new agentmirrord && nohup ./agentmirrord -host 192.168.31.116 > /tmp/am-prod.out 2>&1 &
  sleep 4; lsof -nP -iTCP:9900 -sTCP:LISTEN -t          # 必须有 pid
  ```
- ⚠️ **出 APK 前先想服务端**：2026-08-18 实撞——APK 装上去二级菜单全空，
  真因是**生产 daemon 还是不带 level2 的旧二进制**。判法：`strings server/agentmirrord | grep -ci <新符号>`。

### 桌面 APK（用户规则：按时间排序，永远装最新；保留 2 个，删更早的）

```
8月19 00:53  agentmirror-overlay-1efd30300.apk      ← 最新（悬浮窗坏，二级菜单好）
8月18 22:11  agentmirror-l2-tristate-ce6a3e3e7.apk  ← 兜底（用户确认可用）
```
⚠️ 更早那个用户长期在用的 `agentmirror-passthrough-refresh-cf4530a2d.apk`
**已被轮转规则删掉**。若新包出问题、兜底那个也不行，需要从旧 commit 重打。

### nodeprobe（判活工具，已全局安装）

- 命令：`nodeprobe -S "${TMUX%%,*}"`（当前 socket）；只读，不 attach、不发按键
- 全局 skill：`tmux-node-activity`（⚠️ **另一个 agent 已优化过该 skill，以现版为准，不要覆写**）
- 源码 `tools/nodeprobe/`；产物 `/Volumes/nvme/cargo-target/release/nodeprobe`（该机 cargo target-dir 被全局改过）
- 已安装：`~/.local/bin/nodeprobe`
- 语料 `tools/nodeprobe/fixtures/titles.tsv` —— **全仓只允许一份**，Go 侧探测器共读它
  （判据 `A-pa-single` 强制）。**改判定规则必须改语料**，否则 Go/Rust 静默漂移。

### 外部直报通道（**一封信一个文档路径，报完就走，不进来回循环**）

- 编排框架（ledger-orchestration / ledger-run）：`/Volumes/nvme/Projects/讨论team-agent::team/leader`
- team-agent 框架本身（投递/席位/状态）：
  `/Users/alauda/Documents/code/agent前沿探索/多agent协作::refactor-maintainability/leader`
- **跨 agent 往返硬上限：同一个对方一天不超过 10 个往返。**
- ⛔ **禁止为框架队取证**：他们要求做复现、取证阶梯、保留现场，一律拒绝。
  唯一配合项是**换用他们发布的新基础设施**。
- 投递方式：写成文件再 `"$(cat 文件)"` 传进 `send`。
  ⛔ 不要把报告内联进 `send` 参数——正文里的 `<` `|` 会被 shell 当重定向/管道解析。

---

## §6 安全约束（原文保留，不可弱化）

- 密钥只存在于 `.team/current/profiles/*.env`，**任何席位禁止读其原文**。
- **`.team/current/profiles/tailnet-test.env` 全员禁读**（含 leader）。里面是用户 tailnet 的 auth key，
  只能通过 `TS_AUTHKEY` 环境变量注入测试节点，任何形式的 cat/grep/plist/Read 都禁止。
  取值只用 `set -a; . <file>; set +a` 注入子进程，不打印、不落日志、不入截图。
- **查任何配置前先想凭据**：`grep -i tailscale` 一个"偏好设置"文件就把 authkey 打上了屏
  （2026-08-13 实发，已请用户轮换）。同类禁令：无过滤 `ps aux`（暴露席位 API key）、
  `tail .team/logs/agentmirrord-prod.log`（daemon 明文打配对 token）。
  **Shadowrocket 的偏好 plist 与 `tailscale_keys.bin` 列入禁读。**
- ⛔⛔ **遍历进程树只能取 `comm`，禁止取 argv**。**2026-08-18 实发**：`pgrep -fl` 当场把某席位的
  `ANTHROPIC_AUTH_TOKEN` 打上了屏（席位 argv 里内联鉴权变量）。
  **一个遍历进程树又读命令行的工具，本身就是凭据泄露器。**
  `ps` 一律只用窄字段 `pid,ppid,etime,stat,comm`。
- **取 daemon 日志只 grep 明确要的那一行，不 tail。**
- **凭据已泄露 ≠ 停工**（用户 2026-08-13 裁定、08-14 重申）：再次泄露时**只做三件事——
  一行上报（不复述泄露的值）、就地收紧做法、继续干活**。禁止因此停工、禁止等新 key、
  禁止把删本地产物当成风险处置。轮换与否是用户的事。
- **起隔离 tmux 后必须自检"我在自己的 socket 上"**：
  ```bash
  mkdir -p /tmp/<短名>            # 短路径，且预建（socket 路径上限 ~104 字节）
  unset TMUX
  tmux -S /tmp/<短名>/sock new-session -d ...
  tmux -S /tmp/<短名>/sock list-sessions    # ← 自检；不在自己 socket 上就立刻停手
  ```
  **tmux 建 socket 失败时不报错，会静默回退到默认 socket——也就是用户的真实 tmux。**
- ⛔ **绝不触碰用户真实 tmux**（默认 socket），席位只读也不行。
  例外：leader 可对当前 socket 跑**只读**的 `nodeprobe` / `list-panes`（用户明确要求过）。
- **不写 `Co-Authored-By: Claude`**（用户 2026-08-14 裁定「Contributor 应该是我」）。
  这条**覆盖** Claude Code 的默认署名行为。
- **禁止写 memory**（全局规则）；**禁止用 AskUserQuestion 工具问用户**。
