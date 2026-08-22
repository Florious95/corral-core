#!/usr/bin/env python3
"""汇总 raw-capp（round1 n=10，三夹具）+ raw-capp2（round2 n=20，只 big_scrollback）。

上一轮 10 次与本轮 20 次分开列，另给合并后统计。极端值不剔除。
口径同 tmp/mkbaseline.py。
"""
import json, os, re, statistics, sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "../../../../"))
OUT = os.path.join(ROOT, ".team/perf/recheck-20260822-capp-ab.json")

KEYS = ["tap", "route_enter", "subscribe_sent", "geom_seed",
        "first_frame_recv", "snapshot_applied", "first_draw", "layout_settled"]
ALL_FX = ["real_claude_idle", "redraw_tui", "big_scrollback"]
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
            opens[oid] = {"t": {}}
            order.append(oid)
        opens[oid]["t"].setdefault(ev, t)
    return [(o, opens[o]["t"]) for o in order]


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
    if not os.path.isfile(path):
        return {"n": 0, "load1_min": None, "load1_max": None, "load1_mean": None,
                "source": os.path.relpath(path, ROOT) if path else None, "rows": [],
                "by_fixture": {}}
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
    by_fx = {}
    for fx in ALL_FX:
        xs = [r["load1"] for r in rows if r["fixture"] == fx]
        by_fx[fx] = {
            "n": len(xs),
            "load1_min": min(xs) if xs else None,
            "load1_max": max(xs) if xs else None,
            "load1_mean": round(statistics.fmean(xs), 2) if xs else None,
        }
    return {
        "n": len(l1),
        "load1_min": min(l1) if l1 else None,
        "load1_max": max(l1) if l1 else None,
        "load1_mean": round(statistics.fmean(l1), 2) if l1 else None,
        "source": os.path.relpath(path, ROOT),
        "rows": rows,
        "by_fixture": by_fx,
    }


def stats_of(vals):
    if not vals:
        return {"mean": None, "p50": None, "p95": None, "max": None, "n": 0}
    return {
        "mean": round(statistics.fmean(vals), 1),
        "p50": pct(vals, 50), "p95": pct(vals, 95),
        "max": max(vals), "n": len(vals),
    }


def summarize_dir(raw, group, fixtures, rel_prefix):
    out = {}
    log_count = 0
    for fx in fixtures:
        samples, outliers = [], []
        names = sorted(
            fn for fn in os.listdir(raw)
            if fn.startswith(fx + "-") and fn.endswith(".log")
        )
        for fn in names:
            log_count += 1
            path = os.path.join(raw, fn)
            rel = f"{rel_prefix}/{fn}"
            found = parse(path)
            if not found:
                outliers.append({
                    "open_id": None,
                    "reason": "该次冷点开未采到任何 PerfTrace 事件行（见原始日志）",
                    "raw_log_path": rel,
                })
                continue
            for oid, t in found:
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
        stats = {seg: stats_of([s["t"][seg] for s in samples])
                 for seg in ("first_draw", "layout_settled")}
        for seg in ("first_draw", "layout_settled"):
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
        out[fx] = {"samples": samples, "stats": stats, "outliers": outliers}
    host = load_tsv(os.path.join(raw, "host-load.tsv"))
    return out, host, log_count


def rel_pct(a, b):
    if a in (None, 0) or b is None:
        return None
    return round((b / a - 1) * 100, 1)


def compare(A_fx, B_fx, fixtures, tol=10.0):
    comparison, red, ok, unjudgeable = {}, [], [], []
    for fx in fixtures:
        if fx not in A_fx or fx not in B_fx:
            continue
        comparison[fx] = {}
        for seg in ("first_draw", "layout_settled"):
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
                line = f"{fx} {seg} {m} A={ast[m]}ms B={bst[m]}ms ({p:+.1f}%)"
                if p > tol:
                    slower.append(m)
                    red.append(line)
                else:
                    ok.append(line)
            row["B_slower_than_A_by_over_10pct"] = bool(slower)
            row["over_10pct_metrics"] = slower
            comparison[fx][seg] = row
    return comparison, red, ok, unjudgeable


