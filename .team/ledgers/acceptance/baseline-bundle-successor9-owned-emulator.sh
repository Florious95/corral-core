#!/bin/sh
# //! purpose: 在 successor7 owned-emulator 前先过严格环境闸，再注入 successor9 唯一已验证 SDK root，不泄露值。
# //! contract: 0=selector与原owned apparatus全绿；1=SDK目标策略或后链反证；2=环境/selector/apparatus不可判。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
umask 077
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-successor9-owned-emulator: $*" >&2; exit 2; }

script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
repo_root=$(git -C "${PWD:-.}" rev-parse --show-toplevel 2>/dev/null) || unjudgeable "cannot resolve worktree root"
envcheck="$repo_root/tools/perfbase/envcheck.sh"
selector="$script_dir/baseline-bundle-successor9-sdk-selector.sh"
owned="$script_dir/baseline-bundle-successor7-owned-emulator.sh"
for item in "$envcheck" "$selector" "$owned"; do [ -r "$item" ] && [ -s "$item" ] || unjudgeable "required apparatus input unavailable"; done

# SDK enumeration is deliberately after the first strict host gate.  The
# successor7 command performs the same gate again before any launch.
sh "$envcheck" --gate
gate_rc=$?
[ "$gate_rc" -eq 0 ] || unjudgeable "strict envcheck preflight unavailable"
sh "$selector"
selector_rc=$?
case "$selector_rc" in 0) ;; 1) exit 1 ;; 2) exit 2 ;; *) unjudgeable "SDK selector returned unsupported status" ;; esac

target="$repo_root/app/local.properties"
[ -r "$target" ] && [ -f "$target" ] && [ ! -L "$target" ] || unjudgeable "validated target local.properties unavailable"
line_count=$(sed -n '$=' "$target" 2>/dev/null) || unjudgeable "cannot inspect validated target"
[ "$line_count" = 1 ] || unjudgeable "validated target is not minimal"
selected_sdk=$(sed -n 's/^sdk\.dir=//p' "$target" 2>/dev/null)
[ -n "$selected_sdk" ] && [ -d "$selected_sdk" ] || unjudgeable "validated SDK root unavailable"

ANDROID_SDK_ROOT=$selected_sdk ANDROID_HOME='' exec sh "$owned"
