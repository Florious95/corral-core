#!/usr/bin/env bash
# split-check.sh — A-dw-split：说明.md 必须含分段字段名与读数
set -euo pipefail
DOC="$(cd "$(dirname "$0")" && pwd)/说明.md"
fail() { echo "FAIL $*"; exit 1; }
[ -s "$DOC" ] || fail "说明.md 空"
for s in dt_lock_us dt_post_us dt_super_us dt_body_us dt_clear_us dt_lines_us surface=view; do
  grep -q "$s" "$DOC" || fail "说明.md 缺 $s"
done
# 至少有一段带数字的读数（µs 或 us）
grep -Eq 'dt_(super|body|clear|lines)_us[^0-9]{0,40}[0-9]{2,}' "$DOC" \
  || grep -Eq '[0-9]{2,}.*(dt_super_us|dt_body_us|dt_lines_us)' "$DOC" \
  || fail "说明.md 有字段名但没有分段读数"
echo "PASS A-dw-split"
