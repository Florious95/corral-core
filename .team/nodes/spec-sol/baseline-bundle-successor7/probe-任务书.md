# successor7 apparatus probe 任务书

只写 `.team/nodes/baseline-bundle-successor7-probe/PROBE.md`。独立核：envcheck strict→task AVD→run-input ownership→measurement→adb install→TERM/reap→strict recovery的有界顺序；production无root/runner替换；fixture selector仅空/1，production任意 `SUCCESSOR7_TEST_*` fail-before-launch，精确test mode只能写spec-sol tmp；没有pkill/killall/unknown PID kill。逐个等待核 deadline→TERM grace→仅bound PID KILL→KILL grace，qemu kill 前 start identity 必须与绑定时相同；验证 runner PID/serial/qemu 三项清理与 forced path exit2；官方/fixture evidence 和 path handoff 固定0600，0644齿为1，且不含SDK值/APK内容。

独立重算 permanent fixture/green control/historical log 三个SHA与 `548572dfd:path` provenance，证明 direct forged rc1、missing rc2、新 wrapper rc0；旧 `baseline-bundle-bypass-probes.sh` rc2只作先红，不得成为 successor7 required。

独立核 continuity：`3528c2ad5` ledger SHA、四格 succeeded required、verify planned、registered `wt-maple-core`、HEAD ancestry、manifest/双归档摘要/independent inode；bootstrap commit fast-forward前路径零交集，禁止export/复制/private APK commit。PROBE.md含 `SUCCESSOR7_CONTINUITY`、`SUCCESSOR7_APPARATUS_EVIDENCE`、`SUCCESSOR7_IMPL_BYPASS` 与两边操作数。只 report_result一次。
