#!/usr/bin/env bash
# probe-np.sh — 063 节点判活探针：断言「世界变了」
#
# 绿当且仅当 tools/nodeprobe 真的能按 titles.tsv 给出 working/idle/unknown
# （不是目录在、不是 Cargo.toml 在）。当前 crate/包装都不存在 ⇒ 必须红。
set -u

ORACLE_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$ORACLE_DIR/../../.." && pwd)"
TSV="$ROOT/tools/nodeprobe/fixtures/titles.tsv"
WRAP="$ROOT/tools/nodeprobe.sh"
CRATE="$ROOT/tools/nodeprobe"

fail() { echo "FAIL $1"; exit 1; }
pass() { echo "PASS $1"; }

[ -s "$TSV" ] || fail "缺语料 $TSV"
n="$(wc -l < "$TSV" | tr -d ' ')"
[ "$n" -ge 6 ] || fail "语料行数 $n < 6"

has_tool=0
if [ -x "$WRAP" ]; then
  has_tool=1
fi
if [ -f "$CRATE/Cargo.toml" ] && [ -n "$(find "$CRATE/src" -name '*.rs' 2>/dev/null | head -1)" ]; then
  has_tool=1
fi
if [ "$has_tool" -eq 0 ]; then
  fail "tools/nodeprobe 还不存在（无 nodeprobe.sh、无含 .rs 的 crate）——回退基线预期红"
fi

# 工具在：必须用语料逐行跑出与 TSV 一致的 state（世界变了，不是符号在）
classify=""
if [ -x "$WRAP" ]; then
  classify="$WRAP fixtures"
elif command -v cargo >/dev/null; then
  classify="cargo test --manifest-path $CRATE/Cargo.toml -- fixtures_corpus --nocapture"
fi
[ -n "$classify" ] || fail "有 crate 但没有可跑的 fixtures 入口"

set +e
if [ -x "$WRAP" ]; then
  # 期望包装：stdin 或 argv 吃 tsv，stdout 每行 state\tprovider
  out="$($WRAP fixtures "$TSV" 2>&1)"
  rc=$?
  set -u
  [ "$rc" -eq 0 ] || fail "nodeprobe.sh fixtures 退出 $rc: $out"
  python3 - "$TSV" <<'PY' <<<"$out"
import sys
tsv=open(sys.argv[1],encoding="utf-8").read().splitlines()
got=sys.stdin.read().splitlines()
if len(got)<len(tsv):
    raise SystemExit(f"output lines {len(got)} < corpus {len(tsv)}")
# allow extra log lines: keep those with a tab
g=[ln for ln in got if "\t" in ln]
ok=0
for i,row in enumerate(tsv):
    parts=row.split("\t")
    if len(parts)<3:
        raise SystemExit(f"bad tsv line {i+1}")
    title,state,prov=parts[0],parts[1],parts[2]
    if i>=len(g):
        raise SystemExit(f"missing result for line {i+1}")
    gs=g[i].split("\t")
    if gs[0]!=state or (len(gs)>1 and gs[1]!=prov):
        raise SystemExit(f"line {i+1} want {state}/{prov} got {g[i]!r} title={title!r}")
    ok+=1
print(f"corpus {ok} ok")
PY
  [ $? -eq 0 ] || fail "语料判定与 titles.tsv 不一致"
else
  ( cd "$CRATE" && cargo test -- fixtures_corpus --nocapture )
  [ $? -eq 0 ] || fail "cargo test fixtures_corpus 未过"
fi

pass "nodeprobe 用语料逐行判对（世界变了）"
exit 0
