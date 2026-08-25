# baseline-bundle successor10 AVD-create bootstrap 结果

## 结论

bootstrap 已把 successor9 的 fresh AVD rc1 黑盒改造成可证伪前置：固定 package/device、task-local 0700 empty home、production 唯一随机 name、exact no-input argv 与 owned process-group deadline。只有 rc0 后真实 `config.ini` 的 device/image/ABI/tag 四项重验一致且 0600 固定 schema evidence 通过，才允许进入 retained bundle 与 successor7 ownership 后链。

原始 stdout/stderr 仅在 node-local 0600 文件中短暂存在，持久前取 digest 并清理；安全摘要只含 `reason_code`、tool rc、digest 与清理布尔，不含 SDK/AVD path 或原文。home/name/权限或 postcondition 反证映射 exit 1；工具/package/device/license/input/timeout/事实或清理不足映射 exit 2；不可判和未派发没有折色。

fake regression 已机械覆盖 success、home mode、已有 name、license、input、device missing/mismatch、package missing/mismatch、timeout、路径主动泄露、raw 0600、process-group reaping、evidence 0644/未知键/digest/缺失与零 runner/adb/emulator launch。wrapper 仍保留 strict envcheck、successor9 唯一 SDK selector，以及 successor7 的 PID start identity、owned-only TERM/KILL、serial cleanup、host recovery；旧 `printf no | avdmanager` 路径已消除。

## 产物

- `.team/ledgers/acceptance/baseline-bundle-successor10-avd.py`
- `.team/ledgers/acceptance/baseline-bundle-successor10-avd-regression.sh`
- `.team/ledgers/acceptance/baseline-bundle-successor10-owned-emulator.sh`
- `.team/nodes/spec-sol/baseline-bundle-successor10/任务书.md`
- `.team/nodes/spec-sol/baseline-bundle-successor10/bootstrap-syntax.log`
- `.team/nodes/spec-sol/baseline-bundle-successor10/bootstrap-regression.log`
- `.team/nodes/spec-sol/baseline-bundle-successor10/bootstrap-structure.log`

## Fresh 结果与边界

- Python byte compile、两个 POSIX shell 的 `sh -n`、shellcheck：全 0。
- fake AVD regression：exit 0；timeout 有界且 shell/child 均被 owned process group 清理；所有 AVD 失败为零 launch。
- structure/provenance：successor9 selector 与 successor7 ownership 输入在 HEAD 可取；helper create/verify 严格先于 emulator launch；无 legacy direct-create/input pipe；四态与完整后链已写入任务书。
- 未运行 production selector/wrapper，未启动或查询真实 adb/AVD/emulator/qemu，未改 App/server 或旧 ledger/attempt，未生成 final successor10 ledger，未 commit。
- 下一阶段只能在 leader 独立语义审查并提交 bootstrap 后，以包含该提交的 immutable provenance 生成新 successor10 final ledger；不得复用旧 attempt 充证据。

verdict: pass
