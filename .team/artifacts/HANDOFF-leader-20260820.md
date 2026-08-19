# 交接文档 · 远程Agent安卓 leader · 2026-08-20 00:25 CST

> 落盘路径固定：`.team/artifacts/HANDOFF-leader-20260820.md`（同日再写覆盖本文件）
> 本文写给**刚接手、没看过过程的人**。所有路径/sha/名字写全，不用简称。

---

# §0 compact 后先做什么

## 0.1 一句话现状

账本 **`.team/ledgers/vz-v1.json`（revision 10，五格串行）**正在跑，
第一格 `t.glyph` 席位已交货、驱动器正在跑判据。
剩下四格排队。**五格全绿后出 APK 放桌面，用户 2026-08-20 白天手动验收。**

## 0.2 开口第一句（对用户说这句，不要泛泛报现状）

> `vz-v1` 五格跑到第 N 格（`t.glyph`/`t.bg`/`t.chrome`/`t.diff`/`t.ver`）。
> 昨晚你报的 14 条已全部写进任务书和判据，验收席 `vz-v1-ver` 会在实现全绿后逐条复核并出报告。
> 现在 [已全绿并出包 `~/Desktop/agentmirror-*.apk` / 卡在第 N 格，原因是 X]。要不要我先出一版给你装？

## 0.3 必读清单（按顺序）

1. **本文件**
2. `/Volumes/nvme/Projects/远程Agent安卓/CLAUDE.md`（项目铁律）
3. `/Users/alauda/.claude/CLAUDE.md`（全局铁律；**注意其中「投递前后各做一步，`ok: True` 不是送达」那节，2026-08-19 血案**）
4. **契约（本轮的需求真相源，按编号读）**：
   - `requirement-base/entries/083-真机视觉收口六条.md` ← **本轮主契约**
   - `requirement-base/entries/084-输入框差分同步.md`
   - `requirement-base/entries/081-回前台重连后终端重排错乱.md`（已修，回归用）
   - `requirement-base/entries/080-Compose设计包落位.md`（设计包落位裁定）
5. `.team/issues/清单-20260819.md`（用户实测问题清单 + 全部截图索引）
6. **Skill**：`ledger-orchestration-trial` → 它再调 `ledger-orchestration`；判活用 `tmux-node-activity`

## 0.4 恢复工作流程（编号步骤，照做）

### 步骤 1 — 先核对，后开口（⛔ 本文写的是落笔那一刻，可能已过期）

```bash
cd /Volumes/nvme/Projects/远程Agent安卓
git log --oneline -1                                   # 基线 sha
python3 -c "
import json;d=json.load(open('.team/ledgers/vz-v1.json'));print('rev',d['revision'])
for k,t in d['tasks'].items(): print(' ',k,t['state'])"
P=$(cat .team/nodes/_driver/drv-vz.pid); ps -o pid,etime,stat,comm -p $P   # 驱动器活着？
tail -3 .team/ledgers/vz-drive.log                     # 跑到哪一格
ls -lt .team/acclogs/ | head -5                        # 判据输出在不在长
lsof -nP -iTCP:9900 -sTCP:LISTEN | awk 'NR>1{print $2}'   # 生产 daemon
~/Library/Android/sdk/platform-tools/adb devices        # 模拟器
```

### 步骤 2 — 先恢复守护（⛔ 会话级的东西不会跟过来）

```bash
./.team/artifacts/orch-watch.sh > /dev/null 2>&1 &      # 停滞告警，必须重挂
```
它触发后会**退出**（那就是叫醒机制）。🔴 **每次收到告警，处理完必须立刻重挂**，
否则守护消失且无人知晓（2026-08-19 实撞：忘了重挂，park 40 分钟无人处理）。

### 步骤 3 — 判活只用一把尺

```bash
~/.local/bin/nodeprobe -S /tmp/tmux-501/ta-b7cc1c640ccf
```
⛔ **不许用 `team-agent status` 的 `worker_state` 判活**（已实测证伪）。
⛔ **不许手写 `ps`/`pgrep`/`find` 判活**（macOS `find -newermt` 恒返回 0，已坑过）。
🔴 `unknown` 绝不能当 `idle`；出现 unknown 先查 `~/.local/bin/nodeprobe` 是不是旧副本
（2026-08-19 实撞：全局命令停在旧构建，7 个席位被判 unknown；
`cd tools/nodeprobe && cargo build --release && cp /Volumes/nvme/cargo-target/release/nodeprobe ~/.local/bin/`）。

### 步骤 4 — 恢复期间的禁令

在步骤 1–3 做完之前：⛔ 不重启驱动器、⛔ 不重投派单、⛔ 不清理席位、⛔ 不改账本、⛔ 不开新账本。
**「席位全 idle」不等于停滞** —— 判据阶段席位本来就该 idle，干活的是驱动器。

### 步骤 5 — 判「恢复完毕」

