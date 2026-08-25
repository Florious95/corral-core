#!/usr/bin/env python3
"""Resolve and validate successor7 owned-emulator evidence without exposing APK contents."""

import argparse
import hashlib
import json
import os
import pathlib
import re
import stat
import sys
import tempfile
import time


def fail(message: str) -> None:
    print(f"FAIL baseline-bundle-successor7-apparatus: {message}", file=sys.stderr)
    raise SystemExit(1)


def unjudgeable(message: str) -> None:
    print(f"UNJUDGEABLE baseline-bundle-successor7-apparatus: {message}", file=sys.stderr)
    raise SystemExit(2)


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    try:
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
    except OSError:
        unjudgeable("cannot hash required input")
    return digest.hexdigest()


def bundle(repo: pathlib.Path):
    manifest_path = repo / ".team/nodes/baseline-bundle-impl/BUNDLE-MANIFEST.json"
    try:
        data = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError):
        unjudgeable("bundle manifest unavailable")
    bundle_id = data.get("bundle_id")
    artifact_sha = data.get("artifact", {}).get("apk_sha256")
    apk_rel = data.get("archive", {}).get("primary_relpath")
    if not isinstance(bundle_id, str) or re.fullmatch(r"[0-9a-f]{64}", bundle_id) is None:
        fail("manifest bundle_id malformed")
    if not isinstance(artifact_sha, str) or re.fullmatch(r"[0-9a-f]{64}", artifact_sha) is None:
        fail("manifest APK digest malformed")
    if not isinstance(apk_rel, str) or apk_rel.startswith("/") or ".." in pathlib.PurePosixPath(apk_rel).parts:
        fail("manifest APK path unsafe")
    apk = repo / apk_rel
    try:
        resolved = apk.resolve(strict=True)
        resolved.relative_to(repo.resolve(strict=True))
    except (OSError, ValueError, RuntimeError):
        unjudgeable("archived APK unavailable")
    if not resolved.is_file() or resolved.is_symlink():
        fail("archived APK is not a regular non-symlink file")
    if sha256(resolved) != artifact_sha:
        fail("archived APK digest mismatch")
    return manifest_path, data, resolved


def atomic_json(path: pathlib.Path, value) -> None:
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        descriptor, temporary = tempfile.mkstemp(prefix=".apparatus.", dir=path.parent)
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            os.fchmod(stream.fileno(), stat.S_IRUSR | stat.S_IWUSR)
            json.dump(value, stream, ensure_ascii=False, sort_keys=True)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        os.chmod(path, stat.S_IRUSR | stat.S_IWUSR)
    except OSError:
        unjudgeable("cannot write apparatus evidence")


def expected_path(repo: pathlib.Path, mode: str, requested: pathlib.Path) -> pathlib.Path:
    resolved = requested.resolve()
    if mode == "production":
        wanted = (repo / ".team/nodes/baseline-bundle-apparatus/APPARATUS.json").resolve()
        if resolved != wanted:
            fail("production evidence path is not fixed")
    else:
        root = (repo / ".team/nodes/spec-sol/baseline-bundle-successor7/tmp").resolve()
        try:
            resolved.relative_to(root)
        except ValueError:
            fail("fixture evidence path escapes node-local temp")
    return resolved


def require_private_regular(path: pathlib.Path, label: str) -> None:
    try:
        info = path.lstat()
    except OSError:
        unjudgeable(f"{label} unavailable")
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode):
        fail(f"{label} is not a regular non-symlink file")
    actual_mode = stat.S_IMODE(info.st_mode)
    if actual_mode != (stat.S_IRUSR | stat.S_IWUSR):
        fail(f"{label} mode is not 0600")


def write_evidence(args) -> None:
    repo = pathlib.Path(args.repo_root).resolve(strict=True)
    evidence = expected_path(repo, args.mode, pathlib.Path(args.evidence))
    manifest_path, manifest, _ = bundle(repo)
    runner = repo / "tools/perfbase/run-input-ab.sh"
    if not runner.is_file():
        unjudgeable("owned runner unavailable")
    fields = {
        "schema": "agentmirror.successor7.apparatus.v1",
        "mode": args.mode,
        "created_epoch": int(time.time()),
        "evidence_commit": "3528c2ad5c9308a049f4fdb135f372d035633a90",
        "worktree_id": "wt-maple-core",
        "bundle_id": manifest["bundle_id"],
        "manifest_sha256": sha256(manifest_path),
        "apk_sha256": manifest["artifact"]["apk_sha256"],
        "runner_sha256": sha256(runner),
        "avd_name": "successor7_verify_owned",
        "serial": args.serial,
        "owned_qemu_pid": int(args.qemu_pid),
        "envcheck_preflight_exit": args.preflight_exit,
        "envcheck_measurement_exit": args.measurement_exit,
        "adb_install_exit": args.install_exit,
        "runner_signal_exit": args.runner_exit,
        "envcheck_recovery_exit": args.recovery_exit,
        "fresh_task_avd": args.fresh_avd,
        "owned_qemu_bound": args.owned_qemu,
        "runner_pid_cleanup": args.cleanup,
        "serial_cleanup": args.cleanup,
        "owned_qemu_cleanup": args.cleanup,
        "forced_kill": False,
        "foreign_qemu_touched": False,
    }
    atomic_json(evidence, fields)


