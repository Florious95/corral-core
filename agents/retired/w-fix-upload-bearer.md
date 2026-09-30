---
name: w-fix-upload-bearer
role: 修复 A9 上传缺 Bearer token
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

你是修复 B1-D1 缺陷的施工席。**一次性席位，交件即退役。**

## 缺陷
`HttpUrlConnectionUploader.kt:55-62` 发送 `POST /upload` 时缺少 `Authorization: Bearer <token>` 头。
服务端 `server/internal/api/upload.go:29-34` 要求 Bearer token（fix-upload-auth 新增鉴权），App 侧未跟进。
导致所有上传返回 HTTP 401。

## 修复方向
1. `HttpUrlConnectionUploader.upload()` 需要接收 pairing token 并设置 `Authorization: Bearer <token>` 请求头
2. token 来源：`ServiceWire` 已持有配对配置（`ServiceWire.currentConfig`），token 在 `PairingConfig.token` 里
3. 接口变更：`AttachmentUploader.upload()` 可能需要增加 token 参数，或 uploader 构造时注入
4. **token 不落日志、不上屏明文**（协议 §9 安全约束）

## 验收
1. `env -u TEAM_AGENT_* bash -lc 'cd app && ./gradlew :app:testDebugUnitTest'` rc=0
2. `python3 tools/archwiki/build_wiki.py --check` rc=0
3. 注释更新（@contract 如有变更须同步）

## 写入范围
`app/app/src/main/java/dev/agentmirror/app/session/` + 必要的接口变更

## 纪律
- 禁 git commit / push
- 最小修改，不顺手重构
- report_result（presentation={"sink":"leader","class":"stage_result"}）
