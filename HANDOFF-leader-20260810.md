# HANDOFF · 远程Agent安卓 人工侧 leader · 2026-08-10（01:5x 落笔）

> 工程：agentmirror——手机远程操控主机 tmux 中 Agent CLI 的开源产品（Apache 2.0）。
> 本文写给"刚接手、没看过过程"的后继。**注意：编排架构已与昨日 HANDOFF-leader-20260809.md
> 根本不同**——框架 leader 已不是本会话，先读懂 §0/§1 再动任何东西。

## §0 compact 后先做什么

**一句话现状**：全自动编排终形态已落地（HEAD d2e53e1）——框架 leader 绑定经 `claim-leader`
移交给常驻 **LLM-leader pane**（tmux 窗口 `llm-leader`，codex `gpt-5.6-sol` 运行中），
裁定/验收/派单/值守全部由它自治；人工侧（你）只剩三职责：轮询升级件文件、保活 llm-leader pane、
充当用户接口。生产 daemon 曾无声死亡，我已用旧二进制重启（新 pid 3393，死因**未查**，见 §3）。

**开口第一句**（对用户）："编排已全自动自转（LLM-leader 常驻），生产 daemon 死过一次已恢复
（死因待查），升级件文件暂无新条目；我在轮询与保活。"

**必读清单**（按序）：
1. 本文
2. 根 `CLAUDE.md` §席位与模型（终形态条款+席位通道+Anthropic 订阅铁律——用户多次训诫的结晶）
3. `agents/adjudicator.md`（裁定章程，LLM-leader 承接的职权与红线全在此）
4. `.team/llm-leader-boot.md`（LLM-leader 的开机简报=它的行为契约）
5. 昨日 `HANDOFF-leader-20260809.md`（工程业务全景+十条铁律，业务面仍有效）

**恢复动作**：
```bash
# llm-leader pane 死了（A-26：leader pane 不可空缺，最高优先重建）：
SOCK=/private/tmp/tmux-501/ta-b7cc1c640ccf   # session: team-remote-agent-android（socket 名 restart 后会变，按 session 名重找）
tmux -S "$SOCK" new-window -t team-remote-agent-android -n llm-leader -c /Volumes/nvme/Projects/远程Agent安卓
tmux -S "$SOCK" send-keys -t team-remote-agent-android:llm-leader 'team-agent claim-leader --confirm --workspace .' Enter
# 核出现 status: claimed 后：
tmux -S "$SOCK" send-keys -t team-remote-agent-android:llm-leader 'codex --dangerously-bypass-approvals-and-sandbox -m gpt-5.6-sol "$(cat .team/llm-leader-boot.md)"' Enter
# 生产 daemon 死了（用旧二进制，工作区源码含未验收改动禁止重编上线）：
nohup ./server/agentmirrord -host 192.168.31.116 > .team/logs/agentmirrord-prod.log 2>&1 &
# 看门狗/转投器死了：
nohup python3 .team/watchdog.py > .team/logs/watchdog-escalation.log 2>&1 &
nohup bash .team/outbox-relay.sh > /dev/null 2>&1 &
```

## §1 人工侧铁律（用户当日多次训诫形成，违者=重犯）

1. **零动作默认**：框架注入若仍落到本会话（历史残留/五类关键消息），一律不动作不回复不转述——
   接收端已是 LLM-leader pane，到你屏的都是噪声。**转投=浪费**（用户原话），机械转发也不行。
2. **提示词修补无用论**（用户裁定）：编排问题只接受结构级解法（本次=claim-leader 换绑定）；
   有疑问**先问框架 leader**（通道见 §5），不自行发明。
3. **三职责之外不伸手**：①`.team/escalations-for-human.md` 有新条目→执行其人工动作
   （对外交付：重打 APK 访达定位交用户/重启生产 daemon/通知用户含 TS authkey 验证步骤）；
   ②llm-leader pane 保活（§0 序列）；③用户指令即办。在途任务验收/派单/席位管理**全部归 LLM-leader**，
   人工不得代办。
4. **Anthropic 订阅席位铁律**（额度见底血泪）：一次性投喂/汇报即关/杂务外包低成本席/扩案拆案/2h 墙钟。
   困难通道=codex gpt-5.6-sol（额度解限）；常规=worker-api（DeepSeek）。
5. **席位恢复三情报**：A-24 拒启→弃 id 处女重建；A-31 `start-agent` 对 pane_dead 静默假成功
   →恢复后必须核真活性（BUSY/新落盘物），不信 exit 0；`reset-agent` 不重读 role file 的 model
   →换模型必须 remove+add。
   **通道补丁**：所有 Team Agent CLI 调用统一走 `.team/ta <子命令>`，尤其
   `add-agent`/`start-agent`/`reset-agent`；禁止手写净化前缀或直接调用 `team-agent`。Codex 新席还须核其
   `~/.codex/sessions` 当日 JSONL 已有 `reasoning` 或 `custom_tool_call`，否则屏上 Working 仍是假活。
6. **客观核对不凭自报**：状态查 evidence JSON+git+进程，`team-agent status` 的 worker_state
   对已停席位会显示假 BUSY（本文 §核对 实测），不可信。

## §2 排期与治权

- **业务闭环**：23 案入库（昨日 22+fix-term-bg-cjk d44704b）；test-app-dogfood 已交件
  （35/41 用例、13 缺陷 P0=0/P1=6/P2=7、REPORT.md 682 行），验收权在 LLM-leader。
