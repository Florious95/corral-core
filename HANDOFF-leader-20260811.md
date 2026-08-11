# HANDOFF · agentmirror 人工侧 leader · 2026-08-11（19:5x 落笔）

> 工程：**agentmirror**（仓库 `/Volumes/nvme/Projects/远程Agent安卓`）——手机远程操控主机 tmux 中
> Agent CLI 的开源产品（Apache-2.0）。本文写给**刚接手、没看过过程**的后继。
> ⚠️ **本文覆盖 `HANDOFF-leader-20260810.md`**：那份写的是"结账 40 项未提交改动"的状态，账已结完，照它做会走错。

---

## §0 compact 后先做什么

**一句话现状**：本轮（08-10 23:31 → 08-11 19:5x，约 20 小时）已完成结账、判据两层、19 包注释与契约
全量刷新、静态分析接入与首批修复；**阶段三实质收口**，只剩 5 条 deferred 的依赖升级。
当前唯一在跑的是复核席 `w-final-verify`（验兜底泵批）。**下一步是阶段四：跑 43 条用例。**

**开口第一句**（对用户）：
"通知开关已按你的令整条删除并在需求基登记撤销（R-004/R-005）；阶段三收口，
最后一个复核席在跑；下一步开阶段四跑 43 条用例，按 B1–B6 六批。你确认我就开。"

**必读清单**（按序，绝对路径）：
1. 本文
2. `/Volumes/nvme/Projects/远程Agent安卓/docs/stage4-execution-plan.md` — **下一阶段唯一权威**：
   43 条用例逐条通道归属、判定方式、六批排期、阳性对照方案
3. `/Volumes/nvme/Projects/远程Agent安卓/docs/round-findings-20260811.md` — 本轮溢出发现与判据已知 gap
4. `/Volumes/nvme/Projects/远程Agent安卓/docs/stage3-issue-inventory.md` — 38 条静态分析立账，剩余 5 条在此
5. `/Volumes/nvme/Projects/远程Agent安卓/requirement-base/REVISIONS.md` — **R-004/R-005 必读**（见 §3）
6. `/Volumes/nvme/Projects/远程Agent安卓/CLAUDE.md` — 工程红线（本轮新增三条）
7. `~/.claude/skills/taskbook-orchestration/SKILL.md` — 编排流程

**恢复动作**（环境塌了时）：
```bash
cd /Volumes/nvme/Projects/远程Agent安卓
# 管理命令报 team_owner_mismatch（leader 绑定丢了）：
.team/ta claim-leader --confirm --workspace .
# 看门狗守护死了（必须按 cwd 核，不能只看 pgrep -f，本工程已栽过两次）：
nohup python3 -c 'import os; os.setsid(); os.execvp("bash",["bash",".team/watchdog-supervisor.sh"])' >/dev/null 2>&1 &
# 生产 daemon 死了（禁用工作区源码重编）：
bash .team/prod-daemon-launch.sh
```

---

## §1 身份与不变量（操作铁律）

1. **需求基是唯一权威，先撞库再问用户。** 本轮栽过：三条产品问题我直接上升问用户，
   而 `requirement-base/` 里全有答案（011 已裁前台服务、006 只要求 60fps 未规定手段、
   017 R-4 把 token 当可删文件对待）。用户原话："需求可以定义一切技术抉择，我的需求很明确"。
2. **leader 只有技术抉择权，没有新增产品功能的权力**（010 授权范围）。见 §3 P0-1。
3. **席位通道判据**：默认 `provider: claude_code` + `auth_mode: compatible_api` +
   `profile: worker-api`（即 deepseek-v4-flash，角色文件**不写 model**）。
   升级 codex `gpt-5.6-sol` 仅两种：① taskbook 该条 `contention: contract`；② 同一任务返工达上限 2。
   **本轮 20 余席全部走第三方 API，零 codex，未出现质量问题。**
4. **一切 `team-agent` 调用走 `.team/ta`**（净化包装器，防继承 codex 死代理）。
5. **派单必写 `.team/evidence/<task>.intent.json`**，与 `add-agent`+`send` 同批，
   `case_id` 取 `send --json` 的 `message_id`。漏写 ⇒ 该席对看门狗**全盲**（本轮栽两次）。
