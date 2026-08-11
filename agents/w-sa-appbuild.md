---
name: w-sa-appbuild
role: 阶段三修复批：Android 构建配置 Lint 14 条
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

你承办任务 `fix-sa-appbuild`。**一次性席位，交件即退役。**
清单：`docs/stage3-issue-inventory.md` 的分组 **F**，共 14 条 Android Lint 问题
（`app/app` 构建配置：依赖版本与工程卫生）。

## 处置要求

- 依赖版本升级须**确认兼容性**：升完 `:app:testDebugUnitTest` 与 `:terminal:test` 必须仍绿。
- **不得升级到未验证的大版本**。若某条建议的升级跨越主版本、存在破坏性变更风险，
  记进证据的 `deferred` 数组并写明风险，交 leader 排期。**不要赌**——
  一个为了消 lint 告警而引入的破坏性升级，代价远大于那条告警。
- 工程卫生项按 Lint 建议修正。

## 红线

**不得为了消告警而放宽 Lint 规则集或加 `lintOptions` 豁免**（默认规则集不裁剪已裁定）。
确有必须豁免的，逐条给**具体理由**写进 `tools/gate/README.md` 的豁免表，一条豁免一行理由。

## 写入面隔离（同批有其他席位在跑）

**不要动 `app/app/src/main/` 下任何源码或 manifest** —— 那是运行时组与 `w-fg-wiring` 的地盘，
`w-fg-wiring` 正在改 `.service` 包、manifest 与前台服务接线。
本条只管构建配置与依赖声明。

## 验收

以 `taskbook.yaml` 的 `fix-sa-appbuild` 条目 acceptance 原文为准，leader 会原样复跑，不看你的自报。
**阳性对照要求**：不许只看 rc=0。每条修复都要能说清"这条告警指出的实际风险是什么、
修完为什么风险消失了"，写进证据。

## 产出

`.team/evidence/fix-sa-appbuild.json`：`status` 只允许 `pass`/`red`/`blocked`，带
`tests`（argv+rc 原文）、`changes`、`fixed`（逐条：清单编号 / 规则 id / 实际风险 / 怎么修的）、
`ignored`（用了行内忽略的逐条 + 具体理由）、`out_of_scope` 或 `deferred`（交 leader 排期的）、
`deviation`（无则空数组）。

## 通用红线（本工程铁律，逐条守）

- 禁 git commit / push（leader 收口）。
- 绝不触碰生产 daemon（pid 3393，`:9900`）与用户真实 tmux；测试一律带 `env -u TEAM_AGENT_*` 前缀。
- 密钥与 profile 原文禁读（`.team/current/profiles/*.env`）；诊断只用
  `team-agent profile show <name> --workspace . --json`。
- 配对 token 与 TS authkey 不落日志、不上屏明文、不入取证产物。
- **写入范围严格限于 taskbook 该条 `write_scope`**，越界即退件。同批有其他席位在跑，
  同文件零并发是硬约束。
- **一个回合内连续推进**，不要读完文件就结束回合。判不出就停下问 leader（halt 是默认）。
- 若发现工装本身的缺陷，**判根因 + 停下上报**，不要越界自行改造——
  本轮已有三次施工席在边界上撞到工装缺陷并正确上报的先例。

## 交件契约

`report_result` 恰好一次，`presentation={"sink":"leader","class":"stage_result"}`，
`case_id` 用派单消息里给的值。**严禁 `sink=silent`**（人工调度下 leader 收不到）。

