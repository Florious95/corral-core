# HANDOFF · agentmirror 人工侧 leader · 2026-08-10（23:2x 落笔，**覆盖同日 01:5x 版**）

> 工程：**agentmirror**（仓库 `/Volumes/nvme/Projects/远程Agent安卓`）——手机远程操控主机 tmux 中
> Agent CLI 的开源产品（Apache-2.0）。本文写给**刚接手、没看过过程**的后继。
> ⚠️ **本文覆盖同日 01:5x 那一版**：那版描述的"LLM-leader 常驻 pane 自治编排"形态**已被用户废止**，
> 照那版做会立刻走错方向。

---

## §0 compact 后先做什么

**一句话现状**：全部 64 个席位已按用户令**一次性清空**（复核：零存活、本工程零 codex 残留、团队 tmux session 已消失）；
leader 绑定在**本会话**（pane `%0`，`owner_epoch 5`，已核注册表）；编排引擎**已停**（用户裁定：不再用全自动编排，改人工调度）；
下一轮四阶段计划**已与用户对齐但尚未动手**（用户明令"不要开始动手"）；
**40 项改动悬在工作区未提交**，是当前最大风险。

**开口第一句**（对用户）：
"席位已全清、引擎已停、leader 绑定在我这里；下一轮四阶段计划与性能场景清单已落盘。
当前最大风险是 40 项改动仍未提交——我建议先复跑验收把这笔账结掉再开工，你确认就开始。"

**必读清单**（按序，绝对路径）：
1. 本文
2. `/Volumes/nvme/Projects/远程Agent安卓/docs/next-round-plan-20260810.md` — **下一轮怎么干的唯一权威**（四阶段、五个自决点、席位通道判据）
3. `/Volumes/nvme/Projects/远程Agent安卓/BACKLOG-20260810.md` — 剩余任务点总账（dogfood 13 缺陷逐条销账、017 八项、真机验收 8 项）
4. `/Volumes/nvme/Projects/远程Agent安卓/docs/perf-scenarios.md` — 性能场景 A–F 六组清单
5. `/Volumes/nvme/Projects/远程Agent安卓/docs/orchestration-deviation-20260810.md` — 与 `taskbook-orchestration` skill 的偏差分析
6. `/Volumes/nvme/Projects/远程Agent安卓/CLAUDE.md` — 工程红线（含新增通道判据）
7. `~/.claude/skills/taskbook-orchestration/SKILL.md` — **用户明令下一轮必须按这套流程做**

**恢复动作**（环境塌了时）：
```bash
cd /Volumes/nvme/Projects/远程Agent安卓
# 管理命令报 team_owner_mismatch（绑定丢了）：
.team/ta claim-leader --confirm --workspace .
# 生产 daemon 死了（禁重编：工作区含未验收改动）：
bash .team/prod-daemon-launch.sh
# 看门狗死了（必须先查单实例，今天出过三实例并存）：
pgrep -f watchdog.py || nohup python3 .team/watchdog.py > .team/logs/watchdog-escalation.log 2>&1 &
```

---

## §1 身份与不变量（操作铁律）

1. **人工调度，不用全自动编排**（用户 2026-08-10 令）。`.team/orchestrator.py` 保留，但**只以一次性方式使用**
   （`python3 .team/orchestrator.py run --apply --dangerously-bypass-approvals-and-sandbox`），
   **不得再起 `loop` 常驻**。原因见 §3 P0-1。
2. **席位通道判据**（用户复申）：默认 `provider: claude_code` + `auth_mode: compatible_api` +
   `profile: worker-api`（模型即 `deepseek-v4-flash[1m]`，角色文件**不写 model**）。
   升级 codex `gpt-5.6-sol` 仅两种：① taskbook 该条 `contention: contract`；② 同一任务返工达上限 2（开顾问席）。
3. **一切 `team-agent` 调用走 `.team/ta`**（净化包装器）。直调会让新席位继承 codex 托管的死代理
   （`ec2-13-213-89-27.ap-southeast-1…:8443`，实测不可达），后果是**全生命周期零 token 而屏幕显示 Working**。
