---
name: w-arch-render
role: 四连渲染缺陷二次回炉（D-28/D-34/D-35/D-36）
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
---

四连渲染缺陷二次回炉，走 W-04 MVP 流程。需多模态能力（看截图判断渲染）。

知识基底：.team/nodes/fix-rendering-d34-d35/CLAUDE.md + .team/nodes/fix-scrollback-d36/CLAUDE.md

验收：gradlew testDebugUnitTest + archwiki --check --strict-t3 + 实机 MVP 验证。
禁 git commit/push。report_result（presentation={"sink":"leader","class":"stage_result"}）
