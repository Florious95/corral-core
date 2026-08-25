#!/usr/bin/env python3
"""Select one complete Android SDK root and rewrite local.properties silently."""

from __future__ import annotations

import argparse
import os
import pathlib
import stat
import subprocess
import sys
import tempfile
from dataclasses import dataclass


PACKAGE_ID = "system-images;android-35;google_apis;arm64-v8a"
PACKAGE_XML = pathlib.Path("system-images/android-35/google_apis/arm64-v8a/package.xml")


def fail(message: str) -> None:
    print(f"FAIL baseline-bundle-successor9-sdk-selector: {message}", file=sys.stderr)
    raise SystemExit(1)


def unjudgeable(message: str) -> None:
    print(f"UNJUDGEABLE baseline-bundle-successor9-sdk-selector: {message}", file=sys.stderr)
    raise SystemExit(2)


def safe_resolve(path: pathlib.Path) -> pathlib.Path | None:
    try:
        resolved = path.expanduser().resolve(strict=True)
        if not resolved.is_dir() or not os.access(resolved, os.R_OK | os.X_OK):
            return None
        return resolved
    except (OSError, RuntimeError):
        return None


def parse_local_candidate(path: pathlib.Path) -> pathlib.Path | None:
    try:
        if path.is_symlink() or not stat.S_ISREG(path.stat().st_mode):
            return None
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError):
        return None
    value: str | None = None
    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or stripped.startswith("!"):
            continue
        if not line.startswith("sdk.dir=") or value is not None:
            return None
        candidate = line[len("sdk.dir=") :]
        if (
            not candidate
            or not pathlib.Path(candidate).is_absolute()
            or "\\" in candidate
            or any(ord(char) < 32 for char in candidate)
            or candidate != candidate.strip()
        ):
            return None
        value = candidate
    return pathlib.Path(value) if value is not None else None


def derived_root(executable: pathlib.Path) -> pathlib.Path | None:
    try:
        resolved = executable.resolve(strict=True)
    except (OSError, RuntimeError):
        return None
    parts = resolved.parts
    for marker in range(len(parts)):
        suffix = parts[marker:]
        if len(suffix) == 4 and suffix[0] == "cmdline-tools" and suffix[2:] == ("bin", "sdkmanager"):
            return pathlib.Path(*parts[:marker])
        if suffix == ("tools", "bin", "sdkmanager"):
            return pathlib.Path(*parts[:marker])
    return None


def regular_executable_within(path: pathlib.Path, root: pathlib.Path) -> pathlib.Path | None:
    try:
        resolved = path.resolve(strict=True)
        resolved.relative_to(root)
        if not resolved.is_file() or not os.access(resolved, os.X_OK):
            return None
        return resolved
    except (OSError, RuntimeError, ValueError):
        return None


