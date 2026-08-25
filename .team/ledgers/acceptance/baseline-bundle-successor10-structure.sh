#!/bin/sh
# //! purpose: 锁 successor10 新账本的 successor9-r4 审计连续性、三 command frontier、AVD+SDK apparatus 与未弱化后链。
# //! contract: 0=结构/谱系/WT/旧成功事实精确；1=有效漂移或门弱化；2=Git、量具、WT或证据不可判。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-successor10-structure: $*" >&2; exit 2; }

script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
repo_root=$(CDPATH='' cd "$script_dir/../../.." 2>/dev/null && pwd) || unjudgeable "cannot resolve repository root"
ledger="$repo_root/.team/ledgers/baseline-bundle-successor10-v1.json"
case "$#" in
    0) ;;
    1)
        candidate_dir=$(CDPATH='' cd "$(dirname "$1")" 2>/dev/null && pwd) || unjudgeable "cannot resolve candidate directory"
        case "$candidate_dir/" in
            "$repo_root/.team/nodes/spec-sol/baseline-bundle-successor10-final/tmp/"*) ledger=$1 ;;
            *) printf '%s\n' "FAIL baseline-bundle-successor10-structure: candidate escapes node-local tmp" >&2; exit 1 ;;
        esac
        ;;
    *) printf '%s\n' "FAIL baseline-bundle-successor10-structure: unsupported arguments" >&2; exit 1 ;;
esac

command -v git >/dev/null 2>&1 || unjudgeable "git unavailable"
command -v python3 >/dev/null 2>&1 || unjudgeable "python3 unavailable"
[ -e "$ledger" ] || unjudgeable "compiled successor10 ledger missing"
[ -r "$ledger" ] && [ -s "$ledger" ] || unjudgeable "compiled successor10 ledger unavailable"
[ ! -e "$repo_root/.team/ledgers/baseline-bundle-successor10-v1.json.lease" ] || unjudgeable "successor10 ledger lease exists"

python3 - "$repo_root" "$ledger" <<'PY'
import hashlib
import json
import os
import pathlib
import stat
import subprocess
import sys

root = pathlib.Path(sys.argv[1])
ledger_path = pathlib.Path(sys.argv[2])
pin = "ad7468f747d421305279632f0db9cbc227b08cd4"
r4_commit = "efed31310d94a077940544c65845eec5ec759d3e"
diagnosis_commit = "918b4c06ff93271b0c84bfc187bde1f3a5a93db3"
bootstrap_commit = "9ea73dff8b70e054fbc899bb9fd55c3e490fcc43"
pair_head = "7485102b26ed34eb828e94900902147d5e00e995"
pair_root = pathlib.Path("/Volumes/nvme/Projects/无等编排/.worktrees/wt-cmd-executor")
pair_binary = pathlib.Path("/Volumes/nvme/cargo-target-w7-builder-b/debug/ledger-run")
pair_md5 = "627f5e6fa5f47a61d23a09b918b50567"
r4_ledger_sha = "f579b99d9319f46d87a7e3c5c2c5f32b3d0851af988826b5635b170cac1084e5"
r4_driver_sha = "24a014cf38e3cb6219096f87b4b0e0e11a0c3a0175c92c4fff76b036851e72fd"
red_sha = "04cdbd661548a4b3261c88d491cf80c48f98dcbe3c080e710fb7d12bbe6c105a"
probe_sha = "88868a1a1979d3f1504e5efd6876dc5ca8ed5cc6b45a2eb6f6dd23b8e5176cf7"


def fail(message):
    print("FAIL baseline-bundle-successor10-structure: " + message, file=sys.stderr)
    raise SystemExit(1)


def unknown(message):
    print("UNJUDGEABLE baseline-bundle-successor10-structure: " + message, file=sys.stderr)
    raise SystemExit(2)


def run(argv, cwd=None):
    try:
        return subprocess.run(argv, cwd=cwd, stdin=subprocess.DEVNULL, capture_output=True, check=False, timeout=30)
    except (OSError, subprocess.TimeoutExpired):
        unknown("cannot execute required read-only tool")


def read(path):
    try:
        return path.read_bytes()
    except OSError:
        unknown("required file unavailable")


def git_blob(commit, path):
    result = run(["git", "-C", str(root), "show", f"{commit}:{path}"])
    if result.returncode != 0:
        unknown("immutable Git path unavailable")
    return result.stdout


def digest(data):
    return hashlib.sha256(data).hexdigest()


