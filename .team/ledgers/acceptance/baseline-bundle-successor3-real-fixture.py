#!/usr/bin/env python3
"""Run successor3 red/green teeth through the repository's real judge entrypoints."""
from __future__ import annotations

import argparse
import copy
import hashlib
import importlib.util
import json
import shutil
import subprocess
import sys
from pathlib import Path


def fail(message: str) -> None:
    print(f"FAIL baseline-bundle-successor3-real-fixture: {message}", file=sys.stderr)
    raise SystemExit(1)


def unjudgeable(message: str) -> None:
    print(f"UNJUDGEABLE baseline-bundle-successor3-real-fixture: {message}", file=sys.stderr)
    raise SystemExit(2)


def sha_bytes(value: bytes, algorithm: str = "sha256") -> str:
    return hashlib.new(algorithm, value).hexdigest()


def sha_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def reset_scratch(path: Path) -> None:
    marker = Path(".team/nodes/spec-sol/baseline-bundle-successor3/tmp")
    try:
        path.resolve().relative_to(Path.cwd().resolve())
    except ValueError:
        unjudgeable("scratch must stay inside current repository")
    if marker.as_posix() not in path.as_posix():
        unjudgeable("scratch path is outside successor3 package")
    if path.exists():
        shutil.rmtree(path)
    path.mkdir(parents=True)


def load_contract(path: Path) -> dict:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        unjudgeable(f"control contract unavailable: {exc}")
    if data.get("schema") != "agentmirror.baseline-bundle.successor3-control.v1":
        unjudgeable("control contract schema drift")
    return data


