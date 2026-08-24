#!/bin/sh
# //! purpose: 永久证明判者两枚伪造夹具可绕过旧门、但被当前独立门拒绝。
# //! contract: 0=两齿均 old=0/new=1|2；1=破坏齿失效；2=夹具/量具不可判。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
fail() { printf '%s\n' "FAIL baseline-bundle-bypass-probes: $*" >&2; exit 1; }
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-bypass-probes: $*" >&2; exit 2; }
script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
repo_root=$(CDPATH='' cd "$script_dir/../../.." 2>/dev/null && pwd) || unjudgeable "cannot resolve repository root"
review="$repo_root/.team/nodes/baseline-bundle-prelaunch-review/tmp"
impl_src="$review/impl-bypass"
measure_src="$review/measure-bypass"
run_root="$repo_root/.team/nodes/spec-sol/baseline-bundle/tmp/bypass-probes"

command -v shasum >/dev/null 2>&1 || unjudgeable "shasum unavailable"
command -v cp >/dev/null 2>&1 || unjudgeable "cp unavailable"
for f in "$impl_src/.team/ledgers/acceptance/baseline-bundle-impl.sh" "$measure_src/.team/ledgers/acceptance/baseline-bundle-measure.sh" "$impl_src/.team/nodes/baseline-bundle-impl/BUNDLE-MANIFEST.json" "$measure_src/.team/nodes/baseline-bundle-measure/perf-ab-bundle.json"; do
    [ -r "$f" ] && [ -s "$f" ] || unjudgeable "frozen bypass fixture missing $f"
done
check_sha() {
    got=$(shasum -a 256 "$1" 2>/dev/null | awk '{print $1}') || unjudgeable "cannot hash frozen fixture"
    [ "$got" = "$2" ] || unjudgeable "frozen fixture drift $1"
}
check_sha "$impl_src/.team/ledgers/acceptance/baseline-bundle-impl.sh" aeba81ad49d6ee31000c9222cc1488cc9acef239c6136446cf36b7918beeb1fb
check_sha "$measure_src/.team/ledgers/acceptance/baseline-bundle-measure.sh" dc5f2a9d7e3e3ddc2f5477dbd60693acb19b016928a3e6280f870e62e6e1623a
check_sha "$impl_src/.team/nodes/baseline-bundle-impl/BUNDLE-MANIFEST.json" e1b5333e417e4b45d62b22f27b29fc662dac88e7098b5e42b977570eee4296b9
check_sha "$measure_src/.team/nodes/baseline-bundle-measure/perf-ab-bundle.json" 2b301fa1806d5848c61d0424e61e6826fde43d19d863e0c332cb741524309128

case "$run_root" in "$repo_root/.team/nodes/spec-sol/baseline-bundle/tmp/"*) ;; *) unjudgeable "unsafe temp root" ;; esac
rm -rf "$run_root"
mkdir -p "$run_root/impl" "$run_root/measure" || unjudgeable "cannot create package-local probe temp"
cp -R "$impl_src/." "$run_root/impl/" || unjudgeable "cannot copy impl bypass fixture"
cp -R "$measure_src/." "$run_root/measure/" || unjudgeable "cannot copy measure bypass fixture"

legacy_impl=$(sh "$run_root/impl/.team/ledgers/acceptance/baseline-bundle-impl.sh" >/dev/null 2>&1; printf '%s' "$?")
legacy_measure=$(sh "$run_root/measure/.team/ledgers/acceptance/baseline-bundle-measure.sh" >/dev/null 2>&1; printf '%s' "$?")
[ "$legacy_impl" -eq 0 ] || fail "impl old-door positive control no longer bypasses rc=$legacy_impl"
[ "$legacy_measure" -eq 0 ] || fail "measure old-door positive control no longer bypasses rc=$legacy_measure"

