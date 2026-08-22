# HANDOFF · 远程Agent安卓 leader · 2026-08-23

> 写给**刚接手、没看过过程**的人。代号首次出现即解释，路径与 sha 写全。
> 落笔时刻：2026-08-23 凌晨。`git HEAD = a0681c9b7`（main）。

---

## §0 compact 后先做什么

### 一句话现状

`ledger.coreapp.v1`（core 变成被引用的发布产物、APP 壳搬进独立仓）**已全链跑完并收口**，
三个远端仓都推了、两个 PR 都 merged、`maven` 分支实测可拉。
**手上没有在跑的编排，没有后台进程。**
下一阶段是用户 2026-08-23 指定的新工作：**输入透传适配**（键盘+鼠标全生效），
**卡在两个必须用户拍板的问题上**（见 §2「用户特别交代」与 §4-A）。

### 开口第一句（compact 后对用户说的第一句）

> 「输入透传这件事我已经把方案和验证方式定下来了，**每一步都带性能基线回测**这条已经写进计划。
> 现在卡在两个只有你能定的问题：① **鼠标给谁**——给 tmux（切窗格/拖分隔线）还是给里面的 Agent（点它的界面）？两者只能选一个。
> ② **要不要现在开工**。你定了我就立第 0 格（契约 + 按键回显量具），⛔ 在第 0 格产出之前我不会碰任何产品码。」

### 必读清单（按优先级）

1. **本文件**
2. `/Volumes/nvme/Projects/远程Agent安卓/CLAUDE.md` —— 工程铁律正本。**新增三块必读**：
   §「🔴 当前稳定基线 = 2026-08-22 release」、§「席位与模型」（cursor-only + 守卫只在部分 provider 生效）、
   §「诊断日志纪律」。
3. `/Volumes/nvme/Projects/远程Agent安卓/docs/core-消费者指南.md` —— 三核对外契约（185 行，判据已过）
4. `/Volumes/nvme/Projects/远程Agent安卓/docs/性能关键路径.md` —— 八个性能事件在核还是在壳、各自对应哪个文件
5. `/Volumes/nvme/Projects/远程Agent安卓/.worktrees/wt-ca-close/.team/artifacts/coreapp-收账-20260822.md`
   —— 上一条链的三分口径收账（已验证完成 / 自报未核 / 未做），99 行
6. `/Volumes/nvme/Projects/远程Agent安卓/.worktrees/wt-ca-rv/.team/nodes/ca-rv/裁定.md` —— 异源终审裁定书（`status=pass`）
7. `~/.claude/skills/ledger-orchestration-trial/SKILL.md` —— 编排工作流十条铁律

### 恢复动作（协作环境）

**当前不需要复活任何东西**——没有在跑的驱动器/机器人/心跳。团队仍在：

```bash
cd /Volumes/nvme/Projects/远程Agent安卓
team-agent status --workspace . --team remote-agent-android
# 期望：advisor/dev-app/pb-rv1/pb-rv2/pb-emu 显示"空闲"，ca-rv2 显示"未知"
```

⚠️ `pb-*` 全是 **grok 通道席位，Grok Build 周额度已耗尽（0%），它们是死的**。
派单给它们不会报错，只会让驱动器等到预算耗尽（**账本层零症状**）。
唯一可用的是 cursor 席位 `ca-rv2`。**同一 workspace 同时只能有一个 cursor 席位。**

### 🔴 恢复工作流程（照编号做，做完才算接上）

**第 1 步 · 先核对，后开口**（文档写的是落笔那一刻，可能已过期）

```bash
cd /Volumes/nvme/Projects/远程Agent安卓
git log --oneline -3                      # 期望顶上是 a0681c9b7
git status --porcelain | wc -l            # 期望 0
pgrep -f 'ledger-run --drive'             # 期望空（无在跑编排）
pgrep -f autopr.py                        # 期望空
NP=$(cat .team/runtime/coordinator.pid); lsof -p "$NP" | awk '$4=="txt"{print $NF}'
#   期望 .../runtime/0.5.67/bin/team-agent —— ⛔ 带 .broken 后缀就是没换掉，见 §3
```

**第 2 步 · 先恢复守护**
本阶段**没有需要恢复的守护进程**（上一条链的心跳 cron `4014963a` 已在收口时删除）。
⚠️ **一旦你开了新链，立刻建 30 分钟心跳 cron**——它是会话级的，compact 不会带过来。
心跳正文照抄本文件 §4-C。

