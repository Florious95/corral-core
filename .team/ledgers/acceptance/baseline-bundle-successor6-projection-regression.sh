#!/bin/sh
# //! purpose: 复现 legacy id-prefix 固定点红，并锁 canonical identity/独立槽位新门及防伪破坏齿。
# //! contract: 0=合法投影绿且各单变量伪造按契约拒绝；1=错误放行/错误拒绝；2=fixture/helper/量具不可判。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
fail() { printf '%s\n' "FAIL baseline-bundle-successor6-projection-regression: $*" >&2; exit 1; }
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-successor6-projection-regression: $*" >&2; exit 2; }

script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
repo_root=$(CDPATH='' cd "$script_dir/../../.." 2>/dev/null && pwd) || unjudgeable "cannot resolve repository root"
helper="$script_dir/baseline-bundle-successor6-projection.py"
contract="$script_dir/fixtures/baseline-bundle-successor6/projection-contract.json"
fixture="$script_dir/fixtures/baseline-bundle-successor6/legal-successor5-manifest.json"
scratch="$repo_root/.team/nodes/spec-sol/baseline-bundle-successor6/tmp/projection-regression/case-$$"

command -v python3 >/dev/null 2>&1 || unjudgeable "python3 unavailable"
command -v shasum >/dev/null 2>&1 || unjudgeable "shasum unavailable"
for fixed_input in "$helper" "$contract" "$fixture"; do
    [ -r "$fixed_input" ] && [ -s "$fixed_input" ] || unjudgeable "fixed projection input unavailable"
done
helper_sha=$(shasum -a 256 "$helper" 2>/dev/null | awk '{print $1}') || unjudgeable "cannot hash projection helper"
contract_sha=$(shasum -a 256 "$contract" 2>/dev/null | awk '{print $1}') || unjudgeable "cannot hash projection contract"
fixture_sha=$(shasum -a 256 "$fixture" 2>/dev/null | awk '{print $1}') || unjudgeable "cannot hash legal projection fixture"
[ "$helper_sha" = 00682de427e9597980f17d1b70707b859e9f49d7d093aa0577bf4bb0a5170d33 ] || unjudgeable "projection helper digest drift"
[ "$contract_sha" = c51e84bc7dd17961e78d24b143ae074dcf0dc816582cf18a6c4f544d8d7979be ] || unjudgeable "projection contract digest drift"
[ "$fixture_sha" = 60b27f49819c1254ea6d11b01cc619f5f243bcd8bc537943430d9ef74ab86bd3 ] || unjudgeable "legal projection fixture digest drift"
mkdir -p "$scratch" || unjudgeable "cannot create node-local scratch"

python3 - "$helper" "$contract" "$fixture" "$scratch" <<'PY'
import copy
import hashlib
import json
import pathlib
import subprocess
import sys

helper, contract_path, fixture_path, scratch = map(pathlib.Path, sys.argv[1:])
try:
    contract = json.loads(contract_path.read_text(encoding="utf-8"))
    fixture = json.loads(fixture_path.read_text(encoding="utf-8"))
except (OSError, UnicodeError, json.JSONDecodeError):
    print("UNJUDGEABLE baseline-bundle-successor6-projection-regression: fixed JSON unavailable", file=sys.stderr)
    raise SystemExit(2)

keys = contract.get("canonical_projection_keys")
if keys != ["source", "runtime", "artifact", "build", "equivalence", "implementation"]:
    print("UNJUDGEABLE baseline-bundle-successor6-projection-regression: projection contract drift", file=sys.stderr)
    raise SystemExit(2)
if contract.get("mutation_contract") != {
    "canonical_bundle_id_tamper": 1,
    "path_traversal": 1,
    "slot_swap": 1,
    "legacy_id_prefix_with_stale_bundle_id": 1,
    "missing_manifest_or_tool": 2,
}:
    print("UNJUDGEABLE baseline-bundle-successor6-projection-regression: mutation contract drift", file=sys.stderr)
    raise SystemExit(2)


