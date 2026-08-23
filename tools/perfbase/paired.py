#!/usr/bin/env python3
"""配对重算：把交替测的 A/B 冷点开按序号配成对，估效应与量具分辨率。

取数口径与 ca-emu/tmp/mkbaseline.py、instrperf summarize 相同：
只认 `adb logcat -s PerfTrace`；跳过 emitted=0；相对该次 ev=tap 的 t 差。
⛔ 不另立解析规则。⛔ 不写 instr-ab2-raw / instr-ab3-raw。

用法：
  python3 tools/perfbase/paired.py \\
      --a .team/perf/instr-ab2-raw/A \\
      --b .team/perf/instr-ab2-raw/B \\
      --out .team/perf/paired-r2.json \\
      --round r2
"""
from __future__ import annotations

import argparse
import json
import os
import random
import re
import statistics
import sys

FIXTURES = ("real_claude_idle", "redraw_tui", "big_scrollback")
SEGS = ("first_draw", "layout_settled")
LINE_RE = re.compile(r"D PerfTrace: (open_id=\S+) ev=(\S+) t=(\d+)(.*)$")
LOG_RE = re.compile(r"^(.+)-(\d+)\.log$")
N_BOOT_MIN = 2000
SEED_DEFAULT = 20260823


def parse_log(path: str) -> dict:
    """返回 {ev: t}（第一个 open_id），跳过 emitted=0。无 tap 则 {}。"""
    opens, order = {}, []
    with open(path, encoding="utf-8", errors="replace") as fh:
        for ln in fh:
            m = LINE_RE.search(ln)
            if not m:
                continue
            oid = m.group(1).split("=", 1)[1]
            ev, t, extra = m.group(2), int(m.group(3)), m.group(4)
            if "emitted=0" in extra:
                continue
            if oid not in opens:
                opens[oid] = {}
                order.append(oid)
            opens[oid].setdefault(ev, t)
    if not order:
        return {}
    return opens[order[0]]


def elapsed(evmap: dict, seg: str):
    if "tap" not in evmap or seg not in evmap:
        return None
    return evmap[seg] - evmap["tap"]


def pct(vals, p: float):
    if not vals:
        return None
    s = sorted(vals)
    if len(s) == 1:
        return float(s[0])
    k = (len(s) - 1) * p / 100.0
    i = int(k)
    f = k - i
    if i + 1 < len(s):
        return s[i] * (1.0 - f) + s[i + 1] * f
    return float(s[i])


def load_tsv(path: str) -> dict:
    """(fixture, seq) -> {ts, load1}。同一键多行取最后一行并记 dup。"""
    out, dups = {}, 0
    if not os.path.isfile(path):
        return {"rows": {}, "dups": 0, "missing_file": True}
    with open(path, encoding="utf-8") as fh:
        for ln in fh:
            ln = ln.strip()
            if not ln:
                continue
            parts = ln.split("\t")
            if len(parts) < 4:
                continue
            fx, seq = parts[1], int(parts[2])
            load1 = float(parts[3].split()[0])
            key = (fx, seq)
            if key in out:
                dups += 1
            out[key] = {"ts": parts[0], "load1": load1}
    return {"rows": out, "dups": dups, "missing_file": False}


def list_logs(dirpath: str) -> dict:
    """fixture -> {seq: path}"""
    found = {fx: {} for fx in FIXTURES}
    unknown = []
    if not os.path.isdir(dirpath):
        return {"by_fx": found, "unknown": [dirpath + " (not a dir)"]}
    for fn in sorted(os.listdir(dirpath)):
        if not fn.endswith(".log"):
            continue
        m = LOG_RE.match(fn)
        if not m:
            unknown.append(fn)
            continue
        fx, seq = m.group(1), int(m.group(2))
        if fx not in found:
            unknown.append(fn)
            continue
        found[fx][seq] = os.path.join(dirpath, fn)
    return {"by_fx": found, "unknown": unknown}