**第 3 步 · 恢复期间的禁令（做完第 1、2 步之前一律不许）**
- ⛔ 不许开新编排链、不许派单、不许 `add-agent` / `remove-agent`
- ⛔ 不许动 `tools/perfbase/*.sh`（判据不许绕）
- ⛔ 不许碰 `app/app` 壳（删壳要等用户真机复验，见 §4-B）
- ⛔ 不许 `git add -A`（2026-08-23 我用它把 76 个 worktree 当 gitlink 提了进去，见 §3-C）
- ⛔ 不许用 npm 重装 team-agent（会退回没有 coordinator 修复的 0.5.66，见 §5）

**第 4 步 · 判"恢复完毕"的标准**
第 1 步五条命令的实际输出与期望一致 + 读完 §0 必读清单第 1–3 项 ⇒ 可以开始推进。

**第 5 步 · 发现与文档不符怎么办**
**以现场为准**，并在给用户的第一句话里说明哪一条对不上。
⛔ 不要"修"到与文档一致——文档可能只是旧了。
若不符的是**远端仓状态**（PR、分支），先只读核对再说，⛔ 不擅自推送或合并。

---

## §1 身份与不变量（怎么干活的铁律）

**我是本工程 leader**，同时是账本编排框架（team-agent / ledger-run）的**用户**，不是它的开发者。

### 角色边界

- **leader 只做三件**：定判据、做裁定、并线（seal / land / 推 PR）。**探索性任务一律外派给席位**，用追问收敛。
- ⛔ **leader 不亲写产品码**（含解冲突）、⛔ 不改框架规范、⛔ **判据本身不许绕**。
- ⛔ **席位不许** `git commit/push/checkout/restore/worktree add`；封版与并线是 leader 的独立动作。
- ⛔ **席位不许主动给 leader 发消息**，唯一出口是 `report_result` + 落盘产物；只有**编排调整**才允许发一条。
- 跨工程投递用全名 `<workspace 绝对路径>::<team>/<角色>`，投前 `team-agent status` 验活。
  **`ok: True` 不是送达**——投给全死的 team 照样返回 `ok: True`。

### 客观核对，⛔ 不凭自报

- 席位说完成了 ⇒ **leader 自己重跑判据**才算数。本轮五条判据我逐条复跑过（见 §4-B 表）。
- **判据四态**：通过(0) / 不通过(1) / **不可判(2)** / 不适用。
  ⛔ 不可判与不适用**不许折进**通过或失败。编译不过 ≠ 测试红。
- **判据自己崩了必须报 exit 2**。2026-08-23 实撞：Python 未捕获异常的退出码恰好是 1，
  与"真的失败"撞码，被引擎记成"判据红"，差点让一个**根本没判过**的结论进链子。
  修法见 `tools/perfbase/judge-perf-ab.sh` 顶部的 `excepthook`。

### 显示不等于事实（三个已实撞的实例）

1. **`ps` 的路径名不跟 rename 走**：进程映射的是 inode，要看 `lsof -p <pid> | awk '$4=="txt"'`。
2. **cursor 席位 pane 底栏显示的分支 = 它启动时的 cwd**，⛔ 不是它实际在改的树。
   判位置要**对照两棵树的 `git status`**。
3. **CLI 错误串里的 `reason` 描述的是"守卫阻止的机制"，⛔ 不是"已发生的行为"**。
   转述几手会读成"它会静默覆写"，拿它去报缺陷等于报一件没发生的事。

### 席位死于额度/网络时账本层零症状 ⇒ 读屏

```bash
tmux -S /private/tmp/tmux-501/ta-b7cc1c640ccf list-panes -a -F "#{pane_id} #{window_name}"
tmux -S /private/tmp/tmux-501/ta-b7cc1c640ccf capture-pane -p -t <pane_id>
```

2026-08-22 一夜撞了三次：`pb-emu` 死于 HTTP 402 `usage balance exhausted`、
`pb-rv1` 死于 `Weekly limit left: 0%`、`ca-rv2` 死于 `Connection failed ×10`（网络，非额度）。
**三次驱动器都在正常"等待"，只有读屏能发现。**

### ⛔ 重建/移除席位前先看驱动器在等谁

`remove-agent` 会连同**在飞派单**一起销毁，驱动器会一直等到预算耗尽。换席位只在格与格之间的空档做。

---

## §2 排期与封存令

### 🔴 用户特别交代（2026-08-23 原话，本次 handoff 的侧重点）

> **「接下来的事情就是适配这一点。要求每一个步骤都要有基线性能基线的回测。」**

