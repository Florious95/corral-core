# HANDOFF · 远程Agent安卓 leader · 2026-08-22（全停归零后交接）

> 你是下一任 leader。前一轮（2026-08-21 全天，账本 hl1-v1，31 格）的全部产品改动已被用户
> 判定作废并归档回退——你接手的是一个**干净基线 + 三件明确任务**的工程，不背旧包袱。

## §0 compact 后先做什么

**一句话现状**：工程处于用户下令的全停归零态：双端锁定基线 commit `4120c0884`，
编排全部停摆，无任何在途任务；下一阶段工作 = 交接任务书里的三件事（见 §2）。

**开口第一句（对用户说）**：
> 「我已接手。按交接任务书的顺序，第一件事是给『打开会话→完全加载』链路加 Debug 级日志仪表
> （测试不识图不取帧，全部从日志读数）。我先立仪表格开工，仪表包出来后请你真机验一次
> 『不倒退』，然后我在模拟器上测性能基线。可以开始吗？」

**必读清单（按序）**：
1. 本文档全文
2. `/Volumes/nvme/Projects/远程Agent安卓/docs/交接任务书-性能基线与仓库重构.md` —— 三件事的任务书（打点位表/基线流程/重构骨架/红线），下阶段唯一工作范围
3. `/Volumes/nvme/Projects/远程Agent安卓/docs/优化点清单-1820.md` —— 18 优化点底册（三件事收口后才逐条重启）
4. `requirement-base/entries/092-会话页白屏回归与两处简陋UI.md` §9/§10/§11、`093-Provider图标重绘.md` §2 —— 金标准与两条用户二次纠正
5. `docs/编排方法论.md`（含 §3.5 远端 PR、§10 七问）+ 项目 `CLAUDE.md`（含「当前待办底册」节）
6. `.team/artifacts/audit-20260821-opus-findings.json` 与 `open-latency-plan.md` —— 作废轮的诊断遗产（结论仍真，代码须重写）

**恢复工作流程（编号执行，完毕前 ⛔ 不开新任务/不起驱动器/不清理任何东西）**：
1. 先核对：`git log --oneline -1`（应为 8f7409be7 或其后）；`lsof -nP -iTCP:9900 -sTCP:LISTEN`
   （应有 agentmirrord，2026-08-22 时 pid=16330，重启过会变）；
   `pgrep -x ledger-run` 逐 pid 用 `lsof -a -p <pid> -d cwd` 核 cwd——**本工程应为 0 个**
   （交接时机器上有一个属 `/Volumes/nvme/Projects/无等编排` 的，⛔ 别动它）。
2. 恢复守护：开工时用 CronCreate 重挂 29 分钟心跳（旧的已注销，词面见本文件 §5 附录）；
   起驱动器后同一动作里挂 stall-alert（受管后台任务）。**全停期间不需要**。
3. 判「恢复完毕」：上述核对全符 + 用户对开口第一句给了放行。
4. 现场与本文不符：以现场为准，先报用户再动。

## §1 身份与不变量

- 你是 leader，只编排不亲写产品码（含解冲突）；验证/跑门/封版/并线/部署是 leader 职责。
- **一次一条改动**：一改动→一包→**用户真机验不倒退**→下一条（092 §11；上一轮 5 条打包发船直接触发全停）。
- **金标准**：打开会话「秒进、秒排好」（基线体验）。模拟器绿 ⛔ 不能替代用户真机。
- **模拟器性能基线（任务二产物）一旦测得**：此后不许低于、优化必须高于（用户令）。
- 图标：**只用 Provider 原生厂家官方图标**，⛔ 自绘（093 §2，用户纠正过两次）。
- ⛔ 禁 Deepseek；⛔ 禁 Fable 5，评审席只用 Opus 5（`model: claude-opus-5`，改角色文件必须
  remove-agent --force + add-agent 重建，pane 底部自证）。
- 新席位角色文件必写 `dangerously_skip_permissions: true`（只在启动时生效）。
- 席位卡住先读屏：`tmux -S /private/tmp/tmux-501/ta-b7cc1c640ccf capture-pane -p -t 'team-grok-l2:<席位名>'`。
- 判活只认 nodeprobe；`send-ok`≠送达；投递验证双向说谎（报失败的可能已送达、报成功的可能卡输入框
  未提交）——**唯一可靠信号是席位随后的新 turn/落盘产物**。
