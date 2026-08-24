# baseline-bundle successor 独立启动审查

## 范围与历史连续性

审查对象是 `.team/ledgers/src/baseline-bundle-successor-v1.py`、编译账本及 successor 任务书；没有启动 successor、apply、plan、派单或修改任何被审包。旧 `.team/ledgers/baseline-bundle-v1.json` 仍是历史事实的权威载体，不因 successor 建立而被重置。

指定历史坐标可核：首红 commit `c4846de5497903bf1a0b2db98fe9ede4c2d8ac5d`、返修 commit `488a1f25bc8b0a35afb62df85e3afd5ce666bf8e` 均为 commit 对象；首红诊断、上一轮 repro-fix `verdict: pass` 与 P0 报告的 SHA-256 均与任务书给出的 provenance 一致。

## Fresh 编译与启动边界

- 用 `/usr/bin/python3` + ledgerdsl v0.1.1 fresh 编译 successor，exit 0；编译 stdout 与 `.team/ledgers/baseline-bundle-successor-v1.json` 字节比较 exit 0。该解释器实际有 pydantic 2.13.4 与 jsonschema 4.25.1。
- `ledger-run --preflight --json` fresh exit 0，`preflight_rejected=false`、`issues=[]`；这是实际 ledger.v2 schema/preflight 门，不采信 successor 目录旧日志。
- `ledger-run --dry-run --json` fresh exit 0，得到 `ledger.baseline-bundle.successor.v1`、revision 1、desired_state running，且首 frontier 只有 `t.baseline-bundle.repro`；其余八格均由 `requires_success` 排除。
- successor JSON 有九格，九格全 `planned`，没有 runtime attempts；successor lease/pidfile 均不存在。

上述只验证 detached successor 的可启动形状；没有把它当成已落 live，也没有以 `--drive` 证明成功。

## 旧 runtime 未改与非洗红承接

旧 live SHA-256 仍为 `89ba716e85f151b06f05bf61a5631eacbcad910c0f1190a8f264a9b69a6b5723`，revision 1、ledger id `ledger.baseline-bundle.v1`；`t.baseline-bundle.repro` 仍为 `failed_retryable`，唯一首轮 attempt `att-t.baseline-bundle.repro-seq1-t1787590762873` 仍存在。successor 的全 planned/无 attempts 是“新 ledger 尚未运行”，不是旧红被成功、删除、复制或重放。

successor 以只读 `continuity_read_paths` 承接旧 ledger、首红诊断、上一轮独立判词与 P0 报告，并在任务书中明确 `supersedes ... for future execution`、禁止旧 live plan/apply/清 attempt/重派。P0 的 `Task.parallel` field-ownership 阻塞没有被 successor 内的人为删除字段、手拼 Plan、monkey-patch 或 framework patch 绕过。

## 任务图、门与产品约束

独立 JSON 对照结果：任务集合（9）、roles、10 条 `requires_success` 依赖和 `baseline-bundle-wave`（max_concurrency=3、failure_policy=halt）完全一致；每格 `worktree_id` 与 `write_paths` 一致；旧 required checks 全部保留，只有 repro 增加 `M.baseline-bundle.repro-regression`，并将 `REPRO.json` 与原 `REPRO.md` 一起列为交付物。

任务书与对应旧门仍保留：精确 A/A2 等价证明、bundle 摘要/签名/可取回/备份恢复/安装/envcheck 前置；三夹具 A/B/A/B、同批身份、每段 n>=10、nearest-rank p50/p95、所有 B/A<=1.10；缓存禁用；verify 与用户 gate 全绿后才迁移旧 perf-regress；用户必须在蜂窝网络+广州中转对确切 bundle 做真实真机“秒开无空白”裁定。没有 `AllSucceeded` 替代用户 gate 或机械/独立核验的迹象。

## Fresh repro 三态与反造假

真实 `baseline-bundle-real-chain-probe.sh` 连续两次 fresh exit 1，均给出真实 `legacy_missing_baseline_park`：live perf-regress revision 4、running、impl `failed_retryable`、measurement `unjudgeable`、frontier `[]`、impl `state_not_dispatchable`、verify `dependency_unsatisfied`，窄进程字段为 lease/pidfile=85754、comm=`ledger-run`。仅 heartbeat 造成的 lease SHA 差异按契约允许。

同两份 fresh record 与 successor 目录的 `REPRO.json` 运行真实 translator，exit 0，明确 `probe_runs=2`、`input=expected_legacy_red`、`acceptance_exit=0`；candidate 与 successor 的 repro expected code 都保持 0。fresh regression exit 0，并保留 `semantic_without_magic_tokens=0`、伪造 rc=1、伪造 shape=1、缺 provenance=2 的四态区分。四个 repro 脚本 fresh `sh -n` 与 ShellCheck 均为 0。

因此 successor 的 acceptance 0 是“真实旧红 + 固定 shape/provenance + 两次记录对账”的转译结果，不是裸 probe rc=1 直通，也不是修改 expected code 洗红；后续 final 仍必须让同一 probe 在迁移完成后真实 exit 0。

## 结论

successor 的新 ledger_id/revision=1、九格全 planned/无 attempts、旧 live/attempt 保留、任务图和 required checks 未弱化、1.10 与真机 user gate 保留、只读 provenance 承接、translator/regression 三态正确、fresh compile/schema/preflight/dry-run 与首 frontier 均满足要求。没有 plan/apply 绕过或 framework patch。

verdict: pass