「这一点」= 上一轮定下的**输入透传方案**（让所有键盘操作与鼠标点击对投影的 Agent 界面完全生效）。
**"每一个步骤都要有性能基线回测"是硬要求，⛔ 不许合并步骤、⛔ 不许攒到最后一起测。**
这条与用户 2026-08-22 的裁定同源：**「性能体验是最核心的体验，这个不能回退。」**

⚠️ **必须让后继知道的冲突/前提**：这条要求**目前还执行不了**，因为
**现有的性能量具量的是"打开会话有多快"，不是"打字有多跟手"**。
详见 §4-A 第 0 步。**⛔ 不许在没有新量具的情况下声称"每一步都回测过了"**——
那种绿是假绿（判据没量到你改的东西）。

### 已闭环

- **`ledger.coreapp.v1`**（rev 23）：core 发布成 maven 产物 → corral-app 只引用产物 → A/B 性能门 → 异源终审 pass → 收账。
- **2026-08-22 release 基线**：真机金标准已过（用户在蜂窝+广州中转节点实测"秒开、没有空白"）。

### 封存中（⛔ 未经用户重新裁定不许动）

- **`docs/优化点清单-1820.md`** —— 用户 2026-08-20 晚的 18 个优化点唯一底册。
  上一轮实现**已全部作废**（批量做导致整批不可信），契约 087–091 仍有效。
  重启铁律原文：从基线起**一次一条**，用户真机实测不倒退才做下一条。
  **这正是本次"每步都要回测"要求的历史来源。**

### 在途（见 §4）

- **A · 输入透传适配**（下阶段第一项，卡在用户拍板）
- **B · 上一条链留给用户/leader 的三个缺口**（可延后，但删壳必须等用户真机验过）

---

## §3 P0 / 插队项

### P0-A · coordinator 跑在缺陷版上（**已闭环**，2026-08-23）

- **现象**：节点间收发消息不正常；投给 leader 的消息必现卡住，投给普通席位时好时坏。
- **根因**（框架维护队定位并已修）：缺陷版在**每一颗 Enter 之前无条件**向 pane 发一个孤立的
  bracketed-paste 结束标记 `ESC[201~`。TUI 认为"刚结束一次 paste"，而状态机无对应的进行中状态，
  该失配**吞掉紧随其后的 Enter** ⇒ 文本进得了输入框，回车永远不提交。
- **止血/根治**：`kill -TERM 47220` → 发一条消息触发 coordinator 重拉。**已自证换掉**：
  新 pid `94790`，`lsof txt` = `.../runtime/0.5.67/bin/team-agent`（**不带 `.broken`**）；
  行为自证：轮换后消息**自动上屏 1 次**，无人按回车。
- 🔴 **红线**：⛔ **绝对不要删 `~/.team-agent/runtime/0.5.67.broken`**，也不要对 runtime 目录做无脑 prune。
  只要还有活进程的 `txt` 指向某个版本目录，删掉会触发 macOS `kalloc.1024` 泄漏
  （删除仍被运行的可执行文件后，该进程每次文件操作漏 1KB 直到重启）。要清理先逐个 `lsof` 核无活进程。
- **对排期的扰动**：无。发生在链收口之后。

### P0-B · Grok Build 周额度耗尽（**未闭环，是长期约束**）

- **现象**：席位半路死掉，`Weekly limit left: 0%` / HTTP 402 `usage balance exhausted`，**账本层零症状**。
- **处置**：用户 2026-08-22 令——**只能开 cursor 通道、模型 grok 4.6**。
- **对排期的扰动**：**这是新链最大的资源风险**。同一 workspace 只能有一个 cursor 席位
  ⇒ **实现席与终审席只能时序错开**，异源评审那一层被削弱到只剩"不同席位 + 零上下文 + 判据反造假"。
  ⇒ **写判据时要更狠**，因为没有 provider 异源那一层了。

### P0-C · 我自己当天犯的错（写下来防复犯）

1. **`git add -A` 把 76 个 worktree 当 gitlink 提进去了**（commit `21bfeee31`），
   已在 `a0681c9b7` 清理并写死 `.gitignore` 的 `.worktrees/`。⛔ 以后不许在本仓用 `git add -A`。
2. **用一个恒为真的判据下结论**：跑 `git log origin/main..main -- server/` 判断 corral-serve 是否落后，
   但**本仓根本没配 remote**，该命令必然返回空，我把"空"读成了"没有未推的改动"。
   用户质疑后重核才发现方法是废的。**正确做法：直接比远端与本地的 blob sha。**
   （最终结论：corral-serve **本来就是最新的**，逐文件 sha 核过。）
