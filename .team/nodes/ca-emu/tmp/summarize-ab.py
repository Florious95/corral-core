#!/usr/bin/env python3
"""把 raw-capp/{A,B} 的 PerfTrace logcat 汇总成 A/B 并列 JSON。

口径与 tmp/mkbaseline.py 相同（只认 adb logcat -s PerfTrace）：
- 跳过 emitted=0（仪表「没做/没做成」）
- 八键齐且单调不减才进 samples
- 极端值不剔除，另列 outliers
- raw_log_path 指向 raw-capp/{A|B}/，⛔ 不写 .team/perf/raw/
"""
import json, os, re, statistics, sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "../../../../"))
RAW_ROOT = os.path.join(ROOT, ".team/perf/raw-capp")
OUT = os.path.join(ROOT, ".team/perf/recheck-20260822-capp-ab.json")

KEYS = ["tap", "route_enter", "subscribe_sent", "geom_seed",
        "first_frame_recv", "snapshot_applied", "first_draw", "layout_settled"]
FIXTURES = ["real_claude_idle", "redraw_tui", "big_scrollback"]
line_re = re.compile(r'D PerfTrace: (open_id=\S+) ev=(\S+) t=(\d+)(.*)$')


def parse(path):
    opens = {}
    order = []
    for ln in open(path, encoding="utf-8", errors="replace"):
        m = line_re.search(ln)
        if not m:
            continue
        oid = m.group(1).split("=", 1)[1]
        ev, t, extra = m.group(2), int(m.group(3)), m.group(4)
        if "emitted=0" in extra:
            continue
        if oid not in opens:
            opens[oid] = {"t": {}, "lines": []}
            order.append(oid)
        opens[oid]["lines"].append(ln.rstrip())
        opens[oid]["t"].setdefault(ev, t)
    return [(o, opens[o]["t"], opens[o]["lines"]) for o in order]


def pct(vals, p):
    if not vals:
        return None
    s = sorted(vals)
    if len(s) == 1:
        return s[0]
    k = (len(s) - 1) * p / 100.0
    f = int(k)
    c = min(f + 1, len(s) - 1)
    return round(s[f] + (s[c] - s[f]) * (k - f), 1)


def load_tsv(path):
    rows = []
    for ln in open(path, encoding="utf-8"):
        ln = ln.strip()
        if not ln:
            continue
        parts = ln.split("\t")
        if len(parts) < 4:
            continue
        ts, fx, n, loads = parts[0], parts[1], parts[2], parts[3]
        bits = loads.split()
        load1 = float(bits[0])
        rows.append({
            "ts": ts, "fixture": fx, "n": int(n),
            "load1": load1,
            "load5": float(bits[1]) if len(bits) > 1 else None,
            "load15": float(bits[2]) if len(bits) > 2 else None,
        })
    l1 = [r["load1"] for r in rows]
    summary = {
        "n": len(l1),
        "load1_min": min(l1) if l1 else None,
        "load1_max": max(l1) if l1 else None,
        "load1_mean": round(statistics.fmean(l1), 2) if l1 else None,
        "source": os.path.relpath(path, ROOT),
        "rows": rows,
    }
    by_fx = {}
    for fx in FIXTURES:
        xs = [r["load1"] for r in rows if r["fixture"] == fx]
        by_fx[fx] = {
            "n": len(xs),
            "load1_min": min(xs) if xs else None,
            "load1_max": max(xs) if xs else None,
            "load1_mean": round(statistics.fmean(xs), 2) if xs else None,
        }
    summary["by_fixture"] = by_fx
    return summary