同时满足才算接上：
- 驱动器进程活着，且 **（有子进程 或 `.team/acclogs/` 里有文件在 5 分钟内被写过 或 驱动器日志在 5 分钟内长过）**
- `nodeprobe` 输出 `unknown == 0`
- 停滞告警已重挂

### 步骤 6 — 与本文不符怎么办

**以现场为准**，把差异写进本文件（覆盖更新），⛔ 不要先问用户。
只有「现场显示某格已终态但产物不存在」这种自相矛盾才停下来问。

---

# §0.5 🔴 用户 2026-08-19 特别交代（原文，四条，逐条落实）

> 「1. compact 之后可能有很多东西忘了，**不要亲力亲为，一定要走全自动编排**。
> **一定不要让那些子节点发消息给你**。同时，如果说这个全自动编排阻塞，那就要**投递消息**，
> 叫期间也可以**收集一些全自动编排的优化点，记录成文档，不要直接上报**。就这些」

## 0.5.1 ⛔ 不要亲力亲为，一定走全自动编排

**含义**：需求来了 ⇒ 写契约 → 建账本 → `prep_ledger.py` → `ledger-run --drive --resident` → 席位干活。
⛔ **leader 不许自己改产品码**。

**唯一允许 leader 动手的三类**（2026-08-19 实践边界）：
1. **编排层**：账本 JSON、`tools/*.py`、契约文档、`.team/artifacts/*.sh`
2. **客观核验**：手工跑判据命令确认红绿（退出码权威）、亲眼看截图
3. **基础设施**：重启生产 daemon、起模拟器、换框架二进制、清孤儿进程

⚠️ **2026-08-19 有一次越界**：判据挂死时 leader 手工跑判据并把格子标 succeeded 放行
（`refresh-v1` 的 `t.srv`/`t.app`/`t.ver`）。那是**绕法**，代价已记进
`.team/artifacts/ledger-trial-findings.md`。框架 acceptance 挂死已修，**不应再需要这么做**。

## 0.5.2 ⛔ 一定不要让子节点发消息给 leader

**已落实的机制（继任者不用重做，但要维持）**：
1. **每个任务书末尾都有这段**（`prep_ledger.py` 不会自动加，是写账本时手工带的）：
   > 🔴🔴 **静默纪律（用户令：节点禁止给 leader 发消息）**：⛔ 不许 `team-agent send`、
   > 不发进度/提问/完工通知。唯一对外动作是干完调一次 `report_result`。卡住也不发消息，把卡点写进说明.md。
2. **`tools/prep_ledger.py` 的行为自证不再要求 `report_result`** —— 写出文件本身就是回执
   （2026-08-19 修：之前每建一个席位就漏 1 条消息到 leader 对话）。

🔴 **新建账本时必须把这段抄进每一格的任务书**，⛔ 漏了就会被打断。

## 0.5.3 编排阻塞 ⇒ 投递消息（但只投**阻塞**，其余只收集）

**判别**：账本推不动、驱动器挂死/无声退出、引擎拒绝求值、二进制起不来 ⇒ **阻塞**。

**动作顺序（⛔ 不许颠倒）**：① 先取证落盘 → ② 投递 → ③ **立刻绕行让活继续，不停工等回信**。

**收件人（⛔ 地址一律以此为准，不许凭记忆拼）**：
```
/Volumes/nvme/Projects/讨论team-agent::wiki/leader
```
🔴 **投之前必须先验对方 team 是活的**：
```bash
team-agent status --workspace '/Volumes/nvme/Projects/讨论team-agent' --team wiki
```
输出有「空闲/工作」才是活的；**每一行都是「错误」= 死队，投了没人看**。

⚠️ **2026-08-19 血案**：leader 把当天四封框架缺陷报告全投给了 `::team`（死队），
每封都拿 `ok: True` 当"已送出"向用户汇报。**`ok: True` 只代表消息被持久化，⛔ 不代表对方收到。**
同一 workspace 下 `wiki` 活着、`wiki-team` 和 `team` 都死了，名字像、投错无报错无痕迹。

**投递方式**：写成文件再 `"$(cat 文件)"` 传进 `send`，
⛔ 不要把报告内联进参数（正文里的 `<` `|` 会被 shell 当重定向/管道，整条命令挂掉）。

## 0.5.4 🔴 优化点：收集成文档，**⛔ 不直接上报**

**文件**：`.team/artifacts/ledger-trial-findings.md`（已有 F-01…F-14 + §0 导读）

**规矩**：非阻塞的编排问题（不好用、表达力不够、规范没写清、消息滞后、告警误报）
**一律只追加进这个文件，⛔ 不发**。用户说发才发。

**每条怎么写**（沿用已有格式）：现象（先写用户视角那句）→ 日志原文带量具身份（二进制 md5 + mtime）→
最小复现命令 + cwd + 期望 vs 实际 → 原因分析**及其边界**（查到哪儿为止、从哪步起是推测）。
🔴 **我方自己写错的也照样记**，但分两半写：① 我错在哪 ② **框架在这个错误上应该给出什么**。
第 ② 项才是给对方的（用户 2026-08-19 归因裁定：**任何链路中断一律算框架重大问题，
即使根因是使用者写错——那是易用性问题**）。