def exact_package_reported(sdkmanager: pathlib.Path, root: pathlib.Path) -> bool:
    try:
        result = subprocess.run(
            [str(sdkmanager), f"--sdk_root={root}", "--list_installed"],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=30,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        return False
    if result.returncode != 0:
        return False
    try:
        text = result.stdout.decode("utf-8", errors="strict")
    except UnicodeError:
        return False
    return any(line.split("|", 1)[0].strip() == PACKAGE_ID for line in text.splitlines())


@dataclass
class Candidate:
    root: pathlib.Path
    sources: set[str]


def validate_candidate(candidate: Candidate, derived_sdkmanager: pathlib.Path | None) -> bool:
    root = candidate.root
    package_xml = root / PACKAGE_XML
    try:
        package_stat = package_xml.lstat()
        if not stat.S_ISREG(package_stat.st_mode) or not os.access(package_xml, os.R_OK):
            return False
    except OSError:
        return False

    adb = regular_executable_within(root / "platform-tools/adb", root)
    emulator = regular_executable_within(root / "emulator/emulator", root)
    if adb is None or emulator is None:
        return False

    sdkmanager_options: list[pathlib.Path] = []
    if derived_sdkmanager is not None:
        derived = regular_executable_within(derived_sdkmanager, root)
        if derived is not None:
            sdkmanager_options.append(derived)
    for option in (
        root / "cmdline-tools/latest/bin/sdkmanager",
        root / "tools/bin/sdkmanager",
    ):
        resolved = regular_executable_within(option, root)
        if resolved is not None and resolved not in sdkmanager_options:
            sdkmanager_options.append(resolved)

    for sdkmanager in sdkmanager_options:
        avdmanager = regular_executable_within(sdkmanager.parent / "avdmanager", root)
        if avdmanager is not None and exact_package_reported(sdkmanager, root):
            return True
    return False


def target_is_tracked(repo_root: pathlib.Path) -> bool:
    try:
        result = subprocess.run(
            ["git", "-C", str(repo_root), "ls-files", "--error-unmatch", "app/local.properties"],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=10,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        unjudgeable("cannot inspect target Git identity")
    return result.returncode == 0


def validate_existing_target(target: pathlib.Path) -> None:
    if not target.exists() and not target.is_symlink():
        unjudgeable("target local.properties is missing")
    try:
        target_stat = target.lstat()
    except OSError:
        unjudgeable("cannot inspect target local.properties")
    if stat.S_ISLNK(target_stat.st_mode) or not stat.S_ISREG(target_stat.st_mode):
        fail("target local.properties is not a regular non-symlink file")
    try:
        lines = target.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError):
        unjudgeable("cannot read target local.properties")
    seen = False
    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or stripped.startswith("!"):
            continue
        if not line.startswith("sdk.dir=") or seen:
            fail("target local.properties contains unknown or duplicate keys")
        value = line[len("sdk.dir=") :]
        if (
            not value
            or not pathlib.Path(value).is_absolute()
            or "\\" in value
            or any(ord(char) < 32 for char in value)
            or value != value.strip()
        ):
            fail("target local.properties contains malformed sdk.dir")
        seen = True


def rewrite_target(repo_root: pathlib.Path, target: pathlib.Path, sdk_root: pathlib.Path) -> None:
    if target_is_tracked(repo_root):
        fail("target app/local.properties is tracked")
    validate_existing_target(target)
    try:
        target.parent.mkdir(parents=True, exist_ok=True)
        descriptor, temporary = tempfile.mkstemp(prefix=".local.properties.successor9.", dir=target.parent)
        try:
            with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
                os.fchmod(stream.fileno(), stat.S_IRUSR | stat.S_IWUSR)
                stream.write("sdk.dir=")
                stream.write(str(sdk_root))
                stream.write("\n")
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, target)
            os.chmod(target, stat.S_IRUSR | stat.S_IWUSR)
        finally:
            try:
                os.unlink(temporary)
            except FileNotFoundError:
                pass
        directory_fd = os.open(target.parent, os.O_RDONLY)
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
    except OSError:
        unjudgeable("cannot atomically rewrite target local.properties")

    try:
        target_stat = target.lstat()
        lines = target.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError):
        unjudgeable("cannot verify rewritten target local.properties")
    if (
        stat.S_IMODE(target_stat.st_mode) != 0o600
        or not stat.S_ISREG(target_stat.st_mode)
        or len(lines) != 1
        or lines[0] != f"sdk.dir={sdk_root}"
    ):
        fail("rewritten target local.properties identity mismatch")
    if target_is_tracked(repo_root):
        fail("rewritten target local.properties entered Git")


def main() -> None:
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--repo-root", required=True)
    parser.add_argument("--source-properties", required=True)
    parser.add_argument("--target-properties", required=True)
    parser.add_argument("--sdkmanager-executable")
    args = parser.parse_args()

    repo_root = safe_resolve(pathlib.Path(args.repo_root))
    if repo_root is None:
        unjudgeable("target repository unavailable")
    target = pathlib.Path(args.target_properties)
    try:
        target.parent.resolve(strict=True).relative_to(repo_root)
    except (OSError, RuntimeError, ValueError):
        fail("target local.properties escapes repository")
    if target != repo_root / "app/local.properties":
        fail("target local.properties coordinate mismatch")

    derived_sdkmanager = pathlib.Path(args.sdkmanager_executable) if args.sdkmanager_executable else None
    raw: list[tuple[str, pathlib.Path]] = []
    for label, value in (
        ("env-root", os.environ.get("ANDROID_SDK_ROOT", "")),
        ("env-home", os.environ.get("ANDROID_HOME", "")),
    ):
        if value:
            raw.append((label, pathlib.Path(value)))
    local_candidate = parse_local_candidate(pathlib.Path(args.source_properties))
    if local_candidate is not None:
        raw.append(("root-local", local_candidate))
    if derived_sdkmanager is not None:
        executable_candidate = derived_root(derived_sdkmanager)
        if executable_candidate is not None:
            raw.append(("sdkmanager-derived", executable_candidate))

    deduplicated: dict[tuple[int, int], Candidate] = {}
    for label, path in raw:
        resolved = safe_resolve(path)
        if resolved is None:
            continue
        try:
            identity = (resolved.stat().st_dev, resolved.stat().st_ino)
        except OSError:
            continue
        if identity in deduplicated:
            deduplicated[identity].sources.add(label)
        else:
            deduplicated[identity] = Candidate(resolved, {label})

    valid = [candidate for candidate in deduplicated.values() if validate_candidate(candidate, derived_sdkmanager)]
    if len(valid) != 1:
        unjudgeable("validated SDK root count is not exactly one")

    rewrite_target(repo_root, target, valid[0].root)
    print(
        "PASS baseline-bundle-successor9-sdk-selector: "
        f"sources={len(raw)} canonical_inode_roots={len(deduplicated)} valid_roots=1 target=minimal-0600-untracked"
    )


if __name__ == "__main__":
    main()
