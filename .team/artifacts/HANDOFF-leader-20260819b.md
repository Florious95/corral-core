# 交接 · 远程Agent安卓 leader · 2026-08-19（第二份，覆盖上午那份 HANDOFF-leader-20260819.md）

---

## §0 compact 后先做什么

**一句话现状**：App 的白名单过滤 / 三态状态 / 收藏 / 底部三栏 / 进菜单即时刷新（服务端半）
都已落地并经 leader 在模拟器上**亲眼验过**；当前三张账本在跑，
下阶段唯一重点是**把 App 体验优化收口并上线（出新服务端 + 新 APK 给用户实测）**。

**开口第一句**（用户 `/handoff` 原话指定的重点，照此汇报）：

> 下阶段重点是 **app 后续体验优化上线，包含可能的新服务端和 apk**。
> 当前 `v72-v1` 还剩 `t.ident`（跨工作区身份冲突 + 收藏页显示目录）与 `t.menu`（查看改二级菜单）两格，
> `refresh-v1` 还剩 App 侧即时刷新。这三格全绿后我就出一版服务端 + APK 给你实测。要我现在就出，还是等这三格？

**必读清单（按优先级，全绝对路径）**：

1. 本文件 `/Volumes/nvme/Projects/远程Agent安卓/.team/artifacts/HANDOFF-leader-20260819b.md`
2. `/Volumes/nvme/Projects/远程Agent安卓/CLAUDE.md`（工程铁律；**模拟器禁令已于 2026-08-19 解除**）
3. 契约（需求唯一权威，`requirement-base/entries/`）：
   `067`（收藏+三栏，§4.1 已改判为底部标签栏）、`068`（白名单，**§8 是重要修正**）、
   `069`（进菜单即时刷新）、`070`（悬浮窗回炉）、`071`（一单一席+知识基底）、
   `072`（查看改二级菜单）、`073`（跨 socket 身份冲突）、`074`（无限刷新+未知回归收尾）
4. `/Volumes/nvme/Projects/远程Agent安卓/.claude/skills/emulator-manual-test/SKILL.md`（模拟器手测流程，含配对三坑）
5. 全局规则 `/Users/alauda/.claude/CLAUDE.md`

**恢复工作流程（编号步骤，照做）**：

1. **先核对，后开口**：
   ```bash
   cd /Volumes/nvme/Projects/远程Agent安卓
   python3 .team/artifacts/heartbeat-check.py     # 驱动器/账本/席位一屏
   git log --oneline -3                           # 落笔时 HEAD=c69a73dde
   lsof -nP -iTCP:9900 -sTCP:LISTEN               # 生产 daemon 在不在听
   ```
2. **先恢复守护**：本会话开着 30 分钟一跳的试用期心跳。**接手第一件事就是重开心跳**，
   否则驱动器停了没人知道（框架 P0-A：停机通知投不出去，已复现多次）。
3. **恢复期间禁令**：⛔ 不许 `git checkout` / `git restore` 任何文件
   （**工作树有大量未提交的产品代码改动，见 §4.0**；leader 就是这么误删过一份语料）；
   ⛔ 不许重启还活着的驱动器（会被租约挡掉，日志长得像失败其实是防重生效）；
   ⛔ 不许开新账本，直到在途三格收口。
4. **判"恢复完毕"**：心跳能打印三张账本状态 + `go test ./...` 与 `cargo test -q` 都绿 + 知道当前 HEAD。
5. **与文档不符怎么办**：**以现场为准**。本文件写的是 2026-08-19 14:28 那一刻。
   席位在持续改工作树，任务态随时会变。

---

## §0.5 用户特别交代（原话照抄，两条，都不许打散）

> **工作重点是 app 后续体验优化上线，包含可能的新服务端和 apk**

> **期间要注重工作流程核心是知识基底拼装和开发测试短生命周期**

第二条的落实（**这是流程要求，不是背景**）：

