# driver 探路：`send --json` 输出格式验证（2026-08-15）

> 探路席：`dev-state`（r.dev-state）。仅探路，不改代码。探路方式：对自身 `dev-state` 发无实义消息，
> `--mailbox` 与 live 两种变体各打一发，用 orchestrator 的 `json.loads` 解析路径实测通过。

## 结论

**`ta send TO MSG --workspace . --json` 输出一个单对象 JSON（rc=0），顶层必有 `message_id`，
parse 键是 `message_id`，不是 `case_id`。** orchestrator（`.team/orchestrator.py`）用 `json.loads` 取
`sent["message_id"]` 当 case_id——实测对该输出成立。**driver 适配应同样用 `message_id`。**

## 两个变体的实际输出（字段逐字）

### live 变体（不传 `--mailbox`，orchestrator 派单同款）

```json
{
  "ack_forced_off": false,
  "agent_id": "dev-state",
  "channel": null,
  "content_length_bytes": 69,
  "delivered": false,
  "delivery_status": "pending",
  "message_id": "msg_f3869bde10e0",
  "message_status": "accepted",
  "ok": true,
  "reason": null,
  "reminder": "Message queued; coordinator will notify when the worker receives it. Do not poll the worker terminal with capture-pane.",
  "sender": "/Volumes/nvme/Projects/远程Agent安卓::remote-agent-android/dev-state",
  "stage": null,
  "status": "queued",
  "target": "dev-state",
  "verification": null
}
```

### `--mailbox` 变体（durable 存储，无 live 注入）

```json
{
  "ack_forced_off": false,
  "agent_id": "dev-state",
  "channel": "casefile",
  "content_length_bytes": 70,
  "delivered": false,
  "delivery_status": "stored_only",
  "message_id": "msg_a2b81ba5bb10",
  "message_status": "stored_only",
  "ok": true,
  "reason": null,
  "reminder": "Message queued; coordinator will notify when the worker receives it. Do not poll the worker terminal with capture-pane.",
  "sender": "/Volumes/nvme/Projects/远程Agent安卓::remote-agent-android/dev-state",
  "stage": null,
  "status": "stored_only",
  "target": "dev-state",
  "verification": "durable_without_live_inject"
}
```

## 两变体差异（仅 delivery 元数据，parse 无关）

| 字段 | live | --mailbox |
|---|---|---|
| `channel` | null | `"casefile"` |
| `delivery_status` | `"pending"` | `"stored_only"` |
| `message_status` / `status` | `"accepted"` / `"queued"` | `"stored_only"` / `"stored_only"` |
| `verification` | null | `"durable_without_live_inject"` |
| `delivered` | false | false |

`message_id`、`ok`、`sender`、`target`、`agent_id` 两变体同构。

## parse 键注意（2026-08-15 实发教训）

- **JSON 键是 `message_id`，值是 `msg_*`。** 误找 `case_id` 必落空。
- **`ok` 是 JSON 布尔 `true`**（小写，`"ok": true`）。
  - orchestrator 的 `ta_json` 走 `json.loads`，布尔解析 OK。
  - `tools/ledger-driver.py` 的 `send()` **不传 `--json`**，用**文本**解析找 `ok: True`（大写 True）——
    若误传 `--json`，文本里是 `"ok": true`（小写 true + 引号键），对不上 → send 被误判失败
    （实发 2026-08-15，见 ledger-driver.py:121-122）。**driver 若用 `--json` 就必须 `json.loads`，
    不能复用那套文本匹配。**

## 对 driver 的建议（探路结论，施工由 driver 席定）

1. 取关联键：`json.loads(stdout)["message_id"]`——这就是 orchestrator 的 case_id，全链唯一键。
2. 成功判据：`rc==0 and json.get("ok") is True and json.get("message_id")`。`delivered:false` 不代表失败，
   它只是「已入队未实时投达」；durable/mailbox 变体下 `delivery_status=stored_only` 是预期。
3. 别被 `reminder` 带偏：那是给人类观察者的，不是 parse 目标。
4. live 变体 `channel:null`、mailbox 变体 `channel:"casefile"`——若 driver 想区分投达方式，看
   `delivery_status`（`pending` vs `stored_only`），别看 `channel`。