- seal 后、评审前开远端 PR（tools/mirror-pr.sh / mirror-pr-serve.sh）；land 后推 main 闭 PR（§3.5）。
- 判据写反造假哨兵：正向 status=done + 否定词（stillblank/blocked/norepro/不可判）拦截；
  rv 判据只认 `VERDICT: supports`；⛔ 纯字段名 substring 判据（形式绿三连击的教训）。
- 打针避开判据写回窗口（读-改-写会丢驱动器的 succeeded）；revision 前进会把在途格重派（已知）。
- 席位任务书要给「收工杀掉自己起的进程」一条（孤儿 daemon 实撞）。

## §2 排期与封存令

**下阶段 = 交接任务书三件事，严格串行（用户 /handoff 参数原话：「工作重点就是完成上面的三件事。」）**：
1. **任务一**：打开链路 Debug 日志仪表（8 打点位+open_id+单调钟+级别开关；服务端 subscribe
   三时间戳建议一并）。为什么第一：它是任务二的量具，且用户硬约束「测试不能识图、不能取帧取间隔」。
2. **任务二**：模拟器性能基线（三真实夹具×≥10 次冷开，均值/p50/p95/max，极端值不剔除单独记录，
   落 `.team/perf/baseline-*.json` 入 git）。**任务三在它收口前 ⛔ 不许动**（任务书红线）。
3. **任务三**：corral-core 重构（仓内先拆 :core-protocol/:core-terminal/:core-conn → app 壳迁
   corral-app（https://github.com/Florious95/corral-app）→ composite build 引用 → 引用构建的 APK ≥ 基线）。

**封存令**：18 优化点（docs/优化点清单-1820.md）在三件事收口后才逐条重启；`archive/voided-20260822`
里的作废代码 ⛔ 不修不查不翻案（用户：「都是垃圾代码」）；`taskbook.yaml` 尾部几条 pending
（nodeprobe「+」标题 unknown、099 收藏几何优先）均延后。

**下阶段第一个动作**：向用户念开口第一句拿放行 → `python3 tools/basegen.py`（或按 ledger 流程
prep）为仪表格建基底 → 写新账本（建议 `ledger.perf-inst.v1`，1 实现格+1 评审格起步，判据含
「开关关=0 行 / 开=单次打开 8 事件 open_id 一致且时间单调」的单测门）。做完标准=仪表包用户真机验过不倒退。

## §3 P0 / 插队项

无在途 P0。历史 P0 全部随全停清偿或封存：
- 上一轮白屏/卡顿/重连三案：代码作废；**诊断结论仍有效**（见 §4「诊断遗产」），重启 18 点时用。
- 框架侧 7 份 P0 报告：已投 2（双通知扇出——对方已立案回执；投递验证假阴——写好在
  `.team/artifacts/ledger-p0-投递验证假阴致重复执行风险-20260821.md`，投递时对方死队，**待投**）。
  对方活队标志：`team-agent status --workspace /Volumes/nvme/Projects/讨论team-agent --team wiki`
  有非「错误」行。

## §4 在途未收尾任务

**无任何在途执行任务**（全停）。以下是「知识在途」——不需要动，但后继要知道在哪：

- **诊断遗产（作废轮换来的，结论已对抗验证）**：
  - 打开慢主因：订阅帧在 ws readLoop 排在全量扫描帧（Level2Subscribe/List→23 socket 串行
    Discover、死 socket 5s 超时、全表 ps 持锁）之后，实测排队 ~1.67s——`open-latency-plan.md` P0 节。
  - 快照黑屏机理链：`audit-20260821-opus-findings.json` + 092 §9（SIGWINCH 清屏/资讯快照/夹具三世界）。
  - 收藏页 zip 错位、WebSocket 无保活半开卡重连、level2_unsubscribe 忽略 workspace 等已确认缺陷
    ——都在 findings json，重启对应优化点时直接引用，**代码从基线重写**。
- **框架征集应答**：JSON 账本痛点清单已投（`.team/artifacts/ledger-json-painpoints-20260822.md`，
  msg_80aaccbf2540）；对方承诺回「痛点→判定档位→证据」对照表，收到后归档即可，不追。