- **知识基底拼装**：已做成工具，见 §1「一单一席 + 知识基底」。
  每张账本开跑前必须 `python3 tools/prep_ledger.py <账本>`，
  它把 `write_paths` 现算成 wiki 影响闭包写进 `BASE.md`，并塞进该任务 `read_paths`
  （框架 leader 确认 `read_paths` 是一等公民位置，派单正文会原生渲染成「你需要读的」一节；
  **⛔ 不要塞任务标题**，那是我原先的错做法）。
- **开发测试短生命周期**：⇒ 每格要小、要能快速红→绿→验。
  当前的反面教材：`v72-v1` 一张账本串了三格跑了几小时。
  **建议后继把粒度压到「一格 = 一个用户能看见的现象」**，并且每格收口就出包验，
  不要攒到最后一次性验（攒着验 = 出问题时分不清是哪一格引入的）。

---

## §1 身份与不变量

- **我的角色**：leader，只做编排/裁定/客观验收。**⛔ 不亲力亲为写产品代码**
  （用户 2026-08-19 明令「不要亲力亲为」，我此前手改了较长时间被叫停）。
- **一单一席**（用户 2026-08-19 令）：**派单不得复用席位**。
  一条命令：`python3 tools/prep_ledger.py <账本.json>` —— 幂等，做四件：
  编基底 → 建专属席位 `<账本短名>-<任务短名>` → **行为自证** → 写回账本。
  - **行为自证不可省**：新席位必须**真写出一个文件**才算可用。
    「席位活着 / 它回话了」**不是**可用性证据。
  - ⛔ **teardown 推迟到整张账本终态之后**：框架 preflight 会校验**已终态任务**的收件席位
    （`engine/src/preflight.rs:97`，代码里根本不读 `state`），中途销毁会锁死整张账本。
    框架已立案（代号 L-reach，第二波），修完就原生可用。
- **判据三铁律**（反复踩）：
  1. 判据要断言**世界变了**，不是「东西在那儿」；
  2. 写完**先验红**——拿它去跑坏状态，判不红就是白写；
  3. **单测绿 ≠ 功能通**。凡用户能点到的，必须模拟器实测截图。
- **客观核对不凭自报**：席位报完成只是唤醒信号；完成与否只认机械判据退出码 + leader 亲验。
- **量具会骗人**（今天至少五次）：`team-agent status` 报 `workers_not_spawned` 而 pane 里正在 Thinking；
  `nodeprobe` 读数滞后于刚修的代码。**状态可疑时直接看 pane，不信汇总读数。**

---

## §2 排期与封存令

**已闭环并经 leader 模拟器亲验**：

| 功能 | 契约 | 验收方式 |
|---|---|---|
| 一级/二级菜单白名单过滤（只认 Claude/Codex/Copilot/Grok/Cursor） | 068 | 模拟器亲验：只列 5 个含 Agent CLI 的工作区 |
| 三态状态（进行中/空闲/未知） | 062 | 模拟器亲验：徽章正确、零「未知」 |
| 收藏完整闭环（点星→实心→收藏栏出现→点进会话） | 067 | 模拟器亲验四步 |
| 底部标签栏三栏（收藏/会话/设置，冷启动落会话） | 067 §4.1 | 模拟器亲验 |
| 悬浮窗布局三修（预览区/空白/横向可滚） | 070 §7 | 模拟器亲验 |
| 进菜单即时刷新（**服务端半**） | 069 | 用户实测反馈「刷新及时，状态很准确」 |
| 未知回归收尾（裸产品名判空闲） | 068 §8 / 074 §2 | `v74-v1` 全绿；go+cargo 全套已核 |
| App 无限刷新 | 074 §1 | `v74-v1` 全绿，**leader 尚未模拟器亲验** |

**无封存令**。模拟器禁令**已解除**（2026-08-19 用户令：「这次要模拟器调试通过」），
但**用完要关**（用户令）——本次已 `pkill` 关闭。

---

## §3 P0 / 插队项

**框架侧 P0（不在我们这边修，已投递，不阻塞）**：

