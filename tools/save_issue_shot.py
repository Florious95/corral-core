#!/usr/bin/env python3
"""把用户在对话里贴的截图落盘。

用户贴的图不是文件，是会话记录里的 base64 块；不落盘就只活在上下文里，
compact 之后清单和派单都指不到它。⇒ 报问题的第一动作就是把它取出来。

用法：
  python3 tools/save_issue_shot.py                 # 列出最近 10 张（带时间戳）
  python3 tools/save_issue_shot.py -n 1 -o 名字     # 存最新一张为 .team/issues/shots/<名字>.png
  python3 tools/save_issue_shot.py -n 2 -o 名字     # 倒数第 2 张
"""
import base64, glob, json, os, sys

WS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOGDIR = os.path.expanduser("~/.claude/projects/-Volumes-nvme-Projects---Agent--")
OUT = os.path.join(WS, ".team/issues/shots")

def blocks():
    f = max(glob.glob(os.path.join(LOGDIR, "*.jsonl")), key=os.path.getmtime)
    out = []
    for line in open(f, encoding="utf-8"):
        try: d = json.loads(line)
        except Exception: continue
        c = (d.get("message") or {}).get("content")
        if isinstance(c, list):
            for b in c:
                if isinstance(b, dict) and b.get("type") == "image":
                    out.append((d.get("timestamp"), b["source"]))
    return out

def main():
    a = sys.argv[1:]
    idx = int(a[a.index("-n") + 1]) if "-n" in a else None
    name = a[a.index("-o") + 1] if "-o" in a else None
    bs = blocks()
    if idx is None:
        for i, (ts, s) in enumerate(reversed(bs[-10:]), 1):
            print(f"-n {i}  {ts}  {s.get('media_type')}  {len(s.get('data',''))}B")
        return
    ts, s = bs[-idx]
    ext = {"image/png": "png", "image/jpeg": "jpg"}.get(s.get("media_type"), "bin")
    os.makedirs(OUT, exist_ok=True)
    p = os.path.join(OUT, f"{name or ts.replace(':','-')}.{ext}")
    open(p, "wb").write(base64.b64decode(s["data"]))
    print(os.path.relpath(p, WS), os.path.getsize(p), "B", ts)

main()