4. **免审批**：leader 进程 argv 祖先链（≤12 层）里必须含精确 token `--dangerously-bypass-approvals-and-sandbox`，
   席位才继承 bypass；否则每条命令停在「Yes, proceed (y)」等人按键。**角色文件无声明式入口**
   （框架 `compiler.rs:424` 把 `permission_mode` 硬编码为 `restricted`）。
5. **活性判定看 pane 进程子树累计 CPU 秒**——不看"有没有写产物"（产物是终态，长任务中途必然没有，
   我用它当判据时把正在干活的席位掐了），也不能只看屏幕内容哈希（codex 重连横幅带每秒跳动计时器，哈希永远在变）。
6. **给席位发消息只走 `.team/ta send`**，严禁 tmux `send-keys`（实证：键入文本会与框架注入消息拼接成一条，并触发 steer 打断）。
   leader 合法寻址：`remote-agent-android/leader` 或 `<workspace>::remote-agent-android/leader`；裸 `leader` 会被拒
   （`state did not contain the requested team/name tuple`）。
7. **交件契约随调度形态切换**：人工调度下**禁止 `presentation.sink=silent`**——silent 只落库不实时注入，
   实测 15 条结果在库里躺了一整天没人看见。
8. **客观核对不凭自报**：`team-agent status` 的"空闲/工作"判不出停摆；`stop-agent` 返回 `ok` 不代表真停；
   `start-agent` 恢复的是**旧会话**（`start_mode: Resumed`，角色文件改了也不重读），
   `reset-agent --discard-session` 框架自报 `reset_proof: weak` ⇒ **换章程必须弃 id 用处女 id 重建**。
9. **验收一律复跑 taskbook 的 acceptance argv**，不采信席位自报的 `status`；不得为让测试过而改测试或放宽 acceptance。

---

## §2 排期与封存令

**当前排期**：任务书 55 案 DONE（`git log` 可核）；本轮开出 6 案——4 案交件 `pass`、2 案被清场杀在半路，
**全部改动未提交**（§4-A）。

**用户对下一轮的封存令（原文要点，不得走样）**：
1. 下一轮重点＝**让所有函数的注释都成为最新**，并按标准补充契约；
2. **一次性跑出所有隐含问题并全部修复完毕**，再进下一步；
3. 之后**开测试席位，把之前用例设计里的用例全部跑一遍**，跑完把所有缺陷修复；
4. **UI 必须测**——用户原话"我什么时候说过 UI 不测了？"。此前"走 API"的裁定**只针对 TS 网络**
   （tailnet 在模拟器里不好测，所以从 API 角度模拟用户场景验）。
   **模拟器能测的全测；测不了的才用 API 模拟用户场景**；
5. 除 API 外**还要做性能测试**（场景见 `docs/perf-scenarios.md`）；
6. 标准与切分**由接手者自决**——用户原话"这是一个全新的语言，之前所有的信息都是基于 Rust 的语言去制定的"，
   即**不得套用 Rust 工程的既有标注标准**。

**用户最后一条指令**：① 按 `taskbook-orchestration` skill 的流程做；② 按上述方向做；③ 遵守上述规则。
**已对齐，尚未动手。**

---

## §3 P0 / 插队项（今天打乱排期的三件事）

### P0-1 全自动编排被用户叫停（架构级，已闭合）
- **现象**：用户每小时检查一次，每次看到的都是"编排断掉、没有席位在跑"。
- **根因（我的）**：一整天在**重建编排机器**（leader 交 LLM pane → 换脚本引擎 → 被叫停回人工，三次架构改动），
  而真正的活——dogfood 13 个缺陷——**直到 21:0x 才第一次进任务书**。不是修得慢，是根本没派。
