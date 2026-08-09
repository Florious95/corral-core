# HANDOFF · leader · 2026-08-09（写于 P0 回炉波次中段，主机重启恢复后）

> 工程：**agentmirror**（仓库暂名"远程Agent安卓"，`/Volumes/nvme/Projects/远程Agent安卓`）——
> 手机远程操控主机 tmux 中大量 Agent CLI 的开源产品（Apache-2.0）。Go 服务端 `server/` + Kotlin/Compose 安卓 `app/`。
> 本文写给"没看过全程对话的接手 leader"。读完本文+指向文件即可无缝接管。

## §0 compact/接手后先做什么

**一句话现状**：功能开发全部完成过一轮（任务书 28 项 pass），但**真机首触验收被用户裁定不合格**（三个实锤缺陷+体系性覆盖缺口），当前处于"场景审计驱动的 P0 回炉波次"：4 任务在途、4 席位 BUSY 施工中。**下一阶段用户钦定目标：让 APP 真实可用、体验流畅。**

**开口第一句**（对用户）："我已接手，四个 P0 修复在途（状态装配/导航深链/空闲降耗/e2e 强化），交件后重打 APK 请你按真机清单 T0-T6 走首触复验。"

**必读清单**（按序）：
1. 本文件全文
2. `docs/scenario-coverage.md` —— **总图纸**：8 域场景矩阵、四态标注、P0/P1/P2 补齐任务清单（§12，含每项五栏形状）、真机验收清单 T0-T20（§13）。下一阶段的所有派单都从这里取
3. `taskbook.yaml` —— 任务账本（五栏+争议度；write_scope 扩权均有行内注释留痕）
4. `requirement-base/INDEX.md` → 条目 001-017（需求唯一权威；016=验收定义、017=八项裁定）
5. 根 `CLAUDE.md` —— 工程红线+**工程常识红线五条**+席位恢复纪律
6. `.team/evidence/` —— 任务状态唯一来源（`<task>.json` 存在且 status=pass 即完成；`<task>.intent.json` 有而 `<task>.json` 无=在途）

**恢复动作**（若团队/进程塌了）：
```bash
cd /Volumes/nvme/Projects/远程Agent安卓
team-agent status --json          # 席位 DEAD(host_boot_mismatch)=需恢复
team-agent restart . --yes        # resume 全部会话；之后逐席发探针要求回执存活
python3 .team/watchdog.py &       # 看门狗（后台）；rejoin 后必须重拉
# 席位恢复失败 2 轮 ⇒ 弃 id：remove-agent --from-spec --confirm --force → 改名处女 id → add-agent 带案重派（详见根 CLAUDE.md 席位恢复纪律）
```

## §1 身份与不变量

- 你是 leader，**只编排不亲手写产品代码**；销账=机械复跑验收 argv + 证据 JSON 落盘（leader 亲写）+ 席位退役归档（role 文件移 `agents/retired/`）+ git commit（`git add -A`，注意会把在途席位的半成品一并带入——正常，最终以验收为准，但收账消息里主动向席位说明防困惑）。
- **裁定必落账**：需求/政策裁定发 librarian 入库成条目；write_scope 扩权写进 taskbook 行内注释；消息不是存在，账面才是。
- 席位模型：teammate 一律 `worker-api` profile（第三方 API + claude_code CLI）；**攻坚才开 Fable 5**（subscription `claude-default` + `model: claude-fable-5`，一次性禁杂活）。已用 Fable 5 三次：term-core、app-tsnet、scenario-audit。
- 每任务一个干净节点：`.team/nodes/<task>/CLAUDE.md` 基底（任务五栏+现场基+需求指针+经验基+沉淀区）；派单消息只一句"读基底开工"。**沉淀区是黄金资产**，新任务基底要指向相关前任的沉淀（大量 tmux/gradle/依赖实测坑都在里面）。
- 验收纪律：红测先行；测试净化前缀 `env -u TEAM_AGENT_WORKSPACE -u TEAM_AGENT_ID -u TEAM_AGENT_OWNER_TEAM_ID -u TEAM_AGENT_AGENT_ID`；tmux 测试只用隔离 socket+短路径（sun_path 104 字节上限）；**绝不触碰真实 team-agent socket**。
- **客观核对不凭自报**：席位 report"已写证据文件"要核实（fg-service 曾自称落盘实未落）；"tests executed"警告常在，销账以 leader 复跑 exit code 为准。
- 全量门：`bash tools/gate/run.sh`（三面并行+用例数棘轮，降必须 `--accept-baseline=<理由>`）。每轮销账跑一次。
- 停摆处置：看门狗 `.team/watchdog.py`（三条件：假忙碌 15min/空转欠账 2 采样/全局停滞 3 采样；自动探针预算 2，烧穿升级 leader）。第三方 API 席位"长思考流断"是常见病，nudge 一般能救活。
- 框架缺陷直报：`team-agent send '/Users/alauda/Documents/code/agent前沿探索/多agent协作::refactor-maintainability/leader' '[agentmirror leader] ...'`（已立案 A-23 spawn env 偶发丢失、A-24 恢复路径残留毒化；绕行=弃 id 换处女 id）。
- 心跳：用户指令 55 分钟 ScheduleWakeup 缓存保温（Max 缓存 1h TTL，命中价=写价 1/12），编号至 #13 约 12 小时；重启后该链已断，**接手后若用户未另说，重新起链**。

