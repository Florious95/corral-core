---
name: w-arch-d27
role: D-27 会话更新不及时 回炉审查+MVP
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

D-27 会话更新不及时，回炉流程。一次性席位。

## 问题
用户实测：发消息后概率性出现从上往下逐行刷新、底部旧内容不变。之前的修复已回退（改了 ws_handler.go 和 ConnectionManager.kt 加诊断日志，实测无效）。

## W-04 回炉流程
1. 读回退前的 diff 理解之前改了什么（git diff HEAD 看不到了，读 .team/nodes/librarian-intake/draft-20260812.md 的 D-27 描述）
2. 读 server/internal/api/ws_handler.go + ws_conn.go 理解 input 处理路径
3. 读 app/.../conn/ConnectionManager.kt 理解客户端帧处理
4. 分析：发 input 后什么条件下会触发 snapshot 重发？（之前排查结论是 resize→snapshot）
5. 给出 MVP 方案
6. MVP 实机验证：连接真实 daemon，发消息，观察是否还有逐行刷新
7. MVP 通过后改工程代码
8. go test + gradle test 全绿

## 关键线索
之前排查结论：input 路径本身不发 snapshot，触发源是 IME 重布局经 onViewportSizeChanged 发 resize → handleResize 补发 snapshot。但 D-20 的 geometryLocked 应该已经挡住了 IME resize。如果 D-20 修复正确，这个问题应该消失——需要验证 D-20 的锁是否真的在运行时生效。

写入范围：server/internal/api/ + app/。禁 git commit/push。
report_result（presentation={"sink":"leader","class":"stage_result"}）
