#!/bin/sh
# //! purpose: 将 3528c2ad5 四格成功索引与现存 wt-maple-core 的不可变 bundle 逐项绑定；不复制、不重放旧结果。
# //! contract: 0=ledger 证据与同 WT bundle 连续；1=有效事实矛盾；2=Git/WT/资产不可判。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-successor7-continuity: $*" >&2; exit 2; }

command -v git >/dev/null 2>&1 || unjudgeable "git unavailable"
command -v python3 >/dev/null 2>&1 || unjudgeable "python3 unavailable"
repo_root=$(git -C "${PWD:-.}" rev-parse --show-toplevel 2>/dev/null) || unjudgeable "cannot resolve current worktree"
[ "$(basename "$repo_root")" = wt-maple-core ] || unjudgeable "successor7 must reuse existing wt-maple-core"
common_dir=$(git -C "$repo_root" rev-parse --path-format=absolute --git-common-dir 2>/dev/null) || unjudgeable "cannot resolve common Git directory"
main_root=$(CDPATH='' cd "$common_dir/.." 2>/dev/null && pwd) || unjudgeable "cannot resolve main repository"
[ "$main_root" != "$repo_root" ] || unjudgeable "current directory is not the retained impl worktree"

python3 - "$repo_root" "$main_root" <<'PY'
import hashlib, json, os, pathlib, subprocess, sys

wt, main = map(pathlib.Path, sys.argv[1:])
evidence_commit = "3528c2ad5c9308a049f4fdb135f372d035633a90"
bootstrap_commit = "548572dfd7d8ee2e3f602a274268e8bd881ef8b2"
ledger_path = ".team/ledgers/baseline-bundle-successor6-v1.json"
ledger_sha = "30bc51c09b2deb00c0213d5fce815e03e5181c6ca9cf93d9862fe5d45e5e241c"


def fail(message):
    print("FAIL baseline-bundle-successor7-continuity: " + message, file=sys.stderr)
    raise SystemExit(1)


def unjudgeable(message):
    print("UNJUDGEABLE baseline-bundle-successor7-continuity: " + message, file=sys.stderr)
    raise SystemExit(2)


def run(argv):
    try:
        return subprocess.run(argv, capture_output=True, check=False)
    except OSError:
        unjudgeable("required Git operation unavailable")


obj = run(["git", "-C", str(main), "show", f"{evidence_commit}:{ledger_path}"])
if obj.returncode != 0:
    unjudgeable("3528c2ad5 ledger evidence unavailable")
if hashlib.sha256(obj.stdout).hexdigest() != ledger_sha:
    fail("3528c2ad5 ledger digest drift")
try:
    ledger = json.loads(obj.stdout)
except json.JSONDecodeError:
    fail("3528c2ad5 ledger malformed")
if ledger.get("revision") != 5 or ledger.get("ledger_id") != "ledger.baseline-bundle.successor6.v1":
    fail("3528c2ad5 ledger identity drift")
expected = {
    "t.baseline-bundle.repro": ["M.baseline-bundle.repro", "M.baseline-bundle.repro-regression"],
    "t.baseline-bundle.impl": ["M.baseline-bundle.successor6-impl", "M.baseline-bundle.successor6-bypass"],
    "t.baseline-bundle.probe": ["M.baseline-bundle.successor6-probe"],
    "t.baseline-bundle.test": ["M.baseline-bundle.successor6-test"],
}
for task_id, required in expected.items():
    task = ledger.get("tasks", {}).get(task_id, {})
    if task.get("state") != "succeeded" or task.get("acceptance", {}).get("required") != required:
        fail("durable four-task evidence drift: " + task_id)
if ledger.get("tasks", {}).get("t.baseline-bundle.verify", {}).get("state") != "planned":
    fail("verify history was rewritten instead of retained")

head = run(["git", "-C", str(wt), "rev-parse", "HEAD"])
if head.returncode != 0:
    unjudgeable("retained WT HEAD unavailable")
if run(["git", "-C", str(wt), "merge-base", "--is-ancestor", bootstrap_commit, head.stdout.decode().strip()]).returncode != 0:
    fail("retained WT does not descend from frozen successor6 bootstrap")
listing = run(["git", "-C", str(main), "worktree", "list", "--porcelain"])
if listing.returncode != 0:
    unjudgeable("cannot inspect worktree metadata")
if f"worktree {wt}\n".encode() not in listing.stdout:
    unjudgeable("wt-maple-core is not registered in Git metadata")

node = wt / ".team/nodes/baseline-bundle-impl"
for name in ("ROUTE.md", "IMPL.md", "BUNDLE-MANIFEST.json", "INSTALL.md", "RETRIEVE.md"):
    path = node / name
    if not path.is_file():
        unjudgeable("retained impl artifact missing")
    if path.stat().st_size == 0:
        fail("retained impl artifact empty")
try:
    if (node / "IMPL.md").read_text(encoding="utf-8").splitlines()[-1] != "implementation: pass":
        fail("retained impl report is not pass")
    manifest = json.loads((node / "BUNDLE-MANIFEST.json").read_text(encoding="utf-8"))
except (OSError, UnicodeError, json.JSONDecodeError, IndexError):
    unjudgeable("retained impl manifest unavailable")
bundle_id = manifest.get("bundle_id")
apk_sha = manifest.get("artifact", {}).get("apk_sha256")
primary_rel = manifest.get("archive", {}).get("primary_relpath")
backup_rel = manifest.get("archive", {}).get("backup_relpath")
if not all(isinstance(x, str) and x for x in (bundle_id, apk_sha, primary_rel, backup_rel)):
    fail("retained manifest identity incomplete")


def sha(path):
    digest = hashlib.sha256()
    try:
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
    except OSError:
        unjudgeable("retained archive unreadable")
    return digest.hexdigest()


archives = []
for rel in (primary_rel, backup_rel):
    pure = pathlib.PurePosixPath(rel)
    if rel.startswith("/") or ".." in pure.parts:
        fail("retained archive path unsafe")
    path = wt / rel
    try:
        resolved = path.resolve(strict=True)
        resolved.relative_to(wt.resolve(strict=True))
    except (OSError, ValueError, RuntimeError):
        unjudgeable("retained archive unavailable")
    if not resolved.is_file() or resolved.is_symlink():
        fail("retained archive is not regular")
    if sha(resolved) != apk_sha:
        fail("retained archive digest mismatch")
    if resolved.stat().st_mode & 0o222:
        fail("retained archive is not sealed")
    archives.append(resolved)
if os.stat(archives[0]).st_ino == os.stat(archives[1]).st_ino:
    fail("retained archives share inode")
print("SUCCESSOR7_CONTINUITY evidence_commit=3528c2ad5 four_tasks=succeeded worktree=wt-maple-core manifest_bound=true archives_bound=true")
PY
rc=$?
case "$rc" in 0) exit 0 ;; 1) exit 1 ;; 2) exit 2 ;; *) unjudgeable "continuity judge unsupported rc=$rc" ;; esac
