#!/bin/sh
# //! purpose: 永久锁定 SDK fallback 的白名单解析、环境优先、静默最小生成、0600 与不入 Git 四态。
# //! contract: 0=控制与破坏齿全符合；1=错误放行/错误形状；2=源 SDK、工具或量具不可判。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
fail() { printf '%s\n' "FAIL baseline-bundle-successor5-sdk-regression: $*" >&2; exit 1; }
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-successor5-sdk-regression: $*" >&2; exit 2; }

script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
repo_root=$(CDPATH='' cd "$script_dir/../../.." 2>/dev/null && pwd) || unjudgeable "cannot resolve repository root"
helper="$script_dir/baseline-bundle-successor5-sdk.py"
wrapper="$script_dir/baseline-bundle-successor5-sdk.sh"
scratch="$repo_root/.team/nodes/baseline-bundle-sdk-teeth/case-$$"
seed="$scratch/seed.properties"

for command_name in git python3 sed wc stat; do
    command -v "$command_name" >/dev/null 2>&1 || unjudgeable "required tool unavailable"
done
[ -r "$helper" ] && [ -s "$helper" ] || unjudgeable "SDK helper unavailable"
[ -r "$wrapper" ] && [ -s "$wrapper" ] || unjudgeable "SDK wrapper unavailable"
mkdir -p "$scratch" || unjudgeable "cannot create node-local scratch"

common_dir=$(git -C "$repo_root" rev-parse --path-format=absolute --git-common-dir 2>/dev/null) || unjudgeable "cannot resolve Git common directory"
source_root=$(CDPATH='' cd "$common_dir/.." 2>/dev/null && pwd) || unjudgeable "cannot resolve source repository"
source_properties="$source_root/app/local.properties"
[ -r "$source_properties" ] && [ -s "$source_properties" ] || unjudgeable "source app/local.properties unavailable"
python3 "$helper" --source-properties "$source_properties" --target-properties "$seed" >"$scratch/seed.out" 2>&1
seed_rc=$?
case "$seed_rc" in 0) ;; 2) exit 2 ;; *) unjudgeable "cannot establish safe SDK seed" ;; esac
[ "$(wc -c < "$scratch/seed.out" | tr -d ' ')" = 0 ] || fail "safe seed emitted output"
sdk_dir=$(sed -n 's/^sdk\.dir=//p' "$seed" | sed -n '1p')
[ -n "$sdk_dir" ] && [ -d "$sdk_dir" ] || unjudgeable "safe SDK seed invalid"

make_repo() {
    fixture_root=$1
    mkdir -p "$fixture_root/.team/ledgers/acceptance" "$fixture_root/app" || unjudgeable "cannot create fixture repository"
    cp "$helper" "$fixture_root/.team/ledgers/acceptance/" || unjudgeable "cannot stage helper fixture"
    cp "$wrapper" "$fixture_root/.team/ledgers/acceptance/" || unjudgeable "cannot stage wrapper fixture"
    git -C "$fixture_root" init -q >/dev/null 2>&1 || unjudgeable "cannot initialize fixture repository"
}

good="$scratch/good"
make_repo "$good"
cp "$seed" "$good/app/local.properties" || unjudgeable "cannot stage good source"
env -u ANDROID_SDK_ROOT -u ANDROID_HOME sh "$good/.team/ledgers/acceptance/baseline-bundle-successor5-sdk.sh" >"$scratch/good.out" 2>&1
good_rc=$?
[ "$good_rc" = 0 ] || fail "safe repository fallback did not pass"
[ "$(wc -c < "$scratch/good.out" | tr -d ' ')" = 0 ] || fail "safe repository fallback emitted output"
[ "$(sed -n '$=' "$good/app/local.properties")" = 1 ] || fail "fallback target is not minimal"
[ "$(stat -f '%Lp' "$good/app/local.properties" 2>/dev/null)" = 600 ] || fail "fallback target is not 0600"
git -C "$good" ls-files --error-unmatch app/local.properties >/dev/null 2>&1 && fail "fallback target entered Git"

extra="$scratch/extra"
make_repo "$extra"
sed -n '1p' "$seed" > "$extra/app/local.properties"
printf '%s\n' 'unexpected.key=redacted' >> "$extra/app/local.properties"
env -u ANDROID_SDK_ROOT -u ANDROID_HOME sh "$extra/.team/ledgers/acceptance/baseline-bundle-successor5-sdk.sh" >"$scratch/extra.out" 2>&1
[ "$?" = 2 ] || fail "extra key was not classified unjudgeable"

duplicate="$scratch/duplicate"
make_repo "$duplicate"
sed -n '1p' "$seed" > "$duplicate/app/local.properties"
sed -n '1p' "$seed" >> "$duplicate/app/local.properties"
env -u ANDROID_SDK_ROOT -u ANDROID_HOME sh "$duplicate/.team/ledgers/acceptance/baseline-bundle-successor5-sdk.sh" >"$scratch/duplicate.out" 2>&1
[ "$?" = 2 ] || fail "duplicate sdk.dir was not classified unjudgeable"

invalid="$scratch/invalid"
make_repo "$invalid"
printf '%s\n' "sdk.dir=$invalid/no-such-sdk" > "$invalid/app/local.properties"
env -u ANDROID_SDK_ROOT -u ANDROID_HOME sh "$invalid/.team/ledgers/acceptance/baseline-bundle-successor5-sdk.sh" >"$scratch/invalid.out" 2>&1
[ "$?" = 2 ] || fail "invalid SDK directory was not classified unjudgeable"

tracked="$scratch/tracked"
make_repo "$tracked"
cp "$seed" "$tracked/app/local.properties" || unjudgeable "cannot stage tracked source"
git -C "$tracked" add -f app/local.properties >/dev/null 2>&1 || unjudgeable "cannot stage tracked tooth"
env -u ANDROID_SDK_ROOT -u ANDROID_HOME sh "$tracked/.team/ledgers/acceptance/baseline-bundle-successor5-sdk.sh" >"$scratch/tracked.out" 2>&1
[ "$?" = 1 ] || fail "tracked target was not rejected"

env_first="$scratch/env-first"
make_repo "$env_first"
printf '%s\n' 'unexpected.key=redacted' > "$env_first/app/local.properties"
ANDROID_SDK_ROOT="$sdk_dir" ANDROID_HOME='' sh "$env_first/.team/ledgers/acceptance/baseline-bundle-successor5-sdk.sh" >"$scratch/env-first.out" 2>&1
env_first_rc=$?
[ "$env_first_rc" = 0 ] || fail "valid environment did not take priority"
[ "$(wc -c < "$scratch/env-first.out" | tr -d ' ')" = 0 ] || fail "environment priority emitted output"

invalid_env="$scratch/invalid-env"
make_repo "$invalid_env"
cp "$seed" "$invalid_env/app/local.properties" || unjudgeable "cannot stage invalid-env source"
ANDROID_SDK_ROOT="$invalid_env/no-such-sdk" ANDROID_HOME='' sh "$invalid_env/.team/ledgers/acceptance/baseline-bundle-successor5-sdk.sh" >"$scratch/invalid-env.out" 2>&1
invalid_env_rc=$?
[ "$invalid_env_rc" = 0 ] || fail "invalid environment did not fall back to safe source"
[ "$(wc -c < "$scratch/invalid-env.out" | tr -d ' ')" = 0 ] || fail "invalid-environment fallback emitted output"

printf '%s\n' "PASS baseline-bundle-successor5-sdk-regression: fallback controls and four-state teeth verified"
