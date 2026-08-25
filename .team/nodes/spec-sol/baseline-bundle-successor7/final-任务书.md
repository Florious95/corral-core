# baseline-bundle successor7 final 总任务书

## 目标与输入

本账本 `ledger.baseline-bundle.successor7.v1` 只补 successor6 verify 诚实不可判的两件 apparatus：fresh owned emulator/adb 与 Git 固定 permanent impl-bypass fixture。不重放、不清理、不修改旧 ledger/attempt；`3528c2ad5` 四格成功、successor6 verify rc2、bootstrap `da46a6b2b538faf7954fa4f9af7e8c09a194f45e` 与主线连续性 `0df3562b7f7479ce4a2683f8c98546fab69bcf1c` 均是只读 provenance。任务输入必须由 Git object 取回，不信任调用方替换 root/manifest/fixture。

## 九格与写隔离

固定图为 `continuity ∥ apparatus-test ∥ apparatus-probe → apparatus(command) → fresh verify → user-gate → migrate → fresh measure → final`。首 frontier 只有三格，不得运行 adb/qemu/emulator；test=`wt-s7-cedar`、probe=`wt-s7-orbit` 是彼此隔离且启动前不存在的新 WT。continuity、apparatus及后续串行格使用 retained `wt-maple-core`，不导出 debug APK。apparatus 唯一 argv 为 `/bin/sh .team/ledgers/acceptance/baseline-bundle-successor7-owned-emulator.sh`，required 精确为 successor7 apparatus/fixture/continuity 三门。无自定义 statuses。

## 交付与机械门

路径级交付见本目录 `final-*-任务书.md` 及已冻结 `apparatus/test/probe/verify-任务书.md`。所有判据 0=通过、1=产品/证据有效反证、2=环境或事实不可判，ledger 统一 `unjudgeable_exit_codes=[2]`。测试禁缓存：Go `-count=1`，Gradle `--rerun-tasks --no-build-cache`。性能格必须先过 envcheck，后做同批三夹具 A/B/A/B、每段 n>=10、nearest-rank p50/p95、全格 B/A<=1.10；终局仍必须用户真机蜂窝+广州中转“秒开、没有空白”。

## 红线与收工

只能清理本任务绑定 PID/serial/qemu，禁止碰他人 qemu、生产 daemon/真实 tmux、凭据或公开分发 debug APK。不得降 envcheck/1.10/真机门，不得把 exit2 写成成功。每格仅写声明路径，正常收工只 `report_result` 一次，不直发 leader。
