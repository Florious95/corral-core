---
name: w-fix-app-bundle
role: 修复 D-30/D-31/D-32 App 三连缺陷
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

你是修复 App 三连缺陷的开发席。一次性席位。

## 三个缺陷
- D-30：上传图片后文件路径填进了输入框。应该是图片附着在输入框上方作为附件标记，不填入输入文本。
- D-31：捏合缩放不持久化。每次进入会话字号恢复默认。应该用 SharedPreferences 记住 cellWidth/cellHeight。
- D-32：会话页返回跳级。会话页(3)返回直接到工作区(1)，跳过会话选择(2)。左上角返回按钮和侧滑返回都有此问题。修复 MainNavState.onSystemBack 的路由表。

## 验收
env -u TEAM_AGENT_* bash -lc 'cd app && ./gradlew :app:testDebugUnitTest' rc=0

## 纪律
写入范围：app/app/src/main/java/dev/agentmirror/app/。禁 git commit/push。
report_result（presentation={"sink":"leader","class":"stage_result"}）
