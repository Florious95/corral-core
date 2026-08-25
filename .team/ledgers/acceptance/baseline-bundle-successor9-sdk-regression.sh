#!/bin/sh
# //! purpose: 永久锁定 successor9 SDK 唯一选择、错根降权、target白名单与值零泄露破坏齿。
# //! contract: 0=全部控制/破坏齿符合；1=错误放行、错误分流或值泄露；2=回归量具不可判。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
umask 077
fail() { printf '%s\n' "FAIL baseline-bundle-successor9-sdk-regression: $*" >&2; exit 1; }
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-successor9-sdk-regression: $*" >&2; exit 2; }

script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
repo_root=$(CDPATH='' cd "$script_dir/../../.." 2>/dev/null && pwd) || unjudgeable "cannot resolve repository root"
selector="$script_dir/baseline-bundle-successor9-sdk-selector.sh"
helper="$script_dir/baseline-bundle-successor9-sdk-selector.py"
scratch="$repo_root/.team/nodes/spec-sol/baseline-bundle-successor9/tmp/sdk-regression-$$"
original_path=${PATH:-}
secret_marker='S9_SECRET_VALUE_MUST_NOT_LEAK'

for tool in git python3 sed grep wc stat chmod mkdir rm; do command -v "$tool" >/dev/null 2>&1 || unjudgeable "$tool unavailable"; done
[ -r "$selector" ] && [ -s "$selector" ] || unjudgeable "selector wrapper unavailable"
[ -r "$helper" ] && [ -s "$helper" ] || unjudgeable "selector helper unavailable"
mkdir -p "$scratch" || unjudgeable "cannot create node-local scratch"
trap 'rm -rf "$scratch"' EXIT INT TERM HUP

make_repo() {
    case_root=$1
    mkdir -p "$case_root/app" || unjudgeable "cannot create fixture repository"
    printf '%s\n' '# safe untracked target' >"$case_root/app/local.properties"
    git -C "$case_root" init -q >/dev/null 2>&1 || unjudgeable "cannot initialize fixture repository"
}

make_sdk() {
    sdk_root=$1
    mkdir -p "$sdk_root/platform-tools" "$sdk_root/emulator" "$sdk_root/cmdline-tools/latest/bin" \
        "$sdk_root/system-images/android-35/google_apis/arm64-v8a" || unjudgeable "cannot create SDK fixture"
    printf '%s\n' '<localPackage path="system-images;android-35;google_apis;arm64-v8a" />' \
        >"$sdk_root/system-images/android-35/google_apis/arm64-v8a/package.xml"
    for executable in "$sdk_root/platform-tools/adb" "$sdk_root/emulator/emulator" "$sdk_root/cmdline-tools/latest/bin/avdmanager"; do
        printf '%s\n' '#!/bin/sh' 'exit 0' >"$executable"
        chmod 700 "$executable" || unjudgeable "cannot prepare SDK executable fixture"
    done
    cat >"$sdk_root/cmdline-tools/latest/bin/sdkmanager" <<'SH'
#!/bin/sh
fixture_root=$(CDPATH='' cd "$(dirname "$0")/../../.." 2>/dev/null && pwd) || exit 9
[ "$1" = "--sdk_root=$fixture_root" ] || exit 8
[ "$2" = "--list_installed" ] || exit 7
printf '%s\n' 'Installed packages:' 'Path | Version | Description' 'system-images;android-35;google_apis;arm64-v8a | 1 | fixture'
SH
    chmod 700 "$sdk_root/cmdline-tools/latest/bin/sdkmanager" || unjudgeable "cannot prepare sdkmanager fixture"
}

run_selector() {
    case_repo=$1
    source_properties=$2
    sdkmanager=$3
    output=$4
    shift 4
    env SUCCESSOR9_TEST_MODE=1 \
        SUCCESSOR9_TEST_HARNESS=baseline-bundle-successor9-sdk-regression \
        SUCCESSOR9_TEST_REPO_ROOT="$case_repo" \
        SUCCESSOR9_TEST_SOURCE_PROPERTIES="$source_properties" \
        SUCCESSOR9_TEST_SDKMANAGER="$sdkmanager" \
        PATH="$original_path" "$@" sh "$selector" >"$output" 2>&1
}

assert_no_value_leak() {
    output=$1
    for forbidden in "$scratch" "$secret_marker" 'sdk.dir='; do
        grep -F "$forbidden" "$output" >/dev/null 2>&1 && fail "selector output leaked a protected value"
    done
}

