#!/bin/sh
# //! purpose: 优先用有效 SDK 环境，否则从仓根白名单解析唯一 sdk.dir，静默生成目标 WT 的最小 local.properties。
# //! contract: 0=生成文件仅含 sdk.dir、0600且未入 Git；1=目标文件已入 Git；2=环境、源文件、目录或量具不可判。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
fail() { printf '%s\n' "FAIL baseline-bundle-successor5-sdk: $*" >&2; exit 1; }
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-successor5-sdk: $*" >&2; exit 2; }

script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
target_root=$(CDPATH='' cd "$script_dir/../../.." 2>/dev/null && pwd) || unjudgeable "cannot resolve target repository"
helper="$script_dir/baseline-bundle-successor5-sdk.py"
target_properties="$target_root/app/local.properties"

command -v git >/dev/null 2>&1 || unjudgeable "git unavailable"
command -v python3 >/dev/null 2>&1 || unjudgeable "python3 unavailable"
command -v sed >/dev/null 2>&1 || unjudgeable "sed unavailable"
command -v grep >/dev/null 2>&1 || unjudgeable "grep unavailable"
[ -r "$helper" ] && [ -s "$helper" ] || unjudgeable "SDK helper unavailable"
git_root=$(git -C "$target_root" rev-parse --show-toplevel 2>/dev/null) || unjudgeable "target is not a Git worktree"
[ "$git_root" = "$target_root" ] || fail "target repository root mismatch"

common_dir=$(git -C "$target_root" rev-parse --path-format=absolute --git-common-dir 2>/dev/null) || unjudgeable "cannot resolve Git common directory"
source_root=$(CDPATH='' cd "$common_dir/.." 2>/dev/null && pwd) || unjudgeable "cannot resolve source repository"
source_properties="$source_root/app/local.properties"

selected_sdk=
if [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "$ANDROID_SDK_ROOT" ] && [ -r "$ANDROID_SDK_ROOT" ] && [ -x "$ANDROID_SDK_ROOT" ]; then
    selected_sdk=$ANDROID_SDK_ROOT
elif [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME" ] && [ -r "$ANDROID_HOME" ] && [ -x "$ANDROID_HOME" ]; then
    selected_sdk=$ANDROID_HOME
fi

if [ -n "$selected_sdk" ]; then
    python3 "$helper" --sdk-dir "$selected_sdk" --target-properties "$target_properties"
else
    [ -e "$source_properties" ] || unjudgeable "source app/local.properties missing"
    [ -r "$source_properties" ] && [ -s "$source_properties" ] || unjudgeable "source app/local.properties unavailable"
    python3 "$helper" --source-properties "$source_properties" --target-properties "$target_properties"
fi
helper_rc=$?
case "$helper_rc" in 0) ;; 1) exit 1 ;; 2) exit 2 ;; *) unjudgeable "SDK helper returned unsupported status" ;; esac

[ -r "$target_properties" ] && [ -s "$target_properties" ] || unjudgeable "target app/local.properties unavailable"
line_count=$(sed -n '$=' "$target_properties" 2>/dev/null)
[ "$line_count" = 1 ] || fail "target app/local.properties is not minimal"
grep -E '^sdk\.dir=[^[:space:]].*$' "$target_properties" >/dev/null 2>&1 || fail "target app/local.properties is malformed"
mode=$(stat -f '%Lp' "$target_properties" 2>/dev/null) || unjudgeable "cannot inspect target permissions"
[ "$mode" = 600 ] || fail "target app/local.properties permissions are not 0600"
git -C "$target_root" ls-files --error-unmatch app/local.properties >/dev/null 2>&1 && fail "app/local.properties must remain untracked"
exit 0