- **席位现场**：grok-l2 与 remote-agent-android 各剩 `advisor` + `dev-app`（dev-app 框架拒删两次，
  留档；`.team/grok/agents/dev-app.md` 是 prep 模板 ⛔ 不删文件）。25+5 个 hl1 席位已清退。
- **模拟器**：emulator-5554（agentmirror_test_b）交接时仍在跑，装着已作废的 0111 包——
  下一任开工时 pm clear 重来即可，或直接关掉（资源见 §5）。

## §5 运维与外部

- **生产 daemon**：9900 = `server/agentmirrord-night-4120c0884`（基线构建，2026-08-22 交接时
  pid=16330）。重启无需用户确认：先备份现二进制、换、核 9900 在听。server/ 下另有
  `agentmirrord-rollback-dc9aab11b`、`agentmirrord.bak-20260821-1335swap`、`agentmirrord-ship-3e540f8a2`
  三个历史二进制（不入 git，运维产物）。
- **用户 APP**：`~/Desktop/agentmirror-20260820-2227.apk`（md5 d41e9a6e10bfb4bf6928cd9615a8d31f，
  已核=4120c0884 源，debug 签名）。桌面上其它 apk 均作废。
- **GitHub**：corral-core / corral-serve 各只剩 main（=回退基线的过滤线），无 open PR；
  谱系在 mirror-pr 一侧（`tools/mirror-pr.sh` / `mirror-pr-serve.sh` 推，⛔ 别再用 mirror-push.sh
  推 main——两脚本 strip 实现不同源会撞祖先闸，教训在案）。corral-app 独立仓（任务三目标）。
- **本机同居**：`/Volumes/nvme/Projects/无等编排` 有自己的 ledger-run（交接时 pid=48872），
  ⛔ pgrep 后必须按 cwd 认领，别杀错。
- 框架直报：停机类投 `讨论team-agent::team/leader`，ledger-orchestration 类投 `::wiki/leader`，
  **投前 status 验活**（死队投了没人看）。
- 心跳词面：全停已注销；重启编排时 CronCreate 29 分钟周期，词面照抄本轮（judge §4 四项 +
  nodeprobe 判活 + pid 按 cwd 核 + 停滞四步恢复 + 告警重挂纪律）。

## §6 安全约束（原文，不可弱化）

- 密钥只存在于 `.team/current/profiles/*.env`，任何席位禁止读其原文。
- **`.team/current/profiles/tailnet-test.env` 全员禁读（含 leader）**。取值只用
  `set -a; . <file>; set +a` 注入子进程，不打印、不落日志、不入截图。
- 查任何配置前先想凭据：禁无过滤 `ps aux`（暴露席位 API key）；遍历进程树只取 `comm` 禁 argv
  （`ps -o pid,ppid,etime,stat,comm`）；禁 `tail .team/logs/agentmirrord-prod.log`（明文配对 token）；
  Shadowrocket 偏好 plist 与 `tailscale_keys.bin` 列入禁读。
- 凭据已泄露 ≠ 停工：一行上报（不复述值）、就地收紧、继续干活。
- 起隔离 tmux 必自检 socket 归属（`mkdir -p 短路径` → `unset TMUX` → `tmux -S <sock> new-session -d`
  → `list-sessions` 必须见到自己的会话）；tmux 建 socket 失败会**静默回退到用户真实 tmux**。
- ⛔ 绝不触碰用户真实 tmux（默认 socket）；例外：leader 只读 nodeprobe / list-panes。
- ⛔ 测试 daemon 会扫到真实舰队——app 里绝不点开真实会话，只点自己隔离造的。
- 给席位发消息只走 `team-agent send`，⛔ 禁 tmux send-keys。
- ⛔ 席位不许写 /tmp 或任何项目外路径，临时文件写 `.team/nodes/<格>/tmp/`。
- 截图前先关输入法（`adb shell input keyevent 111`）。
- 不写 `Co-Authored-By: Claude`（mirror 脚本负责摘除历史存量）；commit 无需用户确认，
  每个关键点必须提交，一次修复一个提交，验过才提交，不许攒。
