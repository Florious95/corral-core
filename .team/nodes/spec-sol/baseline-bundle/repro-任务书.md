# t.baseline-bundle.repro — 以固定 JSON 契约先红复现旧链缺 A park

## 背景与必读

读共享 `任务书.md`、`.team/nodes/baseline-bundle-repro-diagnosis/VERDICT.md`、当前 `.team/ledgers/perf-regress-v1.json`、真实 wt-pr-impl 的 `FIXED-MEASURE.md`，以及 `baseline-bundle-real-chain-probe.sh`、`baseline-bundle-repro-translate.sh`。上个 case 已产出的 REPRO.md 含两次真实 probe exit 1，语义证据可沿用作索引；新 case 必须按本任务书补交固定 schema 的 REPRO.json，并重新连续运行两次真实 probe。不得启动/改变 ledger、emulator、tmux 或 daemon。

## 精确交付

只写：

- `.team/nodes/baseline-bundle-repro/REPRO.json`：机器权威；
- `.team/nodes/baseline-bundle-repro/REPRO.md`：人读说明，必须非空，但机器判定不得 grep 其自然语言。

`REPRO.json` 顶层必须逐字段满足：

```json
{
  "schema": "agentmirror.baseline-bundle.repro.v1",
  "evidence_kind": "expected_legacy_red",
  "contract": {
    "taskbook_path": ".team/nodes/spec-sol/baseline-bundle/repro-任务书.md",
    "acceptance_path": ".team/ledgers/acceptance/baseline-bundle-repro.sh",
    "translator_path": ".team/ledgers/acceptance/baseline-bundle-repro-translate.sh",
    "human_report_path": ".team/nodes/baseline-bundle-repro/REPRO.md"
  },
  "runs": ["恰好两个下述 run object"],
  "translation": {
    "rule_id": "expected_legacy_red_to_repro_pass.v1",
    "input_classification": "legacy_missing_baseline_park",
    "input_probe_exit": 1,
    "output_acceptance_exit": 0
  },
  "future_gate": {
    "task_id": "t.baseline-bundle.final",
    "required_probe_exit": 0,
    "classification": "baseline_bundle_migration_complete"
  }
}
```

`runs` 必须恰好两个，按 sequence=1、2 排列；每个 run 必须有：

- `sequence`：1 或 2；
- `probe_exit`：实际值 1；
- `observed_at`：RFC3339；
- `probe_record_sha256`：下述 `probe_record` canonical JSON（UTF-8、key sort、无空白分隔）的 64 位小写 SHA-256；
- `probe_record`：必须原样取自当次真实 probe 的 `REAL_CHAIN_PROBE_JSON` 记录，不得手编。

每个 `probe_record` 固定 schema=`agentmirror.baseline-bundle.real-chain.v1`、classification=`legacy_missing_baseline_park`，并逐字段包含：

- `probe`：固定仓内 path 与实际脚本 `sha256`；
- `ledger`：固定 path、实际 `sha256`、ledger_id=`ledger.perf-regress.v1`、revision=4、desired_state=`running`、impl_state=`failed_retryable`；
- `measurement`：worktree_id=`wt-pr-impl`、固定相对 path、实际 `sha256`、verdict=`unjudgeable`；
- `process`：固定 lease/pidfile path、各自实际 SHA-256、`lease_pid`、`pidfile_pid`、comm=`ledger-run`，两 PID 必须相等且为正整数；
- `dry_run`：实际 dry-run 内容 SHA-256、frontier=`[]`、impl_exclusion=`state_not_dispatchable`、verify_exclusion=`dependency_unsatisfied`。

两次 record 的稳定语义和不可变 provenance 必须一致；lease 文件因 heartbeat 可变化，只允许 `lease_sha256` 不同。REPRO.md 可沿用上一 case 的人读分析并补充 REPRO.json 路径、两次 observed_at 与摘要；不要求 `revision: 4`、`frontier: []` 或恢复路线词以某种 Markdown 字面出现。

## 转译与四态

机械 wrapper 必须亲自连续运行真实 probe 两次，再用 `baseline-bundle-repro-translate.sh` 对 fresh records 与 REPRO.json 对账：

- 两次真实 probe 均 exit 1，且 classification、完整 shape、provenance、REPRO.json 全部吻合：这是“旧链按预期红”的复现事实；translator 映射为 repro acceptance exit 0；
- 任一次 probe exit 2，或 schema/provenance/必要事实缺失：exit 2，不可判；
- probe exit 0（旧红已消失但本 repro case 没有后继迁移证明）、其它 rc、伪造 rc、字段矛盾、摘要不符：exit 1；
- 禁止把 acceptance expected 改为 1，禁止把裸 probe rc=1 直接当产品成功；成功来自固定转译规则的完整合取。

## 硬约束与出口

不得触碰真实 lease/PID/JSON，不修改门槛或旧 A 身份。临时件只写本格 `tmp/`。required artifacts 齐后只 `report_result` 一次；不 commit、不另发消息。REPRO.json+REPRO.md 与两次 fresh expected-red 全合取为 exit 0；伪造/矛盾为 exit 1；环境/证据不可判为 exit 2。