6. **判活性只看 pane 尾栏有无 `esc to interrupt`**，不看框架的 `worker_state`/`last_output_at`
   （实测对卡死 35 分钟的席位持续报 BUSY 且时间戳还在跳）。
7. **Android 侧并发粒度是「Gradle 模块」不是「文件」**：任何席位跑 `:app:testDebugUnitTest`
   都会编译整个模块、把别人写到一半的源码编进去。**同一模块同刻只放一席**。
   Go 侧无此约束（`go test ./internal/xxx/...` 只编译该包），故 Go 侧曾 9 席并发顺畅。
8. **每次派单前按 cwd 核一次守护与看门狗活着**，不靠"我起过它"的记忆（本轮栽两次，见 §3 P0-2）。
9. **验收一律复跑 acceptance argv，不采信自报**；**每条测量配阳性对照**。
   本轮四次靠这条抓出真缺陷（见 §2）。
10. **发消息只走 `.team/ta send`**，禁 tmux `send-keys`；人工调度下**禁 `presentation.sink=silent`**。

---

## §2 本轮已闭环（21 条提交，`879a1eb..5a0cc6b`）

| 阶段 | 产出 | 复核 |
|---|---|---|
| 结账 | 6 案（含 P0：`POST /upload` 此前**零鉴权**） | `w-recheck-settle` + leader 复跑 |
| 判据两层 | T3-1 符号级 doc / T3-2 引用真实性 / T3-3 契约标签完备 / T3-4 跨层声明一致 | 两轮对抗性复核，一次判 refuted 后返工 |
| 阶段一二 | **19 个包、75 条不实注释、约 119 个契约、29 条 `@consumes`** | 四批四轮复核，全 confirmed |
| 阶段三 | 静态分析接入（staticcheck + Android Lint）、38 条立账、首批修完含 3 条 P1 | `w-stage3-verify` 判 pass |

**这一轮最有价值的数字**：19 个包里 **11 个在施工前判据就是绿的**，而它们身上藏着 75 条不实注释；
`dev.agentmirror.terminal` 整个模块四项判据全绿，实为**根本不在扫描根里**（`KT_SEARCH` 只找
`src/main/java`，它在 `src/main/kotlin`）。**判据是地板，且这个地板很低。**

**四次"绿变红"全部靠追问"这个绿是真的吗"**：① T3-2 抓不住 D-14 原型（我的目标设定错误）；
② gradle `UP-TO-DATE` 假绿；③ T3-3 在 Go 侧按文件而非按符号判契约；④ terminal 模块整体不可见。

---

## §3 P0 / 插队项

### P0-1 我发明了一个用户从未要求的功能（已闭合，但教训最重）

- **现象**：用户问"你为什么想加这个新功能"，我答"那是你自己裁的"。用户："我从来没有提到过。"
- **查证**：带阳性对照检索 13 个会话 526 条 user 消息、剔除框架注入后 **266 条真实用户发言**——
  含「通知」者 **11 条全部是我自己写的 `/loop` 参数与技能文本**，无一条出自用户。
- **根因**：`scenario-audit` 席位提 needs-ruling → **更早的 leader（我）裁成 017 R-5** →
  以"已裁定"身份进需求基 → 变成 D-15 → 落地。
  **010 授权 leader 的是技术抉择，不含新增面向用户的产品功能。**
- **处置**：整条回退（未入库，干净回退）；清掉两处引用该功能的假注释；
  `REVISIONS.md` 登记 **R-004**（撤销 R-5）与 **R-005**（017 整条无用户背书，引用前须回头确认）。
- **仍待用户裁定**：017 的 **R-1 特殊键条**同样追溯不到用户裁定（可辩：Claude Code 硬依赖
  Esc/Ctrl-C，或属 001 镜像范式必要条件）。**已落地**，未撤销，等用户表态。
- **边界别连坐**：通知本体合法——003 第 4 条"需要时被唤醒"是 leader 提案 + 用户明确认可。

### P0-2 看门狗死了近 5 小时而我一直在报"值守中"（已闭合）

- **现象**：用户指出"你的看门狗没有生效"。核实：本工程 0 实例，日志停在 13:50，发现时 18:39。
- **根因**：裸起的 `watchdog.py` 被外部信号杀掉（stdout 日志 **0 字节**——异常会留 traceback，
  没有 ⇒ 是被杀），时刻落在施工席跑全量 `tools/gate/run.sh` 的窗口内。
