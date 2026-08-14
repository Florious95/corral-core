# HANDOFF · leader · 2026-08-14（第三版，覆盖前两版）

> 前两版：v1 写于 08-14 01:00（"链路优化收工、三个 App 缺陷待开工"）、
> v2 写于 08-14 04:00（"五缺陷并行"）。**本版覆盖它们。**
> 本轮（08-13 17:00 → 08-14 09:45）共 **54 个提交**，HEAD = `67b06f4f8`。

---

## §0 compact 后先做什么

### 一句话现状

**五条 App 缺陷 + 一条诊断日志能力全部代码完成并已部署，用户已亲测 ①②③ 通过。
现在只等用户在真机上验 ④（上滑投送）与 ⑤（切后台回前台）两条。团队 15 席全部待命，无在途任务。**

### 开口第一句（对用户说）

> 「桌面上是 `agentmirror-scrollfix-67b06f4f8.apk`，服务端也已重编重启（pid 81134）。
> 还剩两条等你验：**④ 在 Claude Code 里上滑**（这次修了三个 bug，应该真的动了）、
> **⑤ 用 TS token 配对 → 切后台 → 回前台**（期望能连上，可能先断几秒再自动恢复，那是正确行为）。
> 哪条不对只说编号；不对的时候先复现、再进设置导出诊断日志发我。」

### 必读清单（按优先级，全绝对路径）

1. 本文件
2. `/Volumes/nvme/Projects/远程Agent安卓/CLAUDE.md` —— **今晚新增一条凭据铁律，见 §6**
3. `/Volumes/nvme/Projects/远程Agent安卓/e2e/artifacts/apk-for-user/README-给用户.md` —— 给用户的六条验收清单
4. `/Volumes/nvme/Projects/远程Agent安卓/docs/remote-scroll-forward-design.md` —— **§已知局限有一张三档表，回答用户"为什么 vim 里滑不动"**
5. `/Volumes/nvme/Projects/远程Agent安卓/docs/d38-rootcause-probe.md` —— 缺陷③ 三次修不好的真正原因
6. `/Volumes/nvme/Projects/远程Agent安卓/docs/tsnet-resume-reconnect-rootcause.md` —— 缺陷⑤ 根因 + **模拟器做不了 tailnet 验证的两条独立机理**
7. `/Volumes/nvme/Projects/远程Agent安卓/docs/upload-transport-vpn-bypass-probe.md` —— **顶部加粗那条模拟器局限，影响整个工程**
8. `/Volumes/nvme/Projects/远程Agent安卓/docs/cols-convergence-patch-triage.md` —— 缺陷② 废补丁分类 + §7 诊断日志栅格字段规格
9. `/Volumes/nvme/Projects/远程Agent安卓/docs/diag-log-review.md` —— 既有代码里的四处凭据泄露路径 + round2 复审
10. `.team/evidence/*.json` —— 每条任务的验收数字与 leader 裁定

### 恢复动作

```bash
# 1. leader 绑定（pane 变了必须重认领，否则 add-agent/send 被 owner gate 拒）
team-agent claim-leader --confirm          # 当前 owner_epoch = 7

# 2. 生产 daemon —— pid 81134，监听 *:9900，二进制编译于 08-14 09:41（含滚轮整条链路）
lsof -nP -iTCP:9900 -sTCP:LISTEN
# 停了才需要拉：
bash .team/prod-daemon-launch.sh -host 192.168.31.116 &

# 3. 看门狗 —— pid 98318(supervisor bash) + 86589(watchdog.py)，按 cwd 核活：
for p in $(pgrep -f "supervisor\.sh|watchdog\.py"); do lsof -a -p $p -d cwd -Fn | grep ^n; done

# 4. 模拟器（emulator 包与 android-35 镜像是我 08-14 手工装的，见 §1 坑 2）
export ANDROID_HOME=$HOME/Library/Android/sdk
export PATH=$PATH:$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools

# 5. 广州 DERP —— 43.136.53.247，SSH 端口 52222（skill 里写的 22 是错的）
#    密钥 /Users/alauda/Documents/code/安卓claude_code_开源框架/guangzhou.pem
```

