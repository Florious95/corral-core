# 交接：远程Agent安卓 · leader · 2026-08-15 凌晨

> 本文覆盖 `HANDOFF-leader-20260814-evening.md`（那份的 §3-A 末尾「部署与源码分歧」警告**已消除**，见 §2）。
> 落笔前跑过客观核对：下面所有 sha / pid / 数字均为实测，不是席位自报。

---

## §0 compact 后先做什么

**一句话现状**：本轮五件事全部闭环并经用户真机验收（右边距、字号档位、上滑滚轮、上滑丢帧、发图内联+预贴）。
席位已全部退役（在册 0），工作区干净，daemon 已部署最新二进制。
用户 2026-08-15 00:2x 叫停：**「先停下来吧，我发现探索的东西有点多。你先写交接，直接交接，下一个角色再来讨论再来做。」**

**⇒ 本次交接的定位是：主动叫停，不是收尾。下一轮先讨论再动手。**

### 开口第一句（compact 后对用户说这个）

> 「上一轮五件事全闭环，你都真机验过了。**下一个问题是 Agent CLI 状态判定，你说「基本完全不成立」。**
> 我已经撞过库了，有两条你可能忘了的旧结论会直接决定怎么做——**先跟你确认这两条，再动手**：
> ① 你 2026-08-12 裁定过「还用字符串判状态就完全走偏了」，并让我们参考 herdr；
> ② 调研做完了（`docs/herdr-agent-state-study.md`，518 行），结论**否定了立案时的预期**——
> herdr 也是刮屏，没有进程/PTY 层的魔法信号，它强在**时序仲裁**不是规则。
> 还有一条你 2026-08-13 裁定但**至今没实现**：服务端取消「完成」态，只留 working/idle。
> 要不要就从这条没实现的裁定开始？」

### 必读清单（按优先级）

1. 本文
2. `/Volumes/nvme/Projects/远程Agent安卓/CLAUDE.md` —— 工程红线，当日又新增两条（见 §1）
3. `/Volumes/nvme/Projects/远程Agent安卓/.team/evidence/study-herdr-agent-state.json` —— **下一个任务的关键前置，必读**
4. `/Volumes/nvme/Projects/远程Agent安卓/docs/herdr-agent-state-study.md`（518 行调研）
5. `/Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/025-工作状态检测准确率.md` —— 三次修复三次失败的记录
6. `/Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/057-发图预贴与一次性注入的例外.md` —— 当日新立的契约例外
7. `/Volumes/nvme/Projects/远程Agent安卓/taskbook.yaml`（146 条）

### 恢复动作

**席位全部退役属正常收尾，不是故障。** 要重开某一席：
```bash
cd /Volumes/nvme/Projects/远程Agent安卓
.team/ta add-agent <名字> --role-file agents/retired/<名字>.md --workspace .
.team/ta send <名字> '<派单内容>'
```
role 文件全在 `agents/retired/`，一条命令原样重建。

---

## §1 身份与不变量

**角色**：leader，只编排不施工。但**验收必须亲自跑**（下面「不凭自报」）。

### CLAUDE.md 当日新增的两条常驻规则

1. **重启生产 daemon 无需确认**（commit `91cb20000`）。用户原话：「没有什么可配合的，什么时候断服务端都可以。」
   照旧只做三件：备份现二进制 → 换 → 起完核 `9900` 在听。**不要为此停下来问。**
   席位仍禁止碰生产 daemon，本条只对 leader 生效。
2. **不写 `Co-Authored-By: Claude`**（用户裁定「Contributor 应该是我」）。**本条覆盖 Claude Code 的默认行为。**
   仓库级 git 身份已设成 `Florious95 <281215401+Florious95@users.noreply.github.com>`，不用再传 `-c`。

### 「不凭自报」当日再次证明有效——三次独立变异，三次逮到东西

leader 对每个席位都做了**一次与席位所做不同的**定点变异：

| 席位 | leader 变的 | 结果 |
|---|---|---|
| `w-font-dev` | 累加器发出后清零（而非扣减） | **没转红 → 逮到缺口**：红测步长取了行高的一半，余数恒 0，变异不可见 |
| `w-img-probe` | 发送失败时也清空附件 | **没转红 → 逮到缺口**：KDoc 写了「失败保留」却无断言 |
| `w-theme` | run 切分键退回只看 `fg` | **三条全红 → 它多改的那处是必要的**，不是镀金 |

