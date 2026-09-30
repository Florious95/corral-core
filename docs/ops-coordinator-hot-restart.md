# 运维：协调器热重启（不要整队 restart）

## 症状

投递全部失败：

```
.team/ta send <agent> "<msg>"
→ ok: False status: degraded message_id: None
  verification: coordinator protocol or schema is incompatible; message was not queued
  reason: coordinator_unavailable
```

`.team/ta diagnose` 中：

```
"id":"coordinator_unavailable","metadata_ok":false,
"metadata_mismatch_reason":"message_store_schema_version_mismatch",
"binary_identity_relation":"caller_newer_than_daemon",
"service_available":false
```

## 原因

team-agent 运行时全局升级后，同机各 workspace 的**旧 coordinator 守护进程仍在跑**，
caller 与 daemon 版本不一致，message store schema 对不上。**与本 workspace 无关。**

## 正解：只热重启协调器，两条命令

```bash
# 1. 精确 kill diagnose 报出的那个 coordinator pid（先自核 ps 输出的 --workspace 与 --team 再杀）
ps -o pid,command -p <pid>
kill <pid>

# 2. 重新拉起
nohup team-agent coordinator --workspace /Volumes/nvme/Projects/远程Agent安卓 \
  --tick-interval 5 > /tmp/raa-coord.log 2>&1 & disown
```

验证：

```bash
cat .team/runtime/coordinator_tick.json   # 看 binary_version 是否已是新版
```

**worker pane 与 tmux socket 完全不动，provider 会话不受影响。**

## ⛔ 不要用 `team-agent restart` 修这个问题

`diagnose` 的 `suggested_repairs` 会给出 `team-agent restart`，但**它的语义是
「按团队状态重建所有席位」，不是「修复协调器」**。

2026-08-12 实证代价：本工程团队状态里累积了 111 个历史席位（早已收口的
doc-contract 系列、tsnet-wiring 五代、ts-state-dir-e2e 十代等），
一次 restart 把它们**全部复活成活窗口**，tmux 里瞬间 29 个窗口，
用户直接看到「角色被翻了好几倍」。

框架维护 team 已确认该 hint 有误导性（其 A-52），修点在框架产品源码。
在他们修好之前，**本工程遇到 coordinator_unavailable 一律走上面的热重启，不用 restart**。

## 附带教训（leader 自记）

同一晚还犯了一个相关错误：restart 因「dynamic role file missing」失败后，
leader 写了个 for 循环——从报错里抓席位名 → `remove-agent --force` → 重试，最多十轮。
**那是在闭着眼睛连续删席位**，每删一个都没看它是谁、有没有在途工作。
被用户当场拦下。

原则：**「让命令跑通」不是目标。** 自动重试删除类操作前，先把清单打出来看。