def bootstrap_median_ci(xs, n_boot: int, rng: random.Random):
    n = len(xs)
    if n == 0:
        return None, None
    meds = []
    for _ in range(n_boot):
        sample = [xs[rng.randrange(n)] for _ in range(n)]
        meds.append(statistics.median(sample))
    meds.sort()
    # 百分位：2.5 / 97.5
    lo = pct(meds, 2.5)
    hi = pct(meds, 97.5)
    return lo, hi


def analyze_round(dir_a: str, dir_b: str, n_boot: int, seed: int) -> dict:
    logs_a = list_logs(dir_a)
    logs_b = list_logs(dir_b)
    tsv_a = load_tsv(os.path.join(dir_a, "host-load.tsv"))
    tsv_b = load_tsv(os.path.join(dir_b, "host-load.tsv"))
    rng = random.Random(seed)

    fixtures = {}
    mdes = []
    pair_counts = []

    for fx in FIXTURES:
        a_files = logs_a["by_fx"][fx]
        b_files = logs_b["by_fx"][fx]
        seqs = sorted(set(a_files) | set(b_files))
        fx_node = {}
        for seg in SEGS:
            dropped_reasons = []
            pairs = []  # list of {seq, a, b, d_pct, ts_a, ts_b}
            a_vals, b_vals = [], []  # unpaired group (parsed samples), for old口径
            for seq in seqs:
                rec = {"seq": seq}
                if seq not in a_files:
                    dropped_reasons.append({"seq": seq, "reason": "missing_log_A"})
                    continue
                if seq not in b_files:
                    dropped_reasons.append({"seq": seq, "reason": "missing_log_B"})
                    continue
                pa, pb = a_files[seq], b_files[seq]
                ea, eb = elapsed(parse_log(pa), seg), elapsed(parse_log(pb), seg)
                if ea is None:
                    dropped_reasons.append({
                        "seq": seq, "reason": "parse_missing_A",
                        "detail": "无 tap 或无 %s" % seg, "log": pa,
                    })
                    continue
                if eb is None:
                    dropped_reasons.append({
                        "seq": seq, "reason": "parse_missing_B",
                        "detail": "无 tap 或无 %s" % seg, "log": pb,
                    })
                    continue
                a_vals.append(ea)
                b_vals.append(eb)
                meta_a = tsv_a["rows"].get((fx, seq))
                meta_b = tsv_b["rows"].get((fx, seq))
                ts_a = meta_a["ts"] if meta_a else None
                ts_b = meta_b["ts"] if meta_b else None
                if ts_a is not None and ts_b is not None and ts_a >= ts_b:
                    dropped_reasons.append({
                        "seq": seq, "reason": "time_order",
                        "detail": "A.ts=%s 不早于 B.ts=%s（不是「A 后紧跟 B」）" % (ts_a, ts_b),
                    })
                    continue
                if ea <= 0:
                    dropped_reasons.append({
                        "seq": seq, "reason": "a_nonpositive",
                        "detail": "A elapsed=%s，相对差分母非法" % ea,
                    })
                    continue
                d_pct = (eb - ea) / ea * 100.0
                pairs.append({
                    "seq": seq, "a_ms": ea, "b_ms": eb, "d_pct": d_pct,
                    "ts_a": ts_a, "ts_b": ts_b,
                })

            ds = [p["d_pct"] for p in pairs]
            n = len(ds)
            med = statistics.median(ds) if n else None
            lo, hi = bootstrap_median_ci(ds, n_boot, rng) if n else (None, None)
            old = None
            p50_a = pct(a_vals, 50)
            p50_b = pct(b_vals, 50)
            if p50_a and p50_b is not None:
                old = (p50_b - p50_a) / p50_a * 100.0
            iqr = None
            if n >= 2:
                iqr = pct(ds, 75) - pct(ds, 25)
            half_width = None
            if lo is not None and hi is not None:
                half_width = (hi - lo) / 2.0
                mdes.append(half_width)
                pair_counts.append(n)

            fx_node[seg] = {
                "n_pairs": n,
                "median_rel_diff": med,
                "ci95_low": lo,
                "ci95_high": hi,
                "dropped": len(dropped_reasons),
                "dropped_reasons": dropped_reasons,
                "iqr_pct": iqr,
                "ci_half_width_pct": half_width,
                "old_group_p50_rel_diff": old,
                "old_A_p50_ms": p50_a,
                "old_B_p50_ms": p50_b,
                "n_parsed_A": len(a_vals),
                "n_parsed_B": len(b_vals),
                "d_pct": ds,
                "pairs": pairs,
            }
        fixtures[fx] = fx_node

    # 量具分辨率：最吵的那一段的 CI 半宽（95% 置信下，效应小于这个数时 CI 往往会盖住 0）。
    y = max(mdes) if mdes else None
    n_ref = min(pair_counts) if pair_counts else None
    n_for_10 = None
    if y is not None and y > 0 and n_ref:
        # CI 宽 ∝ 1/√n  ⇒  半宽目标 10% 时 n' = n (Y/10)^2
        n_for_10 = n_ref * (y / 10.0) ** 2

    return {
        "dir_a": dir_a,
        "dir_b": dir_b,
        "method": (
            "配对：同夹具同序号的 A.log 与 B.log；d=(B-A)/A*100；"
            "median(d_i)；bootstrap 百分位 CI（对 d_i 有放回，n_boot=%d, seed=%d）。"
            "解析：PerfTrace，跳过 emitted=0，相对 tap。"
            % (n_boot, seed)
        ),
        "n_boot": n_boot,
        "seed": seed,
        "min_detectable_effect_pct": y,
        "min_detectable_effect_definition": (
            "各（夹具×段）bootstrap 95% CI 半宽 (high-low)/2 的最大值。"
            "单位与 median_rel_diff 相同（相对差的百分点）。"
            "含义：在当前 n_pairs 下，比这更小的效应，95% CI 往往会盖住 0，量具分不清。"
        ),
        "n_pairs_ref": n_ref,
        "n_pairs_needed_for_10pct": n_for_10,
        "tsv_A_dups": tsv_a["dups"],
        "tsv_B_dups": tsv_b["dups"],
        "unknown_logs_A": logs_a["unknown"],
        "unknown_logs_B": logs_b["unknown"],
        "fixtures": fixtures,
    }


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="A/B 冷点开配对重算")
    ap.add_argument("--a", required=True, help="A 组 raw 目录（含 *.log 与 host-load.tsv）")
    ap.add_argument("--b", required=True, help="B 组 raw 目录")
    ap.add_argument("--out", required=True, help="输出 json")
    ap.add_argument("--round", dest="round_id", default="")
    ap.add_argument("--boot", type=int, default=5000)
    ap.add_argument("--seed", type=int, default=SEED_DEFAULT)
    args = ap.parse_args(argv)
    if args.boot < N_BOOT_MIN:
        print("FAIL --boot 必须 >= %d（任务书）" % N_BOOT_MIN, file=sys.stderr)
        return 1
    body = analyze_round(args.a, args.b, args.boot, args.seed)
    if args.round_id:
        body["round"] = args.round_id
    os.makedirs(os.path.dirname(os.path.abspath(args.out)) or ".", exist_ok=True)
    with open(args.out, "w") as fh:
        json.dump(body, fh, indent=2)
        fh.write("\n")
    print("wrote", args.out)
    print("min_detectable_effect_pct=%.3f n_pairs_ref=%s n_for_10pct=%s" % (
        body["min_detectable_effect_pct"] if body["min_detectable_effect_pct"] is not None else -1,
        body["n_pairs_ref"],
        ("%.1f" % body["n_pairs_needed_for_10pct"]) if body["n_pairs_needed_for_10pct"] is not None else "n/a",
    ))
    return 0


if __name__ == "__main__":
    sys.exit(main())