try:
    ledger = json.loads(read(ledger_path))
except (UnicodeError, json.JSONDecodeError):
    fail("compiled ledger malformed")

ids = {
    "t.baseline-bundle.continuity-consume",
    "t.baseline-bundle.apparatus-test-consume",
    "t.baseline-bundle.apparatus-probe-consume",
    "t.baseline-bundle.apparatus",
    "t.baseline-bundle.verify",
    "t.baseline-bundle.user-gate",
    "t.baseline-bundle.migrate",
    "t.baseline-bundle.measure",
    "t.baseline-bundle.final",
}
frontier = {
    "t.baseline-bundle.continuity-consume",
    "t.baseline-bundle.apparatus-test-consume",
    "t.baseline-bundle.apparatus-probe-consume",
}
deps = {
    ("t.baseline-bundle.continuity-consume", "t.baseline-bundle.apparatus"),
    ("t.baseline-bundle.apparatus-test-consume", "t.baseline-bundle.apparatus"),
    ("t.baseline-bundle.apparatus-probe-consume", "t.baseline-bundle.apparatus"),
    ("t.baseline-bundle.apparatus", "t.baseline-bundle.verify"),
    ("t.baseline-bundle.verify", "t.baseline-bundle.user-gate"),
    ("t.baseline-bundle.user-gate", "t.baseline-bundle.migrate"),
    ("t.baseline-bundle.migrate", "t.baseline-bundle.measure"),
    ("t.baseline-bundle.measure", "t.baseline-bundle.final"),
}
tasks = ledger.get("tasks", {})
bad = []
if ledger.get("ledger_id") != "ledger.baseline-bundle.successor10.v1" or ledger.get("revision") != 1:
    bad.append("ledger identity")
if set(tasks) != ids:
    bad.append("task ids")
actual_deps = {(edge.get("from"), edge.get("to")) for edge in ledger.get("dependencies", []) if edge.get("condition") == "requires_success"}
if actual_deps != deps or len(ledger.get("dependencies", [])) != 8:
    bad.append("dependencies")
if ids - {target for _, target in deps} != frontier:
    bad.append("frontier")
if ledger.get("parallelism") != [{"failure_policy": "halt", "group": "successor10-consume-wave", "max_concurrency": 3}]:
    bad.append("parallelism")
if ledger.get("transitions") not in ([], None):
    bad.append("transitions")
for task_id, task in tasks.items():
    if "statuses" in task:
        bad.append("statuses " + task_id)
    if task.get("resources", {}).get("provenance", {}) != {"identity": "git", "revision": pin}:
        bad.append("provenance " + task_id)

consume_expected = {
    "t.baseline-bundle.continuity-consume": (
        "wt-maple-core",
        ["/bin/sh", ".team/ledgers/acceptance/baseline-bundle-successor7-continuity.sh"],
        [],
    ),
    "t.baseline-bundle.apparatus-test-consume": (
        "wt-s7-cedar",
        ["/bin/sh", ".team/ledgers/acceptance/baseline-bundle-successor7-test.sh"],
        [".team/nodes/baseline-bundle-successor7-test/RED.md"],
    ),
    "t.baseline-bundle.apparatus-probe-consume": (
        "wt-s7-orbit",
        ["/bin/sh", ".team/ledgers/acceptance/baseline-bundle-successor7-probe.sh"],
        [".team/nodes/baseline-bundle-successor7-probe/PROBE.md"],
    ),
}
for task_id, (worktree, argv, artifacts) in consume_expected.items():
    task = tasks.get(task_id, {})
    command = task.get("command", {})
    acceptance = task.get("acceptance", {})
    if task.get("executor") != "command" or task.get("parallel", {}).get("group") != "successor10-consume-wave":
        bad.append("consume executor " + task_id)
    if task.get("resources", {}).get("worktree_id") != worktree or task.get("resources", {}).get("write_paths") != []:
        bad.append("consume isolation " + task_id)
    if command != {
        "argv": argv,
        "artifacts": artifacts,
        "cwd": "${worktree}",
        "expected_exit_code": 0,
        "time_budget_seconds": 300 if task_id.endswith("continuity-consume") else 600,
        "unjudgeable_exit_codes": [2],
    }:
        bad.append("consume command " + task_id)
    if acceptance.get("required") != [] or acceptance.get("mechanical") != [] or acceptance.get("judgment") != []:
        bad.append("consume self-report leak " + task_id)
    if "efed31310" not in task.get("title", "") or pair_head not in task.get("title", ""):
        bad.append("consume provenance title " + task_id)

