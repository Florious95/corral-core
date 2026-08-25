#!/bin/sh
# //! purpose: 静默汇合环境、仓根 local.properties 与 PATH sdkmanager，唯一选出固定 image 可执行 SDK root。
# //! contract: 0=唯一 root 且目标原子收敛为单行0600未跟踪；1=目标策略/身份矛盾；2=环境、工具、零根或多根不可判。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
umask 077
fail() { printf '%s\n' "FAIL baseline-bundle-successor9-sdk-selector: $*" >&2; exit 1; }
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-successor9-sdk-selector: $*" >&2; exit 2; }

script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
helper="$script_dir/baseline-bundle-successor9-sdk-selector.py"
for tool in env awk git python3; do command -v "$tool" >/dev/null 2>&1 || unjudgeable "$tool unavailable"; done
[ -r "$helper" ] && [ -s "$helper" ] || unjudgeable "selector helper unavailable"

test_count=$(env | awk -F= '/^SUCCESSOR9_TEST_[A-Za-z0-9_]*=/{count++} END{print count+0}')
case "${SUCCESSOR9_TEST_MODE:-}" in
    '')
        [ "$test_count" -eq 0 ] || fail "test override present in production"
        repo_root=$(git -C "${PWD:-.}" rev-parse --show-toplevel 2>/dev/null) || unjudgeable "cannot resolve target worktree"
        common_dir=$(git -C "$repo_root" rev-parse --path-format=absolute --git-common-dir 2>/dev/null) || unjudgeable "cannot resolve Git common directory"
        source_root=$(CDPATH='' cd "$common_dir/.." 2>/dev/null && pwd) || unjudgeable "cannot resolve source repository"
        source_properties="$source_root/app/local.properties"
        sdkmanager_executable=$(command -v sdkmanager 2>/dev/null || true)
        ;;
    1)
        [ "${SUCCESSOR9_TEST_HARNESS:-}" = baseline-bundle-successor9-sdk-regression ] || fail "isolated test harness marker missing"
        test_names=$(env | awk -F= '/^SUCCESSOR9_TEST_[A-Za-z0-9_]*=/{print $1}')
        for test_name in $test_names; do
            case "$test_name" in
                SUCCESSOR9_TEST_MODE|SUCCESSOR9_TEST_HARNESS|SUCCESSOR9_TEST_REPO_ROOT|SUCCESSOR9_TEST_SOURCE_PROPERTIES|SUCCESSOR9_TEST_SDKMANAGER) ;;
                *) fail "unknown isolated test override" ;;
            esac
        done
        repo_root=${SUCCESSOR9_TEST_REPO_ROOT:-}
        source_properties=${SUCCESSOR9_TEST_SOURCE_PROPERTIES:-}
        sdkmanager_executable=${SUCCESSOR9_TEST_SDKMANAGER:-}
        case "$repo_root/" in
            */.team/nodes/spec-sol/baseline-bundle-successor9/tmp/*/) ;;
            *) fail "isolated test repository escapes node-local tmp" ;;
        esac
        ;;
    *) fail "invalid test mode selector" ;;
esac

[ -n "$repo_root" ] && [ -d "$repo_root" ] || unjudgeable "target repository unavailable"
[ -n "$source_properties" ] || unjudgeable "source local.properties coordinate unavailable"
target_properties="$repo_root/app/local.properties"

set -- python3 "$helper" --repo-root "$repo_root" --source-properties "$source_properties" --target-properties "$target_properties"
if [ -n "$sdkmanager_executable" ]; then
    set -- "$@" --sdkmanager-executable "$sdkmanager_executable"
fi
"$@"
rc=$?
case "$rc" in 0|1|2) exit "$rc" ;; *) unjudgeable "selector helper returned unsupported status" ;; esac
