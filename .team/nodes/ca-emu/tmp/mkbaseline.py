#!/usr/bin/env python3
"""把 .team/perf/raw/*.log 的 PerfTrace 行归并成 baseline JSON。

只认 `adb logcat -s PerfTrace` 原文；⛔ 不识图、⛔ 不取帧。
- 按 open_id 归并；只取 emitted!=0 的事件行（emitted=0 是仪表的「没做/没做成」记录）。
- 八键齐且单调不减才算一个合格样本；不齐的进 outliers（⛔ 不丢，附原始日志路径）。
- 极端值（IQR 外 / max）**不剔除**，只在 outliers 里另列一条 reason=extreme_value。
"""
import json, os, re, sys, statistics

RAW = sys.argv[1]
OUT = sys.argv[2]
META = json.loads(sys.argv[3])

KEYS = ["tap", "route_enter", "subscribe_sent", "geom_seed",
        "first_frame_recv", "snapshot_applied", "first_draw", "layout_settled"]

line_re = re.compile(r'D PerfTrace: (open_id=\S+) ev=(\S+) t=(\d+)(.*)$')


def parse(path):
    """返回 [(open_id, {ev: t}, [原始行])]，按出现顺序。"""
    opens = {}
    order = []
    for ln in open(path, encoding='utf-8', errors='replace'):
        m = line_re.search(ln)
        if not m:
            continue
        oid = m.group(1).split('=', 1)[1]
        ev, t, extra = m.group(2), int(m.group(3)), m.group(4)
        if 'emitted=0' in extra:
            continue
        if oid not in opens:
            opens[oid] = {'t': {}, 'lines': []}
            order.append(oid)
        opens[oid]['lines'].append(ln.rstrip())
        opens[oid]['t'].setdefault(ev, t)
    return [(o, opens[o]['t'], opens[o]['lines']) for o in order]


def pct(vals, p):
    """线性插值分位（p 为 0..100）。"""
    if not vals:
        return None
    s = sorted(vals)
    if len(s) == 1:
        return s[0]
    k = (len(s) - 1) * p / 100.0
    f = int(k)
    c = min(f + 1, len(s) - 1)
    return round(s[f] + (s[c] - s[f]) * (k - f), 1)


fixtures = {}
for fx in ["real_claude_idle", "redraw_tui", "big_scrollback"]:
    samples, outliers = [], []
    for fn in sorted(os.listdir(RAW)):
        if not fn.startswith(fx + '-') or not fn.endswith('.log'):
            continue
        path = os.path.join(RAW, fn)
        rel = os.path.join('.team/perf/raw', fn)
        found = parse(path)
        if not found:
            outliers.append({"open_id": None, "reason":
                             "该次冷点开未采到任何 PerfTrace 事件行（见原始日志）",
                             "raw_log_path": rel})
            continue
        # 一次冷点开正常只有一个 open_id；多于一个则全部记入，合格的进 samples
        for oid, t, lines in found:
            missing = [k for k in KEYS if k not in t]
            if missing:
                outliers.append({"open_id": oid,
                                 "reason": "八键不齐，缺 " + ",".join(missing),
                                 "raw_log_path": rel})
                continue
            base = t['tap']
            rel_t = {k: t[k] - base for k in KEYS}
            seq = [rel_t[k] for k in KEYS]
            if any(b < a for a, b in zip(seq, seq[1:])):
                outliers.append({"open_id": oid, "reason": "八键非单调不减: " + str(rel_t),
                                 "raw_log_path": rel})
                continue
            samples.append({"open_id": oid, "t": rel_t, "note": "",
                            "raw_log_path": rel})
    # 八键不齐时仍把「能算的前缀分段」如实留下（⛔ 不当基线用，只供 leader 判断）
    partial_runs, partial_stats = [], {}
    for fn in sorted(os.listdir(RAW)):
        if not fn.startswith(fx + '-') or not fn.endswith('.log'):
            continue
        rel = os.path.join('.team/perf/raw', fn)
        for oid, t, lines in parse(os.path.join(RAW, fn)):
            if 'tap' not in t:
                continue
            base = t['tap']
            partial_runs.append({
                "open_id": oid, "raw_log_path": rel,
                "t": {k: t[k] - base for k in KEYS if k in t},
                "missing": [k for k in KEYS if k not in t],
            })
    for seg in KEYS[1:]:
        vals = [r['t'][seg] for r in partial_runs if seg in r['t']]
        if not vals:
            partial_stats[seg] = {"n": 0}
            continue
        partial_stats[seg] = {"mean": round(statistics.fmean(vals), 1),
                              "p50": pct(vals, 50), "p95": pct(vals, 95),
                              "max": max(vals), "min": min(vals), "n": len(vals)}

    stats = {}
    for seg in ["first_draw", "layout_settled"]:
        vals = [s['t'][seg] for s in samples]
        stats[seg] = {
            "mean": round(statistics.fmean(vals), 1) if vals else None,
            "p50": pct(vals, 50), "p95": pct(vals, 95),
            "max": max(vals) if vals else None,
            "n": len(vals),
        }
    # 极端值：不剔除，只标注（>= p95 且 > 1.5×p50 的样本）
    for seg in ["first_draw", "layout_settled"]:
        st = stats[seg]
        if st["p50"]:
            for s in samples:
                v = s['t'][seg]
                if v >= st["p95"] and v > 1.5 * st["p50"]:
                    outliers.append({
                        "open_id": s["open_id"],
                        "reason": f"极端值（未剔除，已计入统计）: {seg}={v}ms "
                                  f"vs p50={st['p50']}ms p95={st['p95']}ms",
                        "raw_log_path": s["raw_log_path"]})
    fixtures[fx] = {"samples": samples, "stats": stats, "outliers": outliers,
                    "partial_runs": partial_runs, "partial_stats": partial_stats}

doc = dict(META)
doc["fixtures"] = fixtures
json.dump(doc, open(OUT, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
print(json.dumps({k: {"n_samples": len(v["samples"]),
                      "n_outliers": len(v["outliers"]),
                      "stats": v["stats"]} for k, v in fixtures.items()},
                 ensure_ascii=False, indent=2))