def combine_fx(r1, r2, fx):
    samples = list(r1[fx]["samples"]) + list(r2[fx]["samples"])
    stats = {seg: stats_of([s["t"][seg] for s in samples])
             for seg in ("first_draw", "layout_settled")}
    outliers = list(r1[fx]["outliers"]) + list(r2[fx]["outliers"])
    for seg in ("first_draw", "layout_settled"):
        st = stats[seg]
        if st["p50"]:
            for s in samples:
                v = s["t"][seg]
                if v >= st["p95"] and v > 1.5 * st["p50"]:
                    outliers.append({
                        "open_id": s["open_id"],
                        "reason": (
                            f"极端值-合并集（未剔除，已计入统计）: {seg}={v}ms "
                            f"vs p50={st['p50']}ms p95={st['p95']}ms"
                        ),
                        "raw_log_path": s["raw_log_path"],
                    })
    return {"n": len(samples), "stats": stats, "outliers": outliers,
            "note": "样本仍分列在 round1/round2，此处只给合并统计，未把 30 次揉成一堆 samples"}


A1, A1load, A1n = summarize_dir(
    os.path.join(ROOT, ".team/perf/raw-capp/A"), "A", ALL_FX, ".team/perf/raw-capp/A")
B1, B1load, B1n = summarize_dir(
    os.path.join(ROOT, ".team/perf/raw-capp/B"), "B", ALL_FX, ".team/perf/raw-capp/B")
A2, A2load, A2n = summarize_dir(
    os.path.join(ROOT, ".team/perf/raw-capp2/A"), "A", ["big_scrollback"],
    ".team/perf/raw-capp2/A")
B2, B2load, B2n = summarize_dir(
    os.path.join(ROOT, ".team/perf/raw-capp2/B"), "B", ["big_scrollback"],
    ".team/perf/raw-capp2/B")

c1, red1, ok1, u1 = compare(A1, B1, ALL_FX)
c2, red2, ok2, u2 = compare(A2, B2, ["big_scrollback"])

combA = {"big_scrollback": combine_fx(A1, A2, "big_scrollback")}
combB = {"big_scrollback": combine_fx(B1, B2, "big_scrollback")}
cc, redc, okc, uc = compare(combA, combB, ["big_scrollback"])


def verdict_of(red, ok, unj, label):
    if unj:
        return f"INCONCLUSIVE {label} 数据不齐：" + "; ".join(unj)
    if red:
        return f"FAIL {label} B 相对同批 A 有段慢超过 +10%：" + "; ".join(red)
    return f"PASS {label} B 相对同批 A 全段未慢超过 +10%（{len(ok)} 段 p50/p95）"


