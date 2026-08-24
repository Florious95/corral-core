#!/bin/sh
# //! purpose: 便宜核对 baseline-bundle 并行红测设计覆盖完整。
# //! contract: 0=RED.md 完整；1=交付失败；2=目录/工具不可读。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
fail() { printf '%s\n' "FAIL baseline-bundle-test: $*" >&2; exit 1; }
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-test: $*" >&2; exit 2; }
script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
repo_root=$(CDPATH='' cd "$script_dir/../../.." 2>/dev/null && pwd) || unjudgeable "cannot resolve repository root"
f="$repo_root/.team/nodes/baseline-bundle-test/RED.md"
[ -e "$f" ] || fail "missing RED.md"
[ -r "$f" ] || unjudgeable "RED.md unreadable"
[ -s "$f" ] || fail "RED.md empty"
command -v grep >/dev/null 2>&1 || unjudgeable "grep unavailable"
for word in BaselineBundleMissingArtifactDeadlockTest BaselineBundleExactRecoveryTest BaselineBundleA2EquivalenceTest BaselineBundleArchiveRestoreTest BaselineBundleMigrationPrecheckTest --rerun-tasks --no-build-cache -count=1 'envcheck.sh --gate' 'exit 2'; do
    grep -F -e "$word" "$f" >/dev/null 2>&1 || fail "RED.md missing $word"
done
printf '%s\n' "PASS baseline-bundle-test: red-test design present"

