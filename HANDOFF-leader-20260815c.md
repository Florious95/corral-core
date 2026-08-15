# 交接：远程Agent安卓 team leader — 2026-08-15 傍晚

> 后继你好。**只读这一份 + 它指向的文件就能接上，不用回放对话。**
> 本工程 = 手机远程操控主机 tmux 里大量 Agent CLI 的开源 App（Apache-2.0）。
> 我（leader）负责编排，不亲手写产品代码——那是席位的活。

---

## §0 compact 后先做什么

**一句话现状**：用户的四个改动点已全部交付并真机手测过；**①直通输入通过**，
**④刷新有小 bug**，**③状态判定完全错误**。用户已裁定：状态判定**不再重建、整个删掉**，
二级菜单改为 `Ctrl-B w` 的**实时流重绘**（契约 060）。拔除工作刚开工就被
编排框架的缺陷打断，**该缺陷上游已修好，可以复工**。

**开口第一句**（对用户说）：

> 「停工期间我把三件事做完了：**①③④ 的产品代码全部提交**（此前全部未提交，
> 而下一步正是归档回退——2026-08-12 的事故就是这么发生的）；**契约 060 已入库**
> （二级菜单改实时流、状态判定不再重建）；**编排队已修好那个阻断缺陷并通知复工**，
> 我已同步他们的新驱动器。二级菜单的连根拔起停在第一步 `t.locate`，advisor 席位
> 当时已收到派单并在干活。要我现在复工把拔除跑完吗？」

**必读清单**（按优先级）：

1. 本文件
2. `/Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/060-二级菜单改为实时流并取代状态判定.md`
   ← **最重要的契约**，它取代了 058 的「重建」部分，并把刷新模型收窄到只管一级菜单
3. `/Volumes/nvme/Projects/远程Agent安卓/docs/rulings/20260815-用户裁定原文-直通输入与刷新模型.md`
   ← 用户原话转录；注意文末表格区分了「用户裁定」与「leader 判断」
4. `/Volumes/nvme/Projects/远程Agent安卓/CLAUDE.md`
   ← 尤其新增两节：「对外部框架队的配合边界」「全自动编排被阻断时：停工 + 严令」
5. `/Users/alauda/.claude/CLAUDE.md`（全局规则：跨 agent 往返硬上限、禁 memory、禁 AskUserQuestion）
6. `.team/ledgers/level2-uproot-v1.json`（在途账本）

**恢复动作**（协作环境如果塌了）：

```bash
cd /Volumes/nvme/Projects/远程Agent安卓
.team/ta status --json --workspace .        # 席位在不在
.team/ta restart .                          # 全停了就这条（先 add-agent 再 restart，若 0 席位）
python3 tools/ledger-driver.py .team/ledgers/level2-uproot-v1.json   # 复工驱动器
```

---

## §1 身份与不变量（怎么干活的铁律）

- **leader 只编排，不写产品代码、不跑 gradle 改代码、不 push**。发现自己在改产品源码 = 越界。
- **给席位发消息只走 `.team/ta send`**（净化包装器），**禁 tmux `send-keys`**。
  `.team/ta` 还承载着框架队 tmux 追踪 shim 的 PATH，绕过它 = 观测断掉。
- **判据是唯一凭据**：完成与否由**驱动器自己跑机械判据拿退出码**决定，
  **席位自报一律不采信**。实证：dev-keybar 自报「刷新模型完成」，驱动器实跑 `A-ri-test` = exit 1。
- **判据自检那句话**：「**如果被测对象是坏的，这条命令会不会仍然返回 0？会，就还不是判据。**」
  实证：`go test ./internal/api/ -run TestPassthroughNoEnter` 在测试**根本不存在**时返回 **0**；
  加 `| grep -q -- '--- PASS: TestPassthroughNoEnter'` 才返回 1。
- **我这一轮判据写弱了三次，规律是：倾向于验「没变坏」而不是验「真的做成了」。**
  三次都靠对照席（`r.control`，零上下文定点变异）兜住。**别省掉对照席。**
- **跨 agent 往返硬上限：同一对方一天不超过 10 个来回。** 今日对 team-agent 框架队约 6 个、
  对 ledger-orchestration 编排队约 4 个。**用户 2026-08-15 当场批评过我跟得太久。**
