#!/bin/sh
# //! purpose: 机械验证 tmux socket 在 fork 前过滤，同时保留用户 Agent CLI 发现阳性臂。
# //! contract:
# //!   provides: scan-filter 四态机械判据
# //!   requires: server/internal/discovery/scan_filter_test.go 的聚焦隔离夹具
# //! boundary: 不连接真实 tmux/生产 daemon；只跑 repo-local spy 测试；go test 禁缓存
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u

fail() {
    printf '%s\n' "FAIL scan-filter: $*" >&2
    exit 1
}

unjudgeable() {
    printf '%s\n' "UNJUDGEABLE scan-filter: $*" >&2
    exit 2
}

script_dir=$(CDPATH= cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
repo_root=$(CDPATH= cd "$script_dir/../../.." 2>/dev/null && pwd) || unjudgeable "cannot resolve repository root"
server_root="$repo_root/server"
test_file="$server_root/internal/discovery/scan_filter_test.go"
fixture_root="$repo_root/.team/nodes/scan-filter-impl/tmp"

command -v go >/dev/null 2>&1 || unjudgeable "go toolchain is unavailable"
[ -f "$server_root/go.mod" ] || unjudgeable "server/go.mod is unavailable"
[ -f "$test_file" ] || fail "missing required focused test: server/internal/discovery/scan_filter_test.go"
grep -F 'func TestDiscoverScanFilterCommandBoundary' "$test_file" >/dev/null 2>&1 || fail "missing exact focused test TestDiscoverScanFilterCommandBoundary"
grep -F 'SCAN_FILTER_FIXTURE_ROOT' "$test_file" >/dev/null 2>&1 || fail "focused test does not bind its fixture to the repo-local root"
if grep -E '(/usr/bin/tmux|/opt/[^" ]*/tmux)' "$test_file" >/dev/null 2>&1; then
    fail "focused test contains an absolute system tmux path"
fi

mkdir -p "$fixture_root" 2>/dev/null || unjudgeable "cannot create repo-local fixture root"
write_probe="$fixture_root/.acceptance-write-$$"
(umask 077 && : > "$write_probe") 2>/dev/null || unjudgeable "repo-local fixture root is not writable"
rm -f "$write_probe" 2>/dev/null || unjudgeable "cannot clean fixture write probe"

# Fail closed around the focused test: its own PATH spy must handle every
# intended tmux call. Any fall-through lands here, is recorded, and cannot
# reach the host tmux binary.
guard_dir="$fixture_root/outer-guard-$$"
guard_cmd="$guard_dir/tmux"
guard_log="$guard_dir/argv.log"
mkdir "$guard_dir" 2>/dev/null || unjudgeable "cannot create outer tmux guard"
cleanup_guard() {
    rm -f "$guard_log" "$guard_cmd" 2>/dev/null || :
    rmdir "$guard_dir" 2>/dev/null || :
}
trap cleanup_guard 0 1 2 3 15
printf '%s\n' '#!/bin/sh' 'printf "%s\\n" "$*" >> "$SCAN_FILTER_OUTER_GUARD_LOG"' 'exit 97' > "$guard_cmd" \
    || unjudgeable "cannot write outer tmux guard"
chmod 700 "$guard_cmd" 2>/dev/null || unjudgeable "cannot make outer tmux guard executable"

test_output=$(
    cd "$server_root" || exit 2
    PATH="$guard_dir:${PATH:-}" \
        SCAN_FILTER_OUTER_GUARD_LOG="$guard_log" \
        SCAN_FILTER_FIXTURE_ROOT="$fixture_root" \
        go test -count=1 -run '^TestDiscoverScanFilterCommandBoundary$' -v ./internal/discovery 2>&1
)
test_rc=$?
printf '%s\n' "$test_output"

case "$test_rc" in
    0) ;;
    1) fail "focused Go test failed" ;;
    *) unjudgeable "focused Go test could not execute (exit $test_rc)" ;;
esac
[ ! -s "$guard_log" ] || fail "focused test escaped its spy and attempted host tmux: $(sed -n '1p' "$guard_log")"

printf '%s\n' "$test_output" | grep -F '=== RUN   TestDiscoverScanFilterCommandBoundary' >/dev/null 2>&1 \
    || fail "go test exited 0 without running the exact focused test"
printf '%s\n' "$test_output" | grep -F -e '--- PASS: TestDiscoverScanFilterCommandBoundary' >/dev/null 2>&1 \
    || fail "exact focused test did not report PASS"

evidence='SCAN_FILTER_EVIDENCE default_list_panes=1 tmux_env_list_panes=1 ta_list_panes=0 isolated_list_panes=0 unknown_list_panes=0 other_uid_list_panes=0 user_agents_found=2 spy_argv_recorded=true'
classification='SCAN_FILTER_CLASSIFICATION_EVIDENCE ta=skip isolated=skip unknown=skip other_uid=skip path_operand=true classification_operand=true'
printf '%s\n' "$test_output" | grep -F "$evidence" >/dev/null 2>&1 \
    || fail "missing exact spy argv/count evidence"
printf '%s\n' "$test_output" | grep -F "$classification" >/dev/null 2>&1 \
    || fail "missing exact classification operand evidence"

printf '%s\n' "PASS scan-filter: user discovery preserved; forbidden list-panes calls are zero"
exit 0
