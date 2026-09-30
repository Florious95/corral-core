#!/bin/sh
# //! purpose: 机械验收 scan-filter 分类边界根因探针产物。
# //! contract: 0=产物非空且锚点齐全；1=产物缺失或内容不合格；2=目录/量具不可判。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u

fail() {
    printf '%s\n' "FAIL scan-filter-probe: $*" >&2
    exit 1
}

unjudgeable() {
    printf '%s\n' "UNJUDGEABLE scan-filter-probe: $*" >&2
    exit 2
}

repo_root=$(pwd -P 2>/dev/null) || unjudgeable "cannot resolve worktree root"
artifact_dir="$repo_root/.team/nodes/scan-filter-probe"
artifact="$artifact_dir/PROBE.md"

[ -d "$artifact_dir" ] || fail "missing artifact directory: .team/nodes/scan-filter-probe"
[ -r "$artifact_dir" ] && [ -x "$artifact_dir" ] || unjudgeable "artifact directory is unreadable"
[ -f "$artifact" ] || fail "missing required artifact: .team/nodes/scan-filter-probe/PROBE.md"
[ -s "$artifact" ] || fail "required artifact is empty: .team/nodes/scan-filter-probe/PROBE.md"
[ -r "$artifact" ] || unjudgeable "required artifact is unreadable"
command -v grep >/dev/null 2>&1 || unjudgeable "grep is unavailable"

grep -F 'probeSocket' "$artifact" >/dev/null 2>&1 \
    || fail "PROBE.md lacks probeSocket"
grep -F 'list-panes' "$artifact" >/dev/null 2>&1 \
    || fail "PROBE.md lacks list-panes"
grep -F 'ta-*' "$artifact" >/dev/null 2>&1 \
    || fail "PROBE.md lacks ta-*"

printf '%s\n' "PASS scan-filter-probe: PROBE.md is non-empty and required anchors are present"
exit 0
