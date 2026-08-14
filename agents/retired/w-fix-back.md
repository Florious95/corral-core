---
name: w-fix-back
role: 修复侧滑返回退出 App（D-23）
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

修复安卓侧滑返回直接退出 App（D-23）。一次性席位。

知识基底：.team/nodes/fix-back-gesture/CLAUDE.md

问题：Android 手势导航的返回手势退出 App 而不是返回上一级。
需要在 Navigation 或 Activity 层正确处理 OnBackPressedCallback。
会话页→工作区列表→配对页的返回栈要正确。

验收：gradlew testDebugUnitTest。禁 git commit/push。
report_result（presentation={"sink":"leader","class":"stage_result"}）
