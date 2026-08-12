---
name: w-qa-listing
role: QA 现场定位：App 看不到工作席位
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
---

QA 现场定位席。一次性席位。

## 问题
用户在 App 上看不到正在工作的 librarian 席位。Leader 确认 librarian turn_active=1（在工作），daemon listing 也能查到会话，但 App 上不显示。

## 任务
1. 用 test/ 框架的 WS 客户端连 daemon（ws://127.0.0.1:9900/ws），拉 listing，确认 librarian 的 pane 是否在列表里
2. 检查 daemon 日志（.team/logs/agentmirrord-prod.log）有没有 listing/list_delta 相关错误
3. 如果日志没有有用信息，记录"日志不足以定位此问题"（验证 P-05 日志完备性缺口）
4. 尝试定位：App 为什么看不到某些会话——是 daemon 没发 list_delta、还是发了但 App 没渲染

## 纪律
禁 git commit/push。report_result（presentation={"sink":"leader","class":"stage_result"}）
