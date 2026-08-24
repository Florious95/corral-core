# t.emu-own.verify

## 终审结论

verdict: pass

当前实现满足 qemu PID/adb serial 所有权链、comm basename 归一、失败清理与信号出口契约。本轮未修改实现、旧判据或其他路径。

## 机械门

| 检查 | 结果 |
| --- | --- |
| `sh .team/ledgers/acceptance/emu-own.sh` | exit 0 |
| 固定 `EMU_OWN_EVIDENCE` | 完整，报告全路径绑定、PID/serial、清理与恢复均为 true |
| `sh .team/ledgers/acceptance/envcheck-emu.sh` | exit 0，固定八臂及清理证据完整 |
| `bash tools/perfbase/run-input-ab.sh --self-test` | exit 0 |
| `python3 tools/archwiki/build_wiki.py --check --strict-t3` | exit 1；既有全仓 T3 缺口，不属于本格允许修改范围 |

机械门使用仓内假 launcher、ps、adb、envcheck，未接触真实 qemu、adb、tmux 或生产 daemon。

## 破坏齿

在终审临时副本中撤掉 `emulator_qemu_rows` 的 comm `basename` 归一（删除 `sub(".*/", "", base)`），保留全路径 comm 阳性臂。聚焦测试变红：`expected rc=0 got=2`；说明该齿有效。恢复原实现后重跑 `emu-own` acceptance 为 exit 0。临时副本及测试件均已清理，工作树实现未被齿修改。

`python3 tools/archwiki/build_wiki.py --check --strict-t3` 本轮 exit 1，仍为既有全仓 T3 缺口；该命令不属于本格允许修改范围，未将其归因于 emu-own 实现。