apparatus = tasks.get("t.baseline-bundle.apparatus", {})
apparatus_command = apparatus.get("command", {})
if apparatus.get("executor") != "command" or apparatus.get("resources", {}).get("worktree_id") != "wt-maple-core":
    bad.append("apparatus executor")
if apparatus_command != {
    "argv": ["/bin/sh", ".team/ledgers/acceptance/baseline-bundle-successor10-owned-emulator.sh"],
    "artifacts": [
        ".team/nodes/baseline-bundle-apparatus/AVD-CREATE.json",
        ".team/nodes/baseline-bundle-apparatus/APPARATUS.json",
    ],
    "cwd": "${worktree}",
    "expected_exit_code": 0,
    "time_budget_seconds": 7200,
    "unjudgeable_exit_codes": [2],
}:
    bad.append("apparatus command")
required = [
    "M.baseline-bundle.successor10-avd",
    "M.baseline-bundle.successor9-sdk-selector",
    "M.baseline-bundle.successor7-apparatus",
    "M.baseline-bundle.successor7-fixture",
    "M.baseline-bundle.successor7-continuity",
]
if apparatus.get("acceptance", {}).get("required") != required:
    bad.append("apparatus required")
mechanical = apparatus.get("acceptance", {}).get("mechanical", [])
mechanical_by_id = {item.get("acceptance_id"): item for item in mechanical}
if set(mechanical_by_id) != set(required):
    bad.append("apparatus mechanical ids")
mechanical_expected = {
    "M.baseline-bundle.successor10-avd": (
        ".team/ledgers/acceptance/baseline-bundle-successor10-avd-regression.sh",
        300,
    ),
    "M.baseline-bundle.successor9-sdk-selector": (
        ".team/ledgers/acceptance/baseline-bundle-successor9-sdk-regression.sh",
        300,
    ),
    "M.baseline-bundle.successor7-apparatus": (
        ".team/ledgers/acceptance/baseline-bundle-successor7-apparatus.sh",
        600,
    ),
    "M.baseline-bundle.successor7-fixture": (
        ".team/ledgers/acceptance/baseline-bundle-successor7-impl-bypass.sh",
        300,
    ),
    "M.baseline-bundle.successor7-continuity": (
        ".team/ledgers/acceptance/baseline-bundle-successor7-continuity.sh",
        300,
    ),
}
for acceptance_id, (script, budget) in mechanical_expected.items():
    if mechanical_by_id.get(acceptance_id) != {
        "acceptance_id": acceptance_id,
        "argv": ["/bin/sh", script],
        "artifacts": [],
        "cwd": "${worktree}",
        "expected_exit_code": 0,
        "time_budget_seconds": budget,
        "unjudgeable_exit_codes": [2],
    }:
        bad.append("mechanical drift " + acceptance_id)
if apparatus.get("handoff", {}).get("required_artifacts") != [
    ".team/nodes/baseline-bundle-apparatus/AVD-CREATE.json",
    ".team/nodes/baseline-bundle-apparatus/APPARATUS.json",
]:
    bad.append("apparatus handoff")
if bad:
    fail(", ".join(sorted(set(bad))))

r4_ledger_bytes = git_blob(r4_commit, ".team/ledgers/baseline-bundle-successor9-v1.json")
r4_driver_bytes = git_blob(r4_commit, ".team/nodes/_driver/baseline-bundle-successor9-v1.out")
if digest(r4_ledger_bytes) != r4_ledger_sha or digest(r4_driver_bytes) != r4_driver_sha:
    fail("successor9 r4 immutable digest drift")
try:
    old = json.loads(r4_ledger_bytes)
except (UnicodeError, json.JSONDecodeError):
    fail("successor9 r4 ledger malformed")
if old.get("ledger_id") != "ledger.baseline-bundle.successor9.v1" or old.get("revision") != 4:
    fail("successor9 r4 identity")
old_tasks = old.get("tasks", {})
for task_id in frontier:
    old_task = old_tasks.get(task_id, {})
    attempts = old_task.get("attempts", [])
    if old_task.get("state") != "succeeded" or len(attempts) != 1 or attempts[0].get("state") != "succeeded":
        fail("successor9 r4 consume not succeeded " + task_id)
    new_task = tasks[task_id]
    if new_task.get("command") != old_task.get("command"):
        fail("successor9 consume command drift " + task_id)
    for key in ("worktree_id", "write_paths", "environment_fidelity"):
        if new_task.get("resources", {}).get(key) != old_task.get("resources", {}).get(key):
            fail("successor9 consume resources drift " + task_id)
