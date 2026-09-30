# t.envcheck-emu.impl

## 交付状态

已实现两阶段环境闸和 owned-emulator 生命周期测试；未改 App、server、解析器、阈值或历史基线。

修前红证据：首次执行 `sh .team/ledgers/acceptance/envcheck-emu.sh` 输出
`FAIL envcheck-emu: missing focused test: tools/perfbase/test-envcheck-measurement-emulator.sh`，退出 1。
`python3 tools/basegen.py envcheck-emu` 退出 1，原因是 `task envcheck-emu not found in taskbook.yaml`；该账本任务不存在于 basegen 索引，未修改 taskbook。

## 接口与判据

- `sh tools/perfbase/envcheck.sh --gate` 是严格 preflight：死 socket 保持上限 10、load1 保持上限 12、daemon CPU 保持 5%，并拒绝任意已存在的 `qemu-system*`。
- `sh tools/perfbase/envcheck.sh --measurement PID SERIAL` 只豁免 load1；要求观测 qemu 集合恰好是传入 PID、PID 的 comm 为 `qemu-system*`，并要求 serial 状态为 `device` 且 `sys.boot_completed=1`。死 socket、daemon CPU、额外 qemu 和量具缺失仍 fail-closed 为 2。
- `run-input-ab.sh` 机械执行 preflight=0、启动 launcher、发现唯一 qemu PID、等待绑定 serial ready/boot=1、执行 measurement gate，再进入采样。成功、失败、不可判和 INT/TERM/HUP 均只 kill 已绑定 qemu/launcher PID；没有 `pkill`/`killall`。
- `--emulator-self-test` 是 runner 的仓内生命周期测试入口；只供聚焦测试使用，不连接真实设备。生产采样仍走普通入口。

日志逐项记录 phase、load1/12、dead/10、daemon_cpu/5、observed_qemu、owned_pid、serial/state/boot 和最终分类；不读取进程 argv。

## 假量具与证据

`test-envcheck-measurement-emulator.sh` 只在 `.team/nodes/envcheck-emu/tmp/` 创建 PATH 前置的 `uptime`、`ps`、`tmux`、`lsof`、`top`、`adb` 与 launcher。八臂实际调用修改后的 envcheck：

`preflight_clean=0`、`preflight_unrelated_qemu=2`、`owned_high_load=0`、`extra_qemu=2`、`dead_socket=2`、`daemon_cpu=2`、`no_adb=2`、`unowned_high_load=2`。

runner success/failure/signal 三路径均启动并绑定假 qemu PID+serial；cleanup marker 和 PID 存活检查证明只清理绑定 launcher/qemu。测试最后逐字输出：

`ENVCHECK_EMU_EVIDENCE preflight_clean=0 preflight_unrelated_qemu=2 owned_high_load=0 extra_qemu=2 dead_socket=2 daemon_cpu=2 no_adb=2 unowned_high_load=2 cleanup_success=true cleanup_failure=true cleanup_signal=true`

## 本轮命令与退出码

- `ENVCHECK_EMU_FIXTURE_ROOT=... sh tools/perfbase/test-envcheck-measurement-emulator.sh` → 0
- `sh .team/ledgers/acceptance/envcheck-emu.sh` → 0
- `bash tools/perfbase/run-input-ab.sh --self-test` → 0
- `python3 tools/archwiki/build_wiki.py --check --strict-t3` → 1。该命令报告现有 18 条 T3-1、1 条 T3-2、34 条 T3-3、30 条 T3-4 违规；本格只允许改四条 envcheck 任务路径，未扩大范围修复。

未验证项：未启动真实 emulator、未连接真实 adb/tmux、未宣称真机性能优化成功；独立判者仍需在本树重跑聚焦验收并执行破坏齿验证。

## revision 6 终审返修

终审 VERDICT 原为 fail，阻断点是量具缺失可能假绿，以及 cleanup 的 `kill`/`wait` 错误被吞掉。已按原任务定义补齐：envcheck 现在先验证 `ps`、`uptime`、`lsof`、`top`、`tmux` 均可用，并检查 `uptime`/`ps`/`top` 执行状态；tmux 非 0 仅允许死 socket 的正常 rc=1，其他 rc fail-closed=2。聚焦测试新增逐一缺失五种量具的 exit 2 臂。

runner cleanup 现在分别记录 kill/wait 状态，清理失败打印 `UNJUDGEABLE emulator cleanup failed` 并出口 2；EXIT 路径和信号路径都经过同一清理判定，信号清理成功仍为 143，清理失败为 2。聚焦测试新增注入失败的 kill/wait 动作并核对 cleanup failure 为 exit 2，避免把 143 当作失败清理的证据。