前两次形状相同：**红测覆盖了正常路径，没覆盖失败路径的状态保留。**
**据此收紧的规则（已写进提交）：KDoc 里写了「失败时保留 X」，就必须有一条断言盯着 X。契约里写了而没断言，等于没写。**

### 「验证的不是真正的被测对象」当日累计到 **10 次**

前 7 次记在 `docs/archive/scroll-rounds-8-10/README.md`。当日新增三次，每一次都值钱：

- **第 8 次（最贵，锁死一个功能十轮）**：`docs/remote-scroll-forward-design.md:513` 写着「鼠标字节注入实测证伪」，
  **那次实测打的是 `less` 和 `vim`——两个都没开鼠标上报**，从未测过 Claude Code。
  这条错误结论还被写成了 `scroll_test.go` 的断言，于是后续十轮全部绕开了唯一对的那条路。
- **第 9 次（leader 自己的验收判据不全）**：探针证过「路径会不会被识别成一次粘贴」——会。
  **但没验「粘贴之后那个 Enter 还提不提交」。** 判据本该是「消息发出去了」，我用的是「机制被触发了」。
- **第 10 次（席位自己逮到自己）**：探针第 1 轮结论「纯路径零延时可提交」用的是**不存在的假路径**，
  Claude Code 对读不到的文件瞬间失败、根本没进异步窗口。真实图片要解码/缩放/写缓存，那才是竞态窗口。

**动手前先问：我验的这个东西，就是用户在用的那个东西吗？**

---

## §2 排期与封存令

| 编号 | 缺陷/功能 | 状态 |
|---|---|---|
| ① 图片上传（蜂窝+TS 必失败） | 已闭环（更早轮次） |
| ② 最右列文字跑出屏幕 | **已闭环**，用户截图确认（commit `240ea6462`） |
| ③ 重进 CLI 输入框位置 | 已闭环（更早轮次） |
| ④ 上滑投送到远端 | **已闭环**，用户「丝滑，特别满意」（`f9a4cedf8` + `83d33ef8c`） |
| ⑤ TS token 后台回前台连不上 | 已闭环（更早轮次） |
| ⑥ 诊断日志 App 内展示 | 已闭环 |
| 字号档位下探 4/6/8/10 | 已实现（`306b61e02`），**用户未反馈 4sp 可读性** |
| 反显 SGR 7 | **已实现**（`01e638f21`），用户未专门验 |
| 发图内联 + 预贴 | **已闭环**，用户「确实很快」（`86ec49c0c`） |
| 终端主题 | **已关闭**：根因是用户自己的 Claude Code 主题，我方无 bug，见 §3-C |

**封存令（用户原话，仍然生效）**：
- 「暂时不能开模拟器」——⛔ 第 2 层安卓模拟器**仍暂停**，未解除。任何席位不得启动 emulator/qemu。
- 「commit 无需我确认，每个关键点都需要提交代码」
- 「没有什么可配合的，什么时候断服务端都可以」

**⚠️ 交接文档 `HANDOFF-leader-20260814-evening.md` §3-A 末尾那条「部署与源码分歧」警告，本轮已消除。**
生产 daemon 现在跑的就是 HEAD 编出来的二进制。**那条警告可以撤，不要再照着它排查。**

---

## §3 本轮做完的五件事（都经用户真机验收，不是自报）

### §3-A ④ 上滑：十轮后闭环，方向被推翻两次

**最终方案**：`mouse_any_flag=1` 时直接注入 SGR 1006 滚轮字节（`ESC[<64;col;rowM`），不进 copy-mode。
`mouse_any_flag=0` 时走 copy-mode——**这个分支判断是硬不变量不是优化项**：
实证裸壳收到这批字节会被打进 `64;40;20M` 字面量**污染用户正在敲的命令行**。

**符号链路经 leader 三段核对，本来就是对的，一行未改**：
手指下拖 `dy<0` → `TermSurfaceView.kt:144 deltaLines = -dy/行高` 为正 →
`SessionViewModel.kt:371 sendScrollWheel(-toSend)` 为负 → 协议 `delta<0=看历史` →
`injectWheelBytes(up=delta<0)` → SGR button 64。**两次取负不是 bug，是两层各自约定在对齐。**

