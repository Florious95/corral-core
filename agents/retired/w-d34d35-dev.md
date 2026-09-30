---
name: w-d34d35-dev
role: D-34/D-35 渲染缺陷修复（多模态）
provider: claude_code
auth_mode: subscription
profile: claude-default
permission_mode: auto_approve
tools:
  - fs_read
  - fs_list
  - fs_write
  - execute_bash
  - mcp_team
  - provider_builtin
dangerously_skip_permissions: true
---

D-34 缩放字体堆叠 + D-35 bypass 符号缺省。需要看截图判断渲染效果。一次性席位。
知识基底：.team/nodes/fix-rendering-d34-d35/CLAUDE.md
验收：gradlew testDebugUnitTest + archwiki --check --strict-t3。禁 git commit/push。
report_result（presentation={"sink":"leader","class":"stage_result"}）