def canonicalize(manifest):
    projection = {key: manifest[key] for key in keys}
    bundle_id = hashlib.sha256(json.dumps(projection, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()
    manifest["bundle_id"] = bundle_id
    manifest["archive"]["primary_relpath"] = contract["archive"]["primary_template"].replace("{bundle_id}", bundle_id)
    manifest["archive"]["backup_relpath"] = contract["archive"]["backup_template"].replace("{bundle_id}", bundle_id)


def run_case(name, manifest):
    path = scratch / f"{name}.json"
    path.write_text(json.dumps(manifest, ensure_ascii=False, sort_keys=True) + "\n", encoding="utf-8")
    return subprocess.run(
        [sys.executable, str(helper), "--manifest", str(path), "--contract", str(contract_path)],
        text=True,
        capture_output=True,
        check=False,
    )


legal = run_case("legal", fixture)
if legal.returncode != 0 or "SUCCESSOR6_CANONICAL_IDENTITY equal=true" not in legal.stdout or "SUCCESSOR6_SLOT_PROJECTION" not in legal.stdout:
    print("FAIL baseline-bundle-successor6-projection-regression: legal projection did not pass", file=sys.stderr)
    raise SystemExit(1)

legacy_prefix = f".team/private/baseline-vault/{fixture['bundle_id']}/builds/"
legacy_path = fixture["build"]["independent_builds"][0]["apk_relpath"]
legacy_rc = 0 if legacy_path.startswith(legacy_prefix) else 1
if legacy_rc != 1:
    print("FAIL baseline-bundle-successor6-projection-regression: legal successor5 path unexpectedly satisfies legacy prefix", file=sys.stderr)
    raise SystemExit(1)

tampered_id = copy.deepcopy(fixture)
tampered_id["bundle_id"] = "f" * 64
tampered_id_case = run_case("tampered-id", tampered_id)
if tampered_id_case.returncode != 1 or "canonical content identity" not in tampered_id_case.stderr:
    print("FAIL baseline-bundle-successor6-projection-regression: bundle_id tamper tooth drift", file=sys.stderr)
    raise SystemExit(1)

traversal = copy.deepcopy(fixture)
traversal["build"]["independent_builds"][0]["apk_relpath"] = "../escape.apk"
canonicalize(traversal)
traversal_case = run_case("traversal", traversal)
if traversal_case.returncode != 1 or "unsafe component" not in traversal_case.stderr:
    print("FAIL baseline-bundle-successor6-projection-regression: traversal tooth drift", file=sys.stderr)
    raise SystemExit(1)

slot_tamper = copy.deepcopy(fixture)
slot_tamper["build"]["independent_builds"][0]["apk_relpath"] = ".team/private/baseline-vault/builds/build-x.apk"
canonicalize(slot_tamper)
slot_tamper_case = run_case("slot-tamper", slot_tamper)
if slot_tamper_case.returncode != 1 or "slot[0] projection mismatch" not in slot_tamper_case.stderr:
    print("FAIL baseline-bundle-successor6-projection-regression: slot tamper tooth drift", file=sys.stderr)
    raise SystemExit(1)

slot_swap = copy.deepcopy(fixture)
first = slot_swap["build"]["independent_builds"][0]["apk_relpath"]
second = slot_swap["build"]["independent_builds"][1]["apk_relpath"]
slot_swap["build"]["independent_builds"][0]["apk_relpath"] = second
slot_swap["build"]["independent_builds"][1]["apk_relpath"] = first
canonicalize(slot_swap)
slot_swap_case = run_case("slot-swap", slot_swap)
if slot_swap_case.returncode != 1 or "slot[0] projection mismatch" not in slot_swap_case.stderr:
    print("FAIL baseline-bundle-successor6-projection-regression: slot swap tooth drift", file=sys.stderr)
    raise SystemExit(1)

legacy_scoped = copy.deepcopy(fixture)
legacy_scoped["build"]["independent_builds"][0]["apk_relpath"] = legacy_prefix + "build-1.apk"
canonicalize(legacy_scoped)
legacy_scoped_case = run_case("legacy-scoped", legacy_scoped)
if legacy_scoped_case.returncode != 1 or "slot[0] projection mismatch" not in legacy_scoped_case.stderr:
    print("FAIL baseline-bundle-successor6-projection-regression: legacy scoped tooth drift", file=sys.stderr)
    raise SystemExit(1)

missing = subprocess.run(
    [sys.executable, str(helper), "--manifest", str(scratch / "missing.json"), "--contract", str(contract_path)],
    text=True,
    capture_output=True,
    check=False,
)
if missing.returncode != 2:
    print("FAIL baseline-bundle-successor6-projection-regression: missing manifest four-state drift", file=sys.stderr)
    raise SystemExit(1)

malformed_path = scratch / "malformed.json"
malformed_path.write_text("{\n", encoding="utf-8")
malformed = subprocess.run(
    [sys.executable, str(helper), "--manifest", str(malformed_path), "--contract", str(contract_path)],
    text=True,
    capture_output=True,
    check=False,
)
if malformed.returncode != 1 or "malformed JSON" not in malformed.stderr:
    print("FAIL baseline-bundle-successor6-projection-regression: malformed manifest four-state drift", file=sys.stderr)
    raise SystemExit(1)

print("SUCCESSOR6_LEGACY_PREFIX_REPRO old_constraint_rc=1 legal_successor5_projection=true")
print("SUCCESSOR6_PROJECTION_RED_GREEN legal=0 bundle_id_tamper=1 traversal=1 slot_tamper=1 slot_swap=1 legacy_scoped=1 malformed=1 missing=2")
PY
rc=$?
case "$rc" in 0) ;; 1) exit 1 ;; 2) exit 2 ;; *) unjudgeable "projection regression returned unsupported status" ;; esac
printf '%s\n' "PASS baseline-bundle-successor6-projection-regression: content identity and independent slot teeth verified"
