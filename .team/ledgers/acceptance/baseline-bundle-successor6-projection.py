#!/usr/bin/env python3
"""Independently validate content identity and non-circular build-slot projection."""

import argparse
import hashlib
import json
import pathlib
import re
import sys


def fail(message: str) -> None:
    print(f"FAIL baseline-bundle-successor6-projection: {message}", file=sys.stderr)
    raise SystemExit(1)


def unjudgeable(message: str) -> None:
    print(f"UNJUDGEABLE baseline-bundle-successor6-projection: {message}", file=sys.stderr)
    raise SystemExit(2)


def load_json(path: pathlib.Path, label: str, malformed_is_failure: bool) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError):
        unjudgeable(f"{label} unavailable")
    except json.JSONDecodeError:
        if malformed_is_failure:
            fail(f"{label} is malformed JSON")
        unjudgeable(f"{label} is malformed JSON")
    if not isinstance(value, dict):
        fail(f"{label} root is not an object")
    return value


def validate_relative(value: object, label: str) -> pathlib.PurePosixPath:
    if not isinstance(value, str) or not value or value.startswith("/") or "\\" in value:
        fail(f"{label} is not a safe repository-relative path")
    candidate = pathlib.PurePosixPath(value)
    if any(part in ("", ".", "..") for part in candidate.parts):
        fail(f"{label} contains an unsafe component")
    return candidate


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--contract", required=True)
    args = parser.parse_args()

    manifest = load_json(pathlib.Path(args.manifest), "manifest", True)
    contract = load_json(pathlib.Path(args.contract), "projection contract", False)
    if contract.get("schema") != "agentmirror.baseline-bundle.successor6-projection.v1":
        unjudgeable("projection contract schema drift")

    bundle_id = manifest.get("bundle_id")
    if not isinstance(bundle_id, str) or re.fullmatch(r"[0-9a-f]{64}", bundle_id) is None:
        fail("bundle_id is not 64 lowercase hex")
    keys = contract.get("canonical_projection_keys")
    if keys != ["source", "runtime", "artifact", "build", "equivalence", "implementation"]:
        unjudgeable("canonical projection key contract drift")
    try:
        projection = {key: manifest[key] for key in keys}
    except KeyError:
        fail("manifest lacks a canonical projection field")
    canonical_bytes = json.dumps(
        projection,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
    ).encode("utf-8")
    calculated = hashlib.sha256(canonical_bytes).hexdigest()
    if calculated != bundle_id:
        fail("bundle_id does not equal canonical content identity")
    print("SUCCESSOR6_CANONICAL_IDENTITY equal=true")

    slots = contract.get("independent_build_slots")
    builds = manifest.get("build", {}).get("independent_builds")
    if not isinstance(slots, list) or len(slots) != 2:
        unjudgeable("independent slot contract drift")
    if not isinstance(builds, list) or len(builds) != len(slots):
        fail("independent build cardinality does not match slot contract")
    seen_paths = set()
    seen_roots = set()
    for index, (slot, build) in enumerate(zip(slots, builds)):
        if not isinstance(slot, dict) or slot.get("index") != index or not isinstance(build, dict):
            unjudgeable("independent slot contract malformed")
        actual = validate_relative(build.get("apk_relpath"), f"slot[{index}].apk_relpath")
        expected = validate_relative(slot.get("apk_relpath"), f"contract slot[{index}].apk_relpath")
        if actual != expected:
            fail(f"independent build slot[{index}] projection mismatch")
        if bundle_id in actual.parts or "{bundle_id}" in actual.parts or "PENDING" in actual.parts:
            fail(f"independent build slot[{index}] is circular")
        if actual in seen_paths:
            fail("independent build slots alias one path")
        seen_paths.add(actual)
        build_root = build.get("build_root")
        if not isinstance(build_root, str) or not build_root or build_root in seen_roots:
            fail("independent build roots are missing or not independent")
        seen_roots.add(build_root)
    print("SUCCESSOR6_SLOT_PROJECTION schema=true non_circular=true stable=true no_traversal=true independent=true")

    archive = manifest.get("archive")
    archive_contract = contract.get("archive")
    if not isinstance(archive, dict) or not isinstance(archive_contract, dict):
        fail("archive projection missing")
    expected_primary = archive_contract.get("primary_template", "").replace("{bundle_id}", bundle_id)
    expected_backup = archive_contract.get("backup_template", "").replace("{bundle_id}", bundle_id)
    if archive.get("primary_relpath") != expected_primary or archive.get("backup_relpath") != expected_backup:
        fail("archive content-addressed projection mismatch")
    validate_relative(expected_primary, "archive.primary_relpath")
    validate_relative(expected_backup, "archive.backup_relpath")
    print("SUCCESSOR6_ARCHIVE_PROJECTION content_addressed=true")


if __name__ == "__main__":
    main()