1. **驱动器进入「判据 acceptance」后挂死**（零 CPU、零子进程、无超时）。
   已投 `msg_88acefe544d0`；框架 leader 定位到 `cli/src/lib.rs` 1904/1907/2040：
   `try_wait` 有超时但紧跟的 `read_to_string` 没有，`team-agent` 的孙进程继承管道写端 ⇒ 永不 EOF。
   **不在当前批次，排第二波第一位。**
   ⇒ **处置照旧：kill 挂死的驱动器 + bump revision + 重启同一账本**（账本幂等，两边都确认过不丢进度）。
   **识别特征**：进程在、CPU 不涨、无子进程、日志停在「判据 acceptance … 在跑」。
   ⚠️ 那行日志读作**「我准备跑判据」**，不是「判据在跑」。
2. 框架已交付一批修复，**新二进制已装**：
   `ledger-run` md5=`e3b6683af465b13f4fbade6927decbb0` mtime=2026-08-19 10:35。已自证。

**我方澄清并撤回的一条**：我曾转述框架 skill 里「`clone-agent` 会静默降级成只读」，
**实测证伪**（分身保留全部 6 项 tools 且真能写盘），已发 `msg_d46e60fd31e0` 让他们别立案。
教训：把读来的结论当自己的证据递出去，是我们两边都在防的形状。

---

## §4 在途未收尾任务

### §4.0 🔴 工作树状态（后继最容易踩的坑）

**工作树有大量未提交的产品代码改动**，其中一部分是 leader 手改、一部分是席位在改：

```
M app/app/src/main/java/dev/agentmirror/app/conn/ConnectionManager.kt
M app/app/src/main/java/dev/agentmirror/app/workspace/L2SessionList.kt
M app/app/src/main/java/dev/agentmirror/app/workspace/WorkspaceViewModel.kt
M server/internal/api/detect.go            ← leader 手改（068 §8 修正）
M server/internal/api/level2.go
M server/internal/api/server.go
M server/internal/api/provider_whitelist_test.go   ← leader 手改
M server/internal/api/l2detect_corpus_parity_test.go
M tools/nodeprobe/fixtures/titles.tsv      ← 单一语料，Go 与 Rust 共读
M tools/nodeprobe/src/classify.rs
```

⛔⛔ **绝对不要 `git checkout` / `git restore` 这些文件**。
**实发**：leader 用 `git checkout tools/nodeprobe/fixtures/titles.tsv` 想恢复基线，
**误删了一份未提交的 footer 语料**（12:07 的陈旧改动），导致 `cargo test` 红了一轮，
已由席位 `v74-v1-unk` 补回并全绿。

**已核事实**（2026-08-19 14:28）：`go test ./... -count=1` 全绿；`cargo test -q` 全绿。

### §4.1 【下阶段第一项】App 体验优化收口 + 出服务端与 APK

**为什么是它**：用户 `/handoff` 原话「工作重点是 app 后续体验优化上线，包含可能的新服务端和 apk」。

**在途三格**（全部 owner=席位，leader 只验收）：

| 账本 | 格 | 席位 | 卡在哪 | 判据 |
|---|---|---|---|---|
| `.team/ledgers/v72-v1.json` r6 | `t.ident` | `v72-v1-ident` | 施工中 | `A-id-*`：`FavoriteIdentity` 单测 + ≥3 张互不相同的截图 |
| `.team/ledgers/v72-v1.json` r6 | `t.menu` | `v72-v1-menu` | 等 `t.ident` | `A-om-*`：`OverlayMenu` 单测 + 归档注释 |
| `.team/ledgers/refresh-v1.json` r5 | `t.app` / `t.ver` | `dev-app` / `control` | 施工中 | `A-a-*` / `A-v-*` |