---

# §1 身份与不变量

- **我是 leader**，工程 `/Volumes/nvme/Projects/远程Agent安卓`，team key **`grok-l2`**，
  tmux socket **`/tmp/tmux-501/ta-b7cc1c640ccf`**。
- 需求真相源 = `requirement-base/entries/`（只增不删，推翻要留档写明成因与教训）。
- 任务状态真相源 = `.team/ledgers/*.json` + 席位产出 `.team/nodes/<node>/说明.md`。
- **验过才提交**：席位自报不算，leader 客观核过（跑判据 / 亲眼看截图）才算。
- **一次修复一个提交**，⛔ 不许攒（2026-08-12 有整条修复以未提交状态被回退抹掉的事故）。
- ⛔ **不写 `Co-Authored-By: Claude`**（用户裁定 Contributor 应该是他）。
- ⛔ **禁止写 memory**；⛔ **禁止用 AskUserQuestion 工具**（要问就在对话里一两句话直接问）。

## 1.1 判据的三条铁律（本工程反复踩的坑，⛔ 写判据前必读）

1. **判据要断言「世界变了」，不是「东西在那儿」。** 写完先拿它去跑坏状态，**判不红就是白写**。
2. **判据要能区分两个同形世界。** 例：「滚不动」vs「能滚但被裁掉」、「浮层没刷新」vs「浮层读错了源」、
   「布局把首列推出去」vs「首列画了但被 clip」。⇒ **量参与比较的两边原始数值，两个数都要记**。
3. ⛔ **不许用「三张截图 md5 互不相同」当判据** —— 它连图里画的是什么都没看。
   2026-08-19 实发：席位交了一张半透明过渡态截图（底部标签栏都没渲染出来），判据照样绿。
   ⇒ 改用 **`python3 tools/uiassert.py` 做 UI 树内容断言**（见 §4.9）。

## 1.2 🔴 模拟器在渲染层没有分辨力（2026-08-19 用户实测推翻旧裁定）

**用户原话**：「上面那个展示的问题，**在模拟器上是完全正常的**，但是我在**手机上就完全不正常**。」

⇒ 用户此前「模拟器 1:1 还原我手机」的裁定**在渲染层不成立**：
密度、字体 fallback 链、亚像素取整策略都不同。**模拟器绿 ≠ 真机绿。**

**处置**：渲染类判据**必须在整数密度与非整数密度各跑一遍**：
```bash
adb shell wm density 420    # 或 480
# ...跑判据...
adb shell wm density reset  # ⛔ 必须复位
```
🔴 **非整数密度那台才是守门员**；只有整数密度的绿**不算数**。

---

# §2 排期与封存令

## 已闭环（客观核过）

| 契约 | 内容 | 核验方式 |
|---|---|---|
| 068 | 节点白名单按进程 comm 过滤（一级+二级） | 模拟器实测 |
| 073 | 收藏身份键含 socket（跨工作区不再互串） | 用户截图三条 claude_code 目录各异、星独立 |
| 075 | 一级菜单转圈不回弹 | 模拟器三张不同现场 |
| 076 | §1 查看菜单按当前会话 ref 取数 / §2 横滑不抢竖滑+顶部空隙+删右上角设置 / §3 显示名+状态标+星移行首 | leader 亲验截图 |
| 077 | §1 会话页标题用显示名 / §2 判据改 UI 树断言 | `uiassert.py` 正反测 |
| 078 | §1 终端首列不被裁 / §2 主题（推翻 A 改 B：APP 自带深浅两套自适应） | 席位说明 + 截图 |
| 080 | Claude Design 的 Compose 包落位（13 个 .kt，零新依赖） | leader 审 import + 亲验截图 |
| 081 | 回前台重连重排错乱（补 cols 仪表） | `land-v1` `t.reflow` 绿 |
| 082 | 收藏页按各工作区取数（非加刷新按钮） | `land-v1` `t.list` 绿 |

## 在途

**`vz-v1` 五格**（详见 §4）—— 本轮唯一在途账本。

## 未开工（已立契约，排在 vz-v1 之后）

- **079 会话页触摸点击转发 SGR 鼠标事件**（🟡 用户裁定「先记录，是之后的目标」，⛔ 本轮不做）

---

# §3 P0 / 插队项

**当前无 P0。** 2026-08-19 的 P0（框架 acceptance 阶段挂死）已由框架队修复并装机：

| 二进制 | md5 | mtime |
|---|---|---|
| `ledger-run` | `5906b341e89b7f7b73ed5bb77473fa06` | 2026-08-19 17:51 |
| `ledger-eval` | `a6709fe704eec378ecc50e7f605dfa60` | 2026-08-19 17:51 |

