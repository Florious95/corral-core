# successor7 apparatus bootstrap result

## 结论

已逐条关闭 `.team/nodes/baseline-bundle-successor7-bootstrap-review/{VERDICT.md,tests.log}` 的三类反证，产出可提交后再生成 final ledger 的 apparatus bootstrap；本轮没有生成 successor7 DSL/compiled ledger、没有启动账本，也没有启动真实 adb/qemu/emulator。连续性采用 retained `wt-maple-core` 上的 `continuity → apparatus(command) → fresh verify`，不复制或提交 private debug APK。

successor6 四格证据固定到 `3528c2ad5c9308a049f4fdb135f372d035633a90`，ledger SHA-256 为 `30bc51c09b2deb00c0213d5fce815e03e5181c6ca9cf93d9862fe5d45e5e241c`。fresh continuity 在 retained WT exit 0，独立重算了四格 state/required、verify planned、WT registration/ancestry、manifest、primary/backup 摘要和独立 inode；没有把旧格重放成新证据。

## apparatus 与 permanent fixture

- production command 固定为 `/bin/sh .team/ledgers/acceptance/baseline-bundle-successor7-owned-emulator.sh`；先跑 strict `envcheck --gate`，再创建 task-local fresh AVD，并只让现有 ownership runner 绑定唯一 PID+serial。`SUCCESSOR7_FIXTURE_MODE` 只接受 unset/空或精确1；其它非空值 exit1。production 中任意 `SUCCESSOR7_TEST_*`（含未知名）均在 launch 前 exit1；精确 test mode 仍须固定 harness 标记、node-local root，未知 test 名同样 exit1。
- timeout helper、runner stop 与 serial/qemu cleanup 都是 `deadline → TERM grace → 仅已绑定 owned PID KILL → KILL grace`；qemu kill 前再核 PID start identity，身份漂移不kill；不再有 TERM 后无界 wait。forced path 仍完成 runner PID、serial、qemu 三项清理，但诚实 exit2，不写 pass evidence；ambient 对照保持存活。
- production 禁止替换 repo root、runner、serial、APK 或 evidence path。入口 `umask 077`；敏感/派生文件0600。APPARATUS.json 原子写0600且 validator 用 lstat 强制 regular/non-symlink/mode0600，chmod0644 破坏齿 exit1；它绑定 bundle/manifest/APK/runner 摘要和 `3528c2ad5`，不记录 SDK 值、APK 内容或凭据。
- retained WT 的旧 `baseline-bundle-bypass-probes.sh` 因临时 fixture 缺失仍诚实 exit 2。新 permanent impl-bypass fixture 位于 Git 可跟踪固定路径，manifest SHA-256 为 `e1b5333e417e4b45d62b22f27b29fc662dac88e7098b5e42b977570eee4296b9`；它用 successor6 真实 projection 入口得到绿控 0、伪造 1、缺失 2，并绑定历史 `548572dfd:path` 与日志摘要。

## fresh 自检

- `sh -n` 全部 successor7 shell：0；`shellcheck -s sh`：0；apparatus Python `py_compile`：0。
- emulator repo-local regression：0，覆盖 strict gate 首动作、fresh AVD、PID/serial/install、成功与超时 cleanup、ambient 存活、dirty preflight 2 且零 launch、证据伪造 1/缺失 2，并复跑 emu-own ownership 齿。
- selector/override 回归：invalid mode=1、production nonempty/empty test override均1、unknown isolated override=1、explicit isolated test mode绿；全部拒绝臂均未触发真实 apparatus。
- mode 回归：evidence与derived path均0600；evidence chmod0644 后 validator=1，恢复0600后绿。
- bounded cleanup 回归：TERM-ignoring preflight 在3秒内 exit2；TERM-ignoring runner 在12秒内完成 owned runner/qemu/serial cleanup并 exit2；非owned ambient 未被 kill。
- permanent bypass regression：0；retained WT legacy gate：2；successor7 permanent gate：0。
- retained `wt-maple-core` continuity：0。
- 未执行 command、缺 fresh APPARATUS.json 时，read-only apparatus validator：2，没有把 bootstrap 当成已完成测量。
- 安全检查：无 final ledger/lease，无真实 Android 工具调用，无 product/App/server、旧 ledger/attempt、生产 daemon 或 private APK 变更；没有按名称 kill；fixture 未被 Git ignore；日志不含 SDK 值。

日志：`bootstrap-syntax.log`、`bootstrap-emulator-red-green.log`、`bootstrap-bypass-red-green.log`、`bootstrap-legacy-bypass.log`、`bootstrap-continuity.log`、`bootstrap-apparatus-red.log`、`bootstrap-safety.log`。

permanent fixture 当前仍是两阶段 bootstrap 的未跟踪候选，这是预期状态，不冒充 durable provenance。下一步只能是 leader 独立语义审查并提交本 bootstrap；随后先做 `git cat-file` 取回验证、retained WT dirty-path 零交集检查和安全 fast-forward，再以包含该 bootstrap commit 的 immutable provenance 生成 final successor7 ledger。本轮不得直接启动。

verdict: pass