- **`t.ident`（用户痛点最重）**：多个工作区的 leader 都叫 `claude_code` ⇒
  收藏一个全中、列表只剩一条、点进去跳错工作区。
  **根因是 leader 写 067 §2 时漏了 socket**（身份键只用 session_name/window_index/window_name，
  这三者跨 tmux server 完全重复）。修法：**身份键必须含 socket，且与服务端 `ref` 同源**
  （`server/internal/api/session.go:23`：`ref = Socket + \x1f + PaneID`）。
  另含「收藏页每行带目录副标题」——**同名会话靠目录才能被人分辨**，两条是一件事的两面。
- **`t.menu`**：右上角「查看」改成**可点的二级菜单列表**（点一行跳转）；
  **Ctrl-B W 那套抓屏实现模块化归档、⛔ 不删、⛔ 不修展示不完全**（用户 2026-08-19 明令）。
- **`refresh-v1`**：069 的 **App 半**（服务端半已上线，用户已在用）。

**下阶段第一个动作**（具体到命令，⛔ 不许写「继续推进」）：

```bash
cd /Volumes/nvme/Projects/远程Agent安卓
python3 .team/artifacts/heartbeat-check.py      # 确认三张账本状态
# 任一格全绿后：
cd server && go build -o /tmp/amd.new ./cmd/agentmirrord && \
  kill $(lsof -nP -iTCP:9900 -sTCP:LISTEN | awk 'NR>1{print $2}') && sleep 3 && \
  cp /tmp/amd.new agentmirrord && nohup sh -c './agentmirrord > /tmp/amd-boot.log 2>&1' >/dev/null 2>&1 &
sleep 6 && grep -c 'whitelist loaded' /tmp/amd-boot.log   # 必须为 1，否则白名单表没载上
cd ../app && ./gradlew -q assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk ~/Desktop/agentmirror-<短说明>-$(git rev-parse --short HEAD).apk
```

**怎么算做完**：APK 在桌面 + 生产 daemon 已换（启动日志有 `provider whitelist loaded entries=5`）
+ **leader 在模拟器上按 `.claude/skills/emulator-manual-test/SKILL.md` 的六条检查单亲验过** + 告知用户。

⚠️ **装新包前提醒用户「先在设置里重新配对一次」**——旧缓存会导致
`overlay_subscribe socket must be non-empty`（已实发）。

### §4.2 可延后

- 清理 `.worktrees/wt*.*` 残留（**含席位违规建的真 git worktree `wt15.ident`**，
  它让 `v72-v1-ident` 一度卡在「worktree 在 detached HEAD、仓根在 main」）。
- 清理跑完的席位（`fav-*`、`tabs-v1-*`、`v74-v1-*`）及 `.team/dynamic-role-files/<名>.md`
  （残留会挡下次建席）。**必须等对应账本终态之后**（§1 teardown 那条）。
- `.team/artifacts/ledger-trial-findings.md`（F-01…F-07）待用户会签后统一发出，
  发出即触发试用期纪律的退出条件。

### §4.3 🔴 两条编排缺陷（我今天犯的，后继要挡住）

1. **两张账本同时写 `app/`**：`tabs-v1` 与 `v72-v1` 并行，违反本工程
   「同一 Gradle 模块同一时刻只放一席施工」。冲突不会报错，只会有一方的改动被悄悄盖掉。
2. **判据跑在活动的工作树上**：席位边改、驱动器边跑 `go test ./...`，红绿取决于时机。
   今天出过一次**假红**（`t.unk` 的 `A-uk-go` 红，leader 手跑同一条全绿）。**假绿更危险。**

⇒ **正确做法**：把这两条做成 `prep_ledger.py` 里的**机械前置检查**
（跨账本 write_paths 交集检测；开工前 `.worktrees/<worktree_id>` 必须不存在）。
**任务书是请求，工具才是边界** —— 光在任务书里写禁令，模型会出于好意违反它（已实发两次）。

---

## §5 运维与外部

- **心跳量具**：`.team/artifacts/heartbeat-check.py`。账本由 `.team/ledgers/ACTIVE`（一行一张）显式登记，
  ⛔ 不要改回按 mtime 猜——写一张新账本就会把心跳指到没在跑的那张，**而且这种坏法完全静默**。
