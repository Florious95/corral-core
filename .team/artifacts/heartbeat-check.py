#!/usr/bin/env python3
"""试用期心跳的核查脚本。量具都在这里，改一处即可。
两条教训写死在实现里（2026-08-18 各撞一次）：
  ① macOS 的 BSD find 不认 -newermt '-N minutes'，永远返回 0 ⇒ 会把健康运行判成卡死并自动发 P0。
  ② pgrep -x ledger-run 是全机匹配，本机同时有别的工作区在跑驱动器 ⇒ 必须按 cwd 认领自己那个。
"""
import glob, json, os, subprocess, time
REPO = "/Volumes/nvme/Projects/远程Agent安卓"
# 账本/日志按 mtime 取最新，不硬编码——硬编码会让心跳一直读一张过期账本，
# 而那种坏法是静默的（读数看着正常，只是量的是别的东西）。
# 选中的是哪一张必须打出来：让选错可见，而不是让它安静地错。
# 当前在跑的账本由 .team/ledgers/ACTIVE 显式登记（起驱动器时写）。
# ⛔ 不要改回「按 mtime 猜最新」——写一张新账本就会把心跳指到没在跑的那张，
#   而且这种坏法完全静默：读数看着正常，只是量的是别的东西。
_active = f"{REPO}/.team/ledgers/ACTIVE"
if os.path.exists(_active):
    LEDGER = open(_active).read().strip()
else:
    LEDGER = max(glob.glob(f"{REPO}/.team/ledgers/*.json"), key=os.path.getmtime)
DRIVELOG = LEDGER.replace(".team/ledgers/", ".team/ledgers/").rsplit("/",1)[0] + "/" + \
           os.path.basename(LEDGER)[:-5].split("-v")[0] + "-drive.log"
if not os.path.exists(DRIVELOG):
    DRIVELOG = max(glob.glob(f"{REPO}/.team/ledgers/*-drive.log"), key=os.path.getmtime)

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

def seat_states():
    """每席真实工作态：用 nodeprobe 读 pane 标题。
    ⚠️ 不要用 ~/.grok/sessions 的 mtime——那个目录是**全机共享**的，
    本机别的工作区也有 grok 席位在写，读到的活动与本 team 无关（同 pgrep -x 全机匹配那类错）。"""
    sock = os.environ.get("TMUX", "").split(",")[0]
    if not sock:
        return None
    try:
        out = subprocess.run(["nodeprobe", "-S", sock], capture_output=True, text=True, timeout=30).stdout
        nodes = json.loads(out)["nodes"]
    except Exception:
        return None
    return [(n["name"], n["state"]) for n in nodes]

pid, et = my_driver()
seats = seat_states()
l = json.load(open(LEDGER))
states = " ".join(f"{k.replace('t.','')}={v['state'][:4]}" for k, v in l["tasks"].items())
print(f"UTC {time.strftime('%H:%M:%S', time.gmtime())}")
print(f"驱动器: {'在跑 pid=' + pid + ' etime=' + et if pid else '⚠️ 本工程无驱动器'}")
print(f"账本 {os.path.basename(LEDGER)[:-5]} r{l['revision']}: {states}")
print("席位: " + (", ".join(f"{k}={v}" for k, v in seats) if seats else "⚠️ nodeprobe 不可用"))
# ⚠️ 排除 leader 自己那一窗：我在跑心跳，我必然是 working，
# 把它算进去会让「席位全空闲」永远看起来健康。
busy = [k for k, v in (seats or []) if v == "working" and k != "claude_code"]

def driver_phase():
    """驱动器最后一条日志说明它在干什么。
    席位全空闲**不等于**没进展：判据（gradle/go 全套、变异脚本、尺寸不变实验）
    是驱动器自己跑的，那几分钟里席位本来就该是空闲的。
    只看「有没有席位 working」会把每一次跑判据都报成异常——误报多了，真出事时没人当真。"""
    try:
        tail = open(DRIVELOG, encoding="utf-8", errors="replace").read()[-4000:]
    except Exception:
        return ""
    for line in reversed(tail.splitlines()):
        for k in ("判据 acceptance", "等待 wait", "派单", "写回", "轮次"):
            if k in line:
                return k
    return ""

phase = driver_phase()
if pid and (busy or phase in ("判据 acceptance", "等待 wait", "派单", "写回", "轮次")):
    print(f"判读: 健康（{'席位在跑: ' + ','.join(busy) if busy else '驱动器在: ' + phase}）")
else:
    print("判读: ⚠️ 需要看日志尾部再判")
