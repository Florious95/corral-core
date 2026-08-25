# successor7 apparatus command 任务书

## 精确执行与交付

本格必须是 final ledger 的 `executor=command`，cwd/worktree 固定 retained `wt-maple-core`，argv 只能是 `/bin/sh .team/ledgers/acceptance/baseline-bundle-successor7-owned-emulator.sh`。不得允许 worker 改 runner/root/serial/APK。`SUCCESSOR7_FIXTURE_MODE` 只允许 unset/空或精确 `1`；其它非空值 exit1。production 出现任意 `SUCCESSOR7_TEST_*` 环境变量（含未列明名称、含空值）立即 exit1，且必须发生在 envcheck/continuity/AVD/runner 之前。精确 mode=1 还必须带固定 regression harness 标记和 node-local root，未知 test 名称仍 exit1；test override 永不写 official evidence、永不控制 production path。

脚本先有限时执行 `envcheck.sh --gate`；非0直接 exit2且未创建 AVD/未启动 adb/qemu。随后 continuity exit0，安全 SDK fallback 后在本格 node-local run 目录以固定 system image `system-images;android-35;google_apis;arm64-v8a` 新建 `ANDROID_AVD_HOME` 与 AVD `successor7_verify_owned`。缺 SDK/emulator/adb/avdmanager/system image 为2，不安装组件、不联网猜测。

只调用现有 `run-input-ab.sh --emulator-self-test` ownership 链：strict gate后 launcher、唯一 post-gate qemu PID、`emulator-5554`、boot=1、measurement gate=0。runner 保持在线时，对 manifest primary archive 做 SHA绑定后执行有限时 `adb -s emulator-5554 install -r` 和 `pm path dev.agentmirror.app`。所有等待/命令有界；任一失败2。

所有后台命令的 `$!` 都是本任务绑定 PID；timeout helper 在主 deadline 后 TERM，TERM grace 后仍存活才对该精确 PID KILL，并再次有限等待，不得直接无界 `wait`。收尾先 TERM本轮 runner；若 runner/qemu 忽略 TERM，只能对已绑定 runner PID 与 `qemu_bound` event 的 owned PID升级 KILL；qemu 在任何 kill 前还必须重核 PID 的 start identity 未变化，PID复用/身份漂移只可 exit2、不得kill。任何强杀路径最终 exit2而非成功；但退出前仍须有限验证 runner PID消失、bound serial不再 `device`、owned qemu PID消失。不得 `pkill`/`killall`、按名称杀进程或触碰外来 PID。三项 cleanup 后有限时重跑严格 gate到0；恢复超时2。无论成功/失败/信号，只删本格 node-local AVD/run目录。

脚本从入口 `umask 077`；SDK/APK path handoff、runner/install/pm/serial日志、AVD input 等派生文件只能在0700 run dir内以0600创建并最终清理。成功原子写0600 regular/non-symlink `.team/nodes/baseline-bundle-apparatus/APPARATUS.json`，validator 必须 lstat 重核 mode；0644 exit1。固定 schema绑定 `3528c2ad5`、`wt-maple-core`、bundle/manifest/APK/runner摘要、fresh AVD、PID、serial、preflight/measurement/install/recovery=0、runner signal=143、runner_pid_cleanup=true、serial_cleanup=true、owned_qemu_cleanup=true、forced_kill=false、foreign_qemu_touched=false。不得记录 SDK值、APK内容或凭据。

required gate：`baseline-bundle-successor7-apparatus.sh`，它只验 fresh evidence+continuity+permanent bypass，不再启动 emulator。产物齐后 command executor由driver交棒；不得另发消息。

合法出口：全绿0；固定身份/证据伪造1；环境/设备/超时/清理不可判2。测试禁缓存，测试格只跑 repo-local fake apparatus和既有 emu-own齿。