**用户报「上滑无效」时代码其实已经修好**，无效的真因是部署与源码分歧（旧二进制里 `injectWheelBytes` 出现 0 次）。

补充：`83d33ef8c` 补了 View 层像素累加器（亚行位移不再被丢弃）。
用户实测「发现不了区别，但也没有其他问题，流畅」——**修的是守恒性，不是可感知的东西，无回归即达标。**

### §3-B 发图：三轮，中间回炉一次

- **第 1 轮**（`d249a7fca`）：`草稿+\n+路径` 拼进同一次 paste → **Enter 被吞，消息卡在输入框** → 用户裁定不可用，回炉（`e7febc2b3`）
- **根因探针**（`991004ec8`）：分界线不是「含不含 `\n`」，是**「trim 后的整段内容是不是能直接当绝对路径读的字符串」**。
  是 ⇒ Claude Code 走同步快分支；不是但结尾像图片文件名 ⇒ 它当成相对路径，**fork `osascript` 查剪贴板文件 URL 兜底**，
  墙钟几百毫秒到一两秒，Enter 在这段异步 I/O 完成前到达就被吞。这解释了 500ms 不够、2000ms 够。
- **第 2 轮**（`d597da97e`）：三步序列（贴纯路径 → 单独发文字 → 等 2s → Enter），能用但有 1 秒迟滞
- **第 3 轮**（`86ec49c0c`，需求 `057`）：**预贴**——上传成功即发 `AttachPreview{Ref,Path}` 贴路径并按 pane 记时间戳，
  点发送只送文字 + Enter。**沉降藏进用户打字的时间里。**

**端到端实测数字**：纯文字基线 **31.06ms** / 带图打过字 **33.04ms** / 带图选完立刻发 **2.04s**（补差额分支）。

**057 带来的两处语义变化，接手时别当成 bug**：
1. **不清理 CLI 残留**：选了图不发，`[Image #N]` 会留在会话输入框里。
   裁定理由：**占位符在 App 镜像里看得见，不是静默**；而「读懂 CLI 渲染文本再决定清不清」是一类**会静默失效**的依赖。
2. **附件由「单附件覆盖」改为「可累加」**：连选两张就是两张，显示「已附加 N 张图」，顺带支持多图。

### §3-C 终端主题：查到根因不在我方，已关闭

用户报「有白底有黑底不太正常」。三轮排查结论：

- **256 色映射逐行核对无误**（立方 `55+40v`、灰阶 `8+10i` 均合 xterm 标准）——leader 的假设被证否
- **Terminal.app 官方 Pro 深色预设前景是 (242,242,242)，比我们的 (232,232,232) 还亮** ——
  leader「我们偏亮要压一档」的判断也被证否
- **tmux 对裸 OSC 11 的行为已实测**：detached 无人答 → CLI 落暗色默认；attached → tmux 转发给客户端，由它的答案决定
- **最终**：用户一条 `/theme dark` 直接解决。**我方无 bug。**

**为什么没做 OSC 11 应答**（写清楚免得后人再走一遍）：CLI 显式设了主题就不再查询；
且「用户 Mac 上的真实终端也会答」是**固有矛盾非实现缺陷**——两端共用一个 pane、两块屏底色不同，
我们赢了竞态也未必对（他在 Mac 上看同一个 pane 会按手机底色渲染）。
tmux 有个原生钩子 `refresh-client -r`（man 点名 OSC 10）可代答，**已存档留待将来**，现在不做。

**顺带落地的真缺陷**：反显 SGR 7（`01e638f21`）——`:terminal` 模块解析早就对了，
是 `TermSurfaceView` 渲染时从不读 `style.inverse`，标记在渲染边界被静默丢弃。cursor-agent 实测在用它。
**装上后某些 CLI 会画出浅色高亮块，那是标准行为不是新 bug**，Terminal.app 自己也这样。

---

## §4 在途未收尾任务

> **无常驻进程在跑。席位已全部退役（在册 0），没有 pid 可查。**
> 进度信号 = 用户真机反馈 + `taskbook.yaml` 的 status 字段 + `.team/evidence/*.json`。

### §4-A 【下一个主线】Agent CLI 状态判定 —— 用户已提出，尚未开工

