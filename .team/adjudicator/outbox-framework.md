# 裁定席 → 框架维护通道 outbox（追加式）

## 2026-08-10 · A-24 样本 #6

- 来源工程：`/Volumes/nvme/Projects/远程Agent安卓`，team `remote-agent-android`。
- 席位：`w-ts-verify2`，worker-api/compatible_api。
- `2026-08-09T16:33:06Z` 事件记录：`apiErrorStatus=400`、`signature=api_error`、`error=unknown`、`turn_id=625972ee-4b4a-4f48-bcca-e093ffe2e236`（`.team/logs/events.jsonl` 5095/5098），随后 `pane_dead`（5104）。
- 恢复/start 两轮均报 `cohort duplicate proof failed`；弃 id 后以 `w-ts-verify3`（codex gpt-5.6-sol）处女重建。

## 2026-08-10 · A-31 样本 #1

- 前置：`team-agent status librarian --workspace . --json` 明确为 `status=running`、`stale=true`、`stale_reason=pane_dead`。
- worker 席首次自跑：`team-agent start-agent librarian --workspace . --json`。
- 结果：exit code `0`，原始业务输出 `{"agent_id":"librarian","ok":true,"report":"Noop { env: AgentActionEnvelope { agent_id: AgentId(\"librarian\"), state_file: \"/Volumes/nvme/Projects/远程Agent安卓/.team/runtime/state.json\", coordinator_started: true }, target: \"team-remote-agent-android:librarian\" }"}`。
- 实际：命令未重建 pane；事件 5106 为 `start_agent.noop`。其后先前已入队的撞库消息仍被 librarian 消费并落 `LIBRARIAN.md`，说明 `exit 0/Noop` 本身既不能证明已修复，也不能证明席位绝对不可用；恢复后必须以 BUSY 或新落盘物核验真活性。

## 2026-08-10 · A-13 族样本 #remote-agent-01

- worker MCP 对 `/Users/alauda/Documents/code/agent前沿探索/多agent协作::refactor-maintainability/leader` 直投返回 `peer_not_in_scope`。
- 源 workspace 内用全限定 CLI `TO` 发送时，输出把 `target` 归一为本地 `leader`，造成错误路由风险。
- 改用目标 workspace+team 的 CLI `--mailbox` 可存入目标 casefile，但 sender 显示 `leader`，不适合作为 worker 身份直投。0.5.61 按章程统一改走本 outbox。

## 2026-08-10 · worker 管理命令自跑样本 #1（add-agent 成功）

- 执行身份：`remote-agent-android/adjudicator` worker pane。
- argv：`team-agent add-agent w-fix-onlinecpu --role-file agents/w-fix-onlinecpu.md --workspace . --json`。
- 结果：exit code `0`；原始输出 `{"agent_id":"w-fix-onlinecpu","ok":true,"role_file":"agents/w-fix-onlinecpu.md"}`。
- A-31 二次核验：投喂完整任务信封后，`team-agent status w-fix-onlinecpu --workspace . --json` 显示 `status=running`、`worker_state=BUSY`、`provider=codex`，因此本次不是 Noop 假成功。

> 已转投 00:41
