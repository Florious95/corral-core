#!/usr/bin/env python3
"""Validate fresh successor11 verify evidence without consulting a live device."""

import argparse
import hashlib
import json
import pathlib
import re
import stat
import sys
import time


HEX64 = re.compile(r"[0-9a-f]{64}")
MARKDOWN = ("VERDICT.md", "INSTALL.md", "RETRIEVE.md", "MUTATION.md")
CONTRACT_KEYS = {
    "schema", "apparatus", "manifest", "permanent_fixture", "verify_dir", "freshness_seconds"
}
VERIFY_KEYS = {
    "schema", "verdict", "verify_batch_id", "verified_epoch",
    "apparatus_evidence_sha256", "apparatus_bundle_id", "apparatus_manifest_sha256",
    "apparatus_install_exit", "apparatus_pm_identity_verified", "runner_pid_cleanup",
    "serial_cleanup", "owned_qemu_cleanup", "forced_kill",
    "permanent_fixture_sha256", "permanent_bypass_probe_exit",
    "legacy_temporary_gate_used", "current_adb_required", "evidence_sha256",
}
FIXED_PATHS = {
    "apparatus": ".team/nodes/baseline-bundle-apparatus/APPARATUS.json",
    "producer": ".team/ledgers/acceptance/baseline-bundle-successor10-owned-emulator.sh",
    "manifest": ".team/nodes/baseline-bundle-impl/BUNDLE-MANIFEST.json",
    "permanent": ".team/ledgers/acceptance/fixtures/baseline-bundle-successor7/impl-bypass/BUNDLE-MANIFEST.json",
    "verify_dir": ".team/nodes/baseline-bundle-verify",
}


def fail(message: str) -> None:
    print(f"FAIL baseline-bundle-successor11-verify: {message}", file=sys.stderr)
    raise SystemExit(1)


def unjudgeable(message: str) -> None:
    print(f"UNJUDGEABLE baseline-bundle-successor11-verify: {message}", file=sys.stderr)
    raise SystemExit(2)


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    try:
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
    except OSError:
        unjudgeable("required evidence cannot be hashed")
    return digest.hexdigest()


def regular(path: pathlib.Path, label: str, mode: int | None = None) -> None:
    try:
        info = path.lstat()
    except FileNotFoundError:
        unjudgeable(f"{label} missing")
    except OSError:
        unjudgeable(f"{label} unavailable")
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode):
        fail(f"{label} is not a regular non-symlink file")
    if mode is not None and stat.S_IMODE(info.st_mode) != mode:
        fail(f"{label} mode is not {mode:04o}")
    if info.st_size <= 0:
        fail(f"{label} is empty")


def load_json(path: pathlib.Path, label: str, mode: int | None = None):
    regular(path, label, mode)
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError):
        fail(f"{label} is not valid JSON")


def contract_path(repo: pathlib.Path) -> pathlib.Path:
    return repo / ".team/ledgers/acceptance/fixtures/baseline-bundle-successor11/verify-contract.json"


def validate_contract(repo: pathlib.Path):
    data = load_json(contract_path(repo), "successor11 verify contract")
    if not isinstance(data, dict) or set(data) != CONTRACT_KEYS:
        fail("verify contract key set mismatch")
    if data.get("schema") != "agentmirror.successor11.verify-contract.v1":
        fail("verify contract schema mismatch")
    apparatus = data.get("apparatus")
    manifest = data.get("manifest")
    permanent = data.get("permanent_fixture")
    if not all(isinstance(item, dict) for item in (apparatus, manifest, permanent)):
        fail("verify contract sections malformed")
    expected_sections = (
        (apparatus, {"relpath", "sha256", "schema", "mode", "created_epoch", "bundle_id", "manifest_sha256", "producer_relpath", "producer_sha256"}),
        (manifest, {"relpath", "sha256"}),
        (permanent, {"relpath", "sha256"}),
    )
    if any(set(section) != keys for section, keys in expected_sections):
        fail("verify contract section key set mismatch")
    fixed = (
        (apparatus.get("relpath"), FIXED_PATHS["apparatus"]),
        (apparatus.get("producer_relpath"), FIXED_PATHS["producer"]),
        (manifest.get("relpath"), FIXED_PATHS["manifest"]),
        (permanent.get("relpath"), FIXED_PATHS["permanent"]),
        (data.get("verify_dir"), FIXED_PATHS["verify_dir"]),
    )
    if any(actual != wanted for actual, wanted in fixed):
        fail("verify contract contains a replaceable production path")
    digest_values = (
        apparatus.get("sha256"), apparatus.get("bundle_id"), apparatus.get("manifest_sha256"),
        apparatus.get("producer_sha256"), manifest.get("sha256"), permanent.get("sha256"),
    )
    if any(not isinstance(value, str) or HEX64.fullmatch(value) is None for value in digest_values):
        fail("verify contract digest or bundle identity malformed")
    if apparatus.get("schema") != "agentmirror.successor7.apparatus.v1" or apparatus.get("mode") != "production":
        fail("verify contract apparatus identity mismatch")
    if not isinstance(apparatus.get("created_epoch"), int) or apparatus["created_epoch"] <= 0:
        fail("verify contract apparatus epoch malformed")
    if manifest.get("sha256") != apparatus.get("manifest_sha256"):
        fail("verify contract manifest digest is not cross-linked")
    freshness = data.get("freshness_seconds")
    if not isinstance(freshness, int) or freshness < 60 or freshness > 86400:
        fail("verify contract freshness bound malformed")
    return data


