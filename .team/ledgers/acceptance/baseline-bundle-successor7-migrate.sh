#!/bin/sh
# //! purpose: successor7 上游全绿后复用机械前置迁移门，保留旧 perf-regress 历史。
# //! contract: 0=安全迁移完成；1=越权/历史丢失；2=现场漂移不可判。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || exit 2
sh "$script_dir/baseline-bundle-migrate.sh"
rc=$?
case "$rc" in 0) exit 0 ;; 1) exit 1 ;; 2) exit 2 ;; *) printf '%s\n' "UNJUDGEABLE baseline-bundle-successor7-migrate: base gate unsupported rc=$rc" >&2; exit 2 ;; esac
