#!/usr/bin/env python3
"""Parse the strict four-segment input A/B sample packet."""
from __future__ import annotations

import argparse
import json
import math
import re
import sys
from pathlib import Path

FIXTURES = ("big_scrollback", "real_claude_idle", "redraw_tui")
EVENTS = ("tap", "route_enter", "first_frame_recv", "first_draw")
SEGMENTS = (
    ("tap_to_route_enter", "tap", "route_enter"),
    ("route_enter_to_first_frame", "route_enter", "first_frame_recv"),
    ("first_frame_to_first_draw", "first_frame_recv", "first_draw"),
    ("tap_to_first_draw", "tap", "first_draw"),
)
BASELINE_TAG = "baseline-20260822-release"
REFERENCE_MD5 = "0907d6881bb1e034ef33a49f89afaa44"
MIN_N = 10
LINE_RE = re.compile(r"\bopen_id=(\S+)\s+ev=(\S+)\s+t=(-?\d+)(?P<extra>.*)$")
LOG_RE = re.compile(r"^(.+)-(\d+)\.log$")


def percentile(values: list[float], p: float) -> float:
    ordered = sorted(values)
    if not ordered:
        raise ValueError("empty sample set")
    pos = (len(ordered) - 1) * p / 100.0
    lo, hi = math.floor(pos), math.ceil(pos)
    if lo == hi:
        return float(ordered[lo])
    return ordered[lo] + (ordered[hi] - ordered[lo]) * (pos - lo)


def parse_log(path: Path) -> dict[str, int]:
    opens: dict[str, dict[str, int]] = {}
    order: list[str] = []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        match = LINE_RE.search(line)
        if not match:
            continue
        oid, event, stamp = match.group(1), match.group(2), int(match.group(3))
        if event not in EVENTS or "emitted=0" in match.group("extra"):
            continue
        if oid not in opens:
            opens[oid] = {}
            order.append(oid)
        opens[oid].setdefault(event, stamp)
    for oid in order:
        if all(event in opens[oid] for event in EVENTS):
            return opens[oid]
    return {}


def log_files(directory: Path) -> tuple[dict[str, dict[int, Path]], list[str]]:
    by_fixture = {fixture: {} for fixture in FIXTURES}
    unknown: list[str] = []
    if not directory.is_dir():
        return by_fixture, [f"{directory} is not a directory"]
    for path in sorted(directory.glob("*.log")):
        match = LOG_RE.match(path.name)
        if not match or match.group(1) not in by_fixture:
            unknown.append(path.name)
            continue
        fixture, sequence = match.group(1), int(match.group(2))
        if sequence in by_fixture[fixture]:
            unknown.append(f"duplicate {path.name}")
        by_fixture[fixture][sequence] = path
    return by_fixture, unknown