def validate_frozen_inputs(repo: pathlib.Path, contract) -> tuple[dict, dict]:
    apparatus_contract = contract["apparatus"]
    apparatus_path = repo / FIXED_PATHS["apparatus"]
    manifest_path = repo / FIXED_PATHS["manifest"]
    producer_path = repo / FIXED_PATHS["producer"]
    permanent_path = repo / FIXED_PATHS["permanent"]

    apparatus = load_json(apparatus_path, "same-batch apparatus", 0o600)
    manifest = load_json(manifest_path, "bundle manifest")
    regular(producer_path, "apparatus producer")
    regular(permanent_path, "permanent successor7 fixture")
    if sha256(apparatus_path) != apparatus_contract["sha256"]:
        fail("same-batch apparatus digest mismatch")
    if sha256(manifest_path) != contract["manifest"]["sha256"]:
        fail("bundle manifest digest mismatch")
    if sha256(producer_path) != apparatus_contract["producer_sha256"]:
        fail("apparatus producer provenance mismatch")
    if sha256(permanent_path) != contract["permanent_fixture"]["sha256"]:
        fail("permanent successor7 fixture digest mismatch")

    exact = {
        "schema": apparatus_contract["schema"],
        "mode": apparatus_contract["mode"],
        "created_epoch": apparatus_contract["created_epoch"],
        "bundle_id": apparatus_contract["bundle_id"],
        "manifest_sha256": apparatus_contract["manifest_sha256"],
        "adb_install_exit": 0,
        "envcheck_preflight_exit": 0,
        "envcheck_measurement_exit": 0,
        "envcheck_recovery_exit": 0,
        "fresh_task_avd": True,
        "owned_qemu_bound": True,
        "runner_pid_cleanup": True,
        "serial_cleanup": True,
        "owned_qemu_cleanup": True,
        "forced_kill": False,
        "foreign_qemu_touched": False,
    }
    bad = [name for name, wanted in exact.items() if apparatus.get(name) != wanted]
    manifest_bundle = manifest.get("bundle_id")
    manifest_apk = manifest.get("artifact", {}).get("apk_sha256") if isinstance(manifest.get("artifact"), dict) else None
    if manifest_bundle != apparatus_contract["bundle_id"] or apparatus.get("apk_sha256") != manifest_apk:
        bad.append("manifest_bundle_or_apk_identity")
    if bad:
        fail("same-batch apparatus facts mismatch: " + ",".join(sorted(set(bad))))
    return apparatus, manifest


def parse_markdown(path: pathlib.Path, expected_name: str, expected: dict, verified_epoch: int) -> str:
    regular(path, expected_name, 0o600)
    try:
        text = path.read_text(encoding="utf-8")
        info = path.stat()
    except (OSError, UnicodeError):
        unjudgeable(f"{expected_name} cannot be read")
    lines = text.splitlines()
    required = {
        "successor11_verify_schema": "agentmirror.successor11.verify-evidence.v1",
        "successor11_verify_artifact": expected_name,
        "successor11_verify_batch_id": expected["verify_batch_id"],
        "apparatus_evidence_sha256": expected["apparatus_evidence_sha256"],
        "apparatus_bundle_id": expected["apparatus_bundle_id"],
        "apparatus_manifest_sha256": expected["apparatus_manifest_sha256"],
        "permanent_fixture_sha256": expected["permanent_fixture_sha256"],
    }
    for key, value in required.items():
        if lines.count(f"{key}: {value}") != 1:
            fail(f"{expected_name} missing unique fresh cross-link {key}")
    if info.st_mtime < verified_epoch - 5:
        fail(f"{expected_name} was not freshly rewritten")
    return text


