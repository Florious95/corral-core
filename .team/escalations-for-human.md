# 人工升级件

- 日期：2026-08-10
  决定：`fix-ts-state-dir-e2e` 的模拟器真扫码被契约级冲突阻塞。用户要求“绝不移动真实鼠标、姿态动作必须后台 Computer Use”；当前 qemu PID 44635 在 LaunchServices 中 `bundleID=NULL`、程序为裸 Mach-O，`@oai/sky` 不能按 displayName、`.app` 路径、bundle id、窗口标题或 PID 寻址，故无法安全操作 Extended Controls。执行席已移除 cliclick/AppleScript/前台控制并停止 E2E；不得猜测或回退鼠标方案。
  所需人工动作：请定夺其一：①授权只针对本任务模拟器的后台 console/gRPC 姿态命令（仍不移动真实鼠标）；②授权把模拟器重启为带 `CFBundleIdentifier`、可被 Computer Use 后台寻址的 `.app`；③确认本项保持 `BLOCKED`，接受真扫码/TS 实链未验证。未获定夺前不再运行该姿态步骤。
- 日期：2026-08-10（已定夺，人工侧关闭）
  上条模拟器扫码阻塞：**用户裁定「模拟器扫不了码就不要测模拟器的扫码；这任务还没完成」**。
  处置：taskbook `fix-ts-state-dir-e2e` 验收口径改为禁摄像头/禁前台寻址、配对经非摄像头路径注入，
  真扫码移交用户真机验收；证据置 red 并写入返工要点，编排引擎按返工回路自动弃 id 重派。
  所需人工动作：无（本条已闭环）。
- 日期：2026-08-10
  决定：任务 `fix-ts-state-dir-e2e` 连续 5 次自动返工均未通过，编排器停止重试（防止无限重试烧额度）。最近一次结论：{'go_gate': 'rc=0', 'authoritative_e2e_run': {'run_id': 'run-20260810T173005-22201', 'state_dir_proof': 'yes', 'keystore_migration': 'yes', 'app_tsnet_state_proof': 'yes', 'daemon_headscale_node_count
  所需人工动作：请定夺——继续攻这个根因、改验收口径、还是把该项移出自动链。

- 日期：2026-08-10
  决定：任务 `fix-ts-state-dir-e2e` 连续 6 次自动返工均未通过，编排器停止重试（防止无限重试烧额度）。最近一次结论：{'go_gate': 'rc=0', 'authoritative_e2e_run': {'run_id': 'run-20260810T173005-22201', 'state_dir_proof': 'yes', 'keystore_migration': 'yes', 'app_tsnet_state_proof': 'yes', 'daemon_headscale_node_count
  所需人工动作：请定夺——继续攻这个根因、改验收口径、还是把该项移出自动链。

