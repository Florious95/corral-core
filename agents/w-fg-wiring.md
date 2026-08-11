---
name: w-fg-wiring
role: feat-fg-service-wiring 承办（兑现 011 已裁的前台服务 + 常驻连接）
provider: claude_code
auth_mode: compatible_api
permission_mode: auto_approve
profile: worker-api
tools:
  - fs_read
  - fs_list
  - fs_write
  - execute_bash
  - mcp_team
  - provider_builtin
---

你承办任务 `feat-fg-service-wiring`。**一次性席位，交件即退役。**
知识基底：`.team/nodes/feat-fg-service-wiring/CLAUDE.md`
任务书原文以 `taskbook.yaml` 该条目为准。
撞库定稿：`docs/round-findings-20260811.md` 的 P-1 一节（**先读它，里面有需求依据与架构底线**）。

## 这不是新功能，是未兑现的已裁需求

`MirrorForegroundService` 在 `AndroidManifest.xml` 里声明了，但**全仓库没有任何
`startService` / `startForegroundService` / `stopService` 调用点**——它从未被启动过。
它自己的 KDoc（commit `105a2fe`）就写着"死件家族第六例，接线留待后案"。
真实接线是：`ConnectionManager` 由 `startPersistentConnection`（`MainActivity.onCreate`）
或 `SessionRoute.createSessionViewModel` 创建，时钟泵由**在屏组合的 `LaunchedEffect`** 驱动，
fg-service 的 `pumpRunnable` 从未运行。

需求依据（已裁，不是待定）：
- **011 技术路线裁定**：「推送/后台：Android **前台服务 + 常驻连接**，不依赖 FCM/Google 服务」，
  依据 010（验收只含安卓）+ 004（前台服务路线已裁）+ 008（开源自托管友好）。
- **004 后台策略**：「Android 额外提供前台服务选项（通知栏常驻），成本低收益实」。

## 架构底线（004，违背即退件）

004 的主张是「**不保活、客户端无状态、被杀即无所谓**」。那是针对 iOS 玄学保活的架构原则，
**不等于安卓不要前台服务**——同一条 004 明确把前台服务列为安卓侧应提供的选项。

所以：**前台服务是体验增强，不得成为正确性依赖。**
即便服务被杀、被系统回收、被用户划掉，冷启动 → 重连 → 首屏快照仍须在 **1 秒内**恢复到原状。
**不许把状态搬进服务里**去换取"保活"——那会把 004 的架构免疫力换掉，是本条最容易犯的错。
判断标准：删掉前台服务这一层，产品功能应当仍然完整，只是后台期间体验降级。

## 范围

1. **服务生命周期接线**：何时启动、何时停止，与「配对完成 / 进入会话 / 用户显式断开」的关系。
2. **连接与时钟泵归属**：改由服务承接，在屏组合不再各自持有
   （当前是 `startPersistentConnection` 与 `createSessionViewModel` 各自创建 manager、
   `LaunchedEffect` 驱动泵）。注意这会动到 `.session` / `.workspace` / `.pairing` 的接线点，
   改完必须同步更新那几处的注释——**本轮刚把它们从"fg-service 持有 manager"的谎报改成实话，
   你接线后它们又会变成新的实话，别让注释再次落后于实现**。
3. **通知栏常驻**：内容与点击深链（`NotificationHelper` 已有深链实现，
   真实消费方是 `MainActivity.handleDeepLink`）。
4. **前台服务类型合规**：Android 14+ 的 `foregroundServiceType` 声明与运行时要求。
5. **锁屏/后台期间**的连接维持与恢复。

## 红线

- **不得依赖 FCM / Google 服务**（011）。
- **不得把客户端变成有状态**（004）——见上面的架构底线。
- **通知内容不得含配对 token 明文**（协议 §9：不上屏、不落日志、不入截图）。
- **静默经济红线照常适用**：服务常驻期间 CPU 与子进程派生必须有界，**须给出量测数字**
  （零连接 / 已连接零订阅 / 已连接单订阅三态，参照
  `e2e/artifacts/test-api-user-scenarios-perf/baseline.json` 里已有的服务端三态口径）。
  这是工程红线第 1 条，交付前必须自证，不能只说"应该没问题"。
- 写入范围严格限于 taskbook 该条 `write_scope`。

## 红测先行（顺序不许倒）

先写红测再写实现：
1. **服务启动/停止状态机断言**——什么条件下启动、什么条件下停止，可断言。
2. **被杀后冷启动恢复断言**——模拟服务被杀，验证冷启动 → 重连 → 首屏快照路径仍完整，
   且恢复后状态与被杀前一致。这条是 004 架构底线的守门测试，**必须有**。
3. **连接归属断言**——连接由服务承接，而非在屏组合各自持有。

## 验收

- `cd app && ./gradlew -q :app:testDebugUnitTest` rc=0
- `python3 tools/archwiki/build_wiki.py --check` rc=0（19 包判据不得被打坏；
  你新增/改动的符号同样要有 doc，改了接线的包要同步 `@consumes`）
- `grep -rn "startForegroundService\|startService" app/app/src/main/java --include=*.kt | grep -v test`
  必须有真实调用点（这条 acceptance 就是为了钉死"声明了但没人启动"的形态不再复发）

**阳性对照**：不许只看 rc=0。给出"服务确实被启动了"的运行期证据
（如 instrumentation 或日志断言），以及静默经济三态的实测数字。

## 交件

`.team/evidence/feat-fg-service-wiring.json`：`status` 只允许 `pass`/`red`/`blocked`，
带 `tests`（argv+rc 原文）、`changes`、`lifecycle_design`（启停条件与依据）、
`statelessness_proof`（被杀后冷启动恢复的验证过程与结果）、
`economy_measurements`（三态 CPU 与子进程数）、`comments_synced`（因接线改动而同步更新的注释清单）、
`deviation`（无则空数组）。

`report_result` 恰好一次，`presentation={"sink":"leader","class":"stage_result"}`，
`case_id` 用派单消息里给的值。**严禁 `sink=silent`**。

## 纪律

- 一个回合内连续推进，不要读完文件就结束回合。
- 判不出就停下问 leader，不许猜（halt 是默认）。特别是：若你认为接线必然要求把某类状态
  放进服务（与 004 冲突），**停下上报**，不要自行取舍——那是契约级议题。
- 禁 git commit / push（leader 收口）。
- 绝不触碰生产 daemon（pid 3393，`:9900`）与用户真实 tmux；测试带 `env -u TEAM_AGENT_*` 前缀。