3. **判据脚本崩溃冒充失败**（见 §1），已修。
4. **corral-app 远端仓 8 天没动静**，根因是我写的 `tools/autopr.py` 映射表漏了 `.team/staging/`
   ⇒ 写该路径的格永远轮不到 PR。已修（`faces()` 加 `capp` 映射 + `tools/push-corral-app.sh`）。
   **用户为此点名第三次。**

---

## §4 在途未收尾任务

### A · 输入透传适配（🔴 下阶段第一项）

**目标**：让所有键盘操作（含组合键）与鼠标点击，对投影的 Agent 界面**完全生效**。

**当前形态与断点**（已核，证据在代码注释里）：

```go
// server/internal/protocol/frames.go:152
// Input injects one whole text line into a session (C→S; requirement 003 —
// whole-line send-keys, never per-keystroke).
// Keys is the R-1 named-key alternative (requirement 017): named special keys
```
```go
// server/internal/bridge/tmux.go:40
// ErrInvalidKey ... a named special key is not in the closed set SendKeys accepts
```

⇒ 今天的输入模型是**「整行文本」+「一个封闭的命名键集合」**，服务端靠 `tmux send-keys` 打名字键。
**鼠标完全没有**（grep 到的 `SGR` 全是 Select Graphic Rendition 颜色样式，不是鼠标 SGR-1006）。

**结论：不是"改核还是改服务端"的选择题，两边都要改。** 分工与顺序：

| 步 | 改哪 | 改什么 | 为什么非它不可 |
|---|---|---|---|
| 0 | **不碰产品码** | 定契约 + **做按键回显量具** | 见下方"第 0 步"，**这是硬前置** |
| 1 | **服务端** | `send-keys` 名字键 → 裸字节注入 | **硬闸**：不改这里客户端做什么都到不了 pty |
| 2 | **core-protocol** | 加裸字节输入帧 | 现有 `Input` 只有 `Text`/`Keys`，表达不了任意字节 |
| 3 | **core-terminal** | 跟踪鼠标模式并编码 | **鼠标必然要动核**：点击编成什么字节取决于对面 app 开了哪个开关，而这个开关**只有终端仿真器在解析输出流时看得到**（`\e[?1006h`） |
| 4 | APP 壳 | 采集鼠标事件与修饰键 | 壳只采集，⛔ 不决定编码 |
| 5 | **core-terminal** | 预测性本地回显 | 唯一预期让指标**变好**的一步，**放最后**才能干净看出前面各步的代价 |

#### 🔴 第 0 步（硬前置，⛔ 不做完不许动产品码）

**两件事**：

1. **写死输入模型契约**，至少含：
   - 字节编码表：**只用 SGR 1006**（纯 ASCII `CSI < Cb ; Cx ; Cy M/m`）；
     ⛔ 不用 X10/1005/1015（xterm.js 的 X10 坐标被 UTF-8 卡在 127）
   - **安卓上用 1002（cell-motion），⛔ 不开 1003（any-motion）**——Termux 实测 1003 压垮 PTY 转发；
     1002 照样能拿滚轮事件（滑动映射成滚轮）
   - **重连必须按原顺序重放模式设置**：记录 app 发过的每个模式码、状态、到达顺序。
     xterm.js 把编码与协议做成**互斥**，谁后设谁生效，顺序错了静默退回有字节上限的旧编码
   - **ack 策略**：现有协议规定每个 `input` 必回 `input_ack`，逐键透传后变成每键一个往返 —— 
     这笔性能账要与第 5 步的预测回显**一起设计**，⛔ 不能分开定
   - **许可证边界**：Termux 的 `terminal-emulator` 与 mosh 都是 **GPLv3**，我们是 **Apache-2.0**，
     ⛔ 不能 vendor，**只能当参照实现读**；xterm.js 是 MIT，可参考甚至移植
2. **做按键回显量具**：量「按下按键 → 屏幕出现字符」的 p50/p95。

**为什么第 0 步不能省**（⚠️ 后继最可能在这里翻车）：
现有量具 `PerfTrace` 量的是 `tap → route_enter → subscribe_sent → geom_seed → first_frame_recv
→ snapshot_applied → first_draw → layout_settled`，**这是"打开会话"链路**。
输入透传基本不碰它。改完一测 `first_draw` 纹丝不动 ⇒ **判据给绿灯，但它没量你改的东西**。
**用户要的"每一步都有性能基线回测"，在新量具做出来之前根本无法兑现。**

**怎么算第 0 步做完**：契约文档落盘 + 量具能在模拟器上稳定量出按键延迟（可复跑、有 p50/p95）+ 
许可证核查结论落纸。

#### 每步的回测方法（用户硬要求，⛔ 不许简化）

