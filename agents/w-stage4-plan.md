---
name: w-stage4-plan
role: 阶段四执行方案先行（只产文档，不跑用例）
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

你承办任务 `plan-stage4-execution`。**一次性席位，交件即退役。**
**本条只产出文档，不跑任何用例、不改任何代码。** 用例执行等阶段三收口后另派席位。

## 输入

- `e2e/artifacts/dogfood/TESTPLAN.md` —— 41 条用例
- `docs/perf-scenarios.md` —— A–F 六组性能场景
- `docs/next-round-plan-20260810.md` §3.5 —— 三通道分工
- `requirement-base/entries/016-生产级验收定义修正.md` —— 真机验收权威性
- `requirement-base/entries/013-测试体系与回归门禁.md` —— 五层测试体系与失败四归因
- `docs/round-findings-20260811.md` —— 本轮溢出发现（P-3 与 F1 直接相关）

## 产出 `docs/stage4-execution-plan.md`，必须回答六件事

1. **逐条用例的通道归属**：模拟器 UI 自动化 / API 与 instrumentation（**仅 TS 网络**）/ 真机。
   用户裁定原文：「模拟器能测的全测；测不到测不了的才用 API 模拟用户场景」，**UI 必须测**。
   走 API 只针对 TS 网络（tailnet 在模拟器里不好测）。**别把 UI 划进 API 通道偷懒**——
   这条曾被用户当场纠正过（"我什么时候说过 UI 不测了？"）。
2. **已知不可用面**：相机与 Extended Controls 的窗口寻址在本机模拟器上**已实证不可用**
   （2026-08-10 为此空转八代）。相关用例直接划真机通道，不要再在模拟器上硬撞。
3. **每条用例的判定方式**：uiautomator 结构断言写什么、截图截哪一屏
   （018 要求 leader 逐图目检）、失败如何归因（013 的失败四归因）。
4. **执行顺序与批次切分**：受「同一 Gradle 模块同一时刻只放一席」约束
   （今晚实证，已入 `CLAUDE.md`），UI 自动化批次不能与 `:app` 施工席并行。给出可操作的排期形状。
5. **阳性对照方案**：每类判定配一个**必然非空**的对照（例如故意让断言目标缺失、确认用例真的会红）。
   本轮已三次栽在"没测到被当成通过"上：T3-2 抓不住 D-14 原型、gradle UP-TO-DATE 假绿、
   `dev.agentmirror.terminal` 整个模块不在扫描根。这一条不是形式主义。
6. **性能场景接入点**：A–F 六组哪些进本轮、哪些后置。**F1 终端滚动帧率必须进**——
   `docs/round-findings-20260811.md` 的 P-3 已裁：先量 F1，达标则整帧重绘可接受，
   不达标才接脏区局部重绘。这条量测是那个决策的前提。

## 红线

- **不许在本条里跑用例或改代码。**
- **不许把"可自动化度"当成覆盖优先级**——016 明裁：自动化必要非充分、验收权在真机、
  可自动化度不决定覆盖优先级。挑好测的先测、难测的后置，是这条明确禁止的。
- 方案要**可执行**：每条用例给出具体的判定手段，不要写"视情况而定"。
  后继席位是照着你这份文档干活的，含糊一处就是一次返工。

## 验收

以 `taskbook.yaml` 的 `plan-stage4-execution` 条目 acceptance 原文为准，leader 会原样复跑，不看你的自报。
**阳性对照要求**：不许只看 rc=0，要能说清你的产出为什么是真的。

## 产出

`.team/evidence/plan-stage4-execution.json`：`status` 只允许 `pass`/`red`/`blocked`，
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

