#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""tools/gate/wiki-ratchet.py —— 架构维基 T3 判据的【增量棘轮】。

同 smell-ratchet.py 的道理：2026-08-21 实测 main 上 T3 已有存量违规
（例：dev.agentmirror.app.conn 的 Frames.kt:538 缺 @err）。
把「必须全绿」当每格判据，会让格为不是自己造成的红卡死，
并诱使席位去改无关文件 —— 违反「一次只改一个缺陷」。

⇒ 判据是【相对冻结基线不许新增】。存量另立格清理。
T1 判据（环依赖 / 包 doc）仍然必须全绿 —— 那是硬约束，不走棘轮。

用法：
  python3 tools/gate/wiki-ratchet.py --root <根> --pkg <包> [--pkg <包> ...]
  python3 tools/gate/wiki-ratchet.py --root <仓根> --pkg <包> ... --freeze
退出码：0=无新增；1=有新增或 T1 红；2=用法/工具链错误。
"""
from __future__ import annotations
import argparse, json, re, subprocess, sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
BASELINE = HERE / "wiki-baseline.json"
VIOL = re.compile(r"^\s*\[(T\d-\d)\]\s+(\S+)\s+(\S+)\s+")


def key_of(item: str) -> str:
    """去掉行号（同 smell-ratchet 的理由：行号漂移会把存量误判成新增）。"""
    return re.sub(r":\d+$", "", item.strip())


def counted(items):
    from collections import Counter
    return Counter(key_of(x) for x in items)

def run(root: Path, pkg: str):
    r = subprocess.run([sys.executable, str(root / "tools/archwiki/build_wiki.py"),
                        "--root", str(root), "--check", "--strict-t3", "--pkg", pkg],
                       cwd=str(root), capture_output=True, text=True)
    out = r.stdout + r.stderr
    viols = sorted({"%s %s %s" % m.groups() for m in
                    (VIOL.match(l) for l in out.splitlines()) if m})
    t1_red = any(l.startswith("T1-") and "FAIL" in l for l in out.splitlines())
    return viols, t1_red, out

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", required=True)
    ap.add_argument("--pkg", action="append", required=True)
    ap.add_argument("--freeze", action="store_true")
    a = ap.parse_args()
    root = Path(a.root).resolve()
    base_all = json.loads(BASELINE.read_text(encoding="utf-8")) if BASELINE.exists() else {}
    rc = 0
    for pkg in a.pkg:
        viols, t1_red, out = run(root, pkg)
        if t1_red:
            print(out); print(f"红：{pkg} 的 T1 判据失败（环依赖/包 doc 是硬约束，⛔ 不走棘轮）", file=sys.stderr)
            rc = 1; continue
        if a.freeze:
            base_all[pkg] = viols
            print(f"冻结 {pkg}：{len(viols)} 条")
            continue
        base = base_all.get(pkg)
        if base is None:
            print(f"{pkg} 没有冻结基线，先跑 --freeze", file=sys.stderr); return 2
        bc, cc = counted(base), counted(viols)
        added = sorted(k for k in cc if cc[k] > bc.get(k, 0))
        gone  = sorted(k for k in bc if cc.get(k, 0) < bc[k])
        print(f"pkg={pkg} 基线={sum(bc.values())} 本次={sum(cc.values())} "
              f"新增={len(added)} 消掉={len(gone)}（键不含行号）")
        for x in gone:  print(f"  ✅ 消掉: {x} ({bc[x]}→{cc.get(x,0)})")
        for x in added: print(f"  ❌ 新增: {x} ({bc.get(x,0)}→{cc[x]})")
        if added: rc = 1
    if a.freeze:
        BASELINE.write_text(json.dumps(base_all, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
        return 0
    if rc:
        print("红：本次引入了新的架构维基违规。⛔ 不许改判据、⛔ 不许 --freeze 洗掉，修掉新增的那几条。", file=sys.stderr)
    return rc

if __name__ == "__main__":
    sys.exit(main())
