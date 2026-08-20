#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""tools/gate/smell-ratchet.py —— 代码坏味道的【增量棘轮】判据。

为什么不是「必须为 0」：2026-08-21 实测 main 上已有 33 条存量坏味道
（app lint 16 / server gofmt 4 + staticcheck 13），单元测试则 1689 条全绿。
把「全绿」设成每格判据，会让每个格都为不是自己造成的红而卡死，
并诱使席位去改无关代码 —— 直接违反「一次只改一个缺陷」。

所以判据是：**相对冻结基线，不许新增**。存量另外立格清理。

判据形态（记操作数，不只记判决）：输出逐条列出「新增了哪几条」与「消掉了哪几条」，
而不是只给一个数字。⛔ 不许只打印「坏味道增加了」。

用法：
  python3 tools/gate/smell-ratchet.py --face app  [--root <仓库或worktree根>]
  python3 tools/gate/smell-ratchet.py --face app  --freeze     # 重新冻结基线（需人显式跑）

退出码：0=没有新增（可以少于基线）；1=有新增；2=用法/工具链错误。
"""
from __future__ import annotations
import argparse, json, os, subprocess, sys, tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
BASELINE = HERE / "smell-baseline.json"

def collect(root: Path, face: str) -> list[str]:
    """跑 gate.py 的单面套件，回收坏味道条目 id（不含单测失败）。"""
    gate = root / "tools" / "gate" / "gate.py"
    if not gate.exists():
        print(f"找不到 {gate}", file=sys.stderr); return None
    with tempfile.NamedTemporaryFile("r", suffix=".json", delete=False) as tf:
        out = tf.name
    rc = subprocess.run([sys.executable, str(gate), "run", face, "--out", out],
                        cwd=str(root)).returncode
    if rc == 2:
        print("gate.py run 用法/工具链错误", file=sys.stderr); return None
    data = json.loads(Path(out).read_text(encoding="utf-8"))
    os.unlink(out)
    # 坏味道 = 带工具前缀的条目；不带前缀的是真单测失败，不归本判据管
    smells, tests = [], []
    for f in data.get("failures") or []:
        t = f.get("test", "")
        (smells if ":" in t.split(" ")[0] and t.split(":")[0] in
         ("lint", "gofmt", "staticcheck", "vet") else tests).append(t)
    if tests:
        print("⚠️ 有真单测失败（不归本判据管，由套件判据负责）：", file=sys.stderr)
        for t in tests[:20]: print("   x", t, file=sys.stderr)
    return sorted(smells)

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--face", required=True, choices=["app", "server"])
    ap.add_argument("--root", default=None)
    ap.add_argument("--freeze", action="store_true")
    a = ap.parse_args()
    root = Path(a.root).resolve() if a.root else HERE.parent.parent
    cur = collect(root, a.face)
    if cur is None: return 2

    base_all = json.loads(BASELINE.read_text(encoding="utf-8")) if BASELINE.exists() else {}
    if a.freeze:
        base_all[a.face] = cur
        BASELINE.write_text(json.dumps(base_all, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
        print(f"已冻结 {a.face} 基线：{len(cur)} 条 → {BASELINE}")
        return 0

    base = base_all.get(a.face)
    if base is None:
        print(f"{a.face} 没有冻结基线，先跑 --freeze", file=sys.stderr); return 2
    bset, cset = set(base), set(cur)
    added, gone = sorted(cset - bset), sorted(bset - cset)
    print(f"face={a.face} 基线={len(bset)} 本次={len(cset)} 新增={len(added)} 消掉={len(gone)}")
    for x in gone:  print("  ✅ 消掉:", x)
    for x in added: print("  ❌ 新增:", x)
    if added:
        print("红：本次引入了新的代码坏味道。⛔ 不许改判据、⛔ 不许 --freeze 洗掉，请修掉新增的那几条。", file=sys.stderr)
        return 1
    return 0

if __name__ == "__main__":
    sys.exit(main())
