# t.emu-own.impl

## 修前红与边界

已执行 `python3 tools/basegen.py emu-own`：退出 1，输出 `task emu-own not found in taskbook.yaml`。
先仅新增聚焦测试并在旧 runner 上执行：输出 `expected rc=0 got=2`，测试退出 1。该功能红来自唯一 qemu 的 comm 为 `/fake/sdk/qemu-system-aarch64`，旧实现按 `$2 ~ /^qemu-system/` 识别为 count=0；因此未把缺测试的 acceptance 红冒充功能红。

本格只改 runner/envcheck/ownership test/本文件，不改 App、server、A/B 判定、阈值或历史基线。

## 实现计划

- runner 先严格 gate=0，再启动 launcher；启动后只读 PID/PPID/comm，取 comm basename 后匹配 `qemu-system*`，要求唯一新 qemu PID。
- 绑定 serial 的 `device`+boot=1 后，按顺序调用 measurement gate；所有失败先只回收本轮 launcher/qemu，再在有界窗口内等待严格 gate 恢复。
- 只清理本轮 owned PID，禁止按名称或 glob 清理；聚焦测试使用仓内假 launcher/ps/adb/envcheck，不接触真实资源。

## 未验证项

尚未修改实现，真实 emulator/adb/tmux 和性能采样均未启动；本格不宣称性能优化成功。

## 修后实现与证据

runner 现在严格执行 `preflight → launcher → 唯一 qemu PID → adb serial ready/boot → measurement`。qemu 量具读取 PID/PPID/comm 后先对 comm 取 basename，因此 `/fake/sdk/qemu-system-aarch64` 被正确绑定；launcher `$!` 仅作回收句柄，不冒充 qemu PID。measurement 收到假量具报告的真实 qemu PID 和 `emulator-5554`。

绑定失败、adb 不可用、measurement 拒绝和信号退出都只清理本轮 launcher/qemu；失败退出的 EXIT trap 在清理后以有界窗口重跑严格 gate，记录 dirty load=27.43 后恢复 load=7.74。清理确认 qemu 消失并 wait 回收 launcher；恢复超时或清理失败均 exit 2。信号清理成功返回 143，清理失败返回 2。

聚焦测试只生成仓内假 ps/launcher/adb/envcheck 和真实 runner 调用，覆盖 full-path comm、count=0、offline adb、measurement reject、signal、失败恢复及成功/失败清理；无 APK install、无样本采样、无 orphan，并核对 PID/serial/order/only-owned。

## 本轮命令

- `sh .team/ledgers/acceptance/emu-own.sh` → 0，固定 `EMU_OWN_EVIDENCE` 完整。
- `sh .team/ledgers/acceptance/envcheck-emu.sh` → 0（同树既有两阶段测试仍通过）。
- `bash tools/perfbase/run-input-ab.sh --self-test` → 0。
- `python3 tools/archwiki/build_wiki.py --check --strict-t3` → 1，报告全仓既有 T3 缺口；未越界修复，并恢复其生成物。
- `python3 tools/basegen.py emu-own` → 1：`task emu-own not found in taskbook.yaml`。

未验证项：未启动真实 emulator/adb/tmux，未执行真实 APK 安装或性能测量；不宣称性能优化成功。工作树中 strict-t3 之外的既有协作证据不属于本格写入路径。

## revision 6 终审返修

`runner_event` 现在默认写 stderr，并在设置 `RUNNER_EVENT_LOG` 时同时落盘；不再依赖可选日志才有证据。默认事件覆盖 preflight rc、launcher_pid、qemu 候选 count/原始与归一 comm、qemu_bound PID、adb state/boot、measurement rc、cleanup begin/kill/wait/reap rc，以及 recovery 上限、每次 gate rc、pass/timeout。

信号 trap 在清理后也调用有界 `emulator_recover`，恢复闸超时或清理失败返回 2；仅清理成功且恢复 gate=0 才返回 143。聚焦测试新增真实 ambient sleeper PID，假 ps 将其作为非 qemu 进程暴露，并在成功、失败、信号路径后用 `kill -0` 核实 ambient PID 仍存活；owned qemu PID 则核实已消失，`only_owned_pid_killed` 不再是固定字符串。

revision 6 验证：emu-own 聚焦测试与 acceptance 均 exit 0。旧 `envcheck-emu` acceptance 在恢复信号路径上 exit 1，原因是其既有假 ps 在 launcher 被清理后仍从 stale `launcher_pid` 行报告 qemu，恢复 gate 因此正确保持不可判；本格未修改该不在 write_paths 内的测试夹具，也未放宽恢复闸。

## revision 9 终审返修

TERM 主动回收 launcher 时，`wait` 的 143（以及 SIGINT 的 130）是预期的子进程终止状态，不再误判为 cleanup failure；其它非零 wait、kill 失败或 owned qemu 未消失仍记录并返回 2。聚焦假 launcher 现在实际以 143 退出，emu-own acceptance 仍为 0，证明 signal 出口保持 143 且清理失败判据未被放宽。

本轮 `sh .team/ledgers/acceptance/envcheck-emu.sh` 仍报告 `runner signal rc=2`：既有 envcheck 聚焦夹具的假 ps 在 launcher 回收后继续报告 stale launcher PID 为 qemu，signal recovery gate 因此按严格契约超时。该夹具不在本格 write_paths 内，未修改或绕过恢复闸。

## revision 12 终审返修

按 leader 裁定，cleanup failure 的判定边界收窄为本轮 owned qemu 的 kill/reap：launcher 的 wait 状态只记录事件，不参与 `CLEANUP_STATUS`，因此 TERM 后 wait=143 不会把 signal 出口染成 2。TERM signal 清理成功直接返回 143；owned qemu kill/reap 失败仍返回 2。失败/不可判的非信号出口仍在清理后执行恢复 gate。

最终机械结果：`sh .team/ledgers/acceptance/envcheck-emu.sh`=0，`sh .team/ledgers/acceptance/emu-own.sh`=0，`bash tools/perfbase/run-input-ab.sh --self-test`=0，shell 语法和 `git diff --check`=0。未启动真实 emulator/adb/tmux，未执行真实性能采样。
