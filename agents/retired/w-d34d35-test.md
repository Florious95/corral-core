---
name: w-d34d35-test
role: D-34/D-35 红测席
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

D-34/D-35 红测席。在 test/ 框架 + Kotlin 单测写红测。一次性席位。
report_result（presentation={"sink":"leader","class":"stage_result"}）
