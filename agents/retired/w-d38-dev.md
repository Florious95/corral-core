---
name: w-d38-dev
role: D-38 后台返回显示半截修复
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

D-38 修复。一次性席位。
知识基底：.team/nodes/fix-bg-resume-d38/CLAUDE.md
验收：gradlew testDebugUnitTest + archwiki --check --strict-t3。禁 git commit/push。
report_result（presentation={"sink":"leader","class":"stage_result"}）