def read_order(path: Path) -> tuple[dict[str, list[tuple[int, str]]], list[str]]:
    rows = {fixture: [] for fixture in FIXTURES}
    errors: list[str] = []
    if not path.is_file():
        return rows, [f"missing order manifest: {path}"]
    for line_no, raw in enumerate(path.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#") or line.lower().startswith("fixture\t"):
            continue
        fields = line.split("\t")
        if len(fields) != 3:
            errors.append(f"order line {line_no}: expected fixture\\tsequence\\tpackage")
            continue
        fixture, sequence_text, package = fields
        if fixture not in rows or package not in ("A", "B"):
            errors.append(f"order line {line_no}: unknown fixture/package")
            continue
        try:
            sequence = int(sequence_text)
        except ValueError:
            errors.append(f"order line {line_no}: sequence is not an integer")
            continue
        rows[fixture].append((sequence, package))
    for fixture, fixture_rows in rows.items():
        seen: set[tuple[int, str]] = set()
        packages = []
        for row in fixture_rows:
            if row in seen:
                errors.append(f"{fixture}: duplicate order row {row[0]} {row[1]}")
            seen.add(row)
            packages.append(row[1])
        if len(packages) < MIN_N * 2:
            errors.append(f"{fixture}: order has fewer than {MIN_N} A/B samples")
        if packages and (packages[0] != "A" or any(a == b for a, b in zip(packages, packages[1:]))):
            errors.append(f"{fixture}: package order is not strict A/B/A/B")
        if packages and packages.count("A") != packages.count("B"):
            errors.append(f"{fixture}: A/B order counts differ")
    return rows, errors


def check_identity(args: argparse.Namespace) -> tuple[dict, list[str]]:
    errors: list[str] = []
    a_md5 = (args.a_md5 or "").lower()
    b_md5 = (args.b_md5 or "").lower()
    tag = args.baseline_tag or ""
    reference = (args.baseline_reference_md5 or "").lower()
    if tag != BASELINE_TAG:
        errors.append(f"A is not bound to stable tag {BASELINE_TAG}")
    if reference != REFERENCE_MD5:
        errors.append("A reference md5 does not match the frozen release")
    if (args.a_revision or "") != BASELINE_TAG:
        errors.append(f"A source revision is not the stable tag {BASELINE_TAG}")
    if not re.fullmatch(r"[0-9a-f]{32}", a_md5):
        errors.append("A measured md5 is missing or malformed")
    elif a_md5 != REFERENCE_MD5:
        errors.append("A measured md5 does not match the frozen release")
    if not re.fullmatch(r"[0-9a-f]{32}", b_md5):
        errors.append("B measured md5 is missing or malformed")
    if a_md5 and a_md5 == b_md5:
        errors.append("A/B md5 are identical")
    if not (args.b_revision or ""):
        errors.append("B source revision is missing")
    return ({"tag": tag, "source_revision": args.a_revision or "", "apk_md5": a_md5, "reference_md5": reference}, errors)


def environment(args: argparse.Namespace) -> tuple[dict, list[str]]:
    """Require host readings that make this batch comparable."""
    errors: list[str] = []
    values: dict[str, float | int | None] = {}
    for name in ("load1", "free", "inactive"):
        raw = getattr(args, name)
        if raw is None or not str(raw).strip():
            errors.append(f"missing environment reading: {name}")
            values[name] = None
            continue
        try:
            value = float(raw)
        except (TypeError, ValueError):
            errors.append(f"invalid environment reading: {name}={raw!r}")
            values[name] = None
            continue
        if not math.isfinite(value) or value < 0:
            errors.append(f"invalid environment reading: {name}={raw!r}")
            values[name] = None
            continue
        values[name] = int(value) if value.is_integer() else value
    free, inactive = values["free"], values["inactive"]
    values["free_inactive"] = free + inactive if free is not None and inactive is not None else None
    return values, errors


def analyse(args: argparse.Namespace) -> tuple[dict, int]:
    issues: list[str] = []
    regressions: list[str] = []
    if args.envcheck_exit != 0:
        issues.append(f"envcheck exit={args.envcheck_exit}")
    a_identity, identity_errors = check_identity(args)
    issues.extend(identity_errors)
    env, environment_errors = environment(args)
    issues.extend(environment_errors)
    b_identity = {"source_revision": args.b_revision or "", "apk_md5": (args.b_md5 or "").lower()}
    order, order_errors = read_order(Path(args.order))
    issues.extend(order_errors)
    a_files, a_unknown = log_files(Path(args.a))
    b_files, b_unknown = log_files(Path(args.b))
    issues.extend(f"A raw: {item}" for item in a_unknown)
    issues.extend(f"B raw: {item}" for item in b_unknown)

    fixtures: dict[str, dict] = {}
    for fixture in FIXTURES:
        fixtures[fixture] = {}
        a_sequences, b_sequences = a_files[fixture], b_files[fixture]
        expected_a = {seq for seq, package in order[fixture] if package == "A"}
        expected_b = {seq for seq, package in order[fixture] if package == "B"}
        if set(a_sequences) != expected_a:
            issues.append(f"{fixture}: A raw/order sequence mismatch")
        if set(b_sequences) != expected_b:
            issues.append(f"{fixture}: B raw/order sequence mismatch")
        values = {segment: {"A": [], "B": []} for segment, _, _ in SEGMENTS}
        for package, files in (("A", a_sequences), ("B", b_sequences)):
            for sequence, path in sorted(files.items()):
                events = parse_log(path)
                if not events:
                    issues.append(f"{fixture} {package} #{sequence}: missing complete event chain")
                    continue
                stamps = [events[event] for event in EVENTS]
                if any(left >= right for left, right in zip(stamps, stamps[1:])):
                    issues.append(f"{fixture} {package} #{sequence}: event timestamps are not monotonic")
                    continue
                for segment, start, end in SEGMENTS:
                    values[segment][package].append(float(events[end] - events[start]))
        for segment, _, _ in SEGMENTS:
            a_samples, b_samples = values[segment]["A"], values[segment]["B"]
            a_p50 = percentile(a_samples, 50) if a_samples else None
            b_p50 = percentile(b_samples, 50) if b_samples else None
            a_p95 = percentile(a_samples, 95) if a_samples else None
            b_p95 = percentile(b_samples, 95) if b_samples else None
            for package, samples in (("A", a_samples), ("B", b_samples)):
                if len(samples) < MIN_N:
                    issues.append(f"{fixture}.{segment}.{package}: n={len(samples)} < {MIN_N}")
            ratios = {"p50": None, "p95": None}
            for metric, av, bv in (("p50", a_p50, b_p50), ("p95", a_p95, b_p95)):
                if av is not None and bv is not None:
                    if av <= 0 or bv < 0:
                        issues.append(f"{fixture}.{segment}.{metric}: non-positive duration")
                    else:
                        ratios[metric] = bv / av
            node = {
                # Keep raw arrays at A/B for independent recomputation.
                "A": a_samples,
                "B": b_samples,
                "n": {"A": len(a_samples), "B": len(b_samples)},
                "p50": {"A": a_p50, "B": b_p50},
                "p95": {"A": a_p95, "B": b_p95},
            }
            node["ratio_b_over_a"] = ratios
            node["B_over_A"] = ratios
            for metric, ratio in ratios.items():
                if ratio is not None and ratio > 1.10:
                    regressions.append(f"{fixture}.{segment}.{metric}: B/A={ratio:.4f} > 1.10")
            fixtures[fixture][segment] = node

    all_issues = issues + regressions
    result = {
        "schema": "perf-ab.v1", "baseline_source": BASELINE_TAG,
        "baseline_tag": a_identity["tag"], "baseline_reference_md5": REFERENCE_MD5,
        "baseline_measured_md5": a_identity["apk_md5"], "candidate_revision": b_identity["source_revision"],
        "candidate_md5": b_identity["apk_md5"], "A": a_identity, "B": b_identity,
        "env": {"gate_exit": args.envcheck_exit, "envcheck_exit": args.envcheck_exit, **env,
                "raw_a": str(Path(args.a)), "raw_b": str(Path(args.b))},
        "order": {"path": str(Path(args.order)), "valid": not order_errors, "rows": order},
        "fixtures": fixtures, "issues": all_issues,
    }
    code = 2 if issues else 1 if regressions else 0
    result["verdict"] = "fail" if code == 1 else "unjudgeable" if code == 2 else "pass"
    output = Path(args.out)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return result, code


def cli(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="strict four-segment input A/B parser")
    parser.add_argument("--a", required=True); parser.add_argument("--b", required=True)
    parser.add_argument("--order", required=True); parser.add_argument("--out", required=True)
    parser.add_argument("--baseline-tag", default=BASELINE_TAG)
    parser.add_argument("--baseline-reference-md5", default=REFERENCE_MD5)
    parser.add_argument("--a-md5", required=True); parser.add_argument("--b-md5", required=True)
    parser.add_argument("--a-revision", default=BASELINE_TAG); parser.add_argument("--b-revision", default="")
    parser.add_argument("--envcheck-exit", type=int, default=0)
    parser.add_argument("--load1", default=None); parser.add_argument("--free", default=None); parser.add_argument("--inactive", default=None)
    args = parser.parse_args(argv)
    try:
        result, code = analyse(args)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"UNJUDGEABLE parser error: {exc}", file=sys.stderr); return 2
    for issue in result["issues"]:
        print(f"  issue: {issue}", file=sys.stderr)
    print(f"{result['verdict'].upper()} schema={result['schema']} out={args.out}")
    return code


if __name__ == "__main__":
    raise SystemExit(cli())