**核验命令**：`md5 -q $(command -v ledger-run)`

⚠️ **框架 leader 明确表示他不认为 F-08 已修完**（形状不同：我方观测到零子进程 + 输出文件从未创建 ⇒
挂点在派生子进程之前，可能是第二个挂死点）。他要三样材料才能复现：
① `refresh-v1` 账本原文 ② `t.srv` 完整判据定义 ③ **四次挂死时 `/tmp/rf-advisor/agentmirrord` 有没有孤儿在跑**。

🔴 **第 ③ 条我方必须照实回答：有。** 时间线已核：
四次挂死分别在 2026-08-19 的 09:55 / 06:45Z / 07:22Z / 07:54Z，
而 leader 清理 12 个孤儿（最老 6h10m）是在 07:0x–15:xx 之间。**⇒ 四次挂死时孤儿都在跑。**
我方自己在 F-14 里写过「探针留孤儿会让判据阶段看起来像挂死，外部形状与真挂死完全同形」
⇒ **那四次可能是假挂死**，这一点必须照实说，⛔ 不许为了保住已报的 P0 而含糊。

**继任者要做的**：把 ①②③ 发给 `/Volumes/nvme/Projects/讨论team-agent::wiki/leader`（先验活）。
材料都是现成的，⛔ **不用做复现实验**（CLAUDE.md：禁止为框架队取证）。

---

# §4 在途任务：`vz-v1` 五格（**本节是继任者的主要工作面**）

## 4.0 一览

| 项 | 值 |
|---|---|
| 账本 | `/Volumes/nvme/Projects/远程Agent安卓/.team/ledgers/vz-v1.json` |
| revision | **10**（落笔时） |
| 驱动器 pid | 见 `.team/nodes/_driver/drv-vz.pid`（落笔时 **91985**，起于 2026-08-19 15:33Z） |
| 驱动器日志 | `.team/ledgers/vz-drive.log` |
| 判据输出 | `.team/acclogs/A-<格前缀>-<判据名>.log` |
| 席位产出 | `.team/nodes/vz-{glyph,bg,chrome,diff,ver}/` |
| 依赖 | **严格串行**：`t.glyph → t.bg → t.chrome → t.diff → t.ver` |
| 为什么串行 | 五格都写 `app/`，本工程铁律「同一 Gradle 模块同一时刻只放一席施工」 |
| 主契约 | `requirement-base/entries/083-真机视觉收口六条.md` + `084-输入框差分同步.md` |

**驱动器起法（若挂了）**：
```bash
cd /Volumes/nvme/Projects/远程Agent安卓
ledger-run --preflight .team/ledgers/vz-v1.json          # 必须 ok:true（⛔ 固定在仓根跑）
nohup sh -c 'exec ledger-run --drive --resident .team/ledgers/vz-v1.json' > .team/ledgers/vz-drive.log 2>&1 &
sleep 16; tail -2 .team/ledgers/vz-drive.log
ps -Ao pid,etime,comm | awk '$3=="ledger-run" && $2 ~ /^0?0:[0-3][0-9]$/ {print $1}' | head -1 > .team/nodes/_driver/drv-vz.pid
```
🔴 **改账本不必再停驱动器**（框架 L-stale 已修，bump revision 即可，驱动器会重读）。
⛔ 但「不 bump 直接改结构」仍会停机，那个行为是对的。

## 4.1 各格的判据 ID 与命令（**继任者核红绿直接抄这些**）

每格五条判据，命名规律 `A-<前缀>-{test,suite,ui,shot,doc}`：

| 格 | 前缀 | 单测过滤器 | 产出目录 |
|---|---|---|---|
| `t.glyph` | `gl` | `*GlyphSeam*` | `.team/nodes/vz-glyph/` |
| `t.bg` | `bg` | `*TermBgRemap*` | `.team/nodes/vz-bg/` |
| `t.chrome` | `ch` | `*ConsoleChrome*` | `.team/nodes/vz-chrome/` |
| `t.diff` | `df` | `*DiffSync*` | `.team/nodes/vz-diff/` |
| `t.ver` | `vr` | `*VzVerify*` | `.team/nodes/vz-ver/`（产物是 `验收报告.md`，不是 `说明.md`） |

**五条判据的固定形状**（`{X}` 代入前缀，`{N}` 代入产出目录名）：
```bash
A-{X}-test   sh -c "{ cd <仓根>/app && ./gradlew -q testDebugUnitTest --tests '<过滤器>' ; } > <仓根>/.team/acclogs/A-{X}-test.log 2>&1"
A-{X}-suite  sh -c "{ cd <仓根>/app && ./gradlew -q testDebugUnitTest ; } > .../A-{X}-suite.log 2>&1"
A-{X}-ui     sh -c "{ bash <仓根>/.team/nodes/{N}/ui-check.sh ; } > .../A-{X}-ui.log 2>&1"   # 席位自己写的探针，改前红改后绿
A-{X}-shot   python3 -c "glob 该目录下 *.png 非空即 0"
A-{X}-doc    test -s <仓根>/.team/nodes/{N}/说明.md
```
🔴 **判据命令全部被 `{ ... } > 日志 2>&1` 包过**——这是绕「acceptance 阶段挂死」的绕法
（让 sh 自己重定向，子孙进程不再持有驱动器管道的写端）。**代价：判据失败诊断里看不到 stderr，
要去 `.team/acclogs/` 翻。** 框架修好后应拆掉这个绕法。

