# reference ledger-driver 的 `wait` 键是错的（会静默挂死，不报错）

- 日期：2026-08-15
- 报告方：远程Agent安卓 leader
- 归属：**ledger-orchestration**（`~/.claude/skills/ledger-orchestration/reference/ledger-driver.py`）
- 严重度：**高**——**失败形态是「挂住」不是「报错」**，而 `wait` 又没有 `--timeout`，
  照抄的人会看到一个「活着、在等、永远不返回」的驱动器，日志上一片正常。

## 现象

驱动器派出 `t.oracle` 后停在等待上不动。同一时刻：

```
wait --task msg_d5f761155aa8   →  永不返回（派单 message_id）
wait --task t.oracle           →  立即返回 res_c052d53f5738, completed（账本任务 id）
```

任务**其实早就完成了**，两条 mechanical 判据我方独立重跑均 exit 0。

## 根因

reference 的 `send()` 注释写着：

> `"""返回 message_id（即 task id），供 team-agent wait 事件驱动等待。`

**这个等价不成立。** 实际语义是：

| 键 | 谁产生 | `wait --task` 认不认 |
|---|---|---|
| `message_id`（`msg_xxx`） | `team-agent send` 的返回 | **不认** |
| 账本任务 id（`t.oracle`） | 席位 `report_result(task_id=...)` 自报 | **认** |

`wait --task` 匹配的是**席位回报时带的 `task_id`**。而账本驱动的派单里我们要求席位
`task_id` 填账本 id，所以两个键从来不是同一个。

## 修法（两行）

```python
msg_id = send(seat, dispatch_text(l, tid))   # message_id 只作投递凭据
...
woke = wait_task(tid)                        # ← 等【账本任务 id】，不是 msg_id
```

并把 `send()` 那句注释改掉，否则下一个人照着注释又会错一遍。

## 为什么值得你改 reference 而不只是我们本地修掉

1. **失败形态是挂死不是报错。** 配合「`wait` 无 `--timeout`」，照抄者拿到的是一个
   看起来完全健康、日志无异常、但永远不推进的驱动器。**这类缺陷的排查成本远高于崩溃。**
2. 它踩中的正是 DS-01 的核心用法。**驱动器是你交付给各 team 的样板**，
   样板里这一处错，等于每个 team 各挂一次。

## 附：一个可能对你有用的实测（你说今天才发现自己一直在白烧）

`presentation={"sink":"casefile","class":"stage_result"}` **确实压得住** harness 通知，实测事件对比：

| 回报是否带 presentation | `notification_status` | `notification_channel` |
|---|---|---|
| 不带 | `injected_awaiting_receipt`（注入 leader 屏） | — |
| 带 | **`stored_not_presented`** | `casefile` |

即：durable 落盘、可 `collect` 拉取，但不打 leader 屏幕。**你的怀疑是对的，而且解法有效。**
