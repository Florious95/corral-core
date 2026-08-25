#!/usr/bin/env python3
"""Bounded, no-input AVD creation with redacted structured evidence."""

import argparse
import hashlib
import json
import os
import pathlib
import re
import secrets
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import time


PACKAGE_ID = "system-images;android-35;google_apis;arm64-v8a"
PACKAGE_PATH = pathlib.Path("system-images/android-35/google_apis/arm64-v8a")
DEVICE_PROFILE = "pixel_6"
CONTRACT = "avdmanager:create avd:force:name-generated:package-api35-google_apis-arm64-v8a:device-pixel_6:stdin-devnull"
CONTRACT_SHA256 = hashlib.sha256(CONTRACT.encode("utf-8")).hexdigest()
SCHEMA = "successor10.avd-create.v1"
SAFE_NAME = re.compile(r"^successor10_[a-z0-9_]{8,80}$")
DIGEST = re.compile(r"^[0-9a-f]{64}$")
REASONS = {
    "created": 0,
    "target_policy_invalid": 1,
    "avd_home_mode_invalid": 1,
    "avd_home_not_empty": 1,
    "avd_name_exists": 1,
    "device_profile_mismatch": 1,
    "package_mismatch": 1,
    "tool_unavailable": 2,
    "package_unavailable": 2,
    "device_profile_unavailable": 2,
    "license_unavailable": 2,
    "interactive_input_required": 2,
    "timeout": 2,
    "create_failed": 2,
    "avd_directory_missing": 2,
}
EVIDENCE_KEYS = {
    "schema_version",
    "mode",
    "reason_code",
    "verdict_exit",
    "avdmanager_rc",
    "stdout_sha256",
    "stderr_sha256",
    "command_contract_sha256",
    "package_id",
    "device_profile",
    "avd_name_sha256",
    "avd_home_mode",
    "created",
    "raw_cleaned",
    "duration_ms",
}


def say(kind, reason, tool_rc, stdout_digest, stderr_digest):
    print(
        "{} baseline-bundle-successor10-avd: reason_code={} rc={} stdout_digest={} stderr_digest={} raw_cleaned=true".format(
            kind, reason, tool_rc, stdout_digest, stderr_digest
        ),
        file=sys.stderr if kind != "PASS" else sys.stdout,
    )


def safe_resolve(path, directory=False):
    try:
        resolved = pathlib.Path(path).resolve(strict=True)
        if directory and not resolved.is_dir():
            return None
        return resolved
    except (OSError, RuntimeError):
        return None


def within(path, parent):
    try:
        path.resolve(strict=False).relative_to(parent.resolve(strict=True))
        return True
    except (OSError, RuntimeError, ValueError):
        return False


def tracked(repo_root, path):
    try:
        relative = path.resolve(strict=False).relative_to(repo_root)
        result = subprocess.run(
            ["git", "-C", str(repo_root), "ls-files", "--error-unmatch", str(relative)],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=10,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired, ValueError):
        return None
    return result.returncode == 0


def hash_bytes(data):
    return hashlib.sha256(data).hexdigest()