---

## §1 身份与不变量

### 角色边界

leader **编排**，不亲手做产品代码、不 push。
但**今晚实际做过且认为正确**的三类：
- **客观核对**（`git cat-file` 核 sha、亲跑 `go test` / `gradlew` / `strict-t3` 验席位自报）
- **环境 ops**（装 SDK 包、起模拟器、修看门狗、重编重启生产 daemon）
- **任务书与证据记账**（taskbook、evidence、intent、commit）

### ⚠️ 客观核对，不凭自报 —— 今晚救了四次

| 谁自报 | 实际 | 谁对 |
|---|---|---|
| `w-rev-upload` 说该调 `OkHttpClient.close()` | 本仓库是 okhttp **4.12.0 JVM 版，根本没有 `close()`** | 开发席用 `javap` 证伪，审查席错 |
| `w-diag-dev` 报"全绿"，我第一次测出 4 failed + strict-t3 exit 2 | strict-t3 是**我在 `app/` 下跑的**（要在仓库根跑）；4 failed 是缺陷③ 在途 | **席位对，我错** |
| `w-d38-test` 报"P4 PASS、P5 FAIL"，我测成反的 | **我的 XML 解析正则跨界匹配**，把 P5 读成 P4 | **席位对，我错** |
| `w-scroll-design` 报 App 侧完成 | 节流是**丢弃不是累加**、服务端 copy-mode **固定滚 1 行** | 用户实测暴露，两个都是真 bug |

**判据**：席位报"完成"时先看有没有贴**实际输出**。只给结论不给输出的，退回去要输出。
**而我给席位数字时也要说清是怎么读出来的** —— 我今晚就因为没说清而误导过一次。

### 今晚立的五条判据（比任何具体缺陷都重要）

1. **测试链路必须先抓到真实缺陷，抓不到就不许改代码**（用户 08-14 原话）。
   审计后 ②⑤ 当时不合格（断言的是结构事实/构造极端值），已分别退回补真复现。
2. **抓不到就加日志**（用户 08-14 原话）→ 立 `feat-diagnostic-log-export`。
3. **审查结论也是要被验证的对象**，不因为它来自审查席就免检，也不因为是 leader 下的令就闭眼执行。
4. **探针跑得再漂亮，没执行到被测代码就不算验过**（纪律⑥）。今晚拦掉两次。
5. **验收线要说"行为要怎样"，不是"代码要长成什么样"** ——
   **我今晚在这上面栽了三次**（缺陷② B-字形断言一个实现够不着的计算值；
   缺陷③ P5 要求必须新增某个方法名；缺陷⑤ 三条探针"由绿转红"与已裁定的修法不可兼得）。
   **三次都是席位顶回来的，三次都对。**

### 环境已知坑（会让人反复误判，务必先读）

1. **⚠️ 这台模拟器做不了任何 tailnet 验证 —— 两条独立机理，都有铁证。**
   - 墙1：Mac 本身在 tailnet 上，模拟器经 host NAT 直达 `100.x`，**App 根本不需要隧道**。
     实证：没有修复的旧包上传 **<1s 成功**（本该 10s 超时）。
   - 墙2：就算确认流量走了内嵌 tsnet SOCKS（GoLog 实锤），
     **掐掉 DERP 后 tsnet 自动 fallback 到 WireGuard 直连**，连接照通。
     判据是 GoLog 打 `connection refused` 而非 `no route to host`。
   - **推论：历史上所有"模拟器验过 tailnet"的结论都要重新审视。**
