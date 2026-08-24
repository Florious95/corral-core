#!/usr/bin/env python3
"""Create a minimal local.properties without ever emitting its value."""

import argparse
import os
import pathlib
import stat
import sys
import tempfile


def unjudgeable(message: str) -> None:
    print(f"UNJUDGEABLE baseline-bundle-successor5-sdk: {message}", file=sys.stderr)
    raise SystemExit(2)


def parse_source(source: pathlib.Path) -> pathlib.Path:
    try:
        lines = source.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError):
        unjudgeable("source local.properties unavailable")
    value = None
    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or stripped.startswith("!"):
            continue
        if not line.startswith("sdk.dir="):
            unjudgeable("source local.properties contains a non-sdk.dir entry")
        if value is not None:
            unjudgeable("source local.properties contains duplicate sdk.dir")
        candidate = line[len("sdk.dir=") :]
        if not candidate or any(ord(char) < 32 for char in candidate):
            unjudgeable("source sdk.dir is empty or malformed")
        value = candidate
    if value is None:
        unjudgeable("source local.properties has no sdk.dir")
    return pathlib.Path(value)


def validate_sdk(candidate: pathlib.Path) -> pathlib.Path:
    try:
        resolved = candidate.expanduser().resolve(strict=True)
    except (OSError, RuntimeError):
        unjudgeable("selected SDK directory is unavailable")
    if not resolved.is_dir() or not os.access(resolved, os.R_OK | os.X_OK):
        unjudgeable("selected SDK directory is unavailable")
    return resolved


def write_target(target: pathlib.Path, sdk_dir: pathlib.Path) -> None:
    try:
        target.parent.mkdir(parents=True, exist_ok=True)
        descriptor, temporary_name = tempfile.mkstemp(prefix=".local.properties.", dir=target.parent)
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            os.fchmod(stream.fileno(), stat.S_IRUSR | stat.S_IWUSR)
            stream.write("sdk.dir=")
            stream.write(str(sdk_dir))
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary_name, target)
        os.chmod(target, stat.S_IRUSR | stat.S_IWUSR)
    except OSError:
        unjudgeable("cannot create target local.properties")


def main() -> None:
    parser = argparse.ArgumentParser(add_help=False)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--sdk-dir")
    source.add_argument("--source-properties")
    parser.add_argument("--target-properties", required=True)
    args = parser.parse_args()

    if args.sdk_dir is not None:
        sdk_dir = validate_sdk(pathlib.Path(args.sdk_dir))
    else:
        sdk_dir = validate_sdk(parse_source(pathlib.Path(args.source_properties)))
    write_target(pathlib.Path(args.target_properties), sdk_dir)


if __name__ == "__main__":
    main()
