#!/usr/bin/env bash
# accuracy.sh — 对照席准确率统计（ledger.nodeprobe.v1 / t.accuracy）
# 读同目录 samples.jsonl（真值独立采集，不由 nodeprobe 自证）。
# 退出码：命中率 < 0.9 则非 0。unknown 单独计，不并进 idle/working。
set -u
DIR="$(cd "$(dirname "$0")" && pwd)"
SAMPLES="$DIR/samples.jsonl"
NP="$(cd "$DIR/../../.." && pwd)/tools/nodeprobe.sh"
THRESHOLD="0.9"

fail() { echo "ACCURACY FAIL: $1" >&2; exit 1; }
[ -s "$SAMPLES" ] || fail "缺少 $SAMPLES"

python3 - "$SAMPLES" "$NP" "$THRESHOLD" <<'PY'
import json, sys, tempfile, os, subprocess
from collections import Counter

path, np, thr = sys.argv[1], sys.argv[2], float(sys.argv[3])
rows = [json.loads(l) for l in open(path) if l.strip()]
if len(rows) < 20:
    print(f"samples={len(rows)} < 20", file=sys.stderr)
    sys.exit(1)
truths = Counter(r["truth"] for r in rows)
if truths.get("working", 0) < 10 or truths.get("idle", 0) < 10:
    print(f"class counts {dict(truths)} need >=10 each", file=sys.stderr)
    sys.exit(1)

def hit(pred, truth):
    return pred == truth

hits = sum(1 for r in rows if hit(r["pred"], r["truth"]))
unk = sum(1 for r in rows if r["pred"] == "unknown")
fw = sum(1 for r in rows if r["pred"] == "working" and r["truth"] != "working")
fi = sum(1 for r in rows if r["pred"] == "idle" and r["truth"] != "idle")
n = len(rows)
rate = hits / n

# single-signal: leading-glyph-only (braille/claude work → working, else idle; no unknown)
def glyph_only(detail):
    # first=U+XXXX X  in nodeprobe evidence
    import re
    m = re.search(r"first=U\+([0-9A-Fa-f]{4,})", detail or "")
    if not m:
        return "idle"
    cp = int(m.group(1), 16)
    if 0x2800 <= cp <= 0x28FF or cp in (0x25D0, 0x25D1, 0x25D2, 0x25D3):
        return "working"
    return "idle"

g_hits = sum(1 for r in rows if glyph_only(r.get("detail")) == r["truth"])
g_rate = g_hits / n

# combined: glyph-only OR wait/think mark in title
def combined(detail):
    d = detail or ""
    if " - Thinking - " in d or " - Waiting for response" in d:
        return "working"
    return glyph_only(d)

c_hits = sum(1 for r in rows if combined(r.get("detail")) == r["truth"])
c_rate = c_hits / n

# re-classify stored titles through nodeprobe fixtures (determinism)
def extract_title(detail):
    marker = " title="
    i = (detail or "").rfind(marker)
    return detail[i + len(marker):] if i >= 0 else ""

tmp = tempfile.NamedTemporaryFile("w", suffix=".tsv", delete=False, encoding="utf-8")
for r in rows:
    t = extract_title(r.get("detail", ""))
    tmp.write(f"{t}\t{r['pred']}\t{r.get('provider') or 'unknown'}\n")
tmp.close()
try:
    out = subprocess.check_output([np, "fixtures", tmp.name], text=True)
except subprocess.CalledProcessError as e:
    print(f"nodeprobe fixtures failed: {e}", file=sys.stderr)
    os.unlink(tmp.name)
    sys.exit(1)
os.unlink(tmp.name)
re_hits = 0
re_n = 0
for line, r in zip(out.splitlines(), rows):
    parts = line.split("\t")
    if len(parts) < 1:
        continue
    re_n += 1
    if parts[0] == r["truth"]:
        re_hits += 1
re_rate = re_hits / re_n if re_n else 0.0

print(f"n={n} working={truths['working']} idle={truths['idle']}")
print(f"title_signal hits={hits} rate={rate:.3f} false_working={fw} false_idle={fi} unknown={unk}")
print(f"glyph_only hits={g_hits} rate={g_rate:.3f}")
print(f"combined_glyph_or_waitmark hits={c_hits} rate={c_rate:.3f} gain={c_rate-g_rate:+.3f}")
print(f"reclassify_fixtures hits={re_hits}/{re_n} rate={re_rate:.3f}")
# unknown never folded into idle
if any(r["pred"] == "unknown" and r["truth"] == "idle" and False for r in rows):
    pass
if rate < thr:
    print(f"hit rate {rate:.3f} < {thr}", file=sys.stderr)
    sys.exit(1)
print("ACCURACY PASS")
sys.exit(0)
PY