2. **sdkmanager 会静默卡死，不是网络问题。**
   实测 `curl` 同一 CDN 大文件 **1.25 MB/s**，而 sdkmanager 跑 3 分 21 秒 **SDK 目录增长 0 KB**。
   绕法：直接 `curl` 拉包再解压。
3. **`archwiki --strict-t3` 必须在仓库根跑**，在 `app/` 下跑会给 exit 2（我栽过）。
4. **共享工作区里任何人编译不过就是全队停摆** —— 今晚发生**三次**（其中一次是我提交席位交件时没先验编译）。
   → 大改动一律 `git worktree add /tmp/xxx <sha>`。
   **主工作区绝不许 `git stash` / `git checkout` / `git merge`。**
5. **看门狗单实例守卫会自匹配**：`pgrep -f "watchdog-supervisor.sh"` 会命中
   **命令行里提到该脚本名的任何进程**（包括拉起它的那条命令），cwd 又相同 ⇒ 判"已在跑"直接 exit 0。
   **它从 08-11 起实际是死的**，今晚加 `ps -o comm=` 只认 bash 已修。
6. **看门狗 T5 探针约 4 分钟出针**，与 CLAUDE.md "心跳周期接近 1 小时"有张力。
   **处置：给已交付的席位写 evidence 文件**（intent 有、evidence 无 = 判在途），写了就不再戳。

---

## §2 排期与封存令

用户 2026-08-14 裁定（原文）：
> 「核心就是把这**四个**缺陷开发好。」（后追加第五条）
> 「持续推进，不要停下来。」
> 「测试链路一定要先抓到真实的缺陷，抓不到就不能改代码。」
> 「你这些东西抓不到，你就加日志。」

**授权范围**：用户已明确授权 leader 代拍契约级议题（据此批准了缺陷④ 的实现）。
**不要再为已裁定过的事回去问他。**

---

## §3 P0 / 插队项

### P0-1：用户 09:14 实测 ④ 报"协议错误 unsupported_type"→ 已解决

**现象**：上滑弹红字 `协议错误: unsupported_type（unknown frame type）`。
**根因**：**生产 daemon 二进制编译于 08-13 00:25，而滚轮服务端代码是 08-14 03:25 提交的** ——
跑着的 daemon 根本不认识 `TypeScrollWheel`。
**止血**：重编 + 重启 daemon（旧 pid 70317 → 新 pid 81134）。tmux 会话完好。
**遗留（未做，等用户验完再处理）**：
App 把协议层内部错误**原样弹给用户看**。正确行为应是**静默降级回本地缓冲滚动**，
用户不该为服务端版本落后买单。**这条我记下了，没做。**

### P0-2：用户实测 ④"上滑无反应"→ 已解决（三个 bug）

1. **App 侧节流是丢弃不是累加**。`SessionViewModel.onScrollWheel` 窗口内直接 `return`，
   而 KDoc 把它写成有意设计"丢中间帧不影响方向正确性"——**方向对，幅度全错**。
   一次滑动几十次回调、总量三四十行，50ms 窗口丢弃后只发五六帧、每帧一两行 ⇒ 远端滚不到 10 行。
2. **服务端 copy-mode 固定滚 1 行**（`send-keys -X scroll-up` 不带次数）。已改 `-N abs(delta)`。
3. **⚠️ `send-keys -H` 注入 SGR 鼠标字节整条路是死的 —— 这是我批错的方案主路径。**
   隔离 tmux 实测：注入 SGR 字节 → less/vim **均无反应**；而 `send-keys "k"` → less **确实滚**；
   tmux 自己的 `WheelUpPane` 用的是 `send-keys -M`，**只在真实鼠标事件回调里有效，外部合成不了**。
   我当时说"tmux 已经实现了这套逻辑，我们照抄"，**但 tmux 那套是 `-M` 不是 `-H`**。
   **裁定：mouseAny=1 也降级到 copy-mode** —— 一条静默无效的路径比一条行为略有不同但真有反应的路径更坏。