# Same canonical/inode root arrives through env, root-local and sdkmanager.
same="$scratch/same-root-$secret_marker"
same_repo="$same/repo"
same_sdk="$same/sdk"
make_repo "$same_repo"
make_sdk "$same_sdk"
ln -s "$same_sdk" "$same/sdk-alias" || unjudgeable "cannot create inode alias fixture"
printf '%s\n' "sdk.dir=$same_sdk" >"$same/source.properties"
printf '%s\n' '# old two-line target' "sdk.dir=$same/sdk-alias" >"$same_repo/app/local.properties"
if ! run_selector "$same_repo" "$same/source.properties" "$same_sdk/cmdline-tools/latest/bin/sdkmanager" "$same/out" \
    ANDROID_SDK_ROOT="$same/sdk-alias" ANDROID_HOME="$same_sdk"; then
    fail "same-root canonical/inode control did not pass"
fi
assert_no_value_leak "$same/out"
[ "$(sed -n '$=' "$same_repo/app/local.properties")" = 1 ] || fail "two-line target was not minimized"
[ "$(stat -f '%Lp' "$same_repo/app/local.properties" 2>/dev/null)" = 600 ] || fail "target mode is not 0600"
[ "$(sed -n 's/^sdk\.dir=//p' "$same_repo/app/local.properties")" = "$same_sdk" ] || fail "same-root target selected wrong root"
git -C "$same_repo" ls-files --error-unmatch app/local.properties >/dev/null 2>&1 && fail "target entered Git"

# Diagnosis shape: root-local looks plausible but lacks package.xml; valid
# sdkmanager-derived root must win rather than being overwritten.
wrong="$scratch/wrong-root-$secret_marker"
wrong_repo="$wrong/repo"
wrong_sdk="$wrong/sdkmanager-root"
wrong_source="$wrong/source-root"
make_repo "$wrong_repo"
make_sdk "$wrong_sdk"
mkdir -p "$wrong_source/system-images/android-35/google_apis/arm64-v8a" || unjudgeable "cannot create wrong-root fixture"
printf '%s\n' "sdk.dir=$wrong_source" >"$wrong/source.properties"
if ! run_selector "$wrong_repo" "$wrong/source.properties" "$wrong_sdk/cmdline-tools/latest/bin/sdkmanager" "$wrong/out" \
    ANDROID_SDK_ROOT= ANDROID_HOME=; then
    fail "invalid source root overrode valid sdkmanager root"
fi
assert_no_value_leak "$wrong/out"
[ "$(sed -n 's/^sdk\.dir=//p' "$wrong_repo/app/local.properties")" = "$wrong_sdk" ] || fail "sdkmanager-derived root was not selected"

# Two distinct complete roots are ambiguous and therefore unjudgeable.
ambiguous="$scratch/two-valid-$secret_marker"
ambiguous_repo="$ambiguous/repo"
make_repo "$ambiguous_repo"
make_sdk "$ambiguous/sdk-a"
make_sdk "$ambiguous/sdk-b"
printf '%s\n' "sdk.dir=$ambiguous/sdk-a" >"$ambiguous/source.properties"
run_selector "$ambiguous_repo" "$ambiguous/source.properties" "$ambiguous/sdk-b/cmdline-tools/latest/bin/sdkmanager" "$ambiguous/out" \
    ANDROID_SDK_ROOT="$ambiguous/sdk-a" ANDROID_HOME=
[ "$?" = 2 ] || fail "two valid SDK roots were not classified unjudgeable"
assert_no_value_leak "$ambiguous/out"

# Escaped local.properties input is not interpreted as a path.
escaped="$scratch/escaped-$secret_marker"
escaped_repo="$escaped/repo"
make_repo "$escaped_repo"
printf '%s\n' "sdk.dir=$escaped/path\\ with-space" >"$escaped/source.properties"
run_selector "$escaped_repo" "$escaped/source.properties" "$escaped/missing-sdkmanager" "$escaped/out" \
    ANDROID_SDK_ROOT= ANDROID_HOME=
[ "$?" = 2 ] || fail "escaped sdk.dir was not rejected as unjudgeable"
assert_no_value_leak "$escaped/out"

# Existing unknown target keys are a policy contradiction, not an apparatus
# absence; the selector must not clobber them.
unknown="$scratch/unknown-key-$secret_marker"
unknown_repo="$unknown/repo"
make_repo "$unknown_repo"
make_sdk "$unknown/sdk"
printf '%s\n' "sdk.dir=$unknown/sdk" >"$unknown/source.properties"
printf '%s\n' 'unknown.key=protected' >"$unknown_repo/app/local.properties"
before_unknown=$(python3 - "$unknown_repo/app/local.properties" <<'PY'
import hashlib,pathlib,sys
print(hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest())
PY
)
run_selector "$unknown_repo" "$unknown/source.properties" "$unknown/sdk/cmdline-tools/latest/bin/sdkmanager" "$unknown/out" \
    ANDROID_SDK_ROOT= ANDROID_HOME=
