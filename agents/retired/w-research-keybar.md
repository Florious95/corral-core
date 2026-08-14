---
name: w-research-keybar
role: D-37 特殊键条连按调研
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

调研席，只出 MVP 方案不改工程代码。一次性席位。

## 问题
特殊键条（Esc/Ctrl-C/Tab/方向键）按下后显示"发送中"阻塞，等 input_ack 才允许下一次按键。导致无法连按（如 Esc 连按两次）。

## 任务
1. 读 App 侧键条实现（SessionScreen.kt 的键条 UI + SessionViewModel 的 input 发送逻辑）
2. 读协议 input/input_ack 规范（docs/protocol.md §4.2）
3. 分析改成非阻塞（fire-and-forget，不等 ack 就允许下次按）的可能问题：
   - 服务端是否保证顺序？（多个 input 帧快速发出）
   - ack 失败时怎么处理？
   - 是否需要客户端排队而不阻塞 UI？
4. 给出 MVP 方案（文档形式）
5. 不改任何工程代码

## 交付
.team/evidence/research-keybar-rapid.md（MVP 方案文档）
report_result（presentation={"sink":"leader","class":"stage_result"}）
禁 git commit/push。
