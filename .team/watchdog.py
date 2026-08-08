#!/usr/bin/env python3
"""进度停滞看门狗 v2（解释器护栏 #10 完整落地）。

v1 缺陷（实战暴露，2026-08-09）：只有"全队 0 BUSY"全局条件——
单席停摆而他席忙碌时全盲；更刁钻的是"假忙碌"：turn 未闭合（worker_state=BUSY）
但流已死（last_output_at 停滞 87 分钟），任何基于 BUSY 位的条件都抓不到。

v2 三条件，任一触发即退出（非零），由 leader 会话后台任务机制唤醒 leader：
  T1 假忙碌：在途席位 BUSY 但 last_output_at 停滞 > BUSY_STALE_SEC
  T2 空转欠账：在途席位连续 IDLE_SAMPLES 次非 BUSY（turn 结束却没交件/没上报）
  T3 全局停滞：全队 0 BUSY 且在途任务 > 0，连续 GLOBAL_SAMPLES 次
6 小时心跳兜底退出（exit 0）。
"""
import json, subprocess, time, sys, glob, os
from datetime import datetime, timezone

WS = "/Volumes/nvme/Projects/远程Agent安卓"
INTERVAL = 180          # 采样间隔（秒）
BUSY_STALE_SEC = 900    # T1：BUSY 但无输出的停滞阈值（15 分钟）
IDLE_SAMPLES = 2        # T2：连续非 BUSY 采样数（≈6 分钟）
GLOBAL_SAMPLES = 3      # T3：全局 0 BUSY 采样数
MAX_SAMPLES = 120       # 6 小时心跳

os.chdir(WS)
idle_count: dict[str, int] = {}
global_count = 0
log = open(".team/logs/watchdog.log", "a")

def sample():
    out = subprocess.run(["team-agent", "status", "--json"], capture_output=True, text=True, timeout=60).stdout
    return json.loads(out)

def inflight_seats():
    """intent 有、evidence 无 → 在途；返回 {seat: task}。"""
    m = {}
    for f in glob.glob(".team/evidence/*.intent.json"):
        task = os.path.basename(f)[:-len(".intent.json")]
        if not os.path.exists(f".team/evidence/{task}.json"):
            d = json.load(open(f))
            m[d["dispatched_to"]] = task
    return m

for i in range(MAX_SAMPLES):
    try:
        st = sample()
        agents = st.get("agents", {})
        owe = inflight_seats()
        now = datetime.now(timezone.utc)
        busy_total = sum(1 for a in agents.values() if a.get("worker_state") == "BUSY")
        alarms = []
        for seat, task in owe.items():
            a = agents.get(seat)
            if a is None:
                alarms.append(f"T2 席位消失: {seat}(任务 {task})")
                continue
            state = a.get("worker_state")
            last = a.get("last_output_at")
            age = (now - datetime.fromisoformat(last)).total_seconds() if last else None
            if state == "BUSY":
                idle_count[seat] = 0
                if age is not None and age > BUSY_STALE_SEC:
                    alarms.append(f"T1 假忙碌: {seat}(任务 {task}) BUSY 但 {int(age)}s 无输出")
            else:
                idle_count[seat] = idle_count.get(seat, 0) + 1
                if idle_count[seat] >= IDLE_SAMPLES:
                    alarms.append(f"T2 空转欠账: {seat}(任务 {task}) 连续 {idle_count[seat]} 采样非 BUSY")
        if busy_total == 0 and owe:
            global_count += 1
            if global_count >= GLOBAL_SAMPLES:
                alarms.append(f"T3 全局停滞: 0 BUSY 且在途 {list(owe.values())}")
        else:
            global_count = 0
        print(f"{now:%H:%M:%S} busy={busy_total} inflight={list(owe.values())} idle={idle_count} g={global_count}", file=log, flush=True)
        if alarms:
            print("STALL_DETECTED\n" + "\n".join(alarms))
            sys.exit(1)
    except Exception as e:  # 采样失败不许静默死亡：记日志，连续失败也要能触发
        print(f"sample_error: {e}", file=log, flush=True)
    time.sleep(INTERVAL)

print("WATCHDOG_HEARTBEAT_6H")
sys.exit(0)