**一个一个上，每上一个测一轮。** 理由：
- 全做完再测，**红了 = N 个嫌疑人**，还得回头二分 ⇒ 那还是一个一个验，只是晚做更贵（此时代码已互相纠缠）；
- **绿了也不安全**：一步退 8%、另一步赚 8%，互相抵消 ⇒ 把那个回退**静默收下**。

**每一轮必须是「改前包 / 改后包，同一台机器、同一时段、A,B,A,B 交替测」**，
⛔ 不许拿今天的数跟存档基线比。**证据**（2026-08-22 实测）：
同一个 A 包在两批之间自己从 p50 **142.0 → 165.5（+16.5%）**，而 B 包两批都稳（167.5 / 166.0）
⇒ **机器漂移量大于要检测的效应**。判据实现见 `tools/perfbase/judge-perf-ab.sh`
（地板 = **同批最大单批**，⛔ 不取跨批合并；门 +10%；两包 md5 相同直接判不可判）。

⚠️ **必须对用户诚实的一点**：现噪声底约 **±10%~16%**。**单步若只值 3%，量不出来。**
每轮真正能证明的是"这一步没退超过噪声"，⛔ 不是"一点没退"。
要守更小的回退，得先压噪声（更多样本 / 更安静的机器 / 更敏感指标）。

**分类降本**：碰输入热路径的（第 1/2/3/5 步）→ 每步一轮完整 A/B；
不碰的（协议加可选字段、文档、许可证核查、纯落盘部分）→ 可并成一个 PR、只过静态判据，⛔ 不占模拟器轮次。

#### 🔴 卡在这里：两个只有用户能定的问题

1. **鼠标给谁**：给 tmux（切窗格、拖分隔线）还是给里面的 Agent（点它的界面）？**两者只能选一个。**
2. **要不要现在开工。**

⛔ **在①有答案之前不许立格**——它决定第 3 步的编码逻辑，猜错整步作废。

#### 已知的"改完仍不生效"坑（写进契约，⛔ 别现场再查一遍）

- **内层 app 没开鼠标上报 = 白改**。判据：**拖拽变成选择复制**就是它没进 tracking 模式的信号。
  验证手段 `script` / `cat -v` 看有没有吐 `\x1b[?1002h\x1b[?1006h`。
- **`tmux send-keys -H` 是已知性能陷阱**：iTerm2 踩过，每个字节变 5 字节（`0x2f 0x62 ...`），
  被报为输入速度 bug。正确：**可打印文本走 `-l`（字面量），只有控制字节走 `-H`，且批量成一次调用。**

#### 参考资料（都已读过，结论已提炼进上面；⛔ 不必重查）

- xterm.js 支持的终端序列：https://xtermjs.org/docs/api/vtfeatures/
- xterm.js #1962 X10 坐标被 UTF-8 卡在 127：https://github.com/xtermjs/xterm.js/issues/1962
- mosh #576 模式顺序重放：https://github.com/mobile-shell/mosh/pull/576
- mosh 官网（预测回显：3G 实测中位 503ms → 近乎瞬时，均值 515→173ms）：https://mosh.org/
- ttyd #218（拖拽变复制 = 没进 tracking 模式）：https://github.com/tsl0922/ttyd/issues/218
- iTerm2 #10179（控制模式每字节 5 字节）：https://gitlab.com/gnachman/iterm2/-/work_items/10179
- Termux/PRoot 1003 不可用需降 1002：https://github.com/Gitlawb/zero/issues/505
- termux-app（GPLv3，⛔ 不可 vendor）：https://github.com/termux/termux-app

---

### B · 上一条链留下的缺口（可延后，但顺序有约束）

**`ledger.coreapp.v1` 已收口**（账本 `.team/ledgers/coreapp-v1.json` rev 23）。
**无活进程可查**——驱动器/autopr/心跳均已在收口时停掉，靠账本 state 与远端仓判断进度。

**我已验证完成的部分**（⛔ 不是席位自报，是我逐条复跑判据）：

| 格 | 判据脚本 | 我复跑的退出码 |
|---|---|---|
| `t.path` 钉住性能关键路径 | `tools/perfbase/judge-perfpath.sh` | 0 |
| `t.pub` 三核发布成 maven 产物 | `tools/perfbase/judge-pub.sh` | 0 |
| `t.capp` corral-app 只引用产物 | `tools/perfbase/judge-capp.sh` | 0 |
| `t.perf` A/B 性能门 | `tools/perfbase/judge-perf-ab.sh` | 0 |
| `t.rv` 异源终审 | `tools/perfbase/judge-verdict.sh`（在 `.worktrees/wt-ca-rv` 里跑） | 0，`status=pass` |
| `t.close` 收账 | `tools/perfbase/judge-doc.sh`（在 `.worktrees/wt-ca-close` 里跑） | 0，99 行 |
| 三核消费者文档 | `tools/perfbase/judge-coredoc.sh` | 0，185 行 |