- **归属路由**（写在全局 CLAUDE.md）：
  - team-agent 框架问题 → `/Users/alauda/Documents/code/agent前沿探索/多agent协作::refactor-maintainability/leader`
  - **全自动编排（ledger-orchestration）问题 → `/Volumes/nvme/Projects/讨论team-agent::team/leader`**
  - 我今天投错过一次，用户当场纠正。**「席位回报冲屏」「驱动器缺陷」都属编排队。**

---

## §2 排期与封存令（用户裁定原文）

### 🔴 最高优先级令（2026-08-15 用户原话）

> 「我现在最高优先级就是整机把全自动编排，包括底下的 Team Agent 的框架，
> 完全的优化完美，并让它能够真实的去进行全自动编排。
> **在这个最高优先级下，你现在的所有的活都是完美的试验场。**
> 你只能按照他们的 skill，包括他们提供的 CLI 去做。有问题直接反馈，
> 让你更新基础设施直接更新，有问题就停下来让他们修复。」

> 「你停下来是完全正确的，因为**现在这个 APP 体验已经非常不错了**。
> 现在这些改动它都是优秀的测试场景。全自动编排是最高优先级，是当前本机的最高优先级。」

⇒ **产品进度让位于「暴露编排框架的缺陷」。** 不许自己打补丁绕过框架缺陷——
每打一个补丁就把一个缺陷从他们视野里藏掉一个。

### 配合边界令（已写入 CLAUDE.md）

> **禁止为框架队取证。唯一配合的事项是：换用他们发布的新基础设施。**
> 他们要求「做一次复现」「按这个顺序取证」「保留现场等我来」——**一律拒绝**。

### 阻断即停工令（已写入 CLAUDE.md）

> 凡是阻断全自动编排流程的问题：① 立刻反馈 ② **中断手上全部工作** ③ 停下等修好。
> 不许绕过、不许人肉编排顶上、不许「先做能做的」。
> ⚠️ 停工前先分归属：**我方驱动器的缺陷自己修不算阻断**，对方投递/编排层的才触发。

### 已闭环

| 改动点 | 状态 | 验收依据 |
|---|---|---|
| ② 键条 Ctrl-C 挪到最后、Tab 第二 | ✅ 完成 | commit `e70652185` |
| ① 直通输入（每键直达含删除键） | ✅ **用户真机手测通过** | 账本 4/4，含 wire 级命名测试；commit `66baff167` |
| ③ 清 DONE 化石 | ✅ 代码完成 | 账本 3/3；commit `467c089a1`。**但状态准确性用户判定完全错误** |
| ④ 刷新模型 | ⚠️ 部分 | 账本 4/4；commit `ef2d56462`。**用户手测出小 bug，见 §4** |

---

## §3 P0 / 插队项

### P0-1：编排框架的送达检查竞态（**已修，可复工**）

- **现象**：参考驱动器 `sleep 5 → 查一次 token 有没有进席位转录 → 查不到就 `return 6` 中止整条运行`。
- **实发数字**：`16:38:38.x` 驱动器判「未进转录」中止；`16:38:39.159Z` 该 token 作为
  **USER TURN** 落进转录 —— **差 1.1 秒**。投递完全正常，席位当时正在 BUSY 干活。
- **最坏形态**：「**驱动器死了，席位在干活**」同时为真。照日志字面理解会去重派，
  而重派正是粘连来源。
- **报告**：`docs/bugs/20260815-参考驱动器送达检查竞态.md`
- **根治**：上游已改成 `confirm_delivery()` 有界轮询——**席位转 BUSY** 或 **token 进转录**，
  **谁先到算谁**，都等不到（默认 90s）才判失败且**仍然绝不重发**。
  已同步到本工程 `tools/ledger-driver.py`（commit `8882c2849`，含 `confirm_delivery`）。
- **⇒ 阻断已解除，可以复工。**

### P0-2：消息投递会被静默吞掉（**根因未闭合，规避有效**）

- **现象**：`send` 返回 ok、框架侧记 delivered，**席位一个字没收到**。
- **实证（本工程）**：我给 dev-keybar 的扩权裁定 `msg_aa40d4e59a28`：
  `enqueue` 07:36:57.685Z → **`remove` 07:37:01.902Z（4.2 秒后）** → 永远没成 user turn。
  对照组（席位空闲时投递）**直接是 USER TURN，全程零 queue-operation**。