## 4.2 `t.glyph` — 框线断点 + logo 黑缝 + 日志刷屏（**落笔时正在跑判据**）

**用户原话**：「线段中间有断点」「Claude Code 那个图标存在黑底……长得很别扭」
「有必要把日志，这些重复日志，让它减少打印」

**根因（已定位，写在任务书里）**：框线 U+2500–257F 与块元素 U+2580–259F 在 `GlyphSlot.kt`
走**同一条 `SYSTEM_FALLBACK` 路径**，该路径是 `TermSurfaceView.drawCentered` —— **逐格把字形在格内居中**：
```kotlin
val actual = paint.measureText(text, i, j)   // 字形自然宽度 < 格宽
centeredGlyphX(x, cellPx, actual)            // 居中 ⇒ 两侧各留 (cellPx-actual)/2
```
⇒ 框线接不上（断点）、色块接不上（露背景 = 用户说的"黑底"）。
🔴 **准确根因**：绘制依赖「字形自然宽度 + 亚像素取整」，**在非整数密度上必崩**；居中只是放大器。
这解释了模拟器（density 整数 3.0）看不出、真机（非整数）满屏是缝。

**修法（已定，⛔ 不许改成 `textScaleX` 拉伸字形）**：这两类字符**用 Canvas 画几何**：
块元素 `█`整格/`▀`上半/`▄`下半/`▌`左半/`▐`右半/`░▒▓`按密度；框线 128 个字符映射成「本格画哪几条边+粗细」，
**格边界按整数像素对齐**。✅ 附带收益：免疫换字体。

**判据要点**：
- `A-gl-seam`：相邻格之间**无背景色像素**；🔴 要能区分「缝」和「字形本来就细」⇒ 量**相邻格绘制矩形边界坐标**（`rect[n].right == rect[n+1].left`）。
- 🔴 **必须整数密度与非整数密度各跑一次，两组读数都写进说明**。
- `A-gl-quiet`（日志去重）：连续 10 秒正常重绘 `[term-left-edge]` **≤ 3 条**；**且**人为改一次 viewW 后**必须立刻出现新记录**。
  🔴 两条断言缺一不可 —— 只断言"变少了"会诱导把仪表整个关掉。
  ⚠️ **修法不是删仪表**（081 刚立了补仪表的规矩），是**只在操作数变化时记** + `verdict` 从 OK 变非 OK 必须立刻打。

## 4.3 `t.bg` — 显式背景色不受主题控制

**用户原话**：「Claude Code 它黑底白底唯一的问题，那就是**我发的消息，它依然是白底，不是灰底**。
然后 **grok 的黑底，在白色的手机主题上，它不会展示为白色**。」
追加：「**grok 它的背景是灰底，不是纯白**，使得它不太好看。」

**机理**：翻转的是**默认背景**；CLI 用**显式背景色**画的块不受影响。

🔴 **第一动作是查清是哪种颜色，⛔ 不许猜着改**：
| 类型 | 能不能翻 |
|---|---|
| ANSI 索引色（16/256） | ✅ 能重映射 |
| 反显 `ESC[7m` | ✅ 能特判 |
| **24 位真彩色 `ESC[48;2;r;g;b`** | ❌ 翻不了（`TerminalColor.Rgb` 原样画），只能**亮度守卫** |
⇒ 抓一次原始字节（服务端帧或 `tmux capture-pane -e`）定案。⚠️ Claude Code 与 grok 可能不同种。

**grok 灰底那条的具体怀疑**：**整屏背景**应落到 `TerminalPalette.background`（近白 `0xFFF7F8FB`），
⛔ 不该走 ANSI 0 号那条"浅底暗格"（`0xFFE7EAF0`）—— 那个值是给**局部色块**的，混用就是"整屏灰"。

**判据要点**：断言的是**相对关系**（消息块底色 ≠ 整体底色且更深），
⛔ **不许只断言「背景是浅的」——把块也刷成同色照样能变绿，那是骗判据**。
另断言 `adb shell cmd uimode night yes|no` 切换前后**真的变了**。

## 4.4 `t.chrome` — 十条子项（**本轮最重的一格**）

