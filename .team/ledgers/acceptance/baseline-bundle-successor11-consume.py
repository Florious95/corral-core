#!/usr/bin/env python3
"""Consume frozen successor10 r5 success facts without touching a device."""

import argparse
import hashlib
import json
import pathlib
import stat
import sys


LEDGER_ID = "ledger.baseline-bundle.successor10.v1"
LEDGER_SHA256 = "018a638ec1fff6a59313609d884c4fadfaca78a0b8d3eaf4b018ab9e9fc81a32"
TASKS = {
    "continuity": {
        "task": "t.baseline-bundle.continuity-consume",
        "worktree": "wt-maple-core",
        "attempt": "cmd-ledger.baseline-bundle.successor10.v1-t.baseline-bundle.continuity-consume-r3",
        "writeback_revision": 4,
        "argv": ["/bin/sh", ".team/ledgers/acceptance/baseline-bundle-successor7-continuity.sh"],
        "artifacts": [],
    },
    "apparatus-test": {
        "task": "t.baseline-bundle.apparatus-test-consume",
        "worktree": "wt-s7-cedar",
        "attempt": "cmd-ledger.baseline-bundle.successor10.v1-t.baseline-bundle.apparatus-test-consume-r2",
        "writeback_revision": 3,
        "argv": ["/bin/sh", ".team/ledgers/acceptance/baseline-bundle-successor7-test.sh"],
        "artifacts": [
            (".team/nodes/baseline-bundle-successor7-test/RED.md", "04cdbd661548a4b3261c88d491cf80c48f98dcbe3c080e710fb7d12bbe6c105a", None),
        ],
    },
    "apparatus-probe": {
        "task": "t.baseline-bundle.apparatus-probe-consume",
        "worktree": "wt-s7-orbit",
        "attempt": "cmd-ledger.baseline-bundle.successor10.v1-t.baseline-bundle.apparatus-probe-consume-r1",
        "writeback_revision": 2,
        "argv": ["/bin/sh", ".team/ledgers/acceptance/baseline-bundle-successor7-probe.sh"],
        "artifacts": [
            (".team/nodes/baseline-bundle-successor7-probe/PROBE.md", "88868a1a1979d3f1504e5efd6876dc5ca8ed5cc6b45a2eb6f6dd23b8e5176cf7", None),
        ],
    },
    "apparatus": {
        "task": "t.baseline-bundle.apparatus",
        "worktree": "wt-maple-core",
        "attempt": "cmd-ledger.baseline-bundle.successor10.v1-t.baseline-bundle.apparatus-r4",
        "writeback_revision": 5,
        "argv": ["/bin/sh", ".team/ledgers/acceptance/baseline-bundle-successor10-owned-emulator.sh"],
        "artifacts": [
            (".team/nodes/baseline-bundle-apparatus/AVD-CREATE.json", "403c9481703f2abff15bf0534809e12a902d75e7c968fb7ce33f0e138f176912", 0o600),
            (".team/nodes/baseline-bundle-apparatus/APPARATUS.json", "fd27d1638ba097ab6a310bc497d80e20720f7625974f61a414a2e63132f23b9d", 0o600),
        ],
    },
}


def fail(message: str) -> None:
    print(f"FAIL baseline-bundle-successor11-consume: {message}", file=sys.stderr)
    raise SystemExit(1)


def unjudgeable(message: str) -> None:
    print(f"UNJUDGEABLE baseline-bundle-successor11-consume: {message}", file=sys.stderr)
    raise SystemExit(2)


def digest(path: pathlib.Path) -> str:
    result = hashlib.sha256()
    try:
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                result.update(chunk)
    except OSError:
        unjudgeable("required evidence cannot be hashed")
    return result.hexdigest()


def regular(path: pathlib.Path, label: str, mode=None) -> None:
    try:
        info = path.lstat()
    except FileNotFoundError:
        unjudgeable(f"{label} missing")
    except OSError:
        unjudgeable(f"{label} unavailable")
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode):
        fail(f"{label} is not regular non-symlink")
    if info.st_size <= 0:
        fail(f"{label} empty")
    if mode is not None and stat.S_IMODE(info.st_mode) != mode:
        fail(f"{label} mode mismatch")


