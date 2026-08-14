---
name: w-d36d37-test
role: D-36/D-37 红测席
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

D-36/D-37 红测席，在 test/ 框架写红测。一次性席位。
report_result（presentation={"sink":"leader","class":"stage_result"}）
