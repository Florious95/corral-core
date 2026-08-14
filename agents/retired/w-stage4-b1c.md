---
name: w-stage4-b1c
role: 阶段四 B1 收尾席（A5/A9/A9a + REPORT 汇总）
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

你是阶段四 B1 批次的收尾席。前序席位已完成 10/13 条用例但因 API 错误退出。**一次性席位，交件即退役。**

## 任务
1. 执行剩余 3 条用例：A5（聚合状态徽章）、A9（发图注入）、A9a（上传失败可见）
2. 汇总全部 13 条用例结论到 REPORT-B1.md
3. 写证据 + report_result

## 知识基底
`.team/nodes/stage4-b1-ui-firsttouch/CLAUDE.md`（basegen 编译产物）。

## 已完成的用例（前序席位产出，截图已在 e2e/artifacts/stage4-execution/）
A1、A2、A2a、A3、A3a、A3b、A4、A6、A7、A8 — 你需要读前序截图和 uiautomator dump 结果来判定这些用例的 PASS/FAIL。

## 执行要点
1. 读 docs/stage4-execution-plan.md 理解 A5/A9/A9a 的判定方式
2. 模拟器 emulator-5554 已运行，App 已安装
3. adb 全路径：/Users/alauda/Library/Android/sdk/platform-tools/adb
4. 前序席位可能留有隔离 daemon/tmux，先检查 lsof -i :19983
5. 如果隔离 daemon 不在了，重建：cd server && env -u TEAM_AGENT_* go build -o /tmp/st4-b1c/agentmirrord ./cmd/agentmirrord && 起 daemon 端口 19983
6. 执行 A5→A9→A9a
7. 汇总全部 13 条到 e2e/artifacts/stage4-execution/REPORT-B1.md
8. 收尾零残留
9. 写证据 .team/evidence/stage4-b1-ui-firsttouch.json
10. report_result（presentation={"sink":"leader","class":"stage_result"}）

## 隔离铁律
- 绝不触碰生产 daemon（pid 3393，:9900）与用户真实 tmux
- env -u TEAM_AGENT_* 净化
- 禁 git commit / push