**P0 对排期的扰动**：无。这两个 P0 都发生在五条缺陷代码全部完成之后，没有压掉任何在途任务。

---

## §4 在途未收尾任务

**当前 15 席全部 `PROBABLY_IDLE`，在途 intent 归零，无活跃任务。**
**唯一的"在途"是等用户真机验收。无常驻进程驱动，靠用户回报判断进度。**

### 4.1 等用户验 ④（上滑投送到远端）—— 最可能还有问题的一条

- **包**：`~/Desktop/agentmirror-scrollfix-67b06f4f8.apk`（同 `e2e/artifacts/apk-for-user/`）
- **服务端**：pid 81134，已含修复
- **怎么验**：在 **Claude Code** 会话里上滑，应能看到该 TUI 自己的上文
- **三档预期（`docs/remote-scroll-forward-design.md` §已知局限）**：
  ```
  ① 非 alt-screen 的 TUI（Claude Code 属于这类）→ 有效   ← 用户主场景
  ② alt-screen 应用（vim/less/htop）           → 不支持  ← tmux 架构约束，非 App bug
  ③ 裸 shell                                    → 有效
  ```
  **别把 ② 说成"已支持"。** 用户在 vim 里滑不动时，文档要能直接回答为什么。
- **已知代价**：远端投送需要**至少一个完整往返**（当前链路约 123ms），本地缓冲滚动是零延迟。
  **功能对了、手感可能变钝。** 不做"乐观本地滚 + 远端权威"，理由是双重滚动 + copy-mode 坐标错位。
  **用户若说钝，要他的判断，不靠我们猜。**
- **未来线索（本轮不做）**：`send-keys "k"` 有效 ⇒ "发应用自己的滚动按键"是可行的第三条路，
  但需知道 pane 里跑什么及其按键约定，是 app-specific 的。已写进设计文档"未来方向"。
- **负责人**：`w-scroll-design`（待命）
- **D-36 不关**：`fix-scrollback-history-d36` 的 status 保持 `refuted_by_user`（那是用户亲口推翻的历史事实），
  已加 `superseded_by` 指针指向 `feat-remote-scroll-forward`。
  **两条一起关的条件是用户真机确认。没有那一下，我们只是又一次"自认为懂了"——那正是前四轮翻车的方式。**

### 4.2 等用户验 ⑤（内嵌 tsnet 回前台连不上）—— 完全没试过

- **怎么验**：用 **TS token 配对**连上 → 切后台 → 回前台
- **期望**：能连上。**可能先断几秒再自动恢复，那是正确行为，不是没修好**
  （席位顶回过我的"静默重试让用户无感"提议，理由是那会掩盖失败、撞"失败可见"红线。它对。）
- **根因**：`TsnetWire.kt:91` 的幂等守卫。`state==Up` 的语义是"`start()` 成功过、SOCKS 端口**曾经**通"，
  不是"现在能拨通"。节点死了 state 还是 Up ⇒ `ensureStarted()` 撞守卫直接 return ⇒ **永远起不来**。
- **修法**：失败驱动（SOCKS 拨号失败本身当触发源），30s 节流，`state==Idle` 不误触发。
  **自愈刻意不经幂等守卫走旁路** —— 守卫是"同 key 同态不重复起网"的**用户语义**，
  自愈是"节点已死强制重建"的**系统语义**；根因恰恰是这两种语义被混成了一条路。
- **⚠️ 复现只能真机**（两堵墙已证明模拟器做不了，见 §1 坑 1）
- **负责人**：`w-tsnet-dev`（实现，待命）、`w-tsresume-probe`（复现取证，待命）

### 4.3 已完成但未做真机验收的三条视觉/行为项