**用户 2026-08-15 原话**：「Agent 的 CLI 的状态判定，**基本上完全不成立，基本上完全不准确**。」

**⚠️ 这是第四次。前三次修复三次失败，已升级过用户讨论**（`requirement-base/entries/025-工作状态检测准确率.md`）。
**所以下一轮的第一件事绝不是再改一次 `rules.go`。**

**撞库已完成，三条旧结论必须先摆给用户看**（否则会第四次走偏）：

1. **用户 2026-08-12 裁定（taskbook `study-herdr-agent-state` 原文）**：
   > 「你假如说认为这两种，一个是完成态，一个是工作中态，还在通过这样的字符串形式去确定它的工作状态，
   > 那就**完全走偏了**。并且**这两个实际上都是完成的状态**。你可以通过 **herdr** 这个仓库去确定如何正确检测。」

   实证：Claude Code 完成时输出 `Brewed for 42m 3s`，也输出 `Churned for 3m 37s`，
   **两个都是完成态且前导同类星号字形**——字形与字符串层面根本分不出来。

2. **herdr 调研已做完，结论否定了立案时的预期**（`.team/evidence/study-herdr-agent-state.json`，
   产物 `docs/herdr-agent-state-study.md` 518 行，`w-librarian` 2026-08-13 产出）：
   > herdr 判定状态**也是「区域文本匹配 + OSC title/progress」为主，没有进程/PTY 层的魔法信号**。
   > 它比我们强的不是规则，是**时序/仲裁层**。立案时「herdr 托管 agent 进程所以能拿结构性信号」的假设**被证伪**。

   herdr 的状态模型是 **四态 Idle / Working / Blocked / Unknown，无 done**——「完成」在 herdr 里根本不是状态。

3. **⚠️ 用户 2026-08-13 给过一条裁定，至今未实现**（记在同一份 evidence 里）：
   > 服务端取消「完成」态，只保留 working/idle；App 侧：完成后标完成、点进去看过标空闲；服务端 idle 即空闲。
   > 且服务端 idle→working 时，App 需从「完成或空闲」一并转 working。
   > **该裁定已给出，尚未实现。**

**leader 当时据调研裁定的技术方向（也未实现）**：
- working/idle 判定不再依赖 done 类文本；字形白名单不再扩充（`✳`/`◐` 退场），working 保留 braille spinner
- 采纳**时序确认**（working→idle 需连续多次采样 + 时间窗），参数按我方采样周期推导，**不照抄 herdr 的 3 次/700ms**
- OSC 9 progress **降级为后置**：先落前两条（只依赖自己），误检消失后再看。
  **一般原则——优先做「只依赖自己」的判据。**

**相关 taskbook 条目**（都还是 todo）：
- `fix-agentstate-detection-d26` —— 立案时以为是「指示符换成 `◐` 检测没跟上」，**这个方向已被上面第 1 条否定**
- `fix-agentstate-anchor-region` —— 规则从「全屏 `strings.Contains` 扫描」改为「最后一个 `❯` 之后」的锚点区域。
  证据里写「已实现未部署」，**接手时必须先核实这句话是否属实**（`git log -- server/internal/agentstate/`）。

**当前代码现状**（leader 已核）：`server/internal/agentstate/rules.go:27`
仍有 `const spinnerFrames = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"` 字形白名单，`:209` 有关于 `esc to interrupt` 历史残留的注释。

**下一轮建议的第一步（leader 未与用户确认，仅建议）**：
**不要先改规则，先建 ground truth。** 现在无法回答「准确率是多少」——因为没有独立事实源。
先做一个能采集「此刻 pane 真实是什么状态」的对照集，把判据变成一个数（混淆矩阵），
否则第四次仍然是「改了、感觉好点、用户说还是不准」。**这是 leader 的判断，需用户点头。**

### §4-B `fix-paste-settle-signal` —— 已降优先级，非阻塞

那 2 秒沉降延时的三条缺口（`.team/evidence/fix-image-upload-input-box.json` 的 `known_gaps`）：
1. **无单测保护**：leader 把 `time.Sleep(pasteSettleDelay)` 整行删掉，**server 全部测试仍然全绿**。
   谁下次顺手清理掉它，门全绿而功能当场废。根因是时钟不可注入。
