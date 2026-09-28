# t.envcheck-emu.verify

## 终审结论

verdict: pass

当前实现满足两阶段闸、所有权约束与生命周期清理契约。上一轮记录的量具失败放行和清理失败吞错问题已在实现中修复；本轮未修改实现文件、旧判据或其他路径。

## 重跑机械门

| 检查 | 结果 |
| --- | --- |
| `sh .team/ledgers/acceptance/envcheck-emu.sh` | exit 0 |
| `bash tools/perfbase/run-input-ab.sh --self-test` | exit 0 |
| 固定 `ENVCHECK_EMU_EVIDENCE` 行 | 完整，八臂为 `0,2,0,2,2,2,2,2`，三条清理证据均为 `true` |
| `python3 tools/archwiki/build_wiki.py --check --strict-t3` | exit 1；报告的是既有全仓 T3 缺口，本格无权扩大修改范围 |

聚焦测试实际使用仓内 PATH 前置假量具，没有连接真实 qemu、adb、tmux 或生产 daemon。

## 破坏齿

在终审临时副本中分别验证两枚齿：

- 去掉 measurement 阶段对“额外 qemu”的拒绝条件，聚焦测试出现 `focused failure expected=2 got=0`；
- 去掉 owned PID 身份约束，并把 unowned-high-load 臂改为单个非 owned qemu，同样出现 `focused failure expected=2 got=0`。

两枚齿均只改临时副本，未改工作树实现；删除临时副本后重跑 acceptance 恢复 exit 0。

## 其他命令

`python3 tools/archwiki/build_wiki.py --check --strict-t3` 本轮 exit 1，仍为既有全仓 T3 缺口；该命令不属于本格允许修改范围，未将无关缺口归因于 envcheck 实现。
