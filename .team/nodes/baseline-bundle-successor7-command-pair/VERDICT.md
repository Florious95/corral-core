# successor7 command pair verdict

只读核验对象：`ledger.baseline-bundle.successor7.v1` revision 1；未修改账本、框架或
二进制，未启动 drive/resident，未派单。

默认安装件 `/Users/alauda/.cargo/bin/ledger-run`（md5
`8c1c850bec4c86d230480b99fd6cd671`）与发布 schema（md5
`4e47e9b1aa68ed918142648c855211b1`）拒收 task-level `executor`/`command`，其
`--preflight`、`--dry-run` 对当前账本均 rc2。

已找到可合法继续的固定兼容 pair：

- binary：`/Volumes/nvme/cargo-target-w7-builder-b/debug/ledger-run`，md5
  `627f5e6fa5f47a61d23a09b918b50567`；
- source/schema/DSL：`/Volumes/nvme/Projects/无等编排/.worktrees/wt-cmd-executor`，HEAD
  `7485102b26ed34eb828e94900902147d5e00e995`，schema `账本标准/ledger.v2.schema.json`，
  DSL `映射层/ledgerdsl/models.py`；
- exact safe entry:

```sh
/Volumes/nvme/cargo-target-w7-builder-b/debug/ledger-run --preflight --json \
  /Volumes/nvme/Projects/远程Agent安卓/.team/ledgers/baseline-bundle-successor7-v1.json
/Volumes/nvme/cargo-target-w7-builder-b/debug/ledger-run --dry-run --json \
  /Volumes/nvme/Projects/远程Agent安卓/.team/ledgers/baseline-bundle-successor7-v1.json
```

两条 pair 命令实测均 rc0；未运行 `--drive`，所以没有实际继续派单。不得使用默认
`/Users/alauda/.cargo/bin/ledger-run`，也不得删除 command 字段降级为 agent 自报。

verdict: pass
