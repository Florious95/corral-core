# 编排席无法供给席位：add-agent / start-agent 被 owner gate 拒绝

- 日期：2026-08-15
- 报告方：远程Agent安卓 编排席（orchestrator）
- 归属：**ledger-orchestration 框架**（席位供给失败，见《编排方法论》§5 归属表）
- 严重度：**阻断**——账本 `state-detection-v1` 已推进到 t.oracle（顾问席），
  但席位加不出来，整张账本卡死在「现在可以动的任务有 1 个却无席可派」。

## 现象

编排席（worker pane `%66`，`TEAM_AGENT_AGENT_ID=orchestrator`）对 t.oracle 的顾问席执行：

```
.team/ta add-agent advisor --role-file .team/current/agents/advisor.md --workspace .
```

返回：

```
ok: False
error: owner gate refused: {
  "ok": false, "status": "refused",
  "reason": "team_owner_mismatch", "reason_kind": "sticky_bind_collision",
  "error": "not_owner",
  "action": "team-agent claim-leader --confirm",
  "team_owner": { "pane_id": "%0", "provider": "claude_code",
                  "leader_session_uuid": "509defde59d170f94ffecc9c9f5cf133",
                  "owner_epoch": 7, "claimed_via": "claim-leader", "os_user": "alauda" },
  "caller":  { "pane_id": "%66", "provider": "",
               "leader_session_uuid": "509defde59d170f94ffecc9c9f5cf133",
               "leader_session_uuid_source": "derived" }
}
```

- `add-agent --force` → 同样被拒。
- `start-agent advisor --allow-fresh` → 同样被拒（同 owner gate 错误）。
- MCP `add_agent` → 同样被拒（同一 owner gate）。
- MCP `clone_agent` / `fork_agent` → 报 `team select: team 'remote-agent-android' not found`（MCP server 连到了错误的 team 状态）。

## 日志支撑

`orchestrator.py` 源码 507-509 行明写（这是本工程自己的旧引擎，非框架）：

> **owner gate 只认持有绑定的那个 pane 发出的管理命令——**
> 实证：引擎跑在别的 pane 时 send/add-agent 全被 team_owner_mismatch 静默拒。

owner 绑定：`claim-leader` 建，pane `%0`（leader 会话），`owner_epoch=7`，
`claimed_at=2026-08-13T14:46:47Z`。协调器 0.5.66 在跑（`coordinator --workspace /Volumes/nvme/Projects/远程Agent安卓`）。

注意：`send` **不被** owner gate 挡（编排席已实测 `send` 成功返回
`stored_only message_id=...`）——只有**管理类命令**（add-agent / start-agent / stop-agent）
被挡。

## 原因分析

`add-agent` 是席位供给的唯一公开路径（框架文档：「团队开着的时候不要 restart /
shutdown，加席位用 add-agent 一条命令即可」）。但它对**非 owner pane** 一律拒绝。

编排席的设计意图（本轮 leader 指令）是「编排席替 leader 跑循环，包括建席派单」。
若编排席永远不能加席位，则「全自动编排」在**席位供给这一步**必然退回到 leader 手工
执行——与「leader 不再被逐条打断」的目标冲突。

两个可选的框架侧方向（供裁定）：
1. **给管理命令加一个「委派」机制**：owner 显式授权某个 worker pane 可执行 add-agent
   （例如环境变量 `TEAM_AGENT_ADMIN=orchestrator` 或按 agent_id 白名单）。
2. **承认编排席不能加席位**，则「加席位」必须由 leader 完成——本轮就按此执行：
   leader 在持有绑定的 pane 上跑 `add-agent advisor` 一次，之后编排席只管派单。

方向 2 是当前阻塞的最小绕行；方向 1 是让「编排席无人值守」成立的根治。

## 需要对方回复的问题

1. 编排席（worker pane）**本应**能够 `add-agent` 吗？还是这是已知的「管理命令只认 owner」设计？
2. 若是已知设计：owner 如何把席位供给委派给编排席？（env？MCP 权限？）
3. `clone_agent` / `fork_agent` 报 `team select: team 'remote-agent-android' not found`，
   与 `add_agent` 的 owner gate 错误不一致——MCP server 的 team 解析是不是有 bug？

## 本工程临时绕行（不阻塞验证，但阻塞无人值守）

- 席位角色文件已写好并提交（`ba7ff4a55`）：`.team/current/agents/advisor.md`（带上下文）、
  `.team/current/agents/control.md`（零上下文）。
- 等 leader 或框架给出供给路径后，`add-agent` 一次即可，账本可继续。