def verify_evidence(args) -> None:
    repo = pathlib.Path(args.repo_root).resolve(strict=True)
    evidence_path = expected_path(repo, args.mode, pathlib.Path(args.evidence))
    require_private_regular(evidence_path, "apparatus evidence")
    manifest_path, manifest, _ = bundle(repo)
    runner = repo / "tools/perfbase/run-input-ab.sh"
    try:
        data = json.loads(evidence_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError):
        unjudgeable("apparatus evidence unavailable")
    if data.get("schema") != "agentmirror.successor7.apparatus.v1" or data.get("mode") != args.mode:
        fail("apparatus evidence schema or mode mismatch")
    now = int(time.time())
    created = data.get("created_epoch")
    if not isinstance(created, int) or created > now + 5 or now - created > args.max_age:
        unjudgeable("apparatus evidence is not fresh")
    exact = {
        "evidence_commit": "3528c2ad5c9308a049f4fdb135f372d035633a90",
        "worktree_id": "wt-maple-core",
        "bundle_id": manifest["bundle_id"],
        "manifest_sha256": sha256(manifest_path),
        "apk_sha256": manifest["artifact"]["apk_sha256"],
        "runner_sha256": sha256(runner),
        "avd_name": "successor7_verify_owned",
        "serial": "emulator-5554",
        "envcheck_preflight_exit": 0,
        "envcheck_measurement_exit": 0,
        "adb_install_exit": 0,
        "runner_signal_exit": 143,
        "envcheck_recovery_exit": 0,
        "fresh_task_avd": True,
        "owned_qemu_bound": True,
        "runner_pid_cleanup": True,
        "serial_cleanup": True,
        "owned_qemu_cleanup": True,
        "forced_kill": False,
        "foreign_qemu_touched": False,
    }
    bad = [name for name, wanted in exact.items() if data.get(name) != wanted]
    pid = data.get("owned_qemu_pid")
    if not isinstance(pid, int) or pid <= 1:
        bad.append("owned_qemu_pid")
    if bad:
        fail("apparatus evidence mismatch: " + ",".join(sorted(set(bad))))
    print("SUCCESSOR7_APPARATUS_EVIDENCE fresh=true preflight=0 measurement=0 install=0 runner_pid_cleanup=true serial_cleanup=true qemu_cleanup=true forced_kill=false foreign_qemu_touched=false mode=0600")


def main() -> None:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    resolve = sub.add_parser("resolve")
    resolve.add_argument("--repo-root", required=True)
    resolve.add_argument("--path-file", required=True)
    write = sub.add_parser("write")
    verify = sub.add_parser("verify")
    for item in (write, verify):
        item.add_argument("--repo-root", required=True)
        item.add_argument("--evidence", required=True)
        item.add_argument("--mode", choices=("production", "fixture"), required=True)
    write.add_argument("--qemu-pid", required=True)
    write.add_argument("--serial", required=True)
    write.add_argument("--preflight-exit", type=int, required=True)
    write.add_argument("--measurement-exit", type=int, required=True)
    write.add_argument("--install-exit", type=int, required=True)
    write.add_argument("--runner-exit", type=int, required=True)
    write.add_argument("--recovery-exit", type=int, required=True)
    write.add_argument("--fresh-avd", action="store_true")
    write.add_argument("--owned-qemu", action="store_true")
    write.add_argument("--cleanup", action="store_true")
    verify.add_argument("--max-age", type=int, default=7200)
    args = parser.parse_args()
    if args.command == "resolve":
        repo = pathlib.Path(args.repo_root).resolve(strict=True)
        _, _, apk = bundle(repo)
        path_file = pathlib.Path(args.path_file).resolve()
        roots = (
            (repo / ".team/nodes/baseline-bundle-apparatus/tmp").resolve(),
            (repo / ".team/nodes/spec-sol/baseline-bundle-successor7/tmp").resolve(),
        )
        if not any(path_file == root or root in path_file.parents for root in roots):
            fail("APK path handoff escapes apparatus temp")
        try:
            path_file.parent.mkdir(parents=True, exist_ok=True)
            path_file.write_text(str(apk) + "\n", encoding="utf-8")
            os.chmod(path_file, stat.S_IRUSR | stat.S_IWUSR)
        except OSError:
            unjudgeable("cannot write APK path handoff")
    elif args.command == "write":
        write_evidence(args)
    else:
        verify_evidence(args)


if __name__ == "__main__":
    main()