## §2 排期与封存令

- **验收状态：未达成**（R-003 推翻过一次"达成"结论；016 重新定义：**真机首触零阻断**才算数，自动化全绿只是必要条件）。接手后**禁止再宣称"验收达成"**，直到真机清单 T0-T20 核心段由用户手机走通。
- 当期范围裁定（017）：特殊键条/多行粘贴/重配对入口/拍照直传=当期必做；多主机档案/多 token/每工作区静音/国际化/无障碍全量=显式后置（INDEX 未决议题表有记录）。
- 排期主线：P0 波次（在途）→ P0 剩余（见 §4"排队"）→ P1（安全/并发/内容保真）→ R-1/R-2/R-8 功能补齐 → 重打 APK → 用户真机清单复验 → P2。

## §3 P0 / 插队项（本轮回炉的由来）

用户真机首触实测 3 缺陷 + 追问出体系性缺口，全部已立案：
- 缺陷 A（QR 广播 TUN 地址 198.18.0.1）：**已修复销账** `fix-qr-host-detect`（真机 smoke 实证候选表正确）
- 缺陷 B（扫码后静默）：**已修复销账** `fix-pairing-scan-flow`（扫码即连+失败五分类+回填手填+token 不上屏）
- 缺陷 C（4 孤儿 daemon 各吃 17.5% CPU）：**在修** `fix-daemon-idle-cpu`（见 §4）
- 体系缺口：scenario-audit（Fable 5）产出总图纸，实锤 D-1~D-5（D-1 状态解析从未装配进 daemon=blocked 通知源头断，D-2 通知深链无消费方，D-3 旋转丢导航态，D-4=缺陷 C，D-5 拍照缺失归 R-8）
- 结构性响应已落账：工程常识红线五条（根 CLAUDE.md）、016 验收定义修正、017 八项裁定

**插队扰动**：原任务书 e2e 的 goal 与实现有落差（"杀App/断网"实为杀 daemon/关 socket），已在 taskbook#e2e 行内注释登记，修正案=在途的 e2e-layer2-harden。

## §4 在途未收尾任务（核实时刻 2026-08-09 14:38，四席全 BUSY）

> 判活方式：`team-agent status --json` 看 worker_state/last_output_at；交件信号=report_result 消息自动到达 leader 会话；无人值守兜底=看门狗。基线 HEAD=c60ac37（第 47 个 commit）。

| 任务 | 席位（全名） | 做什么 | 验收 argv | 卡点/备注 |
|---|---|---|---|---|
| fix-daemon-idle-cpu | w-fix-idlecpu | 零客户端暂停轮询（空闲 CPU<1%）+ 单实例守卫（pidfile）+ e2e layer2 trap 补 daemon 清理 | `cd server && go test ./internal/api/... ./internal/discovery/... ./cmd/...` | 主机重启曾打断，resume 后在途；与 e2e-harden 都动 e2e/layer2.sh trap——**先落者赢，后落者需 rebase 处理，撞行报 leader 排序** |
| fix-state-wiring | w-fix-statewire | D-1：main.go 装配 agentstate→StateProvider，打通识别→listing→聚合→通知；api 集成红测+层1 状态断言 | `cd server && go test ./...` | 已核实现场（state.go:33 恒 unknown），设计红测中；可能需 discovery 加法性补 `#{pane_pid}` 字段（已预授权，照 ws-api 先例） |
| fix-app-nav | w-fix-appnav | D-2+D-3：MainActivity 消费深链 ACTION_OPEN_SESSION（含 onNewIntent）+ rememberSaveable + 引入 Robolectric 基建 | `cd app && ./gradlew -q :app:testDebugUnitTest --tests "*Nav*"` | Robolectric 依赖版本先实测存在再写（camera-bom 幻觉版本前车之鉴，见 pairing-ui 沉淀区陷阱①） |
| e2e-layer2-harden | w-e2e-harden | 层2 真旅程：语义定位+点开会话断言快照文本+输入回显+`am force-stop` 真杀 App 恢复断言 | `bash e2e/run.sh --layer 2` | 产品只读（验收方不修理）；"杀App恢复"断言依赖 fix-app-nav 落地才最强，未落则按当前行为写+注明 |

