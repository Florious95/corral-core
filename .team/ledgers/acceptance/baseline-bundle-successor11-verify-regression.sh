#!/bin/sh
# //! purpose: 用纯文件夹具锁定 successor11 fresh verify 的四态、permanent fixture 与零 live-device 依赖。
# //! contract: 0=绿控与全部破坏齿符合；1=错误放行/分流或 spy 被触发；2=回归量具不可判。
# //! boundary: 只写 successor11 node-local tmp；不读取或调用真实 adb/emulator/qemu/install/kill。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
umask 077

fail() { printf '%s\n' "FAIL baseline-bundle-successor11-verify-regression: $*" >&2; exit 1; }
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-successor11-verify-regression: $*" >&2; exit 2; }

script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
repo_root=$(CDPATH='' cd "$script_dir/../../.." 2>/dev/null && pwd) || unjudgeable "cannot resolve repository root"
wrapper="$script_dir/baseline-bundle-successor11-verify.sh"
helper="$script_dir/baseline-bundle-successor11-verify.py"
scratch="$repo_root/.team/nodes/spec-sol/baseline-bundle-successor11/tmp/verify-regression-$$"

for tool in python3 sh mkdir rm cp chmod ln date grep sed; do
    command -v "$tool" >/dev/null 2>&1 || unjudgeable "$tool unavailable"
done
[ -r "$wrapper" ] && [ -s "$wrapper" ] || unjudgeable "wrapper unavailable"
[ -r "$helper" ] && [ -s "$helper" ] || unjudgeable "validator unavailable"
mkdir -p "$scratch" || unjudgeable "cannot create node-local scratch"
trap 'rm -rf "$scratch"' EXIT INT TERM HUP

build_case() {
    case_root=$1
    mkdir -p "$case_root/.team/ledgers/acceptance/fixtures/baseline-bundle-successor11" \
        "$case_root/.team/ledgers/acceptance/fixtures/baseline-bundle-successor7/impl-bypass" \
        "$case_root/.team/ledgers/acceptance" \
        "$case_root/.team/nodes/baseline-bundle-apparatus" \
        "$case_root/.team/nodes/baseline-bundle-impl" \
        "$case_root/.team/nodes/baseline-bundle-verify" || unjudgeable "cannot create fixture tree"
    python3 - "$case_root" <<'PY'
import hashlib,json,os,pathlib,sys,time
root=pathlib.Path(sys.argv[1])
bundle="1"*64
apk="2"*64
batch="3"*64
now=int(time.time())
def write(rel,data,mode=0o600):
    p=root/rel; p.parent.mkdir(parents=True,exist_ok=True)
    if isinstance(data,(dict,list)): text=json.dumps(data,sort_keys=True,separators=(",",":"))+"\n"
    else: text=data
    p.write_text(text,encoding="utf-8"); p.chmod(mode); return p
def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()
producer=write(".team/ledgers/acceptance/baseline-bundle-successor10-owned-emulator.sh","#!/bin/sh\n# fixed producer with install and pm postcondition\n",0o644)
manifest=write(".team/nodes/baseline-bundle-impl/BUNDLE-MANIFEST.json",{
    "bundle_id":bundle,"artifact":{"apk_sha256":apk}
},0o644)
permanent=write(".team/ledgers/acceptance/fixtures/baseline-bundle-successor7/impl-bypass/BUNDLE-MANIFEST.json",{
    "fixture":"permanent-successor7","forged_bundle_id":"short"
},0o644)
apparatus_data={
    "schema":"agentmirror.successor7.apparatus.v1","mode":"production","created_epoch":now-10,
    "bundle_id":bundle,"manifest_sha256":digest(manifest),"apk_sha256":apk,"adb_install_exit":0,
    "envcheck_preflight_exit":0,"envcheck_measurement_exit":0,"envcheck_recovery_exit":0,
    "fresh_task_avd":True,"owned_qemu_bound":True,"runner_pid_cleanup":True,"serial_cleanup":True,
    "owned_qemu_cleanup":True,"forced_kill":False,"foreign_qemu_touched":False
}
apparatus=write(".team/nodes/baseline-bundle-apparatus/APPARATUS.json",apparatus_data)
contract={
    "schema":"agentmirror.successor11.verify-contract.v1",
    "apparatus":{"relpath":".team/nodes/baseline-bundle-apparatus/APPARATUS.json","sha256":digest(apparatus),
        "schema":"agentmirror.successor7.apparatus.v1","mode":"production","created_epoch":now-10,
        "bundle_id":bundle,"manifest_sha256":digest(manifest),
        "producer_relpath":".team/ledgers/acceptance/baseline-bundle-successor10-owned-emulator.sh",
        "producer_sha256":digest(producer)},
    "manifest":{"relpath":".team/nodes/baseline-bundle-impl/BUNDLE-MANIFEST.json","sha256":digest(manifest)},
    "permanent_fixture":{"relpath":".team/ledgers/acceptance/fixtures/baseline-bundle-successor7/impl-bypass/BUNDLE-MANIFEST.json","sha256":digest(permanent)},
    "verify_dir":".team/nodes/baseline-bundle-verify","freshness_seconds":7200
}
write(".team/ledgers/acceptance/fixtures/baseline-bundle-successor11/verify-contract.json",contract,0o644)
headers={
    "successor11_verify_schema":"agentmirror.successor11.verify-evidence.v1",
    "successor11_verify_batch_id":batch,
    "apparatus_evidence_sha256":contract["apparatus"]["sha256"],
    "apparatus_bundle_id":bundle,
    "apparatus_manifest_sha256":contract["manifest"]["sha256"],
    "permanent_fixture_sha256":contract["permanent_fixture"]["sha256"],
}
evidence={}
for name in ("VERDICT.md","INSTALL.md","RETRIEVE.md","MUTATION.md"):
    lines=[f"successor11_verify_schema: {headers['successor11_verify_schema']}",f"successor11_verify_artifact: {name}"]
    lines.extend(f"{key}: {value}" for key,value in headers.items() if key != "successor11_verify_schema")
    lines.extend(["",f"# Fresh {name}","Archived same-batch evidence was independently cross-checked."])
    if name=="VERDICT.md": lines.extend(["","verdict: pass"])
    p=write(f".team/nodes/baseline-bundle-verify/{name}","\n".join(lines)+"\n")
    evidence[name]=digest(p)
verify={
    "schema":"agentmirror.successor11.verify.v1","verdict":"pass","verify_batch_id":batch,"verified_epoch":now,
    "apparatus_evidence_sha256":contract["apparatus"]["sha256"],"apparatus_bundle_id":bundle,
    "apparatus_manifest_sha256":contract["manifest"]["sha256"],"apparatus_install_exit":0,
    "apparatus_pm_identity_verified":True,"runner_pid_cleanup":True,"serial_cleanup":True,
    "owned_qemu_cleanup":True,"forced_kill":False,
    "permanent_fixture_sha256":contract["permanent_fixture"]["sha256"],"permanent_bypass_probe_exit":0,
    "legacy_temporary_gate_used":False,"current_adb_required":False,"evidence_sha256":evidence
}
write(".team/nodes/baseline-bundle-verify/VERIFY.json",verify)
PY
}

