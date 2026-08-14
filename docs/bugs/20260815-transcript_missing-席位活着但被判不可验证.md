# `transcript_missing`：席位明明活着，框架却判它不可验证——且消息静默不注入

- 日期：2026-08-15
- 报告方：远程Agent安卓 leader
- 归属：**team-agent 框架**（席位归因 / 消息注入）
- 严重度：**高**——两条叠加：**①消息静默丢失**（落库但不注入活会话，席位从没看见）
  **②诊断输出诱导破坏性恢复**（`reset_proof: weak` 看起来像「席位死了」，实际席位好好的）

## 现象

驱动器连续 3 次把 `t.contract` 派给 `advisor`，`send` 每次都 `ok: True` 并返回 message_id。
**席位一次都没动**（`worker_state: PROBABLY_IDLE`，落盘物零产出）。

`team-agent inbox advisor` 里能看到消息正文，末尾带：

```
[status=submitted_unverified attempts=1
 error=transcript_missing:provider=claude_code,
 rollout=.../.team/runtime/provider-config/advisor/claude/projects/-Volumes-.../dadd6f50-….jsonl]
```

## 关键：那个 rollout 文件**是存在的**

```
-rw-------  1 alauda  staff  1015650  8月 15 03:29  dadd6f50-81c4-4d45-8627-3591aa66a7db.jsonl
```

1 MB，mtime 就在报错前后。**不是「文件不在」，是框架没找到它**（路径推导或 session id 对不上）。

## 恢复尝试与结果

| 动作 | 结果 |
|---|---|
| `start-agent advisor` | `Noop`（窗口在，无可修） |
| `reset-agent advisor --discard-session` | `new_session_id: ea75e896-…` 生成，但 **`capture_state: transcript_missing`、`reset_proof: weak`** 依旧 |

## 席位其实是好的（最小落盘探针实证）

不信框架的状态字段，改用**落盘物**判活：

```
$ .team/ta send advisor '[存活探针] 把文本 ok 写进 /tmp/advisor-alive.txt，然后停。'
   ok: True
$ cat /tmp/advisor-alive.txt
   ok
```

⇒ **席位完全正常。** 坏的是归因层，以及复位前那个会话的消息注入。

## 危害

1. **静默丢消息**：`send` 返回 `ok: True` + message_id，调用方拿到的是成功信号，
   而消息**永远不会进入席位的活会话**。对编排器来说这是最坏的形态——
   它会去 `wait` 一个永远不会发生的完成。
2. **诊断输出诱导破坏性恢复**：`reset_proof: weak` / `transcript_missing` 读起来像「这个席位废了」。
   我差一点就把 advisor 换掉重派——**而它是好的**。
   「归因证明不了」和「席位死了」在当前输出里**分不开**。

## 诉求

1. **`transcript_missing` 要打印它期望的路径 vs 实际找到的**，让人能判断是「文件不在」还是「路径算错了」。
   本例中文件明明在，而错误串只说 missing。
2. **`submitted_unverified` 不该长得像成功。** `send` 既然已经知道注入没验证成功，
   返回值就不该是裸 `ok: True`——至少带一个 `injected: false`，让编排器能当场停而不是去 wait。
3. 判活建议以**落盘物**为准而非状态字段——这条我们已写进本工程纪律，供参考。
