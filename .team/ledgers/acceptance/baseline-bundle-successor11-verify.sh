#!/bin/sh
# //! purpose: 只消费同批已归档 apparatus、permanent fixture 与 fresh 0600 五件套完成 successor11 verify。
# //! contract: 0=归档安装/pm/清理与 fresh 证据合取；1=有效反证/伪造/权限错；2=资产、量具或事实不足。
# //! boundary: production 固定仓内路径；不查询或操作 cleanup 后的任何 live device/process。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u

fail() { printf '%s\n' "FAIL baseline-bundle-successor11-verify: $*" >&2; exit 1; }
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-successor11-verify: $*" >&2; exit 2; }

script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
helper="$script_dir/baseline-bundle-successor11-verify.py"
contract="$script_dir/fixtures/baseline-bundle-successor11/verify-contract.json"
contract_sha=b18a695dfdb597edbd0db1930567052e55b103726e6cc05a010076bfe7bfc12e
fixture_mode=${SUCCESSOR11_FIXTURE_MODE-}
command -v python3 >/dev/null 2>&1 || unjudgeable "python3 unavailable"

case "$fixture_mode" in
'')
    if [ "${SUCCESSOR11_TEST_ROOT+x}" = x ] || [ "${SUCCESSOR11_TEST_HARNESS+x}" = x ]; then
        fail "test override present in production"
    fi
    for tool in git shasum awk sh; do
        command -v "$tool" >/dev/null 2>&1 || unjudgeable "$tool unavailable"
    done
    repo_root=$(git -C "${PWD:-.}" rev-parse --show-toplevel 2>/dev/null) || unjudgeable "cannot resolve worktree"
    [ -r "$contract" ] && [ -s "$contract" ] || unjudgeable "fixed successor11 contract unavailable"
    actual_contract_sha=$(shasum -a 256 "$contract" 2>/dev/null | awk '{print $1}') || unjudgeable "cannot hash successor11 contract"
    [ "$actual_contract_sha" = "$contract_sha" ] || fail "fixed successor11 contract digest mismatch"
    mode=production
    ;;
1)
    [ "${SUCCESSOR11_TEST_HARNESS-}" = baseline-bundle-successor11-verify-regression ] || fail "fixture harness identity mismatch"
    [ -n "${SUCCESSOR11_TEST_ROOT-}" ] || fail "fixture root missing"
    main_repo=$(CDPATH='' cd "$script_dir/../../.." 2>/dev/null && pwd) || unjudgeable "cannot resolve main repository"
    repo_root=$(CDPATH='' cd "$SUCCESSOR11_TEST_ROOT" 2>/dev/null && pwd) || unjudgeable "fixture root unavailable"
    case "$repo_root/" in
        "$main_repo/.team/nodes/spec-sol/baseline-bundle-successor11/tmp/"*) ;;
        *) fail "fixture root escapes successor11 node-local temp" ;;
    esac
    mode=fixture
    ;;
*) fail "SUCCESSOR11_FIXTURE_MODE must be unset, empty, or exactly 1" ;;
esac

[ -r "$helper" ] && [ -s "$helper" ] || unjudgeable "successor11 validator unavailable"
python3 "$helper" --repo-root "$repo_root" --mode "$mode"
rc=$?
case "$rc" in 0) ;; 1) exit 1 ;; 2) exit 2 ;; *) unjudgeable "validator unsupported rc=$rc" ;; esac

if [ "$mode" = production ]; then
    output=$(sh "$script_dir/baseline-bundle-successor7-impl-bypass.sh" 2>&1)
    rc=$?
    printf '%s\n' "$output"
    case "$rc" in 0) ;; 1) exit 1 ;; 2) exit 2 ;; *) unjudgeable "permanent fixture gate unsupported rc=$rc" ;; esac
fi

printf '%s\n' "PASS baseline-bundle-successor11-verify: archived apparatus and permanent fixture support fresh private verify evidence"