| § | 内容 | 关键约束 |
|---|---|---|
| §3 | 两层间距 **22dp → 10dp**（卡片外 8→4dp，终端内 14→6dp） | 🔴 **必须同时加 cols 上限**，否则 padding 减少 ⇒ cols 变多 ⇒ CLI 切回宽布局（用户真机上出现过的 Tips 双栏） |
| §4 | 删「已发送」（`SessionScreen.kt:486`） | 🔴 **只删成功态，保留失败态**；判据要断言「成功后读不到"已发送" **且** 失败仍能读到错误提示」——防的是把整个必达状态机删掉 |
| §5 | 输入框改 `TextFieldValue` 重载 | 现在用的是 String 重载，光标每次外部回写被重置到末尾 ⇒ 不能往前编辑。判据：光标移中间 → 外部更新 → **selection 不变** |
| §6 | `LanPill` 写死 `"LAN"`（`CommonUi.kt:188`） | **这是设计落位引入的回归**，旧包显示 `tailnet` 才对。值必须来自连接层真实状态，⛔ 不许按"能不能连通"猜 |
| §7 | 重连提示位置（现在在状态栏与标题之间） | 挪进**内容区顶部**；判据断言顶边 ≥ 标题栏底边，**且列表首行 y 不变**（无跳动） |
| §8 | 顶栏 `‹` 与标题**光学对齐** | 🔴 断言**墨迹包围盒**中心差 ≤1dp，⛔ 不许断言布局盒（布局盒本来就"对齐"，那条判据恒绿）。倾向改用矢量 Icon |
| §9 | 系统弹出组件不跟随设计（拍照/相册菜单是默认紫） | **根因**：`Theme.kt:19` 的 `lightColorScheme(...)` 只覆盖 15 个槽位，**缺 `surfaceContainer*` 系列**，而 DropdownMenu/Dialog/Snackbar 用的正是它们。🔴 **一处修全部跟上**；顺带扫全所有槽位，判据断言每个槽位都不等于框架默认值 |
| §10 | 顶栏指示灯不实时（要切页才变色） | 🔴「切出去再切进来才对」= **只在进入时取一次快照、之后不订阅**，和 076 §1、082 同族。判据里**禁止任何切页动作** |

## 4.5 `t.diff` — 输入框差分同步（契约 084）

**用户选了方案 C 并要求实时**：「我希望它是**比较实时的**，而不是我发送那一刻，它在同步。」

**算法**：`prefix = 公共前缀长度(已同步, 当前)`；发 `BackSpace × (len(已同步)-prefix)` + `当前[prefix:]`。
- 🔴 **常见路径零代价**：纯追加时退格数 0，键序与今天逐键直通**完全一致**。
- 🔴 **补全菜单必须保住**：CLI 仍收逐键输入，`/` `@` `Tab` `↑↓` 照常。⛔ 不许改成"发送时整行提交"（用户已否掉）。
- **实时是硬要求**；唯一允许延迟的是**中文输入法组合期**（`composition != null`）：组合期攒着、上屏立即发。
- ⚠️ **先量再决定要不要合并**：量「单次编辑发出的按键数」与「CLI 侧重绘耗时」；若加合并，**延迟上限 50ms**。

## 4.6 `t.ver` — 独立验收席（🔴 **⛔ 不复用任何实现席**）

用户 2026-08-19 令：「时间太久了，那还是**把测试加上**吧。我明天再来装 APP 手动验收。」

**席位**：`vz-v1-ver`（`seat_policy: fresh`）。⛔ 复用实现席 = 自己验自己。
**任务书里已写死**：⛔ 不许改产品码让判据变绿；不通过就报 fail **让账本红着**。

**它必须交的**（`.team/nodes/vz-ver/验收报告.md`）：
- **A** 本轮 14 条逐条 通过/不通过 + 证据（UI 树断言输出、采样像素值、截图文件名）
- **B** 🔴 **多密度复核**：渲染类每条在整数与非整数密度各跑一遍，**两组读数都写进报告**（缺一即不通过）
- **C** 回归不倒退：073 / 075 / 076 三条 / 077 §1 / 078 §1 / 081 / 082 逐条断言

## 4.7 五格全绿之后的收尾动作（继任者照做）

```bash
cd /Volumes/nvme/Projects/远程Agent安卓
# 1) 客观核（⛔ 不信席位自报）
cd server && go test ./... -count=1 ; cd ../app && ./gradlew -q testDebugUnitTest ; cd ..
# 2) 读验收报告，逐条看有没有"不通过"
cat .team/nodes/vz-ver/验收报告.md
# 3) 提交
git add -A app/ .team/nodes/vz-* .team/ledgers/vz-v1.json && git commit -m "<一句话说清修了什么>"
# 4) 出包（本轮未改 server/，⛔ 不必换 daemon；若改了 server 则先换 daemon 再出 APK）
cd app && ./gradlew -q assembleDebug && cd ..
cp app/app/build/outputs/apk/debug/app-debug.apk ~/Desktop/agentmirror-vz-$(git rev-parse --short HEAD).apk
# 5) 停驱动器 + 清席位（land-v1/vz-v1 全部实现席，⛔ 保留 advisor）
```
**告诉用户**：包名 + **验收报告里"不通过"的条目照实列出**，⛔ 不许为了出包放水。
⚠️ 装新包前提醒用户**在设置里重新配对**（换过 daemon 时尤其）。