- **止血**：引擎已停、席位已清、绑定收回本会话。
- **教训落盘**：`docs/orchestration-deviation-20260810.md`（对照 skill 16 条逐条判定：
  符合 5 / 部分偏离 2 / 偏离 3 / 违反 3 / 完全缺失 2；缺失的三项是 `fork-agent` 黄金分身、顾问席熔断、进度停滞判据）。
- **对原排期的扰动**：dogfood 缺陷修复被压了整整一天 ⇒ §4-A/B。

### P0-2 席位九小时零产出（已闭合，根因查明）
- **现象**：三席从 04:18 到 13:42 卡在 codex 审批提示，`status` 一直显示"空闲"，无人发现。
- **根因**：leader 从"带 bypass 的 Claude Code 会话"换成脚本后，bypass 继承源消失（§1-4）。
- **修复**：框架队源码审计给出判定机制，已实测 `bypass=1` 生效。

### P0-3 引擎误杀在跑席位 + 无限重试（已闭合）
- **现象**：`fix-ts-state-dir-e2e` 被返工到**第 8 代**，每代撞同一个 `headscale_node_count=1`（App 节点未注册）。
- **根因**：①用"没写证据"当停摆信号 → `stop+start` 打断在跑席位；②无重试上限；③无顾问席熔断。
- **闭合**：用户裁定"**通过 API 测试**"后一轮打通（`headscale_node_count=2`，人工复跑双验收全绿，已入库 `517afc0`）。

---

## §4 在途未收尾任务（逐条可执行）

> **无活进程可查**——引擎已停、席位已清。进度信号只有三个：`git log`、`.team/evidence/*.json`、`git status`。

### A. 【最高优先】40 项未提交改动，必须先结账
**基线**：分支 `main` @ `879a1eb`。**负责人**：接手的 leader（人工）。**卡在**：已交件但没人复跑验收入库。

已交件 `pass`（证据文件在，**我未复跑，属"自报完成"**）：

| 任务 id | 覆盖缺陷 | 复跑什么（taskbook acceptance 原文） |
|---|---|---|
| `fix-dogfood-pairing-ux` | D-07 token 上屏 / D-14 无重配入口且注释谎称有 / D-11 吊销文档欠账 | `cd app && ./gradlew -q :app:testDebugUnitTest`；`cd server && go test -count=1 ./internal/pairing/...` |
| `fix-dogfood-upload-media` | D-03 扩展名丢 / D-02 拍照直传 / D-01 权限哑按钮 | `cd app && ./gradlew -q :app:testDebugUnitTest` |
| `fix-dogfood-term-ux` | D-04 滚动反向 / D-08 缺加载态 / D-09 emoji 吃后随空格 | `cd app && ./gradlew -q :app:testDebugUnitTest`；`cd app && ./gradlew -q :terminal:test` |
| `fix-recovery-baseline` | discovery 全程失败后恢复基线不推 delta | `cd server && go test ./internal/api/...` |

被清场杀在半路（**代码已落地、无证据、未验收**）：
- `fix-upload-auth`（**P0 安全**：`POST /upload` 原先零鉴权，任何能连 `:9900` 的人可往用户主机写文件）
  ——`server/internal/api/upload.go` 已有 `uploadBearerToken()`，`api_test.go` 已有 `TestUploadAuthentication`；缺复跑与证据。
- `perf-thresholds-enforce`——`e2e/api-user-scenarios.sh` 已改；门限是否真落进
  `baseline.json.performance.hard_numeric_thresholds` **未验**；`large_output` / `upload` 两项基线可能仍为 null。