old_apparatus = old_tasks.get("t.baseline-bundle.apparatus", {})
old_attempts = old_apparatus.get("attempts", [])
if old_apparatus.get("state") != "planned" or len(old_attempts) != 1 or old_attempts[0].get("state") != "acceptance_pending":
    fail("successor9 apparatus historical state drift")
refs = "\n".join(old_attempts[0].get("artifact_refs", []))
if "acceptance_failure.exit_code=2" not in refs or "fresh AVD creation rc=1" not in refs:
    fail("successor9 apparatus unjudgeable evidence drift")

for task_id in (
    "t.baseline-bundle.verify",
    "t.baseline-bundle.user-gate",
    "t.baseline-bundle.migrate",
    "t.baseline-bundle.measure",
    "t.baseline-bundle.final",
):
    old_task = old_tasks.get(task_id, {})
    new_task = tasks.get(task_id, {})
    for key in ("title", "owner", "seat_wait_seconds", "handoff", "acceptance", "executor", "command"):
        if old_task.get(key) != new_task.get(key):
            fail("downstream weakened " + task_id + " field=" + key)
    old_resources = old_task.get("resources", {})
    new_resources = new_task.get("resources", {})
    for key in ("worktree_id", "write_paths", "environment_fidelity"):
        if old_resources.get(key) != new_resources.get(key):
            fail("downstream resources weakened " + task_id + " field=" + key)
    old_reads = old_resources.get("read_paths", [])
    new_reads = new_resources.get("read_paths", [])
    if not old_reads or len(new_reads) < len(old_reads) or new_reads[-len(old_reads):] != old_reads:
        fail("downstream read paths weakened " + task_id)

for key in ("roles", "handoff", "acceptance", "fallback", "evidence_policy", "resource_isolation", "fanout_aggregation"):
    if ledger.get(key) != old.get(key):
        fail("top-level contract weakened field=" + key)

driver_text = r4_driver_bytes.decode("utf-8", errors="strict")
for task_id in frontier:
    if driver_text.count("命令通过 command-success | task=" + task_id) != 1:
        fail("successor9 r4 command-success count " + task_id)
if driver_text.count("命令不可判 command-unjudgeable | task=t.baseline-bundle.apparatus") != 1:
    fail("successor9 r4 apparatus unjudgeable count")

for commit in (r4_commit, diagnosis_commit, bootstrap_commit):
    if run(["git", "-C", str(root), "merge-base", "--is-ancestor", commit, pin]).returncode != 0:
        fail("provenance ancestry " + commit[:9])
if run(["git", "-C", str(root), "merge-base", "--is-ancestor", pin, "HEAD"]).returncode != 0:
    fail("main no longer contains provenance pin")
for commit, path in (
    (r4_commit, ".team/ledgers/baseline-bundle-successor9-v1.json"),
    (r4_commit, ".team/nodes/_driver/baseline-bundle-successor9-v1.out"),
    (diagnosis_commit, ".team/nodes/baseline-bundle-successor9-apparatus-diagnosis/VERDICT.md"),
    (bootstrap_commit, ".team/ledgers/acceptance/baseline-bundle-successor10-avd.py"),
    (bootstrap_commit, ".team/ledgers/acceptance/baseline-bundle-successor10-avd-regression.sh"),
    (bootstrap_commit, ".team/ledgers/acceptance/baseline-bundle-successor10-owned-emulator.sh"),
    (bootstrap_commit, ".team/nodes/spec-sol/baseline-bundle-successor10/任务书.md"),
    (bootstrap_commit, ".team/nodes/spec-sol/baseline-bundle-successor10/BOOTSTRAP-RESULT.md"),
    (bootstrap_commit, ".team/nodes/spec-sol/baseline-bundle-successor10-bootstrap-review/VERDICT.md"),
    (bootstrap_commit, ".team/nodes/spec-sol/baseline-bundle-successor10-bootstrap-review/tests.log"),
    (bootstrap_commit, ".team/ledgers/acceptance/baseline-bundle-successor9-sdk-selector.py"),
    (bootstrap_commit, ".team/ledgers/acceptance/baseline-bundle-successor9-sdk-selector.sh"),
    (bootstrap_commit, ".team/ledgers/acceptance/baseline-bundle-successor9-sdk-regression.sh"),
    (pin, ".team/nodes/baseline-bundle-successor10-wt-preflight/VERDICT.md"),
    (pin, ".team/nodes/baseline-bundle-successor10-wt-preflight/COMMAND.md"),
):
    if run(["git", "-C", str(root), "cat-file", "-e", f"{commit}:{path}"]).returncode != 0:
        unknown("Git provenance path unavailable")

