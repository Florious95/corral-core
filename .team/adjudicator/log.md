2026-08-10 00:15:30 +0800 | 静默纪律生效：leader 转发默认不回执；仅四类升级件可投 leader；框架 A-24/管理命令实测改由裁定席直报 refactor-maintainability/leader。
2026-08-10 00:19:22 +0800 | dogfood 排程裁定：选方案①，TS 验证席设备优先，让机后 dogfood 连续跑完剩余 6 项；不设 20 分钟截断、不插队。D-14 归 017 R-3 已裁定需求未实现，不修订 016。
2026-08-10 00:25:32 +0800 | D-16 初审：P1 候选成立；实测边界为零连接 0.0% 对比已连接+1 活跃订阅+零用户操作约 14.9%，列表页零订阅未测，轮询/订阅成本未拆分。代码确认 listing 2s、state TTL 1s、采样入口 state_wiring.go:264；REPORT 须纠正 bridge.go:51 误引及“3s budget 限流”误述后再终审。
2026-08-10 00:37:34 +0800 | 框架直报按 0.5.61 限制切换到 outbox；已追加 A-24 #6、A-31 #1、A-13 remote-agent-01。D-16 已立 fix-connected-idle-economy，taskbook/FIELD/LIBRARIAN/basegen 完成。
2026-08-10 00:38:40 +0800 | dogfood 设备 blocking 裁定选③：35/41 就地收口，六项含 016 第7/8/9步明确未验证；不打断 w-ts-verify3 的 TS P0 占机，dogfood 仅离线清场交件。
2026-08-10 00:43:23 +0800 | fix-connected-idle-economy 已完成五件套并 add-agent w-fix-onlinecpu；管理命令 exit0 后以 BUSY 真活性复核，完整信封已投喂开工。框架 outbox 三旧件已收录；0.5.62 后复核全限定 TO 并回投 closure 记台账。
2026-08-10 00:45:21 +0800 | feat-ts-wire 验证裁定：cmd 未暴露 tsnetd.Options.Dir，macOS DefaultDir 只能落用户配置目录；禁止 HOME/container 旁路，真实 headscale E2E 在安全隔离下记 BLOCKED/未验证，余项继续，另立最小 TS state-dir 接线修复后复验。
2026-08-10 01:19:33 +0800 | test-app-dogfood 终审销账：冻结验收 exit 0，48 图逐图目检完成；最终用例口径 26 完整/2 局部/15 未执行=43，缺陷 P1=6/P2=7；窄提交 54be8b9。w-dogfood2 已退役，stop-agent CLI exit 0 后复核 status=stopped（MCP 管理接口先报 team select internal_runtime_error）。
2026-08-10 01:48:00 +0800 | 通道级裁定承接：w-fix-onlinecpu/w-fix-tsstatedir 因 Codex 托管代理环境被快照、全生命周期零 token 假 BUSY，已 stop+remove 弃 id；今后所有 Team Agent CLI 统一走 .team/ta，Codex 新席以 session JSONL reasoning/custom_tool_call 核真。
2026-08-10 02:33:12 +0800 | audit-prod-daemon-lifecycle 裁定 unknown：artifact 级核心窗 01:12–01:23，HANDOFF 宽 pkill 仅为潜在风险且无执行证据；详见 e2e/artifacts/audit-prod-daemon-lifecycle/REPORT.md 与 .team/evidence/audit-prod-daemon-lifecycle.json。