def load_json(path: pathlib.Path, label: str, mode=None):
    regular(path, label, mode)
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError):
        fail(f"{label} invalid JSON")


def verify_runtime(main_root: pathlib.Path, spec) -> None:
    ledger_path = main_root / ".team/ledgers/baseline-bundle-successor10-v1.json"
    ledger = load_json(ledger_path, "successor10 r5 ledger")
    if digest(ledger_path) != LEDGER_SHA256:
        fail("successor10 r5 ledger digest mismatch")
    if ledger.get("ledger_id") != LEDGER_ID or ledger.get("revision") != 5:
        fail("successor10 runtime identity mismatch")
    task = ledger.get("tasks", {}).get(spec["task"])
    if not isinstance(task, dict) or task.get("state") != "succeeded":
        fail("frozen successor10 task is not succeeded")
    command = task.get("command", {})
    if command.get("argv") != spec["argv"] or command.get("expected_exit_code") != 0 or command.get("unjudgeable_exit_codes") != [2]:
        fail("frozen successor10 command contract mismatch")
    attempts = task.get("attempts")
    if not isinstance(attempts, list) or len(attempts) != 1:
        fail("frozen successor10 attempt cardinality mismatch")
    attempt = attempts[0]
    exact = {
        "attempt_id": spec["attempt"],
        "delivery_id": spec["attempt"].replace("cmd-", "del-cmd-", 1),
        "state": "succeeded",
        "writeback_revision": spec["writeback_revision"],
    }
    if any(attempt.get(key) != value for key, value in exact.items()):
        fail("frozen successor10 attempt identity mismatch")


def verify_artifacts(main_root: pathlib.Path, spec, kind: str) -> pathlib.Path:
    target = main_root / ".worktrees" / spec["worktree"]
    try:
        resolved = target.resolve(strict=True)
    except OSError:
        unjudgeable("original worktree unavailable")
    expected = (main_root / ".worktrees" / spec["worktree"]).resolve()
    if resolved != expected or not resolved.is_dir():
        fail("original worktree identity mismatch")
    for relpath, wanted_digest, wanted_mode in spec["artifacts"]:
        artifact = resolved / relpath
        regular(artifact, relpath, wanted_mode)
        if digest(artifact) != wanted_digest:
            fail(f"{relpath} digest mismatch")
    if kind == "apparatus":
        apparatus = load_json(resolved / spec["artifacts"][1][0], "APPARATUS.json", 0o600)
        avd = load_json(resolved / spec["artifacts"][0][0], "AVD-CREATE.json", 0o600)
        apparatus_exact = {
            "schema": "agentmirror.successor7.apparatus.v1",
            "mode": "production",
            "adb_install_exit": 0,
            "envcheck_preflight_exit": 0,
            "envcheck_measurement_exit": 0,
            "envcheck_recovery_exit": 0,
            "owned_qemu_bound": True,
            "runner_pid_cleanup": True,
            "serial_cleanup": True,
            "owned_qemu_cleanup": True,
            "forced_kill": False,
            "foreign_qemu_touched": False,
        }
        avd_exact = {
            "schema_version": "successor10.avd-create.v1",
            "mode": "production",
            "reason_code": "created",
            "verdict_exit": 0,
            "avdmanager_rc": 0,
            "created": True,
            "raw_cleaned": True,
        }
        bad = [f"APPARATUS.{key}" for key, value in apparatus_exact.items() if apparatus.get(key) != value]
        bad += [f"AVD-CREATE.{key}" for key, value in avd_exact.items() if avd.get(key) != value]
        if bad:
            fail("archived apparatus fact mismatch: " + ",".join(bad))
    return resolved


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--main-root", required=True)
    parser.add_argument("--kind", choices=tuple(TASKS), required=True)
    args = parser.parse_args()
    try:
        main_root = pathlib.Path(args.main_root).resolve(strict=True)
    except OSError:
        unjudgeable("main repository unavailable")
    spec = TASKS[args.kind]
    verify_runtime(main_root, spec)
    target = verify_artifacts(main_root, spec, args.kind)
    print(f"SUCCESSOR11_CONSUME kind={args.kind} successor10_revision=5 state=succeeded original_worktree={target.name} frozen_artifacts=true")


if __name__ == "__main__":
    main()
