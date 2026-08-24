#!/bin/sh
# //! purpose: 便宜核对 baseline-bundle 等价/归档/迁移探针与破坏齿。
# //! contract: 0=PROBE.md 完整；1=交付失败；2=目录/工具不可读。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
fail() { printf '%s\n' "FAIL baseline-bundle-probe: $*" >&2; exit 1; }
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-probe: $*" >&2; exit 2; }
script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
repo_root=$(CDPATH='' cd "$script_dir/../../.." 2>/dev/null && pwd) || unjudgeable "cannot resolve repository root"
f="$repo_root/.team/nodes/baseline-bundle-probe/PROBE.md"
[ -e "$f" ] || fail "missing PROBE.md"
[ -r "$f" ] || unjudgeable "PROBE.md unreadable"
[ -s "$f" ] || fail "PROBE.md empty"
command -v grep >/dev/null 2>&1 || unjudgeable "grep unavailable"
for word in source_tree_sha256 normalized_runtime_sha256 signer_certificate_sha256 recover_exact_artifact rebaseline_with_equivalence_proof primary backup inode classes.dex 1.1001 破坏齿; do
    grep -F "$word" "$f" >/dev/null 2>&1 || fail "PROBE.md missing $word"
done
printf '%s\n' "PASS baseline-bundle-probe: independent operands and mutation properties present"