⚠️ **判据住在各自的 worktree 里**，改仓根的副本无效；跑判据要 `cd` 到对应的 `.worktrees/<id>`。

**远端状态（已核）**：

- `corral-app` PR **#1 MERGED**（2026-08-22T17:40:26Z），main 上已是引用式工程：
  `settings.gradle.kts:29-30` 无 `includeBuild`、`app/build.gradle.kts:119-121` 依赖钉死
  `dev.agentmirror.core:*:20260822.0`
- `corral-core` PR **#14 MERGED**（2026-08-22T17:40:47Z）
- `corral-core` 新增 **`maven` 分支** `2bd8b001d`，76 个文件全是产物（3 pom / 3 jar / 3 aar + 校验和 + `maven-metadata.xml` + README），**零源码零凭据**。
  **实测可达**：三个 pom 全 200，`core-conn.aar` 拉下 35503 bytes
- `corral-serve` **本来就是最新的**（逐文件 blob sha 核过，含 `perf_subscribe` 时间戳）。
  ⚠️ 它是**过滤镜像**，提交日期继承被过滤的原始提交 ⇒ **"最后提交 08-14"不等于"08-14 后没更新"**

**仍未做（三件，⛔ 不许把上面的绿当成它们已完成）**：

1. 🔴 **用户真机复验**引用式构建的包「秒开无空白」—— **模拟器 A/B 绿替代不了金标准**。这是**用户的门**。
2. 🔴 **本仓 `app/app` 壳还没删** —— 删壳是**用户真机复验通过之后**的下一步，⛔ 不在任何已完成范围内。
3. **没有一份「引用式 APK vs `baseline-20260822.json` 三夹具」的绿**：
   旧判据 `judge-perf-nonregress.sh` 读不了 A/B 形状（`fixtures=[]` ⇒ exit 2 不可判）。
   收账文书已如实列在"自报未核"里，⛔ 不许粉饰成已验证。

---

### C · 心跳 cron 正文（开新链后立刻建，30 分钟一次）

上一条链用的是这一份，**已在收口时删除（job id `4014963a`）**。开新链照抄并把账本路径换掉：

> 【<链名> 无人值守心跳·每 30 分钟】账本 `.team/ledgers/<链>.json` + 收口机器人 `tools/autopr.py` 在跑。
> 本轮只守夜：⛔ 不新开工作、⛔ 不亲写产品码、⛔ 不改判据脚本放行、⛔ 不自己 grep 代码树（要摸情况派席位并追问）。输出极简。
> 1. `tail` 驱动器日志与 `autopr.log`；`pgrep` 两个 pid 各应有。
> 2. **驱动器不在了 = 收工退出，不是崩了**：`AwaitingHuman + 判者刚判 rework` ⇒ 引擎不复位 fix 格（框架已知欠账 P13），
>    处置=备份账本→fix 格与判者格 state 改回 planned、清 `status_record`/`blocking_reasons`→
>    **把裁定书里的 rework 逐条理由并进 fix 格派单正文**（依赖边重派不带上游 case）→revision+1→重起驱动器。
>    `frozen_no_new_case` ⇒ 清该格 `attempts[]` 里 `failed`/`failed_retryable` 的条目。
>    ⛔⛔ 两种都**不许清 `rounds` 与 `audit.route_hops`**。
>    ⚠️ **`attempts[]` 条目的键是 `state`，⛔ 不是 `outcome`**（2026-08-22 我用 `outcome` 过滤等于没过滤，驱动器直接 `no_dispatch` 退出）。
>    判据红 ⇒ 先疑判据假红（自己在该格 worktree 手跑）→ 再疑产品 → 最后才疑框架；⛔ 不许改判据放行。
> 3. autopr 出现「并线 红(park…)」= 冲突 ⇒ 记 `.team/escalations/`，⛔ 不自动解也不手解产品码冲突。
> 4. 席位像卡住 ⇒ 读屏。**判活别只看提示符**；席位死于 provider 报错时账本层零症状。
> 5. ⛔ **重建/移除席位前先看驱动器在等谁**。
> 6. 同一缺陷已投过就不再重复投递，除非**形状变准或带得出对照组**。
> 7. 全链跑完 ⇒ 停驱动器与 autopr、按收账文书给用户写一页、删掉 cron，
>    **并提醒用户：推远端是 leader 的动作，真机复验是用户的门，`app/app` 壳还没删。**

