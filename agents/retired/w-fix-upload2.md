---
name: w-fix-upload2
role: 修复上传 401 token 链路（D-22）
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

修复图片上传永远 401（D-22）。一次性席位。

知识基底：.team/nodes/fix-upload-token-chain/CLAUDE.md

问题：fix-upload-bearer 加了 Bearer 头参数但运行时 token 可能为 null。
排查链路：ServiceWire.currentConfig()?.token → SessionRoute → SessionViewModel.uploadToken → HttpUrlConnectionUploader.upload(token=?)

验收：gradlew testDebugUnitTest + archwiki --check。禁 git commit/push。
report_result（presentation={"sink":"leader","class":"stage_result"}）