## 4.8 建**新账本**的标准流程（下一轮需求来了照这个做）

```bash
cd /Volumes/nvme/Projects/远程Agent安卓
# ① 先写契约到 requirement-base/entries/<编号>-<短名>.md（编号接着 084 往后）
# ② 建产出目录
mkdir -p .team/nodes/<node1> .team/nodes/<node2>
# ③ 写账本 JSON（结构照抄 .team/ledgers/vz-v1.json，改 ledger_id / tasks / roles / dependencies）
# ④ 预检（⛔ 固定在仓根跑，artifacts 相对路径按 cwd 解析）
ledger-run --preflight .team/ledgers/<新账本>.json
# ⑤ 编知识基底 + 建/复用席位（基底会**前置内联**进任务书）
python3 tools/prep_ledger.py .team/ledgers/<新账本>.json --team grok-l2 \
    [--reuse <格id>:<已绿席位名>] [--suffix -r2]
# ⑥ 起驱动器（见 4.0）
# ⑦ 挂停滞告警
./.team/artifacts/orch-watch.sh > /dev/null 2>&1 &
# ⑧ 登记
printf '%s\n' "$PWD/.team/ledgers/<新账本>.json" > .team/ledgers/ACTIVE
```

**账本 JSON 必填项**（缺一个 preflight 就拒）：
`schema_version:"ledger.v2"` / `ledger_id` / **`revision`（首份写 1）** / `run.desired_state` /
`roles`（每个含 `seat:{agent,team}`；fallback 角色 `r.advisor` 不能与任务 owner 同角色）/
`tasks`（每格必须有 **`state:"planned"`**、`resources.worktree_id`、`resources.write_paths`、
**`resources.environment_fidelity`**（有机械判据时必填）、`handoff.required_artifacts` 用绝对路径）/
`dependencies` / `parallelism:[]` / `transitions:[]` / `handoff` / `acceptance` / `fallback` /
`evidence_policy` / `resource_isolation` / `fanout_aggregation:{}` / `audit:{}`
⚠️ **产物父目录必须先存在**，否则 preflight 拒「父目录不存在」。

**每格任务书必须带的四段**（⛔ 漏了就会出事，直接从 `vz-v1.json` 抄）：
1. **仓根仲裁条款**（最高优先级，压过派单正文下方框架自动生成的「## 工作目录」那段）
2. **静默纪律**（§0.5.2）
3. **判据形态**（`uiassert.py` 内容断言 + `ui-check.sh` 探针先红后绿 + ⛔ 不许 md5 判据）
4. **多密度要求**（渲染类）+ **⛔ 不得倒退清单**

## 4.9 `tools/` 下继任者要用的工具（都已落地并正反测过）

| 工具 | 干什么 |
|---|---|
| `tools/uiassert.py` | UI 树内容断言：`dump` / `has A B` / `absent A` / `distinct N --among …` / `save <path>`。取不到 UI 树时**响亮失败** |
| `tools/prep_ledger.py` | 每格编知识基底（**前置内联进任务书**）+ 建/复用席位 + 行为自证 + 写回账本并 bump revision |
| `tools/basegen_ledger.py` | 从账本任务的 `write_paths` 现算模块影响闭包；**write_paths 命不中代码包时回退用 `read_paths`** |
| `tools/save_issue_shot.py` | 把用户贴在对话里的截图从会话记录里提出来落盘（`-n 1 -o 名字`） |
| `.team/artifacts/orch-watch.sh` | 停滞告警：判活只用 nodeprobe；**分阶段**（等席位 vs 跑判据）；`unknown>0` 直接告警 |
| `.team/artifacts/heartbeat-check.py` | 心跳巡检，读 `.team/ledgers/ACTIVE` |

🔴 **`prep_ledger.py` 的两个已知坑（已修，但要知道为什么）**：
- 模板固定为 `.team/grok/agents/dev-app.md`（**已纳入版本控制**）。
  ⛔ 清理退役席位时**不要删它**；⛔ 不要拿 `advisor.md` 顶替（它 tools 缺 `mcp_team`，新席位调不了 `report_result`）。
- **终态格自动跳过**（succeeded / failed_terminal / not_applicable），
  否则会去戳早已收工的席位做行为自证，席位没响应会把整条准备流程卡死。

---

# §5 运维与外部

## 5.1 基础设施现状（落笔时已核）