if run(["git", "-C", str(pair_root), "rev-parse", "HEAD"]).stdout.decode("ascii", errors="strict").strip() != pair_head:
    unknown("command-compatible source pair unavailable")
try:
    if hashlib.md5(pair_binary.read_bytes()).hexdigest() != pair_md5:
        unknown("command-compatible binary identity mismatch")
except OSError:
    unknown("command-compatible binary unavailable")

listing = run(["git", "-C", str(root), "worktree", "list", "--porcelain"])
if listing.returncode != 0:
    unknown("cannot inspect registered worktrees")
worktrees = {}
current = None
for line in listing.stdout.decode("utf-8", errors="strict").splitlines():
    if line.startswith("worktree "):
        current = pathlib.Path(line[9:])
        worktrees[current.name] = current
expected_heads = {
    "wt-maple-core": bootstrap_commit,
    "wt-s7-cedar": "25517d808cc19e3f002ceba51000b2a269bec362",
    "wt-s7-orbit": "25517d808cc19e3f002ceba51000b2a269bec362",
}
for name, expected_head in expected_heads.items():
    worktree = worktrees.get(name)
    if worktree is None or not worktree.is_dir():
        unknown("required retained worktree missing")
    actual_head = run(["git", "-C", str(worktree), "rev-parse", "HEAD"]).stdout.decode("ascii", errors="strict").strip()
    if actual_head != expected_head:
        fail("retained worktree HEAD drift " + name)

core = worktrees["wt-maple-core"]
for path in (
    ".team/ledgers/acceptance/baseline-bundle-successor10-avd.py",
    ".team/ledgers/acceptance/baseline-bundle-successor10-avd-regression.sh",
    ".team/ledgers/acceptance/baseline-bundle-successor10-owned-emulator.sh",
    ".team/ledgers/acceptance/baseline-bundle-successor9-sdk-selector.py",
    ".team/ledgers/acceptance/baseline-bundle-successor9-sdk-selector.sh",
    ".team/ledgers/acceptance/baseline-bundle-successor9-sdk-regression.sh",
):
    if read(core / path) != git_blob(bootstrap_commit, path):
        fail("retained selector/bootstrap bytes drift")
red = read(worktrees["wt-s7-cedar"] / ".team/nodes/baseline-bundle-successor7-test/RED.md")
probe = read(worktrees["wt-s7-orbit"] / ".team/nodes/baseline-bundle-successor7-probe/PROBE.md")
if digest(red) != red_sha or digest(probe) != probe_sha:
    fail("retained RED/PROBE digest drift")
if red.decode("utf-8", errors="strict").splitlines()[-1] != "test: pass" or probe.decode("utf-8", errors="strict").splitlines()[-1] != "probe: pass":
    fail("retained RED/PROBE verdict drift")

target = core / "app/local.properties"
try:
    target_stat = target.lstat()
    target_lines = target.read_text(encoding="utf-8").splitlines()
except (OSError, UnicodeError):
    unknown("retained target local.properties unavailable")
if stat.S_ISLNK(target_stat.st_mode) or not stat.S_ISREG(target_stat.st_mode):
    fail("retained target local.properties identity")
if run(["git", "-C", str(core), "ls-files", "--error-unmatch", "app/local.properties"]).returncode == 0:
    fail("retained target local.properties is tracked")
seen_sdk = False
for line in target_lines:
    stripped = line.strip()
    if not stripped or stripped.startswith("#") or stripped.startswith("!"):
        continue
    if not line.startswith("sdk.dir=") or seen_sdk:
        fail("retained target local.properties key shape")
    seen_sdk = True
if not seen_sdk:
    unknown("retained target local.properties lacks sdk.dir")

print(
    "SUCCESSOR10_STRUCTURE tasks=9 dependencies=8 frontier=three-command "
    "successor9_r4_consumes=succeeded apparatus_history=unjudgeable avd_and_sdk=required "
    "downstream=unchanged provenance=efed+918b+9ea+ad746 pair=7485102 "
    "worktrees=maple+cedar+orbit statuses=none"
)
PY