- **框架队自己队的数字**：advisor 席位 **151 条入队、103 条从未消费**（0.5.66）。
- **根因**：框架队先说是「自己重按 Enter 删掉的」，**随后自行推翻并作废**。
  现在确证的只有三点：①席位忙时消息进 CLI 队列 ②入队消息有很大比例被 remove 且从未消费
  ③**remove 的成因未知**。
- **有效规避（已在参考驱动器里）**：**绝不朝 BUSY 席位投递**，押住等 idle 再派。
  BUSY 是唯一触发条件。
- **对我们的实际伤害**：dev-keybar 一度按旧写路径施工（裁定被吞）。后由
  **派单水位机制**（`ledger_id + task_id + revision`）强制重派解决。

### P0-3：`TerminalProofIncomplete`（**未解决**）

- 三张账本任务全部 `succeeded`，`ledger-eval` 仍报
  `error: 引擎状态推导失败：state_error TerminalProofIncomplete`，退出码 4。
- **意味着没有一张账本能正式收尾。** 不挡干活，挡收尾。
- 已在 `docs/bugs/20260815-参考驱动器三处缺口.md` 反馈，归编排队。
- ⚠️ 我先前报过「退出码是 0」——**那是量具旧了**（`ledger-eval` 今天 15:32 重装过）。
  **教训：凡报实测，顺手带一句 `ls -la $(which ledger-eval)` 的 mtime。**
  `~/.cargo/bin/ledger-eval` 是全局共享的，席位跑一次 `cargo install` 就会静默换掉它。

### P0 对原排期的扰动

**②③④ 的产品代码此前全部未提交**，而下一步计划正是「归档回退」。
本工程 2026-08-12 出过「整条修复以未提交状态被回退抹掉」的事故。
**我在交接前已全部提交**（见 §4 sha 清单）。后继不必再找。

---

## §4 在途未收尾任务（逐条可执行）

### 任务 A：二级菜单旧模型 + 状态判定「连根拔起」🔴 **当前主线**

- **账本**：`/Volumes/nvme/Projects/远程Agent安卓/.team/ledgers/level2-uproot-v1.json`（commit `5689f76e4`）
- **契约**：`requirement-base/entries/060-二级菜单改为实时流并取代状态判定.md`
- **基线 sha**：`139bbfb19`（账本 provenance 里写的就是它）
- **两步**：
  1. `t.locate` — **负责人：advisor 席位**（`r.advisor`）。产出
     `.team/nodes/level2-locate/拔除清单.md` + `.team/nodes/level2-locate/verify.sh`。
     判据 3 条：清单非空 / 脚本可执行 / **`! bash verify.sh` 必须为 0（即现在跑必须失败）**——
     这是因果证明，一个从没红过的脚本证明不了任何事。
  2. `t.uproot` — **负责人：dev-state 席位**（`r.dev-state`）。按清单归档到
     `scratch/archive-level2/` 后从主干删除。判据 4 条：`verify.sh` 转绿 / 归档目录非空 /
     gradle 测试绿 / go 测试绿。
- **卡在哪一步**：`t.locate` 已派单（`msg_f3214d36dfb8`，**已确认作为 USER TURN 进了
  advisor 转录**），advisor 收到并开始干活；**驱动器因 P0-1 中止**。
- **下一步怎么做**：
  ```bash
  cd /Volumes/nvme/Projects/远程Agent安卓
  python3 tools/ledger-driver.py .team/ledgers/level2-uproot-v1.json
  ```
  新驱动器带派单水位，会认出「本 revision 已派过」+ 席位 BUSY ⇒ 跳过派单直接等，
  **不会重复派单**。
- **无活进程可查**（驱动器已退）。判断进度靠：`.team/ledgers/driver.log` 尾部 +
  `.team/nodes/level2-locate/` 里产物在不在。
- **红线（不可改）**：
  - 拔除**不含**与三级终端流共用的 WebSocket 传输、帧编解码——拔了会伤三级实时终端。
  - 一级菜单的刷新模型**保留**，它是对的。
  - **归档不是备份，不许直接 `rm`**：失败的 diff 是下一轮根因探针的输入。
  - **拔完二级菜单暂时空着是预期的，不许造占位实现**——占位会变成下一轮的化石。