- **治权移交完成**：裁定→验收→派单→commit（尾注 [adjudicator]/[llm-leader]）→席位管理全归
  LLM-leader；原 worker 裁定席 `adjudicator` 由其承接后退役（boot 简报第一批动作，**完成与否待核**）。
- **对外交付冻结**：APK 重打交用户，等 TS state-dir 案+e2e 复验通过后由升级件触发（见 §4）。

## §3 事件与 P0

- **生产 daemon 无声死亡**（发现于 01:5x 交接核对）：原 pid 46081（约 3.5h 龄）进程消失、:9900
  无监听，**死因未查**（旧进程经 osascript Terminal 启动无日志落盘，无从追溯；不排除 Terminal
  窗口被关/崩溃/误杀）。已处置：旧二进制重启，**新 pid 3393**，日志此后落
  `.team/logs/agentmirrord-prod.log`（横幅已验证含 ws://192.168.31.116:9900/ws）。
  **后继动作**：告知 LLM-leader 立案查死因（时间窗比对各席位活动）；用户手机若仍连不上，
  重扫码即可（地址未变）。
- 席位死亡潮（框架缺陷 A-24 家族，样本 6 例已直报）：w-dogfood/w-ts-verify/w-ts-verify2 均
  provider 死亡+拒启弃 id，接力链无产物损失。0.5.62 已发（npm latest），升级建议见 §5。

## §4 在途（全部归 LLM-leader 自治，人工侧只列观察点）

| 事项 | 负责 | 状态（核对方式） | 人工侧何时介入 |
|---|---|---|---|
| dogfood 13 缺陷修复波次 | LLM-leader 排单 | 已见 w-fix-onlinecpu（已连接空闲经济 D-16）、w-fix-tsstatedir（TS state-dir 接线）两席开出（tmux 窗口存在实证）；进度看 `.team/evidence/*.json` 与 git log | 不介入 |
| feat-ts-wire 收尾 | LLM-leader | 证据 blocked（隔离配置缺口非功能失败）；fix-ts-state-dir-e2e（taskbook:594）修后复验 e2e | 复验过→升级件让人工重打 APK+重编重启 daemon+通知用户（TS 验证需用户真 authkey，App 配对页填 key 或服务端 TS_AUTHKEY env——**严禁 argv flag**） |
| 原裁定席 adjudicator 退役 | LLM-leader | boot 第一批动作；**待核**：`tmux 窗口 adjudicator 是否已关` | 不介入 |
| LLM-leader 本体 | 人工保活 | pane 有 codex 界面（gpt-5.6-sol xhigh）；死了按 §0 序列重建 | pane 死时 |
| 升级件轮询 | 人工 | `.team/escalations-for-human.md`（当前无条目）；心跳 1800s 链已排 | 有新条目时 |

**进程现场**（01:5x 核实）：生产 daemon pid 3393；watchdog v4.3 + outbox-relay 活
（`pgrep -f "watchdog.py|outbox-relay"`）；LLM-leader pane=session `team-remote-agent-android`
窗口 `llm-leader`（bound_pane %36，owner_epoch 2，leader_registry
`~/.team-agent/leaders/834d7731010c__remote-agent-android.json`）。

## §5 运维与外部

- 框架版本 0.5.61；**0.5.62 已发**（npm latest）：修 A-13（跨区直投）/A-20/A-22（codex launcher
  连坐）。升级时机=在途席位清空的静默窗，升级后：outbox-relay.sh 退役恢复直投、复核"CLI 全限定
  TO 静默归一"是否改结构化拒绝并回投框架 closure（此复核在裁定台账）。
- 框架直报通道：`team-agent send '/Users/alauda/Documents/code/agent前沿探索/多agent协作::refactor-maintainability/leader' '...'`（人工侧仍可用；LLM-leader 走 outbox 脚本）。
- 关键路径：裁定台账 `.team/adjudicator/log.md`；决定审计 `.team/adjudicator/decisions/`；
  outbox `.team/adjudicator/outbox-framework.md`（脚本增量转投，offset 文件 `.outbox-relay-offset`）；
  dogfood 产物 `e2e/artifacts/dogfood/`（TESTPLAN/REPORT/48 图）。
- 上传目录 `~/Downloads/agentmirror-uploads/`；APK `app/app/build/outputs/apk/debug/app-debug.apk`
  （用户手机装的是 21:19 旧版，含 bgcjk 修复的新版**尚未交付**——等 §4 冻结解除）。

## §6 安全约束（原文，不可弱化）

- 密钥只存在于 `.team/current/profiles/*.env`，**任何席位（含 leader/LLM-leader）禁止读其原文**；
  诊断只用 `team-agent profile show <name> --workspace . --json`。
- 配对 token：不落日志、不上屏明文、QR 是唯一合法出口（协议 §9）。
- TS authkey：与 token 同级——不落日志、不上屏明文、不入截图；传入只经 `TS_AUTHKEY` 环境变量，
  **严禁命令行 flag（argv 经 ps 泄漏，已有实案）**；QR 预授权分发为唯一自动出口。
- 席位禁止 git push（LLM-leader commit 可、push 同禁）。
- GPL 隔离：终端内核自研（R-002）；依赖许可必须 Apache-2.0 兼容。
- 测试净化前缀 `env -u TEAM_AGENT_*`；绝不触碰生产 daemon（现 pid 3393）与用户真实 tmux；
  测试/取证一律自建隔离 TMUX_TMPDIR+高端口 daemon，用后零残留。