def summarize_group(group):
    raw = os.path.join(RAW_ROOT, group)
    fixtures = {}
    log_count = 0
    for fx in FIXTURES:
        samples, outliers = [], []
        for fn in sorted(os.listdir(raw)):
            if not fn.startswith(fx + "-") or not fn.endswith(".log"):
                continue
            log_count += 1
            path = os.path.join(raw, fn)
            rel = f".team/perf/raw-capp/{group}/{fn}"
            found = parse(path)
            if not found:
                outliers.append({
                    "open_id": None,
                    "reason": "该次冷点开未采到任何 PerfTrace 事件行（见原始日志）",
                    "raw_log_path": rel,
                })
                continue
            for oid, t, _lines in found:
                missing = [k for k in KEYS if k not in t]
                if missing:
                    outliers.append({
                        "open_id": oid,
                        "reason": "八键不齐，缺 " + ",".join(missing),
                        "raw_log_path": rel,
                    })
                    continue
                base = t["tap"]
                rel_t = {k: t[k] - base for k in KEYS}
                seq = [rel_t[k] for k in KEYS]
                if any(b < a for a, b in zip(seq, seq[1:])):
                    outliers.append({
                        "open_id": oid,
                        "reason": "八键非单调不减: " + str(rel_t),
                        "raw_log_path": rel,
                    })
                    continue
                samples.append({
                    "open_id": oid, "t": rel_t, "note": "",
                    "raw_log_path": rel,
                })
        partial_runs, partial_stats = [], {}
        for fn in sorted(os.listdir(raw)):
            if not fn.startswith(fx + "-") or not fn.endswith(".log"):
                continue
            rel = f".team/perf/raw-capp/{group}/{fn}"
            for oid, t, _lines in parse(os.path.join(raw, fn)):
                if "tap" not in t:
                    continue
                base = t["tap"]
                partial_runs.append({
                    "open_id": oid, "raw_log_path": rel,
                    "t": {k: t[k] - base for k in KEYS if k in t},
                    "missing": [k for k in KEYS if k not in t],
                })
        for seg in KEYS[1:]:
            vals = [r["t"][seg] for r in partial_runs if seg in r["t"]]
            if not vals:
                partial_stats[seg] = {"n": 0}
                continue
            partial_stats[seg] = {
                "mean": round(statistics.fmean(vals), 1),
                "p50": pct(vals, 50), "p95": pct(vals, 95),
                "max": max(vals), "min": min(vals), "n": len(vals),
            }
        stats = {}
        for seg in ["first_draw", "layout_settled"]:
            vals = [s["t"][seg] for s in samples]
            stats[seg] = {
                "mean": round(statistics.fmean(vals), 1) if vals else None,
                "p50": pct(vals, 50), "p95": pct(vals, 95),
                "max": max(vals) if vals else None,
                "n": len(vals),
            }
        for seg in ["first_draw", "layout_settled"]:
            st = stats[seg]
            if st["p50"]:
                for s in samples:
                    v = s["t"][seg]
                    if v >= st["p95"] and v > 1.5 * st["p50"]:
                        outliers.append({
                            "open_id": s["open_id"],
                            "reason": (
                                f"极端值（未剔除，已计入统计）: {seg}={v}ms "
                                f"vs p50={st['p50']}ms p95={st['p95']}ms"
                            ),
                            "raw_log_path": s["raw_log_path"],
                        })
        fixtures[fx] = {
            "samples": samples, "stats": stats, "outliers": outliers,
            "partial_runs": partial_runs, "partial_stats": partial_stats,
        }
    host = load_tsv(os.path.join(raw, "host-load.tsv"))
    return fixtures, host, log_count


def rel_pct(a, b):
    if a in (None, 0) or b is None:
        return None
    return round((b / a - 1) * 100, 1)


A_fx, A_load, A_n = summarize_group("A")
B_fx, B_load, B_n = summarize_group("B")

comparison = {}
red = []
ok = []
unjudgeable = []
TOL = 10.0
for fx in FIXTURES:
    comparison[fx] = {}
    for seg in ["first_draw", "layout_settled"]:
        ast, bst = A_fx[fx]["stats"][seg], B_fx[fx]["stats"][seg]
        row = {
            "A": {"p50": ast["p50"], "p95": ast["p95"], "max": ast["max"], "n": ast["n"]},
            "B": {"p50": bst["p50"], "p95": bst["p95"], "max": bst["max"], "n": bst["n"]},
            "B_vs_A_pct": {
                "p50": rel_pct(ast["p50"], bst["p50"]),
                "p95": rel_pct(ast["p95"], bst["p95"]),
                "max": rel_pct(ast["max"], bst["max"]),
            },
        }
        slower = []
        for m in ("p50", "p95"):
            p = row["B_vs_A_pct"][m]
            if p is None:
                unjudgeable.append(f"{fx} {seg} {m} 取不到（A n={ast['n']} B n={bst['n']}）")
                continue
            line = (
                f"{fx} {seg} {m} A={ast[m]}ms B={bst[m]}ms "
                f"({p:+.1f}%)"
            )
            if p > TOL:
                slower.append(m)
                red.append(line)
            else:
                ok.append(line)
        row["B_slower_than_A_by_over_10pct"] = bool(slower)
        row["over_10pct_metrics"] = slower
        comparison[fx][seg] = row

