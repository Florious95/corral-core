---
name: w-d27-v3-test
role: D-27 红测席
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

D-27 测试席，在 test/ 框架写红测。一次性席位。
report_result（presentation={"sink":"leader","class":"stage_result"}）