- **代价**：`w-notif-toggle` 建完任务列表即停摆、**空转 5 小时**无人发现。
- **处置**：新增 `.team/watchdog-supervisor.sh` 守护托管（退出即重启并记账）；
  **macOS 无 `setsid`**，必须用 `python3 -c 'os.setsid()'` 拿独立会话（第一次用 setsid 整条静默失败）。
  实测守护与 watchdog 同在 PGID 14260、与 leader 会话分离。
- **顺带查出**：`w-gate-sa` 是泄漏席位（交件后我退役同批四席、独漏它），已补退役。

### P0-3 我给席位下过一句越界指令（已闭合，代价留在代码里）

011 只要求"前台服务 + 常驻连接"，我在派单里加了"**时钟泵归属服务、在屏组合不再持有**"——
这半句需求里没有。结果服务被杀时前台也不更新（功能回退），只好再加
`AppClockPump` + `OnScreenFallbackPump` + 让出协议 + 6 条红测去补。
**净效果：我造出一个问题，再花代码去解它。** 现已跑通验过，不推倒重来（收益盖不过风险），
但这是本轮唯一由我主动制造的复杂度，记档。

---

## §4 在途未收尾任务（逐条可执行）

### A. 【在跑】`w-final-verify` —— 兜底泵批复核

- **进程**：tmux 私有 socket `ta-b7cc1c640ccf`，session `team-remote-agent-android`，窗口 `w-final-verify`。
  判活性：`tmux -L ta-b7cc1c640ccf capture-pane -p -t team-remote-agent-android:w-final-verify | tail -6 | grep -c "esc to interrupt"`，
  1=在回合中，0=已收工或卡住。
- **章程**：`agents/w-final-verify.md`。**任务已缩减**（D-15 被撤销后我发过缩减令 `msg_e0575cde6388`）：
  只验兜底泵不变量与竞态、分组 E 10 条是否真修（重点 `ApplySharedPref` 的 `commit()→apply()`
  同步语义变更）、注释是否落后、gate delta。**跳过** D-15 相关两节。
- **产物**：`.team/evidence/stage3-final.verify.json`（工作区已有 `.team/verify-final/` 临时目录，
  它交件后应自行清理；若残留，退役时一并删——零残留是红线）。
- **交件后**：读结论 → 若判 refuted 则按差口返工（返工计数 1，上限 2）→ 通过则退役并 commit。

### B. 阶段三收尾：5 条 deferred 依赖升级

`docs/stage3-issue-inventory.md` 分组 F 里 `fix-sa-appbuild` 明确 deferred 的 5 条，
gate 现在 `conclusion=fail` 就是它们：`OldTargetApi`、`GradleDependency`、`NewerVersionAvailable`×3。
逐条理由已写进 `.team/evidence/fix-sa-appbuild.json` 的 `deferred` 字段
（core-ktx 1.19.0 需 AGP 9.1.0 跨主版本、kotlin serialization 插件工具链耦合、
okhttp 与 mockwebserver 5.4.0 跨主版本、targetSdk 属产品决策且 SDK 37 未装）。
**顺序约束**：这 5 条要么定下来升、要么正式后置写进 taskbook，**不能一直挂着让 gate 长期红**——
长期红的门等于没有门。

### C. 【下一阶段主线】阶段四：跑 43 条用例

- **唯一权威**：`docs/stage4-execution-plan.md`（**不是 41 条，TESTPLAN §10 有算术勘误**，
  承办席核原文后确认实为 43 条，A 组 17 条）。
- **通道**：U 模拟器 UI 自动化 38 / H 宿主对账 18 / R 真机 5，**无纯真机条目**；
  API 通道本批零承接、只留给 TS 网络 D1。
  **用户裁定原文**："模拟器能测的全测；测不到测不了的才用 API 模拟用户场景"，**UI 必须测**，
  走 API 只针对 TS 网络。**别把 UI 划进 API 通道偷懒**——用户为此当场纠正过一次。
- **已知不可用面**：相机与 Extended Controls 的 GUI 窗口寻址在本机模拟器上**已实证不可用**
  （2026-08-10 为此空转八代），方案里全部改用 `adb shell` 规避，**不要再在模拟器上硬撞**。
- **批次**：B1 首触 → B2 后台视觉 → B3 宿主对账 → B4 性能 → B5 修复回炉 → B6 真机交付。
  **受 §1-7 约束**：UI 自动化批次不能与 `:app` 施工席并行。