def load_real_module(entry: Path):
    spec = importlib.util.spec_from_file_location("successor3_real_baseline_bundle", entry)
    if spec is None or spec.loader is None:
        unjudgeable("cannot load real baseline_bundle.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    if not callable(getattr(module, "canonical_id", None)):
        unjudgeable("real implementation lacks canonical_id")
    return module


def run(command: list[str]) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(command, text=True, capture_output=True, check=False)
    except OSError as exc:
        unjudgeable(f"cannot execute fixture command: {exc}")


def manifest_for(projection: dict, bundle_id: str, backup_relpath: str, apk: bytes) -> dict:
    return {
        "schema": "agentmirror.baseline-bundle.v1",
        "bundle_id": bundle_id,
        **copy.deepcopy(projection),
        "archive": {"backup_relpath": backup_relpath},
        "reports": {},
    }


def run_retrieve(entry: Path, manifest: Path, output: Path) -> subprocess.CompletedProcess[str]:
    return run([sys.executable, str(entry), "retrieve", "--manifest", str(manifest), "--out", str(output)])


def canonical_fixture(implementation_root: Path, scratch: Path, contract: dict) -> None:
    source_entry = implementation_root / "tools/perfbase/baseline_bundle.py"
    if not source_entry.is_file():
        unjudgeable("real tools/perfbase/baseline_bundle.py missing")
    sandbox = scratch / "canonical-root"
    entry = sandbox / "tools/perfbase/baseline_bundle.py"
    entry.parent.mkdir(parents=True)
    shutil.copyfile(source_entry, entry)
    real = load_real_module(entry)
    apk = b"successor3-real-retrieve-fixture"
    artifact_sha = sha_bytes(apk)
    artifact_md5 = sha_bytes(apk, "md5")
    common = {
        "source": {"tag": "baseline-20260822-release", "commit": "26f46642d3960b1bd96a39753b3f25516c5821eb"},
        "runtime": {"normalized_runtime_sha256": "1" * 64, "entry_count": 1, "package_name": "dev.agentmirror.app", "version_code": "1", "version_name": "1"},
        "artifact": {"apk_sha256": artifact_sha, "apk_md5": artifact_md5, "size_bytes": len(apk), "signer_certificate_sha256": "2" * 64},
        "build": {"independent_builds": [{"apk_relpath": ".team/private/baseline-vault/final/builds/build-1.apk"}]},
        "equivalence": {"route": "rebaseline_with_equivalence_proof", "matched": True},
        "implementation": {"bundle_py": {"path": "tools/perfbase/baseline_bundle.py", "sha256": sha_file(entry)}},
    }
    stale_projection = copy.deepcopy(common)
    stale_projection["build"]["independent_builds"][0]["apk_relpath"] = ".team/private/baseline-vault/PENDING/builds/build-1.apk"
    stale_id = real.canonical_id(stale_projection)
    green_id = real.canonical_id(common)

    stale_backup = f".team/private/baseline-backup/{stale_id}/baseline.apk"
    green_backup = f".team/private/baseline-backup/{green_id}/baseline.apk"
    for relpath in (stale_backup, green_backup):
        path = sandbox / relpath
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(apk)

    stale_manifest = manifest_for(common, stale_id, stale_backup, apk)
    green_manifest = manifest_for(common, green_id, green_backup, apk)
    forged_manifest = copy.deepcopy(green_manifest)
    forged_manifest["implementation"]["bundle_py"]["sha256"] = "f" * 64
    paths = {}
    for name, data in (("stale", stale_manifest), ("green", green_manifest), ("forged", forged_manifest)):
        path = sandbox / f"{name}-manifest.json"
        path.write_text(json.dumps(data, ensure_ascii=False, sort_keys=True) + "\n", encoding="utf-8")
        paths[name] = path

    stale = run_retrieve(entry, paths["stale"], sandbox / "stale-out.apk")
    green = run_retrieve(entry, paths["green"], sandbox / "green-out.apk")
    forged = run_retrieve(entry, paths["forged"], sandbox / "forged-out.apk")
    expected = contract["canonical"]
    if expected["forged_hardened_exit"] != 1:
        unjudgeable("control contract does not classify canonical provenance forgery as product failure")
    stale_text = stale.stdout + stale.stderr
    forged_text = forged.stdout + forged.stderr
    if stale.returncode != expected["stale_exit"] or expected["stale_reason"] not in stale_text:
        fail(f"real stale-path tooth drift rc={stale.returncode} output={stale_text.strip()}")
    if green.returncode != expected["green_exit"] or (sandbox / "green-out.apk").read_bytes() != apk:
        fail(f"real final-path green control failed rc={green.returncode}")
    if forged.returncode != expected["forged_base_exit"] or expected["forged_reason"] not in forged_text:
        fail(f"real forged-provenance tooth drift rc={forged.returncode} output={forged_text.strip()}")
    print(
        "SUCCESSOR3_CANONICAL_REAL "
        f"entry_sha256={sha_file(source_entry)} green_rc={green.returncode} "
        f"stale_rc={stale.returncode} stale_reason=manifest_bundle_id_mismatch "
        f"forged_base_rc={forged.returncode} forged_hardened_rc={expected['forged_hardened_exit']} "
        "forged_reason=manifest_bundle_id_mismatch "
        "stale_operand=build.independent_builds[0].apk_relpath "
        "forged_operand=implementation.bundle_py.sha256"
    )


def measurement_data(root: Path, wrapper_source: Path, base_source: Path, forged: bool) -> tuple[Path, str]:
    acceptance = root / ".team/ledgers/acceptance"
    acceptance.mkdir(parents=True)
    shutil.copyfile(base_source, acceptance / "baseline-bundle-measure.sh")
    wrapper = acceptance / "baseline-bundle-successor3-measure.sh"
    shutil.copyfile(wrapper_source, wrapper)
    subprocess.run(["git", "-C", str(root), "init", "-q"], check=True)
    runner = root / "tools/perfbase/run-input-ab.sh"
    runner.parent.mkdir(parents=True)
    runner.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
    a = b"successor3-measure-a"
    b = b"successor3-measure-b"
    a_sha, a_md5 = sha_bytes(a), sha_bytes(a, "md5")
    b_sha, b_md5 = sha_bytes(b), sha_bytes(b, "md5")
    actual_runner_sha = sha_file(runner)
    declared_runner_sha = "f" * 64 if forged else actual_runner_sha
    bundle_id = "a" * 64
    batch = "successor3-control-batch"
    a_path = root / f".team/private/baseline-vault/{bundle_id}/baseline.apk"
    b_path = root / f".team/private/baseline-candidates/{b_sha}/candidate.apk"
    a_path.parent.mkdir(parents=True); b_path.parent.mkdir(parents=True)
    a_path.write_bytes(a); b_path.write_bytes(b)
    impl = root / ".team/nodes/baseline-bundle-impl"
    impl.mkdir(parents=True)
    manifest = {
        "bundle_id": bundle_id,
        "artifact": {"apk_sha256": a_sha, "apk_md5": a_md5},
        "archive": {"primary_relpath": str(a_path.relative_to(root))},
        "implementation": {"runner": {"path": "tools/perfbase/run-input-ab.sh", "sha256": declared_runner_sha}},
    }
    (impl / "BUNDLE-MANIFEST.json").write_text(json.dumps(manifest), encoding="utf-8")
    node = root / ".team/nodes/baseline-bundle-measure"
    node.mkdir(parents=True)
    identity = {
        "batch_id": batch,
        "runner_sha256": declared_runner_sha,
        "baseline_bundle_id": bundle_id,
        "a_apk_sha256": a_sha,
        "a_apk_md5": a_md5,
        "b_apk_sha256": b_sha,
        "b_apk_md5": b_md5,
        "b_revision": "successor3-control-b",
    }
    pre = {
        **identity,
        "bundle_retrieve_exit": 0,
        "archive_restore_exit": 0,
        "install_exit": 0,
        "envcheck_gate_exit": 0,
        "runner_path": "tools/perfbase/run-input-ab.sh",
        "candidate_apk_sha256": b_sha,
        "candidate_apk_md5": b_md5,
        "candidate_revision": identity["b_revision"],
        "candidate_apk_relpath": str(b_path.relative_to(root)),
    }
    (node / "PRE-MEASURE.json").write_text(json.dumps(pre), encoding="utf-8")
    (node / "MEASURE.md").write_text("measurement: pass\n", encoding="utf-8")
    segments = {
        "tap_to_route_enter": 10.0,
        "route_enter_to_first_frame": 10.0,
        "first_frame_to_first_draw": 10.0,
        "tap_to_first_draw": 30.0,
    }
    fixtures = {}
    for fixture in ("big_scrollback", "real_claude_idle", "redraw_tui"):
        fixtures[fixture] = {}
        for name, value in segments.items():
            fixtures[fixture][name] = {
                "A": [value] * 10,
                "B": [value] * 10,
                "n": {"A": 10, "B": 10},
                "p50": {"A": value, "B": value},
                "p95": {"A": value, "B": value},
                "ratio_b_over_a": {"p50": 1.0, "p95": 1.0},
            }
    result = {
        "schema": "perf-ab.v1",
        "baseline_bundle_id": bundle_id,
        "baseline_measured_sha256": a_sha,
        "baseline_measured_md5": a_md5,
        "candidate_sha256": b_sha,
        "candidate_md5": b_md5,
        "candidate_revision": identity["b_revision"],
        "batch_id": batch,
        "runner_sha256": declared_runner_sha,
        "env": {"gate_exit": 0},
        "measurement": "pass",
        "verdict": "pass",
        "fixtures": fixtures,
    }
    (node / "perf-ab-bundle.json").write_text(json.dumps(result), encoding="utf-8")
    raw = node / "raw"
    headers = [f"# {key}={value}" for key, value in identity.items()]
    order = headers.copy()
    for fixture in fixtures:
        for sequence in range(1, 11):
            for package in ("A", "B"):
                order.append(f"{fixture}\t{sequence}\t{package}")
                log = raw / package / f"{fixture}-{sequence:02d}.log"
                log.parent.mkdir(parents=True, exist_ok=True)
                meta = headers + [f"# fixture={fixture}", f"# sequence={sequence}", f"# package={package}"]
                chain = [
                    f"open_id={fixture}-{sequence}-{package} ev=tap t=100",
                    f"open_id={fixture}-{sequence}-{package} ev=route_enter t=110",
                    f"open_id={fixture}-{sequence}-{package} ev=first_frame_recv t=120",
                    f"open_id={fixture}-{sequence}-{package} ev=first_draw t=130",
                ]
                log.write_text("\n".join(meta + chain) + "\n", encoding="utf-8")
    (raw / "order.tsv").write_text("\n".join(order) + "\n", encoding="utf-8")
    return wrapper, actual_runner_sha


def measure_fixture(source_root: Path, scratch: Path, contract: dict) -> None:
    wrapper_source = source_root / ".team/ledgers/acceptance/baseline-bundle-successor3-measure.sh"
    base_source = source_root / ".team/ledgers/acceptance/baseline-bundle-measure.sh"
    if not wrapper_source.is_file() or not base_source.is_file():
        unjudgeable("measure gate sources missing")
    good_root = scratch / "measure-green-root"
    forged_root = scratch / "measure-forged-root"
    good_root.mkdir(parents=True); forged_root.mkdir(parents=True)
    good_gate, good_actual = measurement_data(good_root, wrapper_source, base_source, False)
    forged_gate, forged_actual = measurement_data(forged_root, wrapper_source, base_source, True)
    if good_actual != forged_actual:
        fail("measure control changed actual runner bytes")
    good = run(["sh", str(good_gate)])
    forged = run(["sh", str(forged_gate)])
    expected = contract["measurement"]
    if expected["forged_hardened_exit"] != 1:
        unjudgeable("control contract does not classify runner provenance forgery as product failure")
    forged_text = forged.stdout + forged.stderr
    if good.returncode != expected["green_exit"]:
        fail(f"measure green control failed rc={good.returncode} output={(good.stdout + good.stderr).strip()}")
    if forged.returncode != expected["forged_hardened_exit"] or expected["forged_reason"] not in forged_text:
        fail(f"measure forged-provenance tooth drift rc={forged.returncode} output={forged_text.strip()}")
    if "empty raw log" in forged_text or "repository root mismatch" in forged_text:
        fail("measure tooth reached a forbidden side-path rejection")
    print(
        "SUCCESSOR3_MEASURE_CONTROL "
        f"green_rc={good.returncode} forged_rc={forged.returncode} "
        "reason=runner_provenance_mismatch operand=runner_sha256 raw_nonempty=true root_valid=true"
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=("canonical", "bypass"), required=True)
    parser.add_argument("--implementation-root", type=Path, required=True)
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--contract", type=Path, required=True)
    parser.add_argument("--scratch", type=Path, required=True)
    args = parser.parse_args()
    contract = load_contract(args.contract)
    reset_scratch(args.scratch)
    canonical_fixture(args.implementation_root.resolve(), args.scratch, contract)
    if args.mode == "bypass":
        measure_fixture(args.source_root.resolve(), args.scratch, contract)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, KeyError, ValueError, subprocess.SubprocessError) as exc:
        unjudgeable(str(exc))