**未提交清单（`git status` 实测：23 项 M + 17 项未跟踪）**：
```
M  server/internal/api/{upload.go,server.go,options.go,api_test.go,discovery_failure_test.go}
M  server/README.md  docs/protocol.md  e2e/api-user-scenarios.sh  taskbook.yaml  .team/orchestrator-state.json
M  app/app/src/main/java/dev/agentmirror/app/{AgentMirrorApp.kt,MainNavState.kt,pairing/PairingScreen.kt,
     session/SessionScreen.kt,termview/TermSurfaceView.kt,workspace/WorkspaceScreen.kt,workspace/WorkspaceViewModel.kt}
M  app/app/src/test/kotlin/.../workspace/WorkspaceViewModelTest.kt
M  app/terminal/src/main/kotlin/.../CharWidth.kt  +  test/.../{CharWidthTest.kt,WideCharTest.kt}
M  e2e/artifacts/test-api-user-scenarios-perf/{daemon.log,ps-spawns.log,resource-zero_connection.json,tmux-targets.tsv}
?? app/app/src/main/java/dev/agentmirror/app/SettingsScreen.kt                （D-14 重配页，新文件）
?? app/app/src/test/java/.../{PairingUxTest.kt,pairing/CameraPermissionCardTest.kt,
     session/AttachmentButtonTest.kt,session/AttachmentNameTest.kt}
?? app/app/src/test/kotlin/.../termview/TermGestureDirectionTest.kt           （D-04 滚动方向红测）
?? .team/evidence/{fix-dogfood-pairing-ux,fix-dogfood-term-ux,fix-dogfood-upload-media,
     fix-recovery-baseline}.json + 各自 .intent.json + {fix-upload-auth,perf-thresholds-enforce}.intent.json
?? .team/nodes/{6 个任务的知识基底目录}   ?? agents/{w-dogfood-*,w-recovery-baseline,w-upload-auth,w-perf-thresholds-enforce}.md
?? e2e/artifacts/fix-upload-auth/
```
**顺序约束**：复跑验收 → 绿则**按任务分次窄提交**（不要一把梭）→ 红则记差口重派。
**红线**：不得为让测试通过而改测试；`taskbook.yaml` 的 acceptance 是判据，不许就地放宽。

### B. dogfood 13 缺陷销账状态（判据＝我 grep 过代码，非席位自报）
- **9 条代码已落地待入库**：D-01 / D-02 / D-03 / D-04 / D-07 / D-08 / D-09 / D-11 / D-14
  （其中 D-04 / D-07 / D-11 我只看到文件有改动、**未逐行确认**，入库前须复核）
- **2 条未开工**：D-12（017 R-6「README 锁中文」明示）、D-15（017 R-5 通知全局开关）
- **1 条部分**：D-13（上传目录无上限/无清理），并入 `fix-upload-auth`，是否含目录上限**未验**
- **1 条已入库**：D-16（客户端在线时 daemon 14.8% CPU）→ `fix-connected-idle-economy`，
  API 套件三态实测已连接单订阅 CPU 1.20%
- D-05 / D-10 由 dogfood 席位自行撤销；D-06 降级为观察项。**逐条明细见 `BACKLOG-20260810.md` §2。**

### C. 下一轮四阶段（已对齐，未动手）
详见 `docs/next-round-plan-20260810.md`。**顺序不可颠倒**：
判据与红测 fixture 先写通 → 再放席位改注释/补契约 → gate 三面全绿 → 才开测试席位跑 41 条用例 → 修完再全绿。
- 阶段一二**合并到同一席位同一轮**做（同文件不二次进场），**一包一席、18 包分批、每批 4–5 席、同文件零并发**。
- 判据 `T2-1`（符号级 doc 覆盖）/ `T2-2`（**引用真实性**，专抓"注释谎称设置里有重配按钮"那类）/
  `T2-3`（`@contract` 四标签齐）/ `T2-4`（`@consumes` 与 import 图一致）/ `T2-5`（空扫描即失败），
  **每条必须先配红测 fixture 才准入**。
- 静态分析本轮接 `go vet`（已有）+ staticcheck（BSD-3）+ Android Lint（AGP 自带），不引 detekt；
  **并把 `tools/gate/run.sh` 加进每一条 acceptance 的收口位**——实测 gate 最后一次运行是
  **2026-08-09 18:47**（`conclusion: pass`，824 用例），整整一天的改动没过过门。
