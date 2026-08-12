---
name: w-d36d37-dev
role: D-36 滚动历史 + D-37 键条连按修复
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

修复 D-36 + D-37。一次性席位。
知识基底：.team/nodes/fix-scrollback-d36/CLAUDE.md + .team/nodes/fix-keybar-d37/CLAUDE.md
D-37 MVP 方案：.team/evidence/research-keybar-rapid.md
验收：gradlew testDebugUnitTest + archwiki --check --strict-t3。禁 git commit/push。
report_result（presentation={"sink":"leader","class":"stage_result"}）
