# P0：`ledger-run` help/DSL 宣称 command，但默认 schema 拒收 command 方言

日期：2026-08-25  
账本：`.team/ledgers/baseline-bundle-successor7-v1.json`，`ledger_id=ledger.baseline-bundle.successor7.v1`，`revision=1`  
本报告只读；未启动 `--drive`/`--resident`，未派单、未重派、未修改框架或安装覆盖任何二进制。

## 1. 现象

用户视角：successor7 的语义/谱系已经通过，但规定的 `ledger-run --preflight` 和
`--dry-run` 都在尚未派单前以 rc2 拒绝账本；同一 CLI 的 help/DSL 已经提供
command executor 方言，导致“能写出、不能过门”。

机器视角：账本有 2 个任务同时声明 `executor: "command"` 和 `command`。默认安装
件的 task schema 是 `additionalProperties: false` 且没有这两个属性，两个入口都报同一
schema 错：

```text
ledger schema violation: /tasks/t.baseline-bundle.apparatus Additional properties are not allowed ('command', 'executor' were unexpected)
```

## 2. 量具身份

账本：

- `.team/ledgers/baseline-bundle-successor7-v1.json`
- SHA-256 `447d8a6fb608c2ab520c0a4f3a3d7bb5ab69f9818d2e008bb049096d103320dd`
- mtime `2026-08-25T11:27:06+0800`，其中 `executor=command` 任务数 2，带 `command` 的任务数 2。

默认量具及其发布副本：

- `/Users/alauda/.cargo/bin/ledger-run`，arm64，mtime `2026-08-20T15:06:13+0800`，md5
  `8c1c850bec4c86d230480b99fd6cd671`，SHA-256
  `1cf44a9d40d2dbf025fd9c0bd65ab6ae345e40d7ef103916474d6160c3414175`。
- `/Users/alauda/.claude/skills/ledger-orchestration/reference/ledgerdsl-0.1.1/ledger.v2.schema.json`，
  mtime `2026-08-22T04:21:57+0800`，md5 `4e47e9b1aa68ed918142648c855211b1`；其
  `$defs.task.properties` 不含 `executor`、`command`。
- 同一发布 DSL 的 `ledgerdsl/models.py`，md5 `a96ba2f51b40e890a2b72694bb2cad9f`，已含
  `CommandSpec`、`Task.executor`、`Task.command`；即 DSL 与发布 schema 已经不同代。

现行 help 量具 `/Users/alauda/.cargo/bin/ledger-run --help` 返回 rc0，声明
`--preflight`、`--dry-run`、`--drive`、`--resident` 及 JSON/退出码契约；help 是 CLI
接口说明，不是 task schema 的兼容性证明。实际 schema 门仍拒收 command 字段。

## 3. 最小安全复现

在仓根 `/Volumes/nvme/Projects/远程Agent安卓`，只读执行：

```sh
ledger-run --preflight --json .team/ledgers/baseline-bundle-successor7-v1.json
# rc=2；上述 Additional properties 错误

ledger-run --dry-run --json .team/ledgers/baseline-bundle-successor7-v1.json
# rc=2；同一 Additional properties 错误
```

两条命令均未写账本、未创建 worktree、未调用 `team-agent send`。这复现的是 schema
方言拒收，不是命令本身的运行失败。

## 4. 兼容 pair 核对与精确路径

仓内已有且未安装覆盖的 command 实现固定在：

- worktree `/Volumes/nvme/Projects/无等编排/.worktrees/wt-cmd-executor`
- HEAD `7485102b26ed34eb828e94900902147d5e00e995`
- `账本标准/ledger.v2.schema.json`，md5 `bae5e8874356dc5bcb2adc547aa1fc76`
- `映射层/ledgerdsl/models.py`，md5 `c03913805cb2a31768b8ee0d522e749b`；该文件具备
  `CommandSpec`、`executor` 和 `command` 的构造/emit 约束。

已有独立构建件：

- `/Volumes/nvme/cargo-target-w7-builder-b/debug/ledger-run`
- arm64，mtime `2026-08-25T08:18:22+0800`，md5 `627f5e6fa5f47a61d23a09b918b50567`
- 它与上述 `7485102b…` worktree 的独立验收记录相配套（`.team/nodes/w7-cmd-executor/verify.md`）。

不改任何文件时，精确的安全调用路径是：

```sh
LEDGER_RUN=/Volumes/nvme/cargo-target-w7-builder-b/debug/ledger-run
LEDGER=/Volumes/nvme/Projects/远程Agent安卓/.team/ledgers/baseline-bundle-successor7-v1.json
"$LEDGER_RUN" --preflight --json "$LEDGER"
# rc=0, {"ok":true,"preflight_rejected":false,"issues":[]}

"$LEDGER_RUN" --dry-run --json "$LEDGER"
# rc=0, workflow_state=active；frontier 为 apparatus-probe、apparatus-test、continuity
```

这条 pair 路径未执行 `--drive`，不会派发 successor7。若以后需从 DSL 源重新生成
JSON，必须使用同一 worktree 的 `映射层/ledgerdsl`（例如
`PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=/Volumes/nvme/Projects/无等编排/.worktrees/wt-cmd-executor/映射层 /usr/bin/python3`），
不能把它与默认发布副本混用；本次没有重新生成或落盘 JSON。

## 5. 原因、边界与回归

根因是供给发布不成对：command 实现 `7485102` 已更新 Rust engine、task schema 与
DSL，但默认 `/Users/alauda/.cargo/bin/ledger-run` 及其发布 schema 仍在旧 dialect。
因此 help/DSL 可见 command，schema 门却按 `additionalProperties:false` 拒收；这不是
successor7 账本写错，也不是 agent 自报替代了 command。

边界：本次确认了默认量具的 schema 拒收、command pair 的 source/build 身份，以及 pair
对真实 successor7 JSON 的 preflight/dry-run 结果；没有审查或修复 command 执行器的全部
运行语义，也没有运行任何会派单或触及设备的路径。`.team/nodes/w7-cmd-executor/verify.md`
已有该实现的 7 条 Rust command 测试、4 条 DSL 测试和 schema-engine 一致性记录；它们
是既有回归材料，不被本次重新包装成产品验收。

## 6. 既有 P0 关联与最小框架修复

本案是此前 `.team/artifacts/ledger-p0-pi-no-managed-submit-20260824.md`、
`.team/artifacts/ledger-p0-pi-foreground-drive-timeout-20260824.md`、
`.team/artifacts/ledger-p0-drive-resume-redispatch-before-consume-20260824.md`、
`.team/artifacts/ledger-p0-direct-result-silent-global-collect-20260825.md` 之外的同族
供给/恢复边界：CLI、schema、DSL 必须作为同代 pair 发布并能被使用方显式选择。

最小修复是发布并注册包含 `7485102` command schema/engine 的 `ledger-run`，同时更新
同代 ledgerdsl schema 副本及 manifest；或提供受支持的 pair 选择/版本探针，让
`--help`、schema 门和 DSL 在运行前拒绝代际混用。不得通过删除 `executor`/`command`
把动作降级为 agent 自报，也不得让 `--preflight` 静默放行未知字段。

verdict: pass