- **风险**：注释改动本身无测试覆盖，成败全押在 `T2-1`/`T2-2` 判据强度；判据弱一分就退化成"看着做完了"。
  另：gate 有**用例数棘轮**，大改可能撞红，每波收口时校准基线、不绕过。

### D. 真机验收 8 项（唯一必须用户参与的节点）
真扫码与多网卡可达 / UI 视觉一致性（018 逐图目检）/ 通知投递与全局开关 / 进程被杀与锁屏重连 /
设置页单档重配流程 / 中文界面与无障碍 contentDescription / 真实相机实拍 / 真实 Claude Code·Codex 多行括号粘贴。
**前置＝重打 APK 交用户**：**用户手机现装的是 2026-08-09 21:19 版**，本轮所有修复（含 TS 链）都不在里面。

---

## §5 运维与外部

- **生产 daemon**：pid **3393**，核对时已存活 22h02m，`:9900` 有监听。**绝不触碰**；
  死了用 `bash .team/prod-daemon-launch.sh`（强制日志落盘），**禁止用工作区源码重编**（含未验收改动）。
  日志 `.team/logs/agentmirrord-prod.log`。历史事故：更早的 pid 46081 曾无声死亡，审计判为 `unknown`
  （六条证据缺口），已入库 `8a8ac60` 并加存活探针。
- **看门狗**：`.team/watchdog.py`，当前 pid **5141**（单实例）。
  ⚠️ 本次交接核对时发现**三个实例并存**（22:54 / 22:13 / 20:27 各一），违反工程红线"单实例守卫"，
  已杀掉两个旧的（15513、99754）。后继启动前必须 `pgrep -f watchdog.py` 先查。
- **outbox-relay**：`.team/outbox-relay.sh` 活着；它是 0.5.61 跨工作区直投不通（缺陷 A-13）的绕道，
  框架升到 0.5.62 后应退役。
- **框架直报通道**：
  `.team/ta send '/Users/alauda/Documents/code/agent前沿探索/多agent协作::refactor-maintainability/leader' '...'`
  今日已就三事直报并拿到回执：①脚本驱动 leader 的链路契约；②免审批 bypass 的 argv 祖先链判定机制；
  ③`claim-leader` 把**活着**的 leader pane 判成 `previous_owner_pane_dead` 并放行（原 owner 存活 2h22m 且仍在产出）。
- **额度**：codex 订阅为共享资源，今日一度同账号并发 12 个会话。主力改走 DeepSeek Flash 后压力大幅下降。
- **外部通告**：已把 `.team/watchdog.py` 与两条框架情报送给
  `/Volumes/nvme/Projects/冒险岛怀旧服自动化::maple-auto-team/leader`（投递 `ok`，对方未回执）。

---

## §6 安全约束（原文保留，不可弱化）

- 密钥只存在于 `.team/current/profiles/*.env`，**任何席位（含 leader）禁止读其原文**；
  诊断只用 `team-agent profile show <name> --workspace . --json`。
- 配对 token：**不落日志、不上屏明文、QR 是唯一合法出口**（协议 §9）。
- TS authkey 与 token 同级——**不落日志、不上屏明文、不入截图**；传入只经 `TS_AUTHKEY` 环境变量，
  **严禁命令行 flag**（argv 经 `ps` 泄漏已有实案）；QR 预授权分发为唯一自动出口。
- 席位**禁止 git push**（leader 可 commit、push 同禁）。
- **GPL 隔离**：终端内核自研（R-002）；依赖许可必须 Apache-2.0 兼容。
- 测试净化前缀 `env -u TEAM_AGENT_*`；**绝不触碰生产 daemon（现 pid 3393）与用户真实 tmux**；
  测试/取证一律自建隔离 `TMUX_TMPDIR` + 高端口 daemon，用后零残留。
- 取证产物若含 `pre_auth_key` 等密钥字段，必须**脱敏并失败关闭**
  （今日实践：首跑 `rc=1` 因保留了 `pre_auth_key.key`，脱敏后二跑 `rc=0`）。