def atomic_json(path, payload):
    descriptor = None
    temporary = None
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        descriptor, temporary = tempfile.mkstemp(prefix=".avd-create.", dir=path.parent)
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            descriptor = None
            os.fchmod(stream.fileno(), 0o600)
            json.dump(payload, stream, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        temporary = None
        os.chmod(path, 0o600)
    except OSError:
        raise SystemExit(2)
    finally:
        if descriptor is not None:
            os.close(descriptor)
        if temporary is not None:
            try:
                os.unlink(temporary)
            except OSError:
                pass


def atomic_name(path, name):
    descriptor = None
    temporary = None
    try:
        descriptor, temporary = tempfile.mkstemp(prefix=".avd-name.", dir=path.parent)
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            descriptor = None
            os.fchmod(stream.fileno(), 0o600)
            stream.write(name + "\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        temporary = None
        os.chmod(path, 0o600)
    except OSError:
        raise SystemExit(2)
    finally:
        if descriptor is not None:
            os.close(descriptor)
        if temporary is not None:
            try:
                os.unlink(temporary)
            except OSError:
                pass


def sanitized_env(sdk_root, avd_home, task_home, raw_dir):
    env = {
        "ANDROID_SDK_ROOT": str(sdk_root),
        "ANDROID_HOME": str(sdk_root),
        "ANDROID_AVD_HOME": str(avd_home),
        "ANDROID_USER_HOME": str(task_home / "android-user"),
        "HOME": str(task_home),
        "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
        "TMPDIR": str(raw_dir),
    }
    if os.environ.get("JAVA_HOME"):
        env["JAVA_HOME"] = os.environ["JAVA_HOME"]
    return env


def run_bounded(argv, env, stdout_path, stderr_path, timeout):
    started = time.monotonic()
    with stdout_path.open("wb") as stdout_stream, stderr_path.open("wb") as stderr_stream:
        os.fchmod(stdout_stream.fileno(), 0o600)
        os.fchmod(stderr_stream.fileno(), 0o600)
        try:
            process = subprocess.Popen(
                argv,
                stdin=subprocess.DEVNULL,
                stdout=stdout_stream,
                stderr=stderr_stream,
                env=env,
                close_fds=True,
                start_new_session=True,
            )
        except OSError:
            return None, False, int((time.monotonic() - started) * 1000)
        try:
            return process.wait(timeout=timeout), False, int((time.monotonic() - started) * 1000)
        except subprocess.TimeoutExpired:
            try:
                os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                try:
                    process.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    return 124, True, int((time.monotonic() - started) * 1000)
            return 124, True, int((time.monotonic() - started) * 1000)


def parse_config(path):
    try:
        if path.is_symlink() or not stat.S_ISREG(path.stat().st_mode):
            return None
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError):
        return None
    values = {}
    for line in lines:
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            return None
        key, value = line.split("=", 1)
        if key in values:
            return None
        values[key] = value
    return values


def classify_stderr(data):
    text = data.decode("utf-8", errors="ignore").lower()
    if "license" in text and ("accept" in text or "not accepted" in text):
        return "license_unavailable"
    if any(token in text for token in ("yes/no", "do you wish", "interactive input", "prompt required")):
        return "interactive_input_required"
    if "device" in text and any(token in text for token in ("not found", "unknown", "invalid")):
        return "device_profile_unavailable"
    if "package" in text and any(token in text for token in ("not found", "unknown", "invalid")):
        return "package_unavailable"
    return "create_failed"


def build_evidence(mode, reason, tool_rc, stdout_digest, stderr_digest, name, duration_ms):
    return {
        "schema_version": SCHEMA,
        "mode": mode,
        "reason_code": reason,
        "verdict_exit": REASONS[reason],
        "avdmanager_rc": tool_rc,
        "stdout_sha256": stdout_digest,
        "stderr_sha256": stderr_digest,
        "command_contract_sha256": CONTRACT_SHA256,
        "package_id": PACKAGE_ID,
        "device_profile": DEVICE_PROFILE,
        "avd_name_sha256": hash_bytes(name.encode("utf-8")) if name else hash_bytes(b""),
        "avd_home_mode": "0700",
        "created": reason == "created",
        "raw_cleaned": True,
        "duration_ms": duration_ms,
    }


def create(args):
    empty_digest = hash_bytes(b"")
    repo_root = safe_resolve(args.repo_root, directory=True)
    if repo_root is None:
        say("UNJUDGEABLE", "tool_unavailable", 2, empty_digest, empty_digest)
        return 2
    mode = args.mode
    if mode == "production":
        allowed_root = repo_root / ".team/nodes/baseline-bundle-apparatus/tmp"
        evidence_expected = repo_root / ".team/nodes/baseline-bundle-apparatus/AVD-CREATE.json"
        if repo_root.name != "wt-maple-core" or args.test_name:
            say("FAIL", "target_policy_invalid", 1, empty_digest, empty_digest)
            return 1
    else:
        allowed_root = repo_root / ".team/nodes/spec-sol/baseline-bundle-successor10/tmp"
        evidence_expected = None
    avd_home = pathlib.Path(args.avd_home)
    evidence = pathlib.Path(args.evidence)
    name_file = pathlib.Path(args.name_file)
    raw_dir = pathlib.Path(args.raw_dir)
    if not all(within(path, allowed_root) for path in (avd_home, name_file, raw_dir)):
        say("FAIL", "target_policy_invalid", 1, empty_digest, empty_digest)
        return 1
    if evidence_expected is not None and evidence.resolve(strict=False) != evidence_expected.resolve(strict=False):
        say("FAIL", "target_policy_invalid", 1, empty_digest, empty_digest)
        return 1
    if mode == "fixture" and not within(evidence, repo_root / ".team/nodes/spec-sol/baseline-bundle-successor10/tmp"):
        say("FAIL", "target_policy_invalid", 1, empty_digest, empty_digest)
        return 1
    for path in (evidence, name_file):
        tracked_state = tracked(repo_root, path)
        if tracked_state is None:
            say("UNJUDGEABLE", "tool_unavailable", 2, empty_digest, empty_digest)
            return 2
        if tracked_state or path.exists() or path.is_symlink():
            say("FAIL", "target_policy_invalid", 1, empty_digest, empty_digest)
            return 1

    name = args.test_name or "successor10_{}_{}_{}".format(os.getpid(), time.time_ns(), secrets.token_hex(4))
    if not SAFE_NAME.fullmatch(name):
        say("FAIL", "target_policy_invalid", 1, empty_digest, empty_digest)
        return 1
    try:
        home_stat = avd_home.lstat()
        if stat.S_ISLNK(home_stat.st_mode) or not stat.S_ISDIR(home_stat.st_mode):
            reason = "avd_home_mode_invalid"
        elif stat.S_IMODE(home_stat.st_mode) != 0o700:
            reason = "avd_home_mode_invalid"
        elif (avd_home / (name + ".avd")).exists() or (avd_home / (name + ".ini")).exists():
            reason = "avd_name_exists"
        elif any(avd_home.iterdir()):
            reason = "avd_home_not_empty"
        else:
            reason = None
    except OSError:
        reason = "avd_home_mode_invalid"
    if reason:
        payload = build_evidence(mode, reason, 1, empty_digest, empty_digest, name, 0)
        atomic_json(evidence, payload)
        say("FAIL", reason, 1, empty_digest, empty_digest)
        return REASONS[reason]

    sdk_root = safe_resolve(args.sdk_root, directory=True)
    avdmanager_input = pathlib.Path(os.path.abspath(args.avdmanager))
    if sdk_root is None or avdmanager_input != sdk_root / "cmdline-tools/latest/bin/avdmanager":
        reason = "tool_unavailable"
    else:
        avdmanager = safe_resolve(avdmanager_input)
        try:
            avdmanager.relative_to(sdk_root)
        except (AttributeError, ValueError):
            avdmanager = None
        if avdmanager is None or not avdmanager.is_file() or not os.access(avdmanager, os.X_OK):
            reason = "tool_unavailable"
        else:
            package_xml = sdk_root / PACKAGE_PATH / "package.xml"
            try:
                package_stat = package_xml.lstat()
                reason = None if stat.S_ISREG(package_stat.st_mode) and os.access(package_xml, os.R_OK) else "package_unavailable"
            except OSError:
                reason = "package_unavailable"
    if reason:
        payload = build_evidence(mode, reason, 2, empty_digest, empty_digest, name, 0)
        atomic_json(evidence, payload)
        say("UNJUDGEABLE", reason, 2, empty_digest, empty_digest)
        return 2

    try:
        raw_dir.mkdir(mode=0o700)
        os.chmod(raw_dir, 0o700)
        task_home = raw_dir.parent / "avd-task-home"
        task_home.mkdir(mode=0o700)
        (task_home / "android-user").mkdir(mode=0o700)
    except OSError:
        payload = build_evidence(mode, "target_policy_invalid", 1, empty_digest, empty_digest, name, 0)
        atomic_json(evidence, payload)
        say("FAIL", "target_policy_invalid", 1, empty_digest, empty_digest)
        return 1

    env = sanitized_env(sdk_root, avd_home, task_home, raw_dir)
    files = {
        "device_stdout": raw_dir / "device.stdout",
        "device_stderr": raw_dir / "device.stderr",
        "create_stdout": raw_dir / "create.stdout",
        "create_stderr": raw_dir / "create.stderr",
    }
    tool_rc = 2
    duration_ms = 0
    reason = "create_failed"
    stdout_data = b""
    stderr_data = b""
    try:
        device_rc, device_timeout, elapsed = run_bounded(
            [str(avdmanager), "list", "device", "-c"],
            env,
            files["device_stdout"],
            files["device_stderr"],
            args.timeout,
        )
        duration_ms += elapsed
        device_stdout = files["device_stdout"].read_bytes()
        device_stderr = files["device_stderr"].read_bytes()
        stdout_data += device_stdout
        stderr_data += device_stderr
        if device_timeout:
            reason = "timeout"
            tool_rc = 124
        elif device_rc is None:
            reason = "tool_unavailable"
            tool_rc = 2
        elif device_rc != 0:
            reason = classify_stderr(device_stderr)
            tool_rc = device_rc
        else:
            try:
                devices = {line.strip() for line in device_stdout.decode("utf-8", errors="strict").splitlines() if line.strip()}
            except UnicodeError:
                devices = set()
            if DEVICE_PROFILE not in devices:
                reason = "device_profile_unavailable"
                tool_rc = 2
            else:
                create_rc, create_timeout, elapsed = run_bounded(
                    [
                        str(avdmanager),
                        "create",
                        "avd",
                        "--force",
                        "--name",
                        name,
                        "--package",
                        PACKAGE_ID,
                        "--device",
                        DEVICE_PROFILE,
                    ],
                    env,
                    files["create_stdout"],
                    files["create_stderr"],
                    args.timeout,
                )
                duration_ms += elapsed
                create_stdout = files["create_stdout"].read_bytes()
                create_stderr = files["create_stderr"].read_bytes()
                stdout_data += create_stdout
                stderr_data += create_stderr
                if create_timeout:
                    reason = "timeout"
                    tool_rc = 124
                elif create_rc is None:
                    reason = "tool_unavailable"
                    tool_rc = 2
                elif create_rc != 0:
                    reason = classify_stderr(create_stderr)
                    tool_rc = create_rc
                else:
                    tool_rc = 0
                    avd_dir = avd_home / (name + ".avd")
                    if avd_dir.is_symlink() or not avd_dir.is_dir():
                        reason = "avd_directory_missing"
                    else:
                        config = parse_config(avd_dir / "config.ini")
                        if config is None:
                            reason = "avd_directory_missing"
                        elif config.get("hw.device.name") != DEVICE_PROFILE:
                            reason = "device_profile_mismatch"
                        elif (
                            config.get("image.sysdir.1", "").rstrip("/") != str(PACKAGE_PATH)
                            or config.get("abi.type") != "arm64-v8a"
                            or config.get("tag.id") != "google_apis"
                        ):
                            reason = "package_mismatch"
                        else:
                            reason = "created"
    except OSError:
        reason = "tool_unavailable"
        tool_rc = 2

    stdout_digest = hash_bytes(stdout_data)
    stderr_digest = hash_bytes(stderr_data)
    for path in files.values():
        try:
            path.unlink()
        except FileNotFoundError:
            pass
        except OSError:
            reason = "target_policy_invalid"
            tool_rc = 1
    try:
        raw_dir.rmdir()
    except OSError:
        reason = "target_policy_invalid"
        tool_rc = 1
    try:
        shutil.rmtree(task_home)
    except OSError:
        reason = "target_policy_invalid"
        tool_rc = 1
    payload = build_evidence(mode, reason, tool_rc, stdout_digest, stderr_digest, name, duration_ms)
    atomic_json(evidence, payload)
    if reason == "created":
        atomic_name(name_file, name)
    outcome = REASONS[reason]
    say("PASS" if outcome == 0 else "FAIL" if outcome == 1 else "UNJUDGEABLE", reason, tool_rc, stdout_digest, stderr_digest)
    return outcome


def verify(args):
    evidence = pathlib.Path(args.evidence)
    if not evidence.exists() and not evidence.is_symlink():
        print("UNJUDGEABLE baseline-bundle-successor10-avd: evidence missing", file=sys.stderr)
        return 2
    try:
        evidence_stat = evidence.lstat()
        if stat.S_ISLNK(evidence_stat.st_mode) or not stat.S_ISREG(evidence_stat.st_mode):
            return 1
        if stat.S_IMODE(evidence_stat.st_mode) != 0o600:
            return 1
        payload = json.loads(evidence.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError):
        return 1
    if set(payload) != EVIDENCE_KEYS:
        return 1
    reason = payload.get("reason_code")
    if payload.get("schema_version") != SCHEMA or payload.get("mode") != args.mode or reason not in REASONS:
        return 1
    if payload.get("verdict_exit") != REASONS[reason]:
        return 1
    if payload.get("command_contract_sha256") != CONTRACT_SHA256:
        return 1
    if payload.get("package_id") != PACKAGE_ID or payload.get("device_profile") != DEVICE_PROFILE:
        return 1
    if payload.get("avd_home_mode") != "0700" or payload.get("raw_cleaned") is not True:
        return 1
    if payload.get("created") is not (reason == "created"):
        return 1
    if not all(DIGEST.fullmatch(str(payload.get(key, ""))) for key in ("stdout_sha256", "stderr_sha256", "avd_name_sha256")):
        return 1
    if not isinstance(payload.get("avdmanager_rc"), int) or not isinstance(payload.get("duration_ms"), int):
        return 1
    print("SUCCESSOR10_AVD_EVIDENCE reason_code={} verdict_exit={} digests=true raw_cleaned=true mode0600=true".format(reason, REASONS[reason]))
    return 0


def main():
    parser = argparse.ArgumentParser(add_help=False)
    subparsers = parser.add_subparsers(dest="command", required=True)
    create_parser = subparsers.add_parser("create", add_help=False)
    create_parser.add_argument("--repo-root", required=True)
    create_parser.add_argument("--sdk-root", required=True)
    create_parser.add_argument("--avdmanager", required=True)
    create_parser.add_argument("--avd-home", required=True)
    create_parser.add_argument("--evidence", required=True)
    create_parser.add_argument("--name-file", required=True)
    create_parser.add_argument("--raw-dir", required=True)
    create_parser.add_argument("--timeout", type=int, required=True)
    create_parser.add_argument("--mode", choices=("production", "fixture"), required=True)
    create_parser.add_argument("--test-name")
    verify_parser = subparsers.add_parser("verify", add_help=False)
    verify_parser.add_argument("--evidence", required=True)
    verify_parser.add_argument("--mode", choices=("production", "fixture"), required=True)
    args = parser.parse_args()
    if args.command == "create":
        if args.timeout < 1 or args.timeout > 120:
            print("FAIL baseline-bundle-successor10-avd: invalid timeout", file=sys.stderr)
            raise SystemExit(1)
        raise SystemExit(create(args))
    raise SystemExit(verify(args))


if __name__ == "__main__":
    main()