| 缺陷 | 用户 09:40 反馈 | 真机还欠什么 |
|---|---|---|
| ① 图片上传 | **OK** | — |
| ② 捏合右列 | **OK** | JVM stub 下 advance=1，"字形真被收进画布"严格说只有真机能验，但用户已目视通过 |
| ③ 输入框跑中间 | **OK** | ⚠️ **黑屏闪只有真机能验**，JVM 抓不到。v3 就是单测全绿、真机一看就废。用户说 OK 但未必专门盯过 |

### 4.4 诊断日志（`feat-diagnostic-log-export`）—— 已交付，一项未测通

- 设置页有"导出诊断日志"入口，环形缓冲 4096 + 落盘 1MiB + **写入点脱敏** + 导出目录轮转（上限 8）
- 记录覆盖：tsnet 状态迁移与原因、幂等守卫拦下、SOCKS 拨号失败码、WS 关闭原因、
  上传选路、渲染栅格七字段、前后台生命周期、崩溃 cause 链
- **验收判据**：用户复现一次、导出一份，**光看日志就能定位根因**。
  已验：缺陷⑤ 三信号同时可见且可排序；缺陷② 能独立复算 `overflow_px`（1260/10/11 → 5，修复后 → 0）
- **未测通（诚实标注）**：**设备级空闲 CPU** —— 需协调独占构建窗口后补测。
  代码级已确认零线程零定时器，JVM 热路径实测 **4.15µs/次**。
- **负责人**：`w-diag-dev`（实现）、`w-diag-test`（红测）、`w-diag-rev`（审查，round2 已交）

### 4.5 未部署 / 未处理的小项

- **App 把协议内部错误原样弹给用户**（P0-1 遗留）→ 应改为静默降级 + 日志留痕
- **`sendError` 服务端正常路径不打日志** → 旧客户端发未知类型时我们自己瞎，留给日志任务统一处理
- **SGR 坐标硬编码 `1;1`** → 已随 `send-keys -H` 路径删除，不再是问题
- **F3（SOCKS 失败文案含协议细节会进用户 UI）** → 记档，等日志任务分层"用户看什么 / 日志记什么"时一起处理

---

## §5 运维与外部

- **生产 daemon**：pid **81134**，监听 `*:9900`，`-host 192.168.31.116`，
  二进制 `/Volumes/nvme/Projects/远程Agent安卓/server/agentmirrord`（编译于 08-14 09:41）。
  启动入口 `.team/prod-daemon-launch.sh`（唯一入口，不做 kill/takeover）。
- **广州 DERP**：`43.136.53.247`，**SSH 端口 52222**（skill 写的 22 是错的），
  TCP **8444** / UDP **3478**（**不是 443**），自签证书 `sha256-raw:92f3b9d9...22f850`。
  手机↔Mac 已从 1221ms 降到约 50ms。
  ⚠️ 该机 `:443` 跑着另一项目的 `claude-chat.service`，**不要碰**。
- **worktree**：`/tmp/am-before`、`/tmp/am-after`、`/tmp/am-diag`、`/tmp/am-scroll-head` 等。
  用完 `git worktree remove`。
- **模拟器**：`emulator-5554`（AVD `agentmirror_geo_1260x2800`，1260x2800，API 35）。
  `wedding_user_a_api35` 是别的工程的，**不要碰**。
- **看门狗**：supervisor pid **98318**（bash）+ watchdog.py pid **86589**（Python）。
  日志 `.team/logs/watchdog-supervisor.log`。supervisor 每约 22 分钟重启一次 watchdog.py（跑满 MAX_SAMPLES），正常。
- **给用户的产物**：`~/Desktop/agentmirror-scrollfix-67b06f4f8.apk` +
  `~/Desktop/验收清单-20260814.md`（源在 `e2e/artifacts/apk-for-user/README-给用户.md`）。
- **未提交的工作区残留**：`docs/wiki/t3-report.md`、`tools/gate/*.json`（都是生成物）、
  以及一个过期的 `e2e/artifacts/apk-for-user/agentmirror-5fixes-be214a375.apk`（gitignore 内，可删）。

