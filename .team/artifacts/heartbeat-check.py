#!/usr/bin/env python3
"""试用期心跳的核查脚本。量具都在这里，改一处即可。
两条教训写死在实现里（2026-08-18 各撞一次）：
  ① macOS 的 BSD find 不认 -newermt '-N minutes'，永远返回 0 ⇒ 会把健康运行判成卡死并自动发 P0。
  ② pgrep -x ledger-run 是全机匹配，本机同时有别的工作区在跑驱动器 ⇒ 必须按 cwd 认领自己那个。
"""
import glob, json, os, subprocess, time
REPO = "/Volumes/nvme/Projects/远程Agent安卓"
LEDGER = f"{REPO}/.team/ledgers/overlay-v1.json"   # 当前在跑的账本，换账本改这一行

def my_driver():
    """只认 cwd 落在本工程的那个 ledger-run。"""
    out = subprocess.run(["pgrep", "-x", "ledger-run"], capture_output=True, text=True).stdout.split()
    for pid in out:
        r = subprocess.run(["lsof", "-a", "-p", pid, "-d", "cwd", "-Fn"], capture_output=True, text=True)
        for line in r.stdout.splitlines():
            if line.startswith("n") and line[1:] == REPO:
                et = subprocess.run(["ps", "-o", "etime=", "-p", pid], capture_output=True, text=True).stdout.strip()
                return pid, et
    return None, None

def grok_activity(window=600):
    fs = glob.glob(os.path.expanduser("~/.grok/sessions/**/*.jsonl"), recursive=True)
    now = time.time()
    if not fs: return None, 0
    newest = max(os.path.getmtime(f) for f in fs)
    return int(now - newest), sum(1 for f in fs if now - os.path.getmtime(f) < window)

pid, et = my_driver()
age, n = grok_activity()
l = json.load(open(LEDGER))
states = " ".join(f"{k.replace('t.','')}={v['state'][:4]}" for k, v in l["tasks"].items())
print(f"UTC {time.strftime('%H:%M:%S', time.gmtime())}")
print(f"驱动器: {'在跑 pid=' + pid + ' etime=' + et if pid else '⚠️ 本工程无驱动器'}")
print(f"账本 r{l['revision']}: {states}")
print(f"grok 活动: 最近写入距今 {age}s, 近 600s 内 {n} 个会话文件被写")
print("判读: " + ("健康" if pid and age is not None and age < 600
                 else "⚠️ 需要看日志尾部再判"))