make_tool_path() {
    case_root=$1
    bin="$case_root/bin"
    spy="$case_root/forbidden.spy"
    mkdir -p "$bin" || unjudgeable "cannot create isolated tool path"
    ln -s "$(command -v python3)" "$bin/python3" || unjudgeable "cannot link python3"
    ln -s "$(command -v dirname)" "$bin/dirname" || unjudgeable "cannot link dirname"
    for forbidden in emulator qemu-system-aarch64 kill pkill; do
        cat >"$bin/$forbidden" <<'SH'
#!/bin/sh
tool_name=${0##*/}
printf '%s\n' "$tool_name $*" >>"$SUCCESSOR11_SPY"
exit 97
SH
        chmod 700 "$bin/$forbidden" || unjudgeable "cannot prepare forbidden spy"
    done
}

run_case() {
    case_name=$1
    expected_rc=$2
    case_root="$scratch/$case_name"
    build_case "$case_root"
    make_tool_path "$case_root"
    shift 2
    "$@" "$case_root"
    env PATH="$bin" SUCCESSOR11_SPY="$spy" SUCCESSOR11_FIXTURE_MODE=1 \
        SUCCESSOR11_TEST_HARNESS=baseline-bundle-successor11-verify-regression \
        SUCCESSOR11_TEST_ROOT="$case_root" /bin/sh "$wrapper" >"$case_root/output" 2>&1
    actual_rc=$?
    [ "$actual_rc" -eq "$expected_rc" ] || fail "$case_name expected rc=$expected_rc got rc=$actual_rc"
    [ ! -s "$spy" ] || fail "$case_name touched a forbidden live-device/process tool"
}

no_mutation() { :; }
add_live_adb() {
    case_root=$1
    cat >"$case_root/bin/adb" <<'SH'
#!/bin/sh
printf '%s\n' "adb $*" >>"$SUCCESSOR11_SPY"
exit 0
SH
    chmod 700 "$case_root/bin/adb"
}
add_legacy_fixture() {
    case_root=$1
    mkdir -p "$case_root/.team/nodes/baseline-bundle-verify/legacy-temporary"
    printf '%s\n' forged >"$case_root/.team/nodes/baseline-bundle-verify/legacy-temporary/BYPASS.json"
}
remove_permanent() {
    rm "$1/.team/ledgers/acceptance/fixtures/baseline-bundle-successor7/impl-bypass/BUNDLE-MANIFEST.json"
}
forge_permanent() {
    printf '%s\n' tampered >>"$1/.team/ledgers/acceptance/fixtures/baseline-bundle-successor7/impl-bypass/BUNDLE-MANIFEST.json"
}
mode_0644() { chmod 644 "$1/.team/nodes/baseline-bundle-verify/VERIFY.json"; }
missing_verify() { rm "$1/.team/nodes/baseline-bundle-verify/VERIFY.json"; }
old_unjudgeable_last_line_only() {
    case_root=$1
    for name in VERDICT.md INSTALL.md RETRIEVE.md MUTATION.md; do
        printf '# old successor10 artifact\n' >"$case_root/.team/nodes/baseline-bundle-verify/$name"
        chmod 600 "$case_root/.team/nodes/baseline-bundle-verify/$name"
    done
    printf 'verdict: pass\n' >>"$case_root/.team/nodes/baseline-bundle-verify/VERDICT.md"
    printf '%s\n' '{"schema":"baseline-bundle-successor10.verify.v1","verdict_basis":"apparatus_unavailable"}' \
        >"$case_root/.team/nodes/baseline-bundle-verify/VERIFY.json"
    chmod 600 "$case_root/.team/nodes/baseline-bundle-verify/VERIFY.json"
}
live_does_not_rescue_forgery() { add_live_adb "$1"; forge_permanent "$1"; }

# Positive control has no adb executable at all: archived apparatus pass remains 0.
run_case apparatus_pass_adb_absent 0 no_mutation
run_case fake_live_adb_ignored 0 add_live_adb
run_case legacy_temporary_missing 0 no_mutation
run_case legacy_temporary_present_ignored 0 add_legacy_fixture
run_case permanent_missing 2 remove_permanent
run_case permanent_forged 1 forge_permanent
run_case live_adb_does_not_rescue_forgery 1 live_does_not_rescue_forgery
run_case verify_mode_0644 1 mode_0644
run_case verify_missing 2 missing_verify
run_case old_unjudgeable_only_last_line_changed 1 old_unjudgeable_last_line_only

# Fixture selectors and production overrides fail closed before any production fact is read.
selector_root="$scratch/selector"
mkdir -p "$selector_root" || unjudgeable "cannot create selector tooth"
make_tool_path "$selector_root"
env PATH="$bin" SUCCESSOR11_SPY="$spy" SUCCESSOR11_FIXTURE_MODE=2 \
    /bin/sh "$wrapper" >"$selector_root/mode.output" 2>&1
[ "$?" -eq 1 ] || fail "non-exact fixture mode was accepted"
env PATH="$bin" SUCCESSOR11_SPY="$spy" SUCCESSOR11_TEST_ROOT="$selector_root" \
    /bin/sh "$wrapper" >"$selector_root/production.output" 2>&1
[ "$?" -eq 1 ] || fail "production test override was accepted"
[ ! -s "$spy" ] || fail "selector teeth touched a forbidden tool"

grep -F 'baseline-bundle-successor7-impl-bypass.sh' "$wrapper" >/dev/null 2>&1 || fail "production permanent fixture gate missing"
grep -F 'baseline-bundle-successor6-verify.sh' "$wrapper" >/dev/null 2>&1 && fail "legacy successor6 verify gate returned"
grep -F 'current_adb_required' "$helper" >/dev/null 2>&1 || fail "live-device negative contract missing"
grep -F 'legacy_temporary_gate_used' "$helper" >/dev/null 2>&1 || fail "legacy temporary negative contract missing"

printf '%s\n' "SUCCESSOR11_VERIFY_REGRESSION control=0 adb_absent=0 live_adb_ignored=0 legacy_missing=0 permanent_missing=2 permanent_forged=1 verify0644=1 stale_last_line=1 forbidden_actions=0"
printf '%s\n' "PASS baseline-bundle-successor11-verify-regression: archived facts and fresh private evidence are fail-closed"
