#!/bin/sh
# //! purpose: 锁住形式红回归：语义 JSON 绿；伪造 rc/shape 红；缺 provenance 不可判。
# //! contract: 0=四个对照符合；1=回归齿失效；2=夹具工具不可用。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
fail() { printf '%s\n' "FAIL baseline-bundle-repro-regression: $*" >&2; exit 1; }
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-repro-regression: $*" >&2; exit 2; }
script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
repo_root=$(CDPATH='' cd "$script_dir/../../.." 2>/dev/null && pwd) || unjudgeable "cannot resolve repository root"
translate="$script_dir/baseline-bundle-repro-translate.sh"
tmp="$repo_root/.team/nodes/baseline-bundle-repro/tmp/regression-$$"
[ -r "$translate" ] || unjudgeable "translator unreadable"
command -v python3 >/dev/null 2>&1 || unjudgeable "python3 unavailable"
mkdir -p "$tmp" 2>/dev/null || unjudgeable "cannot create regression temp"
cleanup() { [ ! -d "$tmp" ] || find "$tmp" -depth -delete 2>/dev/null || :; }
trap cleanup EXIT HUP INT TERM

python3 - "$tmp" <<'PY'
import hashlib,json,sys
from pathlib import Path
p=Path(sys.argv[1]); h="a"*64
record={
 "schema":"agentmirror.baseline-bundle.real-chain.v1","classification":"legacy_missing_baseline_park",
 "probe":{"path":".team/ledgers/acceptance/baseline-bundle-real-chain-probe.sh","sha256":h},
 "ledger":{"path":".team/ledgers/perf-regress-v1.json","sha256":h,"ledger_id":"ledger.perf-regress.v1","revision":4,"desired_state":"running","impl_state":"failed_retryable"},
 "measurement":{"worktree_id":"wt-pr-impl","path":".team/nodes/perf-regress/FIXED-MEASURE.md","sha256":h,"verdict":"unjudgeable"},
 "process":{"lease_path":".team/ledgers/perf-regress-v1.json.lease","lease_sha256":h,"pidfile_path":".team/nodes/_driver/perf-regress-v1.pid","pidfile_sha256":h,"lease_pid":85754,"pidfile_pid":85754,"comm":"ledger-run"},
 "dry_run":{"sha256":h,"frontier":[],"impl_exclusion":"state_not_dispatchable","verify_exclusion":"dependency_unsatisfied"}
}
canon=lambda v: json.dumps(v,sort_keys=True,separators=(",",":"),ensure_ascii=False)
digest=lambda v: hashlib.sha256(canon(v).encode()).hexdigest()
report={
 "schema":"agentmirror.baseline-bundle.repro.v1","evidence_kind":"expected_legacy_red",
 "contract":{"taskbook_path":".team/nodes/spec-sol/baseline-bundle/repro-任务书.md","acceptance_path":".team/ledgers/acceptance/baseline-bundle-repro.sh","translator_path":".team/ledgers/acceptance/baseline-bundle-repro-translate.sh","human_report_path":".team/nodes/baseline-bundle-repro/REPRO.md"},
 "runs":[{"sequence":i,"probe_exit":1,"observed_at":f"2026-08-25T00:00:0{i}Z","probe_record_sha256":digest(record),"probe_record":record} for i in (1,2)],
 "translation":{"rule_id":"expected_legacy_red_to_repro_pass.v1","input_classification":"legacy_missing_baseline_park","input_probe_exit":1,"output_acceptance_exit":0},
 "future_gate":{"task_id":"t.baseline-bundle.final","required_probe_exit":0,"classification":"baseline_bundle_migration_complete"}
}
(p/"run1.json").write_text(canon(record)+"\n"); (p/"run2.json").write_text(canon(record)+"\n"); (p/"report.json").write_text(canon(report)+"\n")
missing=json.loads(canon(record)); del missing["probe"]["sha256"]; (p/"missing-provenance.json").write_text(canon(missing)+"\n")
forged=json.loads(canon(record)); forged["ledger"]["impl_state"]="succeeded"; (p/"forged-shape.json").write_text(canon(forged)+"\n")
(p/"REPRO.md").write_text("# Human repro narrative\n\nTwo real observations agree; machine semantics live only in REPRO.json.\n")
PY
fixture_rc=$?
[ "$fixture_rc" -eq 0 ] || unjudgeable "cannot create regression fixtures"
if grep -F -e 'revision: 4' -e 'frontier: []' -e 'recover_exact_artifact' -e 'rebaseline_with_equivalence_proof' "$tmp/REPRO.md" >/dev/null 2>&1; then fail "human control accidentally contains legacy magic token"; fi

sh "$translate" "$tmp/run1.json" 1 "$tmp/run2.json" 1 "$tmp/report.json" >/dev/null 2>&1
semantic_rc=$?
sh "$translate" "$tmp/run1.json" 0 "$tmp/run2.json" 1 "$tmp/report.json" >/dev/null 2>&1
forged_rc=$?
sh "$translate" "$tmp/missing-provenance.json" 1 "$tmp/run2.json" 1 "$tmp/report.json" >/dev/null 2>&1
missing_rc=$?
sh "$translate" "$tmp/forged-shape.json" 1 "$tmp/run2.json" 1 "$tmp/report.json" >/dev/null 2>&1
shape_rc=$?
[ "$semantic_rc" -eq 0 ] || fail "semantic evidence without legacy prose token false-red rc=$semantic_rc"
[ "$forged_rc" -eq 1 ] || fail "forged probe rc not rejected rc=$forged_rc"
[ "$missing_rc" -eq 2 ] || fail "missing provenance not unjudgeable rc=$missing_rc"
[ "$shape_rc" -eq 1 ] || fail "forged semantic shape not rejected rc=$shape_rc"
printf '%s\n' "REPRO_REGRESSION semantic_without_magic_tokens=$semantic_rc forged_rc=$forged_rc missing_provenance=$missing_rc forged_shape=$shape_rc"
printf '%s\n' "PASS baseline-bundle-repro-regression: translation layer preserves 0/1/2 and rejects formal false-red regression"
