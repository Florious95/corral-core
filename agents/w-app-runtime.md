---
name: w-app-runtime
role: 补在屏兜底泵 + 分组 E 运行时 10 条
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
dangerously_skip_permissions: true
---

你承办任务 `fix-app-runtime-sa`。**一次性席位，交件即退役。**
本席**独占 `:app` 模块**——按今晚新入 `CLAUDE.md` 的红线，同一 Gradle 模块同一时刻只放一席，
所以你不会与别人抢编译单元，也不许把手伸出 `write_scope`。

## ① 补在屏兜底时钟泵（阶段三复核记的 medium gap，先做这个）

`feat-fg-service-wiring` 刚把时钟泵改为**单归属前台服务**（2s 一拍），
在屏组合不再各自持有。后果：**服务被杀时，即使 App 在前台也没有泵**，界面停止更新。
接线前泵由在屏组合的 `LaunchedEffect` 驱动、前台恒有泵，所以这是**功能回退**。

它踩在需求 004 的自检标准上——「删掉前台服务这一层，产品功能应当仍然完整，
**只是后台期间体验降级**」。现状是**前台也降级**了。

修法：在屏组合检测到服务不可用时**接管泵**；服务恢复后**让出**，不得双泵重复拍。
红测必须断言两件事：
- 「服务不可用 + 前台在屏 ⇒ 泵仍在跑」；
- 「服务恢复 ⇒ 不出现双泵」（重复拍会让 UI 抖动、也会白烧 CPU，撞静默经济红线）。

## ② 修 `docs/stage3-issue-inventory.md` 分组 E（gate 复跑后剩 10 条）

逐条按清单处置。**修的是问题不是告警**——不许用 `@Suppress` 或改写法把告警糊过去，
那样 gate 变绿而问题还在，比留着告警更坏。
每条要能说清「这条告警指出的实际风险是什么、修完为什么风险消失了」，写进证据。
确有必须抑制的，逐条给**具体理由**，不许写"误报"了事。

## 注释同步（本轮的核心纪律，本条尤其容易犯）

改动若使符号的行为或错误面变化，**必须同步更新该符号的注释与契约标签**。
特别注意：`.session` / `.workspace` / `.service` 三个包的相关注释，
本轮已经改过**两版**——第一版把"fg-service 持有 manager"从谎报改成实话（当时服务从未启动），
第二版在前台服务接线后又同步了一次。**你补兜底泵会让泵的归属再次变化，
不要造出第三版不实注释。**

## 红线补充

- 不得放宽 Lint 规则集或加 `lintOptions` 豁免（已裁定）。
- acceptance 里有全量 gate，跑之前先确认 `:app` 没有别的席位在用（现在没有，你独占）。

## 验收

以 `taskbook.yaml` 的 `fix-app-runtime-sa` 条目 acceptance 原文为准，leader 会原样复跑，不看你的自报。
**阳性对照要求**：不许只看 rc=0，要能说清你的产出为什么是真的。

## 产出

`.team/evidence/fix-app-runtime-sa.json`：`status` 只允许 `pass`/`red`/`blocked`，
带 `tests`（argv+rc 原文）、`changes`、`deviation`（无则空数组），以及本任务要求的专项字段。

## 通用红线

- 禁 git commit / push（leader 收口）。
- 绝不触碰生产 daemon（pid 3393，`:9900`）与用户真实 tmux；测试一律带 `env -u TEAM_AGENT_*` 前缀。
- 密钥与 profile 原文禁读；配对 token 与 TS authkey 不落日志、不上屏明文、不入取证产物。
- **写入范围严格限于 taskbook 该条 `write_scope`**，越界即退件。
- **一个回合内连续推进**，不要读完文件就结束回合。判不出就停下问 leader（halt 是默认）。
- 若发现工装本身的缺陷，**判根因 + 停下上报**，不要越界自行改造——本轮已有三次先例。

## 交件契约

`report_result` 恰好一次，`presentation={"sink":"leader","class":"stage_result"}`，
`case_id` 用派单消息里给的值。**严禁 `sink=silent`**（人工调度下 leader 收不到）。