| 项 | 状态 |
|---|---|
| 生产 daemon | `:9900` 在听，pid 19107（重启后由 leader 起：`cd server && nohup sh -c './agentmirrord > /tmp/amd-boot.log 2>&1' &`，起完核 `grep -c 'whitelist loaded' /tmp/amd-boot.log` 必须为 1） |
| 模拟器 | `emulator-5554`（AVD `agentmirror_geo_1260x2800`），起法：`nohup ~/Library/Android/sdk/emulator/emulator -avd agentmirror_geo_1260x2800 -no-snapshot-load -no-boot-anim &` |
| 桌面最新包 | `~/Desktop/agentmirror-design-0c05082e4.apk`（2026-08-19 23:02，设计落位版） |
| 席位 | advisor / land-v1-{set,term,reflow} / vz-v1-{glyph,bg,chrome,diff,ver}，共 9 个 + leader |

⚠️ **重启生产 daemon 无需用户确认**（用户裁定「什么时候断服务端都可以」）。席位仍禁止碰生产 daemon。
⚠️ **模拟器用完要关闭**（用户令）；起之前看内存（曾出现 free 只剩 59MB、load 29）。

## 5.2 资源与卫生

🔴 **`probe-rf.sh` 曾漏 daemon/node 孤儿**（一天攒 12 个，最老 6h10m），已修
（node 成功路径加 `process.exit(0)` + daemon 加 `trap` 收尾，900s 超时 → 8.9s）。
**巡检命令**：`ps -Ao pid,comm | grep -c "/tmp/rf-advisor/agentmirrord"` 应为 0。

## 5.3 外部通道

| 对象 | 地址 | 用途 |
|---|---|---|
| **全自动编排框架 leader** | `/Volumes/nvme/Projects/讨论team-agent::wiki/leader` | 账本编排/ledger-run 的阻塞项。🔴 **投前先 `team-agent status --workspace ... --team wiki` 验活** |
| team-agent 框架维护 leader | `/Users/alauda/Documents/code/agent前沿探索/多agent协作::refactor-maintainability/leader` | team-agent 本体（投递/席位/tmux 注入）问题 |

**已投出且对方已确认收到**：`.team/artifacts/ledger-trial-findings.md` 全文（F-01…F-14 + §0 导读），
message_id `msg_c53d40eab2a8`，投到 `::wiki/leader`。对方回信要点：
- **F-12 他认了**（read_paths 那条指导是错的，端到端没验过）；修好后会通知我方拆掉「BASE.md 全文内联」这个绕法
- **F-03 已修**（通知 team key 改从账本取）
- **F-07 可放宽**：改账本不必再停驱动器，bump revision 即可
- **§0.3「拿介入次数当验收标准」他不采纳**（理由：验收标准必须框架自己能判），降级为健康指标
- 他的归纳：**「账本能表达出『不可满足』和『自相矛盾』，而没有任何一层负责回答『这张账本自洽吗』」**，
  第三波形状定为 A 账本自洽性 / B 派单装配单一事实源 / C 静默路径响亮出口

🔴 **跨 agent 往返一天硬上限 10 个**（一来一回算一个）。⛔ 不主动追问进度。

---

# §6 安全约束（原文保留，⛔ 不可弱化）

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
- **凭据已泄露 ≠ 停工**（用户裁定）：再次泄露时**只做三件事：一行上报（不复述泄露的值）、
  就地收紧做法、继续干活**。⛔ 禁止因此停工、禁止等新 key、禁止把删本地产物当成风险处置。
  ⚠️ 2026-08-19 实发一次：模拟器里 `adb shell input text "$TOKEN"` 填配对 token，
  **输入法候选栏把 token 明文显示在了截图里**。已就地收紧：**截图前先关输入法**（`adb shell input keyevent 111`）。
- **起隔离 tmux 后必须自检"我在自己的 socket 上"**：`mkdir -p /tmp/<短名>` → `unset TMUX` →
  `tmux -S <sock> new-session -d` → `tmux -S <sock> list-sessions`。
  **tmux 建 socket 失败时不报错，会静默回退到默认 socket——也就是用户的真实 tmux。**
- ⛔ **绝不触碰用户真实 tmux**（默认 socket），席位只读也不行。
  例外：leader 可对当前 socket 跑**只读**的 `nodeprobe` / `list-panes`。
- ⛔⛔ **不要 `git checkout` / `git restore` 任何文件**（工作树有大量未提交产品代码；
  2026-08-19 已因此误删过一份未提交语料）。
- ⛔⛔ **不要 `git worktree add`** —— `worktree_id` 在本工程只是并发互斥标签；必须在仓根干活。
- **不写 `Co-Authored-By: Claude`**（用户裁定 Contributor 应该是他）。
- **禁止写 memory**；**禁止用 AskUserQuestion 工具问用户**。
- 给席位发消息只走 `team-agent send`，**⛔ 禁 tmux `send-keys`**。
- ⛔ **禁止为框架队取证**（复现、取证阶梯、保留现场一律拒绝）。唯一配合项是换用他们发布的新基础设施。
  ⚠️ 例外：**现成材料**（账本原文、判据定义、我方已记录的事实）可以给，那不是"做实验"。