### 任务 B：二级菜单新形态（实时流）— **尚未立账本**

- 契约 060 已定形态，**实现方案未拆**。等任务 A 拔干净后在**干净基线**上做。
- **已讨论定下的设计**（用户确认过）：
  - 二级菜单 = `Ctrl-B w` 的**重绘**，样式做成我们二级菜单的样子。
  - **不 attach tmux 客户端**（避免多客户端参与尺寸谈判压小用户真实窗口），
    改用 `tmux list-panes -a -F` 取 `session_name` / `window_index` / `window_name` / `pane_title`，
    自己排 `└─>` 树形。
  - 🔴 **pane 标题原样显示，一个字符都不解析**。`◐`（工作）/ `✳`（空闲）本来就在标题里。
  - 🔴 **会话身份（点某行跳进哪个会话）只用结构字段，不得从标题抠。**
- **仍待用户裁定的一件事**：这块屏**只读**，还是**可操作**（点一行跳进会话）？
  可操作要把手机按键映射到 chooser，复杂度大一档。**我问过两次，用户尚未明确回答。**
- **待实测的一件事**：（若改走 attach 路线才需要）tmux chooser 在独立 session 里能否列出其他 session。
  走 `list-panes` 路线则不需要。
- **红线**：实时流**只在二级菜单打开时**拉数据，关掉即停——否则违反工程常识红线①
  「空闲 CPU 趋近 0、无固定频率子进程派生」。**此条必须写成机械判据，不靠自觉。**

### 任务 C：④ 下拉刷新的两个手测缺陷 — **尚未处理**

用户 2026-08-15 真机手测原话：

> 「向下滑尝试刷新失败，它会展示出一个转圈的图标，但是你一松手它就弹回去了。
> 在这种情况下，我不确定它刷新了没有。**它应该是这样的**，你手指往下拉，
> 然后那个转圈它开始转，然后它什么时候刷好了，然后它就又回退过去。」

- 诊断（未验证）：刷新指示器没有和**真实的刷新状态**绑定，松手立刻回弹。
- ⚠️ **二级菜单的这条会随任务 A 一起消失**（不再有下拉刷新）。
  **一级菜单的下拉刷新仍在，这条对一级仍然有效，需要单独修。**

### 任务 D：一个我没搞懂的用户反馈 — **需要问清楚**

用户原话：「**首次进入菜单会刷屏**」。

- 「刷屏」我判不出是**好**（＝进入即刷新了一次，符合预期）还是**坏**（＝屏幕闪烁，视觉缺陷）。
- **我问过一次，用户没回答。** 后继请再问一句，两种解读要做的事完全相反。

### 任务 E：③ 状态判定的归档 — 已并入任务 A

契约 060 明确：**不再重建，整个删掉**。不要另开任务。

---

## §5 运维与外部

### 交付物现状（已客观核过）

```
APK   /Users/alauda/Desktop/agentmirror-passthrough-refresh-cf4530a2d.apk   39,377,065 B  16:23
      /Users/alauda/Desktop/agentmirror-attach-preview-86ec49c0c.apk        （上一版，回退用）
      仓库内同一份：.team/artifacts/apk/app-debug.apk（sha256 前 16 位 9a2f769c1a28d77d，两处一致）
服务端 已部署：pid 47518，`./agentmirrord -host 192.168.31.116`，9900 在听
      新二进制 sha256 前 8 位 00b05342；旧的备份在 server/agentmirrord.bak-20260815-1620
```

**APK 桌面维护规则（用户 2026-08-15 裁定）**：桌面永远保留**两个** APK——最新的 + 上一个。
用户按时间排序、永远装最新的；出问题回退到上一个并反馈。**每次放新的，删掉最老的那个。**
本次没有删（桌面原本只有一个）。

⚠️ **回退提醒**：服务端已换成新二进制。用户若回退到旧 APK，**daemon 也要一起回退**
（`server/agentmirrord.bak-20260815-1620`），旧 App 配新 daemon 未测过。

### 本次交付的 commit 清单（全部已 `git log` 核过）

```
66baff167  ① 直通输入（用户真机手测通过）
467c089a1  ③ 清 DONE 化石
ef2d56462  ④ 刷新模型
139bbfb19  契约 060
5689f76e4  拔除账本
8882c2849  同步上游修复的驱动器（confirm_delivery）
0fe8d5d25  送达竞态缺陷报告
8df57f41b  用户裁定：禁止为框架队取证
0d08e45d2  用户裁定：阻断即停工并严令
```