doc = {
    "recheck_date": "2026-08-22",
    "kind": "capp-ab (t.perf r15)：round1 n=10 三夹具 + round2 n=20 只 big_scrollback 加样本证伪；分列不合并成一堆",
    "measured_by": "ca-emu2",
    "method": "只从 adb logcat -s PerfTrace 取数（⛔ 不识图/不取帧/不取帧间隔）；口径同 tmp/mkbaseline.py：跳过 emitted=0，相对该次 tap 的 elapsedRealtime 差值",
    "unit": "ms，相对该次 tap 的 elapsedRealtime 差值",
    "floor": "同批 A 组（⛔ 不跟历史基线比）",
    "threshold_pct": 10,
    "emulator": "agentmirror_test_b / sdk_gphone64_arm64 Android 15 (API 35) arm64-v8a；gpu=host",
    "apk": {
        "A": {"md5": "aecdbd461deece5daec8f81c70af8e54",
              "path": ".team/nodes/ca-emu/tmp/app-release-a.apk",
              "label": "includeBuild 源码 composite release"},
        "B": {"md5": "3ebc9c55703c780c842a2f410b85034e",
              "path": ".team/staging/corral-app/app/build/outputs/apk/release/app-release.apk",
              "label": "格3 引用 maven 产物 release"},
    },
    "round1_n10": {
        "source": ".team/perf/raw-capp/{A,B}/",
        "note": "上一轮三夹具各 10 次；本轮未重跑 real_claude_idle / redraw_tui",
        "log_counts": {"A": A1n, "B": B1n},
        "A": {"host_load": {k: A1load[k] for k in ("n", "load1_min", "load1_max", "load1_mean", "by_fixture", "source", "rows")},
              "fixtures": A1},
        "B": {"host_load": {k: B1load[k] for k in ("n", "load1_min", "load1_max", "load1_mean", "by_fixture", "source", "rows")},
              "fixtures": B1},
        "comparison": c1,
        "comparison_ok": ok1,
        "comparison_red": red1,
        "verdict": verdict_of(red1, ok1, u1, "round1 n=10"),
    },
    "round2_n20": {
        "source": ".team/perf/raw-capp2/{A,B}/",
        "note": "本轮只跑 big_scrollback，A/B 各 20 次交替",
        "log_counts": {"A": A2n, "B": B2n},
        "A": {"host_load": {k: A2load[k] for k in ("n", "load1_min", "load1_max", "load1_mean", "by_fixture", "source", "rows")},
              "fixtures": A2},
        "B": {"host_load": {k: B2load[k] for k in ("n", "load1_min", "load1_max", "load1_mean", "by_fixture", "source", "rows")},
              "fixtures": B2},
        "comparison": c2,
        "comparison_ok": ok2,
        "comparison_red": red2,
        "verdict": verdict_of(red2, ok2, u2, "round2 n=20 big_scrollback"),
    },
    "combined_n30_big_scrollback": {
        "note": "round1 的 10 次 + round2 的 20 次合并统计；样本仍分列在 round1/round2，此处无合并 samples 堆",
        "A": combA["big_scrollback"],
        "B": combB["big_scrollback"],
        "comparison": cc,
        "comparison_ok": okc,
        "comparison_red": redc,
        "verdict": verdict_of(redc, okc, uc, "combined n=30 big_scrollback"),
    },
    # 形状兼容上一份：顶层 A/B 指向分列结构，不把 30 次揉进一个 samples
    "A": {
        "label": "includeBuild 源码 composite release",
        "apk_md5": "aecdbd461deece5daec8f81c70af8e54",
        "apk_path": ".team/nodes/ca-emu/tmp/app-release-a.apk",
        "fixtures": {
            "real_claude_idle": {**A1["real_claude_idle"], "round": "round1_n10 本轮未重测"},
            "redraw_tui": {**A1["redraw_tui"], "round": "round1_n10 本轮未重测"},
            "big_scrollback": {
                "round1_n10": A1["big_scrollback"],
                "round2_n20": A2.get("big_scrollback"),
                "combined_n30_stats": combA["big_scrollback"]["stats"],
                "note": "样本分列 round1/round2，不要当成一堆",
            },
        },
    },
    "B": {
        "label": "格3 引用 maven 产物 release",
        "apk_md5": "3ebc9c55703c780c842a2f410b85034e",
        "apk_path": ".team/staging/corral-app/app/build/outputs/apk/release/app-release.apk",
        "fixtures": {
            "real_claude_idle": {**B1["real_claude_idle"], "round": "round1_n10 本轮未重测"},
            "redraw_tui": {**B1["redraw_tui"], "round": "round1_n10 本轮未重测"},
            "big_scrollback": {
                "round1_n10": B1["big_scrollback"],
                "round2_n20": B2.get("big_scrollback"),
                "combined_n30_stats": combB["big_scrollback"]["stats"],
                "note": "样本分列 round1/round2，不要当成一堆",
            },
        },
    },
    "comparison": {
        "round1_n10": c1,
        "round2_n20_big_scrollback": c2,
        "combined_n30_big_scrollback": cc,
        "floor": "同批 A 组",
    },
    "verdict_round1": verdict_of(red1, ok1, u1, "round1 n=10"),
    "verdict_round2": verdict_of(red2, ok2, u2, "round2 n=20"),
    "verdict_combined": verdict_of(redc, okc, uc, "combined n=30"),
}

# 本轮证伪问的是 round2；合并统计另给。总 verdict 并列三句，不替判者选噪声/真回退。
doc["verdict"] = (
    "ROUND_SPLIT 无权判噪声。round1: " + doc["verdict_round1"]
    + " || round2: " + doc["verdict_round2"]
    + " || combined: " + doc["verdict_combined"]
)

with open(OUT, "w", encoding="utf-8") as f:
    json.dump(doc, f, ensure_ascii=False, indent=2)
    f.write("\n")

print(json.dumps({
    "out": os.path.relpath(OUT, ROOT),
    "A1n": A1n, "B1n": B1n, "A2n": A2n, "B2n": B2n,
    "A2_samples": {k: len(v["samples"]) for k, v in A2.items()},
    "B2_samples": {k: len(v["samples"]) for k, v in B2.items()},
    "A2_stats": {k: v["stats"] for k, v in A2.items()},
    "B2_stats": {k: v["stats"] for k, v in B2.items()},
    "combined_A": combA["big_scrollback"]["stats"],
    "combined_B": combB["big_scrollback"]["stats"],
    "red1": red1, "red2": red2, "redc": redc,
    "ok2": ok2, "okc": okc,
    "u1": u1, "u2": u2, "uc": uc,
    "verdict": doc["verdict"],
}, ensure_ascii=False, indent=2))
