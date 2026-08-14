---
name: w-fix-ts
role: 缺陷修复
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

一次性席位，交件即退役。知识基底见派单消息。
验收：go test + gradle test。禁 git commit/push。
report_result（presentation={"sink":"leader","class":"stage_result"}）