cp "$script_dir/baseline-bundle-impl.sh" "$run_root/impl/.team/ledgers/acceptance/baseline-bundle-impl.sh" || unjudgeable "cannot install hardened impl gate in fixture"
cp "$script_dir/baseline-bundle-measure.sh" "$run_root/measure/.team/ledgers/acceptance/baseline-bundle-measure.sh" || unjudgeable "cannot install hardened measure gate in fixture"
python3 - "$run_root/measure" <<'PY'
import hashlib,json,sys
from pathlib import Path
r=Path(sys.argv[1]); bid="a"*64; batch="bypass-empty-raw"
runner=r/"tools/perfbase/run-input-ab.sh"; runner.parent.mkdir(parents=True,exist_ok=True); runner.write_text("#!/bin/sh\nexit 0\n")
a=b"fake-a-apk"; b=b"fake-b-apk"
sha=lambda x: hashlib.sha256(x).hexdigest(); md5=lambda x: hashlib.md5(x).hexdigest()
ap=r/f".team/private/baseline-vault/{bid}/baseline.apk"; ap.parent.mkdir(parents=True,exist_ok=True); ap.write_bytes(a)
bp=r/f".team/private/baseline-candidates/{sha(b)}/candidate.apk"; bp.parent.mkdir(parents=True,exist_ok=True); bp.write_bytes(b)
runner_sha=sha(runner.read_bytes())
identity={"batch_id":batch,"runner_sha256":runner_sha,"baseline_bundle_id":bid,"a_apk_sha256":sha(a),"a_apk_md5":md5(a),"b_apk_sha256":sha(b),"b_apk_md5":md5(b),"b_revision":"bypass-candidate"}
manifest={"bundle_id":bid,"artifact":{"apk_sha256":sha(a),"apk_md5":md5(a)},"archive":{"primary_relpath":f".team/private/baseline-vault/{bid}/baseline.apk"},"implementation":{"runner":{"path":"tools/perfbase/run-input-ab.sh","sha256":runner_sha}}}
node=r/".team/nodes/baseline-bundle-measure"
pre={**identity,"bundle_retrieve_exit":0,"archive_restore_exit":0,"install_exit":0,"envcheck_gate_exit":0,"runner_path":"tools/perfbase/run-input-ab.sh","candidate_apk_sha256":sha(b),"candidate_apk_md5":md5(b),"candidate_revision":"bypass-candidate","candidate_apk_relpath":f".team/private/baseline-candidates/{sha(b)}/candidate.apk"}
result={"schema":"perf-ab.v1","baseline_bundle_id":bid,"baseline_measured_sha256":sha(a),"baseline_measured_md5":md5(a),"candidate_sha256":sha(b),"candidate_md5":md5(b),"candidate_revision":"bypass-candidate","batch_id":batch,"runner_sha256":runner_sha,"env":{"gate_exit":0},"measurement":"pass","verdict":"pass","fixtures":{}}
(r/".team/nodes/baseline-bundle-impl/BUNDLE-MANIFEST.json").write_text(json.dumps(manifest))
(node/"PRE-MEASURE.json").write_text(json.dumps(pre)); (node/"perf-ab-bundle.json").write_text(json.dumps(result))
raw=node/"raw"
for p in raw.glob("*/*.log"): p.unlink()
lines=["# "+k+"="+str(v) for k,v in identity.items()]
rows=[]
for fx in ("big_scrollback","real_claude_idle","redraw_tui"):
    for seq in range(1,11):
        for package in ("A","B"):
            rows.append(f"{fx}\t{seq}\t{package}")
            p=raw/package/f"{fx}-{seq:02d}.log"; p.parent.mkdir(parents=True,exist_ok=True); p.write_bytes(b"")
(raw/"order.tsv").write_text("\n".join(lines+rows)+"\n")
PY
python_rc=$?
[ "$python_rc" -eq 0 ] || unjudgeable "cannot prepare hardened empty-raw tooth"
git -C "$run_root/measure" init -q >/dev/null 2>&1 || unjudgeable "cannot create isolated git provenance for raw tooth"
sh "$run_root/impl/.team/ledgers/acceptance/baseline-bundle-impl.sh" >"$run_root/hardened-impl.out" 2>&1
hardened_impl=$?
sh "$run_root/measure/.team/ledgers/acceptance/baseline-bundle-measure.sh" >"$run_root/hardened-measure.out" 2>&1
hardened_measure=$?
case "$hardened_impl" in 1|2) ;; *) fail "hardened impl gate accepted bypass rc=$hardened_impl" ;; esac
case "$hardened_measure" in 1|2) ;; *) fail "hardened measure gate accepted bypass rc=$hardened_measure" ;; esac
grep -F 'repository root mismatch' "$run_root/hardened-impl.out" >/dev/null 2>&1 || fail "impl tooth did not reach fixed-provenance rejection"
grep -F 'empty raw log' "$run_root/hardened-measure.out" >/dev/null 2>&1 || fail "measure tooth did not reach empty-raw rejection"
printf '%s\n' "BYPASS_TOOTH impl legacy=$legacy_impl hardened=$hardened_impl fixture=short-digest-stub-manifest"
printf '%s\n' "BYPASS_TOOTH measure legacy=$legacy_measure hardened=$hardened_measure fixture=empty-raw-fabricated-json"
printf '%s\n' "PASS baseline-bundle-bypass-probes: both frozen old-door bypasses are rejected by hardened gates"