⚠️ **`tools/autopr.py` 已知行为**（我改过两处，别当 bug 重修）：
- 一棵 worktree 上**还有没收口的格**就不封版（席位正在写时封版会把半成品一层层提交上去）。
  ⚠️ **在飞的格在账本里仍写着 `planned`**（派单态由驱动器在内存持有、不落盘）⇒ 判据不能用 `state=="dispatched"`。
- `faces()` 现在有三条映射：`app/`→corral-core、`server/`→corral-serve、`.team/staging/`→corral-app
  （最后一条走 `tools/push-corral-app.sh`，推前四道自检：无 `includeBuild` / 无核源码 / 依赖钉死版本 / 无凭据类文件）。
  🔴 **源必须取施工 worktree 里那份**——2026-08-22 我拿仓根旧版差点把带 `includeBuild` 的形态推上公开仓，是这道自检拦下来的。

---

## §5 运维与外部

### 资源约束

- **Grok Build 周额度 = 0%**（见 §3-B）。唯一可用通道是 **cursor**，模型 id 是
  **`cursor-grok-4.6-high`**（⛔ 不是 `grok-4.6`，写错会打印一屏模型目录后 `exit 1`）。
- **同一 workspace 只能一个 cursor 席位**（`.cursor/mcp.json` 目录作用域）。
  cursor 是 **fail-closed**（第二席被硬拒、先起席位身份不被改写）；
  🔴 **grok 同为目录作用域但 `add-agent` 不拦** ⇒ **守卫只在部分 provider 生效**。
- `remove-agent` **会连角色文件一起删**，重建前要重写角色文件。
- **本机负载**：2026-08-22 曾被隔壁工程 `/Volumes/nvme/Projects/tmux桌面端` 的 perf 席位跑 ffmpeg 抽帧
  占满（load1 到 138、6.5 核、1 小时吃 126GB 盘），导致模拟器起不来、席位如实报**不可判**。
  ⛔ **不要杀别的工程的进程**——那是别人在飞的活。处置是告知用户由他决定。
  负载自动续跑脚本：`.team/ledgers/resume-on-idle.sh`（load1≤15 且 free+inactive≥3000MB 才复位重起）。
- **网络**：本机走代理 `127.0.0.1:39282`，单次握手实测 **5.8–6.3 秒**。
  `git clone` / `gh` 调用要给**长超时**（≥8 分钟），否则会被工具超时打断。
  cursor 席位曾因此死于 `Connection failed ×10`。**先问网络路径，再疑代码。**

### 外部直报通道

- **框架/账本编排问题** → `/Volumes/nvme/Projects/讨论team-agent::wiki/leader`
  ⚠️ **`讨论team-agent::team` 是死队**（全行"错误"），投了没人看且照样返回 `ok: True`。**⛔ 不许凭记忆拼 team 名。**
- **投递纪律**：**同一根因【且无新信息】才算重复投递**；同一根因但**形状变准**或**带得出对照组**，
  那是把案卷补完整，**该报**。
- **跨 agent 往返硬上限：同一对方一天不超过 10 个往返。** 2026-08-23 与框架队已用掉 **5 个**。
- ⛔ **不为框架队做复现取证阶梯**（工程边界）。正常干活期间自然产生的观测可以报，**报完就走**。

### 外部通告状态

- 框架维护队已受理：`provider 身份配置是目录作用域 ⇒ 单 workspace 单席位` 的归类，
  以及"守卫是哪个版本进去的 / 是否覆盖 grok 席位"两个追查方向。**我方无需跟进。**
- ⛔ **现在不要用 npm 重装 team-agent**：npm 上 `@team-agent/installer` 仍是 0.5.66，
  0.5.67 未发布（CI 还有红），重装会退回**没有 coordinator 修复**的版本。等对方发版通知。
- **9 条未投递的编排优化点**积在 `.team/artifacts/ledger-trial-findings.md`。
  新增一条待投：**驱动器收工通知会重复投递 4 份，且携带已被取代的 revision 反复重放**
  （同一驱动器进程，rev 18 的通知与 rev 16 的通知同时在投）——
  这比"重复投递"形状更准（**重放过期 revision 会让消费者按已不成立的事实行动**），够格投。

---

## §6 安全约束（原文保留，⛔ 不可弱化）