if unjudgeable:
    verdict = "INCONCLUSIVE 数据不齐，" + "; ".join(unjudgeable)
elif red:
    verdict = (
        "FAIL B 相对同批 A 有段慢超过 +10%："
        + "; ".join(red)
    )
else:
    verdict = (
        "PASS B 相对同批 A 全段未慢超过 +10% "
        f"（{len(ok)} 段 p50/p95；地板=同批 A 组，未跟历史基线比）"
    )

doc = {
    "recheck_date": "2026-08-22",
    "kind": "capp-ab (t.perf r13)：includeBuild A vs maven B；只汇总已采集 raw-capp，未重跑",
    "measured_by": "ca-emu2（汇总）/ ca-emu（采集，前任测完后额度耗尽死在汇总）",
    "method": "只从 adb logcat -s PerfTrace 取数（⛔ 不识图/不取帧/不取帧间隔）；口径同 tmp/mkbaseline.py：跳过 emitted=0，相对该次 tap 的 elapsedRealtime 差值",
    "unit": "ms，相对该次 tap 的 elapsedRealtime 差值",
    "floor": "同批 A 组（⛔ 不跟历史基线 .team/perf/baseline-20260822.json 比；上一轮跨包比历史地板得出假的 -90%）",
    "threshold_pct": 10,
    "emulator": "agentmirror_test_b / sdk_gphone64_arm64 Android 15 (API 35) arm64-v8a；gpu=host；见 raw-capp/diag/batch-env.txt",
    "batch_env": ".team/perf/raw-capp/diag/batch-env.txt",
    "navigation": "60/60 OK，见 .team/perf/raw-capp/diag/navigation-runlog.log",
    "log_counts": {"A": A_n, "B": B_n},
    "A": {
        "label": "includeBuild 源码 composite release",
        "apk_md5": "aecdbd461deece5daec8f81c70af8e54",
        "apk_path": ".team/nodes/ca-emu/tmp/app-release-a.apk",
        "host_load": {
            "n": A_load["n"],
            "load1_min": A_load["load1_min"],
            "load1_max": A_load["load1_max"],
            "load1_mean": A_load["load1_mean"],
            "by_fixture": A_load["by_fixture"],
            "source": A_load["source"],
            "tsv": A_load["rows"],
        },
        "fixtures": A_fx,
    },
    "B": {
        "label": "格3 引用 maven 产物 release",
        "apk_md5": "3ebc9c55703c780c842a2f410b85034e",
        "apk_path": ".team/staging/corral-app/app/build/outputs/apk/release/app-release.apk",
        "host_load": {
            "n": B_load["n"],
            "load1_min": B_load["load1_min"],
            "load1_max": B_load["load1_max"],
            "load1_mean": B_load["load1_mean"],
            "by_fixture": B_load["by_fixture"],
            "source": B_load["source"],
            "tsv": B_load["rows"],
        },
        "fixtures": B_fx,
    },
    "comparison": comparison,
    "comparison_ok": ok,
    "comparison_red": red,
    "verdict": verdict,
}

os.makedirs(os.path.dirname(OUT), exist_ok=True)
with open(OUT, "w", encoding="utf-8") as f:
    json.dump(doc, f, ensure_ascii=False, indent=2)
    f.write("\n")

print(json.dumps({
    "out": os.path.relpath(OUT, ROOT),
    "A_logs": A_n, "B_logs": B_n,
    "A_n_samples": {k: len(v["samples"]) for k, v in A_fx.items()},
    "B_n_samples": {k: len(v["samples"]) for k, v in B_fx.items()},
    "A_n_outliers": {k: len(v["outliers"]) for k, v in A_fx.items()},
    "B_n_outliers": {k: len(v["outliers"]) for k, v in B_fx.items()},
    "A_stats": {k: v["stats"] for k, v in A_fx.items()},
    "B_stats": {k: v["stats"] for k, v in B_fx.items()},
    "A_load1": {k: A_load[k] for k in ("n", "load1_min", "load1_max", "load1_mean")},
    "B_load1": {k: B_load[k] for k in ("n", "load1_min", "load1_max", "load1_mean")},
    "ok": ok, "red": red, "unjudgeable": unjudgeable,
    "verdict": verdict,
}, ensure_ascii=False, indent=2))
