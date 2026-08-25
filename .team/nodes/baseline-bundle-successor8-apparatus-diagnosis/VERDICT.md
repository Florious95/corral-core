# successor8 r4 apparatus command 只读归因

## 范围与结论

本次只读核查 `ledger.baseline-bundle.successor8.v1` revision 4 的 apparatus
command attempt；未重跑、未启动设备、未修改账本、未清理 attempt、未 collect 或重派，
也未读取凭据、敏感值或 APK 内容。

结论：这是 apparatus 前置环境不可判，不是产品失败。首个不可判 guard 是固定
Android system image 不可用；因此本次 command 在 launch 以前结束。

## 账本与 attempt 证据

- revision 4 的三个 durable consume 格均为 `succeeded`：apparatus-probe、
  apparatus-test、continuity。
- apparatus attempt 为 `executor=command`，状态 `acceptance_pending`，预期退出码
  `0`，实际退出码 `2`；cwd 为目标 worktree。
- attempt 的 acceptance failure 为 `exit_code_mismatch: expected 0, observed 2`。
- stderr 尾部明确为：`UNJUDGEABLE ...: fixed system image unavailable`。
- driver 时间线为 `05:54:08Z` 开始、`05:54:24Z` command-unjudgeable，约 16 秒后
  halt，之后没有 apparatus 重派。

## guard、操作数与执行顺序

owned-emulator 脚本的生产顺序是：strict envcheck、retained continuity、SDK gate、
SDK root、emulator/adb/avdmanager 可执行性、固定 system image 检查，然后才创建
task-local AVD、解析 APK、启动 runner/emulator、adb install 和 cleanup。

本次最早失败的比较是：

- 预期操作数：SDK root 下固定镜像的
  `system-images/android-35/google_apis/arm64-v8a/package.xml` 可读；
- 实际操作数：该固定镜像不可用（guard 输出 `fixed system image unavailable`）。

命令确实以 `sh <envcheck> --gate` 进入环境门；现行 envcheck 的固定门值为 dead
socket ≤ 10、load1 ≤ 12、daemon CPU ≤ 5%，并拒绝非 owned `qemu-system*`。
本次 attempt 没有生成一份可把这些门值与现场数值逐项绑定的 APPARATUS 记录，故不
把历史 envcheck 数值冒充本次测量。continuity 同样位于固定镜像检查之前，r4 没有
新的 apparatus continuation 事实可消费。

SDK root、emulator/adb/avdmanager、AVD、ADB install、APPARATUS 候选及 cleanup
均未被本次 command 走到；不存在“设备启动后 cleanup 失败”的证据。

## launch 与残留核验

固定镜像 guard 位于 AVD 创建、runner/emulator 启动和 install 之前，因此本次
attempt 是零 launch：没有创建本次 fresh AVD，没有启动本次 emulator/qemu，也没有
生成本次 APPARATUS.json。针对性扫描未发现 successor8 apparatus 目录产物、候选
APPARATUS.json 或 runner log；进程量具 `ps -axo pid,ppid,etime,stat,comm` 未见
`qemu-system*` 或 `emulator`。只见一个未能归因于本次 attempt 的 `adb` 进程，不能
把它当作 owned qemu 或本次 launch 证据。

历史 `bootstrap-apparatus-red.log` 仅说明旧的 apparatus evidence unavailable，
不能替代本次 command 的 apparatus 证据。

## 最小安全继续方案

保留 r4 的 `acceptance_pending` 与 rc2 证据，不把它改判为产品 red，也不复用该
attempt。由受管账本流程产生新的 revision/case；在新 apparatus attempt 前记录并
验证 SDK gate、SDK root、emulator/adb/avdmanager 可执行性、固定镜像
`package.xml` 可读、strict envcheck rc0 和 continuity rc0，再执行原
owned-emulator command。若仍缺固定镜像，继续保持 `unjudgeable`，不得以重跑或历史
consume 结果补齐 apparatus 门。

verdict: unjudgeable
