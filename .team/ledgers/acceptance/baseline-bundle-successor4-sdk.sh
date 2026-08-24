#!/bin/sh
# //! purpose: 无值输出地核对当前 WT 的 Android SDK 与非版本化 local.properties 前置。
# //! contract: 0=前置可用且成功时无输出；1=工作区交付违反不提交约束；2=环境或量具不可判。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
fail() { printf '%s\n' "FAIL baseline-bundle-successor4-sdk: $*" >&2; exit 1; }
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-successor4-sdk: $*" >&2; exit 2; }

script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
repo_root=$(CDPATH='' cd "$script_dir/../../.." 2>/dev/null && pwd) || unjudgeable "cannot resolve repository root"
command -v git >/dev/null 2>&1 || unjudgeable "git unavailable"
command -v sed >/dev/null 2>&1 || unjudgeable "sed unavailable"
command -v find >/dev/null 2>&1 || unjudgeable "find unavailable"

sdk_root=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
[ -n "$sdk_root" ] || unjudgeable "Android SDK environment missing"
[ -d "$sdk_root" ] && [ -r "$sdk_root" ] || unjudgeable "Android SDK environment directory unavailable"

local_properties="$repo_root/app/local.properties"
[ -e "$local_properties" ] || unjudgeable "app/local.properties missing"
[ -r "$local_properties" ] && [ -s "$local_properties" ] || unjudgeable "app/local.properties unavailable"
git -C "$repo_root" ls-files --error-unmatch app/local.properties >/dev/null 2>&1 && fail "app/local.properties must remain untracked"
sdk_dir=$(sed -n 's/^sdk\.dir=//p' "$local_properties" 2>/dev/null | sed -n '1p')
[ -n "$sdk_dir" ] || unjudgeable "sdk.dir missing"
[ -d "$sdk_dir" ] && [ -r "$sdk_dir" ] || unjudgeable "sdk.dir directory unavailable"
[ "$sdk_dir" = "$sdk_root" ] || unjudgeable "sdk.dir does not match selected SDK environment"

apksigner=$(find "$sdk_dir/build-tools" -type f -name apksigner -perm -111 -print 2>/dev/null | sort | tail -n 1)
aapt=$(find "$sdk_dir/build-tools" -type f -name aapt -perm -111 -print 2>/dev/null | sort | tail -n 1)
[ -n "$apksigner" ] && [ -x "$apksigner" ] || unjudgeable "apksigner unavailable"
[ -n "$aapt" ] && [ -x "$aapt" ] || unjudgeable "aapt unavailable"
exit 0