---

## §6 安全约束（原文保留，不可弱化）

- 密钥只存在于 `.team/current/profiles/*.env`，**任何席位禁止读其原文**。
- **`.team/current/profiles/tailnet-test.env` 全员禁读（含 leader）** ——
  只能 `set -a; . <file>; set +a` 注入子进程，不打印、不落日志、不入截图。
- **【今晚新增，已写进 CLAUDE.md】凭据已泄露 ≠ 停工**：
  用户对该文件的长期决定是「既然泄露了，就写进文件，接下来就用它去测」。
  再次泄露时**只做三件事：一行上报（不复述泄露的值）、就地收紧做法、继续干活**。
  **禁止**因此停工、禁止等新 key、禁止把删本地产物当成风险处置 ——
  片段一旦进入上下文就擦不掉，删截图减少的是执行者的不适而非真实风险。
  轮换与否是用户的事，不是开工前置条件。
  （我今晚违反过一次，被用户批为「掩耳盗铃」且双输，已改正并写死进规则。）
- **报告泄露时永远不要复述泄露的值** —— 那一贴会把片段从一个上下文扩散到另一个。
  写「authkey 的前 N 个字符出现在 X 处并被截进图，已删」即可。
- **`adb shell input text "$KEY"` 会把凭据放进 argv**（宿主与 guest 两侧都留痕）——
  改用 `printf '%s' "$KEY" | adb shell 'read -r k; input text "$k"'`，且用完 `pm clear` / `-wipe-data`。
- 查任何配置前先想凭据。**Shadowrocket 偏好 plist 与 `tailscale_keys.bin` 禁读。**
- ⚠️ 禁止 `tail .team/logs/agentmirrord-prod.log`（daemon 明文打配对 token）。
  需要查时用**窄 grep**（如 `grep -o -iE "scroll[a-z_]*"`），不加上下文行。
- ⚠️ 本机禁跑无过滤 `ps aux`（暴露席位 API key），核进程一律 `pgrep -fl <精确路径>`。
- 配对 token 与 TS authkey 同级：不落日志、不上屏明文、不入截图，**QR 是唯一合法出口**。
- **不许手改 App 的 SharedPreferences 来绕过配对流程**（今晚拦下过一次）。
- 绝不触碰生产 daemon 与用户真实 tmux（**席位**只读也不行）；
  起隔离 daemon 必须用 `AGENTMIRROR_E2E_DISCOVERY_SOCKET_DIRS` 收窄扫描。
- 席位禁 `git push`。
- **GPL 隔离**：Termux 系 GPLv3 不可用；herdr-remote(AGPL)、mosh(GPLv3) 只借鉴算法、禁止复制代码。
  本工程是 Apache-2.0。
- 测试净化前缀 `env -u TEAM_AGENT_*`；派单必经 `.team/ta`；派单必写 intent。
- 全局 CLAUDE.md：**禁止写 memory；禁止用 AskUserQuestion 工具问用户**。

---

## §7 给后继的一句话

**今晚最容易犯的错，是把"席位报了绿"当成"验过了"；第二容易犯的，是把自己没验证过的数字当成事实丢给席位。**

我今晚两样都犯了。四次自报与实测不符里，**有两次是我错**。

所以下面这句要一直问：

> **这条绿，是在被测对象上、用用户的参数、从红转过来的吗？**

三个条件缺一个，它就还没证明任何事。

而如果你要给席位一个数字 —— **说清它是怎么读出来的**。
我曾经把一个正则跨界匹配的解析结果当成"P4 倒退了"发给开发席，
让它去追了一个不存在的回归。**我要求他们贴实际输出，自己却给了个结论。**

**最后一条，也是今晚最值钱的一条：席位顶回我四次，四次都对。**
如果他们不敢顶，那四次就会变成四个错误的决定进主线。
**把"顶回来"这件事保护好，比任何一条纪律都重要。**