### 席位（2026-08-15 17:0x 核过）

```
advisor      BUSY   ← 正在跑 t.locate
control      PROBABLY_IDLE
dev-keybar   PROBABLY_IDLE
dev-state    PROBABLY_IDLE   ← 任务 A 第二步的负责人
librarian    PROBABLY_IDLE
```

### 环境事实（会污染对账，务必知道）

- **本机同时跑着多个 team 的 coordinator，版本不一**：曾见 `0.5.65` 的 coordinator
  从 8/14 09:40 活到现在，还有一个自定义 `ta-p0-fixed` 二进制。**那些不是我们的。**
  我们的：pid 7149，`.team-agent/runtime/0.5.66/bin/team-agent`，启动 14:25:17。
- **框架队装了 tmux 追踪 shim**：`/Users/alauda/ta-tmux-shim/bin/tmux`，
  日志落 `/Users/alauda/ta-tmux-trace/remote-agent-android/`。
  **它是被动采集，我们正常干活即可，不需要为它做任何事。**
  我们的 `.team/ta` 里已前置了它的 PATH（删掉那两行即可摘除）。
- **`~/.cargo/bin/ledger-eval` 全局共享**，席位跑 `cargo install` 会静默换掉它。
  **凡报实测带上 mtime。**

### 外部通告状态

- 编排队（`/Volumes/nvme/Projects/讨论team-agent::team/leader`）：已修好送达竞态并**通知复工**；
  已收下我方四条反馈并全部改进参考实现。**他们预告 Python 驱动器是过渡件**，
  Rust `ledger-run` 出来后会撤掉，**迁移由他们负责，不要我们重写**。
  ⇒ **别在这份 Python 上长新东西。**
- team-agent 框架队（`.../多agent协作::refactor-maintainability/leader`）：
  已**撤回全部取证要求**，明确「你们正常干活本身就是我的实验」。
  投递吞消息的根因**仍未闭合**，修好会通知我们换二进制。**球在他们那边，不要追问。**

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
- **取 daemon 日志只 grep 明确要的那一行，不 tail。**
- **凭据已泄露 ≠ 停工**（2026-08-13 用户裁定，2026-08-14 重申并批评过一次违反）：
  再次泄露时**只做三件事：一行上报（不复述泄露的值）、就地收紧做法、继续干活**。
  **禁止**因此停工、禁止等新 key、禁止把删本地产物当成风险处置——
  片段一旦进入上下文就擦不掉。轮换与否是用户的事，不是开工前置条件。
- **起隔离 tmux 后必须自检"我在自己的 socket 上"**：`tmux` 建 socket 失败时**不报错，
  静默回退到默认 socket**——也就是用户的真实 tmux。已实证两条回退路径：
  ① `TMUX_TMPDIR` 路径过长（unix socket 上限 ~104 字节）；② 该目录**未预先存在**。
  **唯一可靠的不变量是自检**：
  ```
  mkdir -p /tmp/e2e-<席位名>          # 短路径，且预建
  unset TMUX
  tmux -S <sock> new-session -d ...
  tmux -S <sock> list-sessions         # ← 自检：会话必须在自己的 socket 上，否则立刻停手
  ```
  踩了怎么办：一行上报、只清理自己建的东西、就地收紧、继续干活。
- 给席位发消息只走 `.team/ta send`（净化包装器），**禁 tmux `send-keys`**。
- ⛔ **绝不触碰用户真实 tmux**，席位只读也不行。
  （leader 重启生产 daemon 已获常驻授权：先备份现二进制、换、起完核 9900 在听，不必问。）
- ⛔ **不许启动安卓模拟器 / emulator / qemu**（用户 2026-08-14 指令，**未解除**）。
  第 2 层测试暂停，**用户真机是唯一的渲染验收路径**。
- **不写 `Co-Authored-By: Claude`**（用户 2026-08-14 裁定「Contributor 应该是我」）。
- **禁止写 memory**（全局规则，memory 系统已废止）；关键知识只沉淀到项目 skill/规则文件。
- **禁止用 AskUserQuestion 工具问用户**；一两句话能说清的直接在对话里问。