def validate_verify(repo: pathlib.Path, contract, apparatus: dict) -> None:
    verify_dir = repo / contract["verify_dir"]
    verify_path = verify_dir / "VERIFY.json"
    verify = load_json(verify_path, "VERIFY.json", 0o600)
    if not isinstance(verify, dict) or set(verify) != VERIFY_KEYS:
        fail("VERIFY.json key set mismatch")
    if verify.get("schema") != "agentmirror.successor11.verify.v1":
        fail("VERIFY.json schema is not fresh successor11")
    batch = verify.get("verify_batch_id")
    if not isinstance(batch, str) or HEX64.fullmatch(batch) is None:
        fail("VERIFY.json batch identity malformed")
    verified_epoch = verify.get("verified_epoch")
    now = int(time.time())
    if not isinstance(verified_epoch, int):
        fail("VERIFY.json verified_epoch malformed")
    if verified_epoch < apparatus["created_epoch"] or verified_epoch > now + 5:
        fail("VERIFY.json epoch contradicts apparatus or clock")
    if now - verified_epoch > contract["freshness_seconds"]:
        unjudgeable("fresh verify evidence expired")

    expected = {
        "apparatus_evidence_sha256": contract["apparatus"]["sha256"],
        "apparatus_bundle_id": contract["apparatus"]["bundle_id"],
        "apparatus_manifest_sha256": contract["apparatus"]["manifest_sha256"],
        "apparatus_install_exit": 0,
        "apparatus_pm_identity_verified": True,
        "runner_pid_cleanup": True,
        "serial_cleanup": True,
        "owned_qemu_cleanup": True,
        "forced_kill": False,
        "permanent_fixture_sha256": contract["permanent_fixture"]["sha256"],
        "permanent_bypass_probe_exit": 0,
        "legacy_temporary_gate_used": False,
        "current_adb_required": False,
    }
    bad = [name for name, wanted in expected.items() if verify.get(name) != wanted]
    if bad:
        fail("VERIFY.json archived-fact cross-link mismatch: " + ",".join(sorted(bad)))
    evidence = verify.get("evidence_sha256")
    if not isinstance(evidence, dict) or set(evidence) != set(MARKDOWN):
        fail("VERIFY.json evidence hash map mismatch")
    if any(not isinstance(value, str) or HEX64.fullmatch(value) is None for value in evidence.values()):
        fail("VERIFY.json evidence digest malformed")

    texts = {}
    for name in MARKDOWN:
        path = verify_dir / name
        texts[name] = parse_markdown(path, name, {**verify, **expected}, verified_epoch)
        if sha256(path) != evidence[name]:
            fail(f"{name} digest mismatch")
    verdict = verify.get("verdict")
    if verdict not in ("pass", "fail", "unjudgeable"):
        fail("VERIFY.json verdict malformed")
    if texts["VERDICT.md"].splitlines()[-1:] != [f"verdict: {verdict}"]:
        fail("VERDICT.md final line disagrees with VERIFY.json")
    if verdict == "fail":
        fail("fresh independent verifier reports fail")
    if verdict == "unjudgeable":
        unjudgeable("fresh independent verifier reports unjudgeable")
    print(
        "SUCCESSOR11_VERIFY archived_apparatus=true install=0 pm_identity=true "
        "cleanup=true permanent_fixture=true live_adb_required=false fresh_private_evidence=true"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", required=True)
    parser.add_argument("--mode", choices=("production", "fixture"), required=True)
    args = parser.parse_args()
    try:
        repo = pathlib.Path(args.repo_root).resolve(strict=True)
    except OSError:
        unjudgeable("repository root unavailable")
    contract = validate_contract(repo)
    apparatus, _ = validate_frozen_inputs(repo, contract)
    validate_verify(repo, contract, apparatus)


if __name__ == "__main__":
    main()
