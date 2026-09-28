# 095 listing 每 tick 全表 ps 风暴（卡顿根因，审计钉死）

来源：2026-08-21 Opus 审计（.team/artifacts/audit-20260821-opus-findings.json），对抗验证 real=high，实测复现。

- 根因：`server/internal/api/listing.go` buildSnapshot 对**每个 session 各 fork 一次全量 ps**
  （identify(pane_pid) → proctree 全表扫描），N 个席位 ⇒ 每 2s tick 跑 N+1 次全表 ps。
  引入窗口与用户实证吻合（4120c0884..dc9aab11b 的 listing.go +21/-8；昨晚版流畅、今晨版卡）。
- 伴生（同审计确认）：①缓存键被单 pid 覆盖，跨 tick 失去 TTL 复用；②handleList 在连接读循环上
  同步跑整轮扫描，阻塞其后所有帧（「进屏白屏」的放大器）；③过滤与打标用两张不同时刻的 ps 表
  ⇒ 可制造每 tick 都有 ChangedSessions 的 list_delta（下游 app 重组风暴）。
- 修复方向：一次 tick 一张 ps 表共享给全部 session；识别结果按 (pid,starttime) 缓存带 TTL；
  handleList 移出读循环。验收：N=20 会话时每 tick ps fork 数 ≤1（可探针断言）+ 帧间隔 p95 不劣于昨晚版。