2. **2000ms 是实测阈值不是理论上界**（500ms 不够、2000ms 够）。更大的图/更慢的机器可能不够，
   **且失败时不报错、症状与修复前一模一样，会被误判成回归。**
3. `time.Sleep` 跑在 `ws_conn.go:347 handleFrame → handleInput` 的读循环里，发图后该连接帧处理阻塞约 2 秒。

**降优先级的依据**：057 预贴落地后，这 2 秒只出现在「选完图立刻发、不打字」这条路径上，
而用户明说「**基本上很少有纯图片的**」。缺口仍真实，但不紧迫。

**建议方向**：等一个明确的「粘贴已处理完成」信号（轮询 capture-pane 直到 `[Image #N]` 出现）替代固定 sleep，
三条一起解。**但轮询判据是 Claude Code 专属字面量，对别的 CLI 要有降级路径**，这是要先想清楚的地方。

### §4-C `decide-pane-reflow-wip` —— 一份来源不明的 WIP，等定夺

`PaneSizeChanged` 帧 + `Server.fanoutPaneReflow`（手机与 Web 同时订阅同一 pane 时的尺寸广播）。
代码完整（含契约注释、validate、往返测试、App 侧 `applyRemoteSize`/`noteRemoteSize`），
**但本轮无人派单、无人验收，来源未查明**。leader 已完整剥离并存档：
`docs/archive/pane-reflow-wip/pane-size-changed-wip.patch`，剥离后 server + app 双门复跑全绿，**未进生产**。

- **不直接丢**：它解决的「两端共用一个 pane、两块屏尺寸不同」与主题那轮的「两块屏底色不同」是同一形状，将来会重新撞上。
- **不直接合**：影响面是协议 + 双端渲染，改的是「谁说了算」这类归属规则，属契约级；当前用户只用 App 单端，收益零风险非零。

### §4-D 用户提过但未立项的两件

1. **App 缺「隐藏/过滤工作区」的能力**。
   现象：隔壁 team 每跑一次 team-agent 测试套件，用户的工作区列表就被几十条 `/private/tmp/...` 淹没。
   leader 已清理主机上 44 个陈旧测试 socket（30 分钟以上无活动的），**但这事会反复发生**。
   **leader 问过要不要立项，用户未答。**
2. **字号 4sp 在真机上是否可读**，用户未反馈。若不可读，要做的是**调档位表**，不是改 sp→px 映射（那条一个字没动，保持）。

---

## §5 运维与外部

### 生产环境（实测值，2026-08-15 00:2x 复核）

- **生产 daemon**：pid **4140**，`./agentmirrord -host 192.168.31.116`，`23:57:31` 起，监听 `0.0.0.0:9900`
  - 二进制含 `attach_preview` × 4，**与 HEAD 一致，无分歧**
  - 备份：`server/agentmirrord.bak-prePreview-*`、`agentmirrord.bak-preAttach-*`、`agentmirrord.bak-preScrollWheel-*`
  - 日志 `/tmp/agentmirrord-preview.log`（**只 grep 目标行，禁止 tail 全文**，见 §6）
- **席位**：在册 **0**，role 文件全在 `agents/retired/`
- **桌面唯一 APK**：`~/Desktop/agentmirror-attach-preview-86ec49c0c.apk`（用户要求桌面只留一个）
- **leader 自己的 tmux**：socket `/private/tmp/tmux-501/ta-b7cc1c640ccf`，会话 `team-agent-leader-claude_code-Agent-824c7e38-eb74-18cb609b095140f8`
  - 该会话 `window-size` 是 `manual`，窗口会缩回去；修复命令：
    `tmux -S /private/tmp/tmux-501/ta-b7cc1c640ccf resize-window -t team-agent-leader -A`

### 云端备份（本轮新建）

三个 GitHub 私有仓，**将来要开源**：
- `Florious95/corral-core` —— 当前 App 全部代码 + 需求维基 + 任务书 + `.team/evidence/`
- `Florious95/corral-serve` —— 服务端 daemon
- `Florious95/corral-app` —— 下一代 UI 的壳（目前只有 README + `docs/需求索引.md`）

**同步方式**：`bash tools/mirror-push.sh`（本地是单仓、远端是三仓，历史结构不同，**不能直接 `git push`**）。
脚本里带**凭据兜底闸**：过滤后历史里还能找到 `.env` 就中止不推。

