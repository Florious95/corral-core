# successor10 verify rc2 只读归因

## 范围与 attempt

对象为 `ledger.baseline-bundle.successor10.v1` revision 5 的
`M.baseline-bundle.successor7-verify` fresh verify attempt。未重跑、未启动设备、未
collect、未改账本或证据，也未读取 APK 字节。

attempt artifact_refs 的硬事实：expected exit `0`、observed exit `2`，state
`failed_retryable`，error 为 acceptance exit-code mismatch；driver 随后将 verify
判为不可判并停机。apparatus r4 本身已 succeeded，故不把 verify rc2 回溯为 apparatus
失败。

## APPARATUS / AVD-CREATE 事实

retained WT 中 fresh 产物均存在且非空：

- `APPARATUS.json`：regular、mode `0600`，schema
  `agentmirror.successor7.apparatus.v1`，mode `production`；
- `AVD-CREATE.json`：regular、mode `0600`，schema
  `successor10.avd-create.v1`，mode `production`；`avdmanager_rc=0`、
  `created=true`、`reason_code=created`、`raw_cleaned=true`；
- APPARATUS 的 fixed image/package、fresh task AVD、envcheck 三阶段和 install
  exits 均为成功值；`forced_kill=false`、`owned_qemu_bound=true`、
  `owned_qemu_cleanup=true`、`runner_pid_cleanup=true`、`serial_cleanup=true`。

APPARATUS byte SHA-256 为
`fd27d1638ba097ab6a310bc497d80e20720f7625974f61a414a2e63132f23b9d`；
`VERIFY.json.apparatus_evidence_sha256` 与之相等。manifest SHA 为
`0f5638e15805c61fb06734ac7ddf9611644c1d6f29f4744033c63159c095baea`，同时满足
APPARATUS manifest digest 与 VERIFY manifest digest；APPARATUS bundle_id 与 retained
manifest bundle_id 相等。故 schema/mode/hash/bundle cross-link 不是首个缺口。

`VERIFY.json` 也是 regular fresh 产物，但 mode 不是 `0600`；这是产物保密性/写出
策略缺口，当前 `successor7-verify.sh` 不以它作为首个 guard。其关键字段为：

- `envcheck_gate_exit=0`、`install_exit=0`、`permanent_bypass_probe_exit=0`；
- `permanent_bypass_probe_status=successor7_permanent_fixture_green_red_missing`；
- `independent_inode=true`、`mutation_red=true`、`mutation_restored_green=true`、
  `restore_pass=true`；
- `runner_pid_cleanup=true`、`serial_cleanup=true`、`owned_qemu_cleanup=true`；
- `verdict_basis=apparatus_unavailable`、`verify_wrapper_exit=2`；
- `verify_wrapper_blocker=successor6_verify_requires_current_adb_and_missing_legacy_fixture`。

## gate 顺序与首个缺失操作数

`baseline-bundle-successor7-verify.sh` 先运行 successor7 apparatus gate，再运行
successor6 verify gate，最后才执行 APPARATUS/VERIFY cross-link Python 检查。apparatus
gate 已成功；successor6 impl、projection、deep、successor3 anti-forgery、permanent
fixture 与 continuity 证据也都已返回 0。

因此首个失败不是 hash 或 cross-link，而是
`baseline-bundle-successor6-verify.sh:18-19` 的 verdict 门：

- 预期操作数：`VERDICT.md` 最后一行 `verdict: pass`；
- 实际操作数：最后一行 `verdict: unjudgeable`；
- 结果：`UNJUDGEABLE ... independent verifier could not judge`，rc2。

该 verdict 的底层原因已在 fresh `VERIFY.json` 固定为 current adb 不可用与旧 frozen
successor6 bypass fixture 缺失。它不是产品 refute；也不是 APPARATUS 过期。由于
successor6 verify 在 verdict 门已经 exit 2，后面的 verify-json 字段校验及最终
cross-link (`verdict_basis` 应为 `apparatus_complete`、APPARATUS SHA/bundle/cleanup
字段应全等) 本轮没有成为首个执行 guard；当前 `verdict_basis=apparatus_unavailable`
是该前置失败的交叉引用反映。

## fresh verify 产物与清理

`VERIFY.md`、`INSTALL.md`、`RETRIEVE.md`、`MUTATION.md` 和 `VERIFY.json` 均为本批
fresh 非空产物。它们共同记录：APPARATUS install exit 0、manifest/bundle identity
相等、permanent fixture/deep/continuity gates 通过；但 INSTALL 与 MUTATION 明确不
把缺失 current adb/legacy fixture 冒充为新的 verify/install green。

对记录的 qemu PID 做了 `kill -0` 存活核验，已不存在；受限进程量具
`ps -axo pid,ppid,etime,stat,comm` 未见 qemu-system、emulator、runner 或 avdmanager。
APPARATUS cleanup 布尔与 serial cleanup 均为 true。只见一个无法归因于本批的既有
`adb` comm，不能将其当作本批设备残留或 serial 活跃证据。

## 最小继续方案

不要重派或修改当前 verify attempt，也不要手写/改写 `VERIFY.json` 为绿。最小路径是
保留已成功的 apparatus r4，先在受管新 revision/case 中恢复 successor6 verify 所需
的 current adb 可用性与旧 frozen bypass fixture；然后以 command-consume 方式重新执行
未通过的 `baseline-bundle-successor7-verify.sh`，要求同一 APPARATUS SHA、manifest
SHA、bundle_id 和 cleanup 字段重新闭合。若旧 fixture 按策略不再可恢复，则必须由
新的账本/契约明确替代证据后再开 fresh verify case；不能绕过该永久门或沿用本次
unjudgeable verdict。

本次未重跑设备、未 collect、未清理现场、未改变任何账本或产物。

verdict: unjudgeable