[ "$?" = 1 ] || fail "unknown target key was not classified fail"
assert_no_value_leak "$unknown/out"
after_unknown=$(python3 - "$unknown_repo/app/local.properties" <<'PY'
import hashlib,pathlib,sys
print(hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest())
PY
)
[ "$before_unknown" = "$after_unknown" ] || fail "unknown-key target was modified"

# A tracked target is an immutable identity violation and must not be rewritten.
tracked="$scratch/tracked-$secret_marker"
tracked_repo="$tracked/repo"
make_repo "$tracked_repo"
make_sdk "$tracked/sdk"
printf '%s\n' "sdk.dir=$tracked/sdk" >"$tracked/source.properties"
printf '%s\n' "sdk.dir=$tracked/sdk" >"$tracked_repo/app/local.properties"
git -C "$tracked_repo" add -f app/local.properties >/dev/null 2>&1 || unjudgeable "cannot stage tracked target tooth"
run_selector "$tracked_repo" "$tracked/source.properties" "$tracked/sdk/cmdline-tools/latest/bin/sdkmanager" "$tracked/out" \
    ANDROID_SDK_ROOT= ANDROID_HOME=
[ "$?" = 1 ] || fail "tracked target was not classified fail"
assert_no_value_leak "$tracked/out"

# A symlink target is a fail-closed identity contradiction and its referent
# must remain untouched.
symlinked="$scratch/symlink-target-$secret_marker"
symlinked_repo="$symlinked/repo"
make_repo "$symlinked_repo"
make_sdk "$symlinked/sdk"
printf '%s\n' "sdk.dir=$symlinked/sdk" >"$symlinked/source.properties"
rm "$symlinked_repo/app/local.properties"
printf '%s\n' 'referent-must-not-change' >"$symlinked/referent"
ln -s "$symlinked/referent" "$symlinked_repo/app/local.properties" || unjudgeable "cannot prepare symlink target tooth"
run_selector "$symlinked_repo" "$symlinked/source.properties" "$symlinked/sdk/cmdline-tools/latest/bin/sdkmanager" "$symlinked/out" \
    ANDROID_SDK_ROOT= ANDROID_HOME=
symlinked_rc=$?
[ "$symlinked_rc" = 1 ] || fail "symlink target was not classified fail"
assert_no_value_leak "$symlinked/out"
[ "$(sed -n '1p' "$symlinked/referent")" = referent-must-not-change ] || fail "symlink referent was modified"

# A missing target is apparatus absence, so it is unjudgeable and is not
# silently created.
missing="$scratch/missing-target-$secret_marker"
missing_repo="$missing/repo"
make_repo "$missing_repo"
make_sdk "$missing/sdk"
printf '%s\n' "sdk.dir=$missing/sdk" >"$missing/source.properties"
rm "$missing_repo/app/local.properties"
run_selector "$missing_repo" "$missing/source.properties" "$missing/sdk/cmdline-tools/latest/bin/sdkmanager" "$missing/out" \
    ANDROID_SDK_ROOT= ANDROID_HOME=
missing_rc=$?
[ "$missing_rc" = 2 ] || fail "missing target was not classified unjudgeable"
assert_no_value_leak "$missing/out"
[ ! -e "$missing_repo/app/local.properties" ] || fail "missing target was silently created"

# sdkmanager reporting the wrong package invalidates the otherwise complete root.
wrong_package="$scratch/wrong-package-$secret_marker"
wrong_package_repo="$wrong_package/repo"
make_repo "$wrong_package_repo"
make_sdk "$wrong_package/sdk"
sed 's/system-images;android-35;google_apis;arm64-v8a/system-images;android-34;google_apis;arm64-v8a/' \
    "$wrong_package/sdk/cmdline-tools/latest/bin/sdkmanager" >"$wrong_package/sdkmanager.changed"
mv "$wrong_package/sdkmanager.changed" "$wrong_package/sdk/cmdline-tools/latest/bin/sdkmanager"
chmod 700 "$wrong_package/sdk/cmdline-tools/latest/bin/sdkmanager"
printf '%s\n' "sdk.dir=$wrong_package/sdk" >"$wrong_package/source.properties"
run_selector "$wrong_package_repo" "$wrong_package/source.properties" "$wrong_package/sdk/cmdline-tools/latest/bin/sdkmanager" "$wrong_package/out" \
    ANDROID_SDK_ROOT= ANDROID_HOME=
[ "$?" = 2 ] || fail "wrong sdkmanager package report was not unjudgeable"
assert_no_value_leak "$wrong_package/out"

printf '%s\n' "PASS baseline-bundle-successor9-sdk-regression: same-root wrong-root ambiguity escaping two-line unknown-key tracked symlink missing exact-package no-leak"