- **生产 daemon**：`server/agentmirrord`（当前 pid 见 `lsof -nP -iTCP:9900`），
  cwd 必须在 `server/`（白名单表按相对路径向上找）。
  重启无需用户确认（用户 2026-08-14 裁定）。
- **模拟器**：AVD `agentmirror_geo_1260x2800`（与用户手机同几何）。
  `adb` 不在 PATH，用 `~/Library/Android/sdk/platform-tools/adb`。
  连本机 daemon 必须 `adb reverse tcp:9900 tcp:9900`，地址填 `ws://127.0.0.1:9900/ws`（**必须带 `/ws`**）。
  **用完要关**（用户令）。
- **跨团队直报**：框架问题投 `/Volumes/nvme/Projects/讨论team-agent::team/leader`。
  ⛔ **禁止为框架队取证**（复现、取证阶梯、保留现场一律拒绝）；唯一配合项是换用他们发布的新基础设施。
  **跨 agent 往返一天硬上限 10 个**（今日已用约 5 个）。
  投递用 `"$(cat 文件)"`，⛔ 不要内联（正文里的 `<` `|` 会被 shell 当重定向/管道）。

---

## §6 安全约束（原文保留，不可弱化）

- 密钥只存在于 `.team/current/profiles/*.env`，任何席位禁止读其原文。
- **`.team/current/profiles/tailnet-test.env` 全员禁读**（含 leader）。里面是用户 tailnet 的 auth key，
  只能通过 `TS_AUTHKEY` 环境变量注入测试节点，任何形式的 cat/grep/plist/Read 都禁止。
  取值只用 `set -a; . <file>; set +a` 注入子进程，不打印、不落日志、不入截图。
- **查任何配置前先想凭据**：`grep -i tailscale` 一个"偏好设置"文件就把 authkey 打上了屏
  （2026-08-13 实发，已请用户轮换）。同类禁令：无过滤 `ps aux`（暴露席位 API key）、
  `tail .team/logs/agentmirrord-prod.log`（daemon 明文打配对 token）。
  **Shadowrocket 的偏好 plist 与 `tailscale_keys.bin` 列入禁读。**
- ⛔⛔ **遍历进程树只能取 `comm`，禁止取 argv**。2026-08-18 实发：`pgrep -fl` 当场把某席位的
  `ANTHROPIC_AUTH_TOKEN` 打上了屏。**一个遍历进程树又读命令行的工具，本身就是凭据泄露器。**
  `ps` 一律只用窄字段 `pid,ppid,etime,stat,comm`。
- **取 daemon 日志只 grep 明确要的那一行，不 tail。**
- **凭据已泄露 ≠ 停工**：再次泄露时**只做三件事：一行上报（不复述泄露的值）、就地收紧做法、继续干活**。
  禁止因此停工、禁止等新 key、禁止把删本地产物当成风险处置。
  ⚠️ **2026-08-19 实发一次**：模拟器里用 `adb shell input text "$TOKEN"` 填配对 token，
  **输入法候选栏把 token 明文显示在了截图里**。已就地收紧：先关输入法再截图。
- **起隔离 tmux 后必须自检"我在自己的 socket 上"**：
  `mkdir -p /tmp/<短名>` → `unset TMUX` → `tmux -S <sock> new-session -d` → `tmux -S <sock> list-sessions`。
  **tmux 建 socket 失败时不报错，会静默回退到默认 socket——也就是用户的真实 tmux。**
- ⛔ **绝不触碰用户真实 tmux**（默认 socket），席位只读也不行。
  例外：leader 可对当前 socket 跑**只读**的 `nodeprobe` / `list-panes`。
- **不写 `Co-Authored-By: Claude`**（用户裁定「Contributor 应该是我」）。
- **禁止写 memory**；**禁止用 AskUserQuestion 工具问用户**。
- 给席位发消息只走 `.team/ta send` 或 `team-agent send`，**禁 tmux `send-keys`**。