**排队（依赖满足即派，五栏形状在总图纸 §12）**：
1. `test-aggregate-status`（P0-5，等 fix-state-wiring 落——同写 internal/api 互斥）
2. `test-app-android-seams`（P0-6，等 fix-app-nav 落——共用其 Robolectric 基建）
3. R-1 特殊键条 + R-2 多行粘贴（017 已裁语义：键条最小集 Esc/Ctrl-C/Tab/↑↓←→、input 帧加可选 keys 字段前向兼容；多行走 server 既有 paste-buffer -p 路径）——**这两个直接决定"真实可用"**，协议+server+app 三处，建议协议改动单独小任务先行
4. R-8 拍照直传（app 侧小任务）
5. P1 五项（test-upload-hardening / test-multi-client / test-term-content / e2e-real-tui / test-conn-lifecycle / test-fleet-scale）
6. 全部 P0+R 落地后：**重打 APK**（`cd app && ./gradlew -q :app:assembleDebug`，产物 `app/app/build/outputs/apk/debug/app-debug.apk`）→ 交用户按总图纸 §13 真机清单走 T0-T6+T14+T17 核心段 → fail 立 fix- 案，**未走到的步进"未验证清单"随交付明示**（016d 铁律）

**下一阶段目标注入（用户原话）**："让 APP 真实可用，体验流畅"——排期上把 R-1 特殊键（Claude Code 日常硬依赖：Esc 打断/方向键选菜单）与 R-2 多行粘贴提到 test-* 补齐之前做，因为它们是"可用性"缺口而非"覆盖"缺口；"流畅"维度在真机 T3/T6/T7（首帧/捏合/滚动）复验中量化，发现卡顿再立性能案。

## §5 运维与外部

- 用户手机：已装旧 APK（含缺陷 B 的版本）。手填配对可用：`ws://192.168.31.116:9900/ws`（主机另一地址 10.20.55.20；token 看服务端启动指引，存 `~/Library/Application Support/agentmirror`）。**修复齐后必须重打新包**。
- 起服务端给用户配对：`cd server && go run ./cmd/agentmirrord`（终端打 QR+全候选地址表；`-host` 可显式指定）。用完杀掉——单实例守卫落地前注意别留孤儿（缺陷 C 教训）。
- e2e 模拟器：emulator-5554 曾在跑；`~/Library/Android/sdk`，JAVA_HOME 见 `~/.zprofile`（openjdk@17，PATH 前置是关键，link 无效——env-android 沉淀）。
- 框架团队通道见 §1；A-23/A-24 修复排期在 0.5.62 之后批次，w-ws-api 死 id 的 runtime 残留（provider-config/env/events）承诺冻结待框架取证，**勿清理**。
- 心跳链断了（主机重启），接手后重起：ScheduleWakeup 3300s，prompt 模板见对话史或自拟（collect+看门狗自检+续排）。

## §6 安全约束（原文，不可弱化）

- 密钥只存在于 `.team/current/profiles/*.env`，**任何席位（含 leader）禁止读其原文**（Read/cat/grep/编辑器均禁）；诊断只用 `team-agent profile show <name> --workspace . --json`。
- 配对 token 纪律：QR 与启动指引是 token 的**唯一**合法出口；日志/错误消息/App 屏显不得含 token（server 有 TestErrorsNeverContainToken 阳性对照锁死；App 侧 016 后已撤裸 JSON 上屏）。
- gen-image skill 的 config.env 同禁读原文（全局规则）。
- 席位纪律：禁止 git push；本地不 commit（commit 由 leader 收口）。
- GPL 隔离：Termux 核/ConnectBot 核（JTA 血统）等 GPL 代码禁止引入或摹写（本仓 Apache-2.0；R-002 有案）。
- 用户密钥/token 变更只能请用户本地编辑，不入对话。

---
*账面核实时刻：47 commits（HEAD c60ac37）、28 任务 pass、4 在途、watchdog alive、五席 BUSY/IDLE 正常。*