- 密钥只存在于 `.team/current/profiles/*.env`，**任何席位禁止读其原文**。
- **`.team/current/profiles/tailnet-test.env` 全员禁读（含 leader）**。里面是用户 tailnet 的 auth key，
  只能通过 `TS_AUTHKEY` 环境变量注入测试节点，**任何形式的 cat/grep/plist/Read 都禁止**。
  取值只用 `set -a; . <file>; set +a` 注入子进程，**不打印、不落日志、不入截图**。
- **查任何配置前先想凭据**：`grep -i tailscale` 一个"偏好设置"文件就把 authkey 打上了屏（2026-08-13 实发，已请用户轮换）。
  同类禁令：**无过滤 `ps aux`**（暴露席位 API key）、**`tail .team/logs/agentmirrord-prod.log`**（daemon 明文打配对 token）。
  **Shadowrocket 的偏好 plist 与 `tailscale_keys.bin` 列入禁读。**
  进程只取 `ps -o pid,ppid,etime,stat,comm`，⛔ **不取 argv**。
- **凭据已泄露 ≠ 停工**（2026-08-13 用户裁定，2026-08-14 重申并批评过一次违反）：
  再次泄露时**只做三件事：一行上报（⛔ 不复述泄露的值）、就地收紧做法、继续干活**。
  **禁止**因此停工、禁止等新 key、禁止把删本地产物当成风险处置——
  片段一旦进入上下文就擦不掉，删截图减少的是执行者的不适而非真实风险。轮换与否是用户的事，不是开工前置条件。
- **起隔离 tmux 后必须自检"我在自己的 socket 上"**：`tmux` 建 socket 失败时**不报错，静默回退到默认 socket**
  ——也就是用户的真实 tmux。两条已实证的回退路径：① `TMUX_TMPDIR` 路径过长（unix socket 上限 ~104 字节）；
  ② `TMUX_TMPDIR` 目录**未预先存在**。**唯一可靠的不变量是自检**：
  `mkdir -p /tmp/e2e-<名>`（短路径且预建）→ `unset TMUX` → `tmux -S <sock> new-session -d`
  → `tmux -S <sock> list-sessions` 自检，会话必须在自己的 socket 上，否则立刻停手。
- ⛔ **席位不许写 `/tmp` 或任何项目外路径**，临时文件写 `.team/nodes/<格>/tmp/`。
- ⛔ **不许碰用户真实 tmux**；测试 daemon 会扫真实舰队 ⇒ app 里 ⛔ 不许点开真实会话。⛔ 不碰 9900 生产 daemon。
- 给席位发消息**只走 `team-agent send`**，⛔ 禁 tmux `send-keys`。
- ⛔ **不写 `Co-Authored-By: Claude`**（用户裁定 Contributor 应该是他本人）。
- ⛔ **禁写 memory**；⛔ **禁用 AskUserQuestion 工具**（一两句话能说清的直接在对话里问）。
- ⛔⛔ **禁 Deepseek 模型**、⛔⛔ **禁 Fable 5**、⛔⛔ **禁开 Opus 席位**。
- ⛔ **席位不许** `git commit` / `push` / `checkout` / `restore` / `worktree add`；封版与并线是 leader 的独立动作。
- **`ok: True` 不是送达**；投前 `team-agent status` 验活；⛔ **不许凭记忆拼地址**。
- ⛔ **绝对不要删 `~/.team-agent/runtime/0.5.67.broken`**，不要对 runtime 目录做无脑 prune（kalloc.1024 泄漏，见 §3-A）。

---

## 附 · 关键坐标速查

| 项 | 值 |
|---|---|
| 仓根 | `/Volumes/nvme/Projects/远程Agent安卓` |
| 当前 HEAD | `a0681c9b7`（main） |
| 稳定基线 tag | `baseline-20260822-release`（真机金标准已过） |
| 基线 APK md5 | `0907d6881bb1e034ef33a49f89afaa44`（35044459 bytes，⛔ 仅本地对比不可分发） |
| A 包（源码 composite） | md5 `aecdbd461deece5daec8f81c70af8e54` |
| B 包（maven 引用式） | md5 `3ebc9c55703c780c842a2f410b85034e` |
| 三核 maven 坐标 | `dev.agentmirror.core:{core-protocol,core-terminal,core-conn}:20260822.0` |
| maven 分支 URL | `https://raw.githubusercontent.com/Florious95/corral-core/maven/` |
| 远端仓 | corral-core（public）/ corral-app（private）/ corral-serve |
| team 私有 tmux socket | `/private/tmp/tmux-501/ta-b7cc1c640ccf` |
| coordinator pid | `94790`（`lsof txt` 必须是 `.../0.5.67/` 不带 `.broken`） |
| 唯一可用席位 | `ca-rv2`（cursor，`cursor-grok-4.6-high`） |