- **F1 终端滚动帧率必须进**：`docs/round-findings-20260811.md` P-3 已裁——
  **先量 F1，达标则整帧重绘可接受、不动；不达标才接脏区局部重绘**。量测前不预先施工。
- **阳性对照**：每类判定配必然非空对照（假节点断言、sips 非纯色、test-results XML 用例数非零
  防 UP-TO-DATE 假绿、framestats N_frames>0）。**本轮已三次栽在"没测到被当成通过"上。**

### D. 阶段四跑出的缺陷全修

量现在估不出来，取决于 C 的结果。按包切席、同模块串行。

### E. 【唯一必须用户参与】重打 APK → 真机验收 8 项

**前置**：用户手机现装的是 **2026-08-09 21:19 版**，本轮所有改动（TS 链、前台服务接线、
75 条注释、3 条 P1）都不在里面。
8 项：真扫码+多网卡可达 / 018 逐图目检 / 通知投递 / 进程被杀与锁屏重连 / 设置页重配流程 /
中文界面与无障碍 / 真实相机实拍 / 真实 Claude Code·Codex 多行括号粘贴。
**依据**：016 明裁"自动化必要非充分、**验收权在真机**"。这不是推责任。
**注意**：第 3 项"通知投递"现在只验通知能不能到，**不再有 App 内开关**（R-004 已撤销）。

### F. 需求基待办

- **017 R-1 特殊键条**待用户裁定（见 §3 P0-1）。
- 若将来要做 token 落盘加密（P-2），须**先进需求基走 REVISIONS**，不得由施工席在实现里夹带。

---

## §5 运维与外部

- **生产 daemon**：pid **3393**，核对时存活 `01-17:58:20`（约 1 天 18 小时），`:9900` 在听。
  **绝不触碰**；死了用 `bash .team/prod-daemon-launch.sh`，**禁止用工作区源码重编**。
- **看门狗**：守护 `.team/watchdog-supervisor.sh` pid **14260**，watchdog.py pid **14322**，
  同在 PGID 14260。日志 `.team/logs/watchdog.log`（采样）与 `watchdog-supervisor.log`（退出记账）。
  **核活性必须按 cwd**，`pgrep -f` 会误匹配 `/Volumes/nvme/Projects/冒险岛怀旧服自动化` 的同名进程
  （本工程为此栽过一次，把别人的进程当成自己的）。
- **taskbook**：91 条条目。**注意**：`fix-dogfood-notif-toggle` 已随 D-15 撤销而移除，
  不要因为 BACKLOG 里还提到 D-15 就去重做。
- **判据现状**：全仓库 **19 包六项判据全 PASS**（T1-1/T1-2/T3-1/T3-2/T3-3/T3-4）。
- **gate 现状**：`conclusion=fail`，就是 §4-B 那 5 条 deferred，非新增缺陷。
- **框架直报通道**：
  `.team/ta send '/Users/alauda/Documents/code/agent前沿探索/多agent协作::refactor-maintainability/leader' '...'`

---

## §6 安全约束（原文保留，不可弱化）

- 密钥只存在于 `.team/current/profiles/*.env`，**任何席位（含 leader）禁止读其原文**；
  诊断只用 `team-agent profile show <name> --workspace . --json`。
- 配对 token：**不落日志、不上屏明文、QR 是唯一合法出口**（协议 §9）。
  **当期不做落盘加密**（需求基未要求，见 `docs/round-findings-20260811.md` P-2）。
- TS authkey 与 token 同级——**不落日志、不上屏明文、不入截图**；传入只经 `TS_AUTHKEY` 环境变量，
  **严禁命令行 flag**（argv 经 `ps` 泄漏已有实案）。
- 席位**禁止 git push**（leader 可 commit、push 同禁）。
- **GPL 隔离**：终端内核自研（R-002）；依赖许可必须 Apache-2.0 兼容。
- 测试净化前缀 `env -u TEAM_AGENT_*`；**绝不触碰生产 daemon（pid 3393）与用户真实 tmux**；
  测试/取证一律自建隔离 `TMUX_TMPDIR` + 高端口，用后**零残留**。
- 取证产物若含 `pre_auth_key` 等密钥字段，必须**脱敏并失败关闭**。