**已知事实**：三个席位 `.env` 从基线 commit 就在**本地** git 历史里（`d6f450e16` 只停止跟踪没抹掉），
但 mirror-push 的过滤把它们挡在远端之外，**远端全树 `.env`/`profiles`/`tailscale_keys` 命中数 = 0**（已核）。
`tailnet-test.env` 从未入库。

**corral-app 的定位（用户裁定）**：core 是**权限边界**不是复用边界。
用户原话：「APP 的相关改动，它**不能够自行的去修改核心的代码**。」
边界近似判据：**`@Composable` 是壳，其余是核**（`termview/` 1532 行零个 `@Composable`）。

### 跨团队

`refactor-maintainability` team（workspace `/Users/alauda/Documents/code/agent前沿探索/多agent协作`）直报通道：
```bash
team-agent send '/Users/alauda/Documents/code/agent前沿探索/多agent协作::refactor-maintainability/leader' '<内容>'
```
**硬上限：同一对方一天不超过 10 个往返。** 本轮未使用。

**有一件框架问题值得报但 leader 未报**（下一轮可自行决定）：
team-agent 新增必填字段 `dangerously_skip_permissions` 后，**`remove-agent` 会在 spec compile 阶段
校验持久化的 `team.spec.yaml` 与全部角色文件，任一缺字段就整体失败，导致席位清不掉**。
用户给的解法一次就过（已记入 commit `f02df2278`）：
`shutdown → 补齐字段（含 .team/runtime/<team>/team.spec.yaml 与 agents/ 及 agents/retired/ 下全部 90 份） → restart → remove`。
**⚠️ restart 前必须把要退役的席位角色文件临时放回 `agents/` 根目录**，否则报 `dynamic role file missing`；
顺序是：先 restart 成功 → 再 remove → 最后归档到 `retired/`。

### 本机资源

`/private/tmp/tmux-501/` 下 socket 已从 162 清到 **13**（清掉 44 个 30 分钟以上无活动的测试残留 + 更早清的 124 个）。
**还留了 5 个 `team-bypass-e2e`**——它们在 22:12 之后仍有活动，**可能是隔壁 team 正在跑的测试**，
leader 问过用户要不要一并清，**用户未答**。

---

## §6 安全约束（原文保留，不可弱化）

- 密钥只存在于 `.team/current/profiles/*.env`，**任何席位禁止读其原文**。
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
- **取 daemon 日志只 grep 明确要的那一行，不 tail**（英文词过滤中文日志是弱的，曾近失）。
- **起隔离 tmux 后必须自检"我在自己的 socket 上"**：`mkdir -p /tmp/e2e-<席位名>`（短路径且预建）→
  `unset TMUX` → `tmux -S <sock> new-session -d` → **`tmux -S <sock> list-sessions` 确认**。
  tmux 建 socket 失败时**不报错、静默回退到用户真实 tmux**，自检是唯一可靠的不变量。
- 给席位发消息只走 `team-agent send` / `.team/ta send`，**禁 tmux `send-keys`**。
- ⛔ **绝不触碰生产 daemon（现 pid 4140）与用户真实 tmux**，席位只读也不行。
  （leader 重启 daemon 已获常驻授权，见 §1。）
- ⛔ **不许启动安卓模拟器 / emulator / qemu**，用户指令，**未解除**。

---

## §7 给后继的一句话

当日最贵的一课仍然是那个形状：**「我验的东西，就是用户在用的那个东西吗？」——一天累计到第 10 次。**

但当日第二贵的一课是新的：**leader 自己的验收判据也会不全。**
第 9 次那回，探针、单测、定点变异**全都做了而且都真实有效**，功能还是一装就废——
因为整条链只验到「机制被触发」，没验到「用户可见结果」。
**判据必须落在用户能看见的那一端，中间机制验得再漂亮都不算。**

第三条：**三个席位都顶回过 leader，三次都对。**
`w-theme` 两次推翻 leader 的假设（256 色映射、前景亮度），
`w-img-probe` 顶回了「落点可能在服务端」的预设，还自己推翻了自己上一轮的探针结论。
**如果他们顺着我做，第一次会去改一个没坏的调色板，第二次会去调暗一个本来就不亮的前景。**
保护「顶回来」这件事。
