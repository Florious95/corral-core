#!/usr/bin/env python3
"""进度停滞看门狗 v2（解释器护栏 #10 完整落地）。

v1 缺陷（实战暴露，2026-08-09）：只有"全队 0 BUSY"全局条件——
单席停摆而他席忙碌时全盲；更刁钻的是"假忙碌"：turn 未闭合（worker_state=BUSY）
但流已死（last_output_at 停滞 87 分钟），任何基于 BUSY 位的条件都抓不到。

v2 三条件，任一触发即退出（非零），由 leader 会话后台任务机制唤醒 leader：
  T1 假忙碌：在途席位 BUSY 但 last_output_at 停滞 > BUSY_STALE_SEC
  T2 空转欠账：在途席位连续 IDLE_SAMPLES 次非 BUSY（turn 结束却没交件/没上报）
  T3 全局停滞：全队 0 BUSY 且在途任务 > 0，连续 GLOBAL_SAMPLES 次

v3 增量（2026-08-09，session-ui 席位 API 流超时 70 分钟空耗案）：检测后先**自动处置**——
对 T1/T2 席位直接 send 中性探针（"等裁定请回执，否则继续"），每席每任务预算 NUDGE_BUDGET 次；
下一采样 last_output_at 前进 = 复活成功，计数清零；预算烧穿仍停摆才 exit 1 升级 leader。
T3（系统性停滞）不自动处置，立即升级。探针文本中性化：不会被等裁定的席位误读为裁定。

v3.2 增量（2026-08-09，w-input-keys Cloudflare 524 停摆 13 分钟未被抓案）：
①根因是派单纪律缺口而非条件缺口——本表以 .team/evidence/*.intent.json 判定"在途"，
  leader 派单漏写 intent 则该席位对看门狗**全盲**（任何条件都不评估）。铁律：
  add-agent 派单的同一批操作里必写 intent；本脚本每轮把"live 席位却无在途任务"
  记入日志（blind= 字段）供 leader 巡检对账。
②上游 API 可重试错误（524/529/overloaded/stream timeout）表现为 turn 中断、
  席位非 BUSY 停在空提示符——T2（连续 2 采样非 BUSY ≈6 分钟）即覆盖，无需读 pane 原文；
  探针文本明示"上游 API 报错直接继续即自动重试"，一条消息即救活。
"""
import json, subprocess, time, sys, glob, os
from datetime import datetime, timezone

WS = "/Volumes/nvme/Projects/远程Agent安卓"
INTERVAL = 180          # 采样间隔（秒）
BUSY_STALE_SEC = 900    # T1：BUSY 但无输出的停滞阈值（15 分钟）
IDLE_SAMPLES = 2        # T2：连续非 BUSY 采样数（≈6 分钟）
GLOBAL_SAMPLES = 3      # T3：全局 0 BUSY 采样数
MAX_SAMPLES = 120       # 6 小时心跳
NUDGE_BUDGET = 2        # v3：每席每任务自动探针预算，烧穿才升级 leader

os.chdir(WS)
idle_count: dict[str, int] = {}
nudges: dict[str, int] = {}          # v3：seat -> 已发探针数（按 seat:task 键）
last_seen_output: dict[str, str] = {}  # v3：seat -> 上次采样的 last_output_at（复活判定）
global_count = 0
log = open(".team/logs/watchdog.log", "a")

def nudge(seat: str, task: str, why: str) -> None:
    """v3 自动处置：中性探针（等裁定的席位回执说明即可，不会误读为裁定）。"""
    text = (f"看门狗探针（{why}）：若你在等 leader 裁定请回执说明；若上一轮因上游 API 报错中断"
            f"（524/529/overloaded/timeout 均可重试），直接继续即可；否则从你的知识基底 "
            f"{WS}/.team/nodes/{task}/CLAUDE.md 与已落盘成果继续推进任务 {task}，完成后 report_result。")
    subprocess.run(["team-agent", "send", seat, text], capture_output=True, text=True, timeout=60)

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
        escalations = []   # 升级 leader 的告警（预算烧穿/席位消失/T3）
        for seat, task in owe.items():
            key = f"{seat}:{task}"
            a = agents.get(seat)
            if a is None:
                escalations.append(f"席位消失: {seat}(任务 {task})")
                continue
            state = a.get("worker_state")
            last = a.get("last_output_at")
            age = (now - datetime.fromisoformat(last)).total_seconds() if last else None
            # v3 复活判定：探针发出后 last_output_at 前进 → 计数清零
            if nudges.get(key) and last and last != last_seen_output.get(key):
                print(f"revived: {key} (nudge 后输出前进)", file=log, flush=True)
                nudges[key] = 0
                idle_count[seat] = 0
            stall_why = None
            if state == "BUSY":
                idle_count[seat] = 0
                if age is not None and age > BUSY_STALE_SEC:
                    stall_why = f"假忙碌 BUSY 无输出 {int(age)}s"
            else:
                idle_count[seat] = idle_count.get(seat, 0) + 1
                if idle_count[seat] >= IDLE_SAMPLES:
                    stall_why = f"空转欠账 连续 {idle_count[seat]} 采样非 BUSY"
            if stall_why:
                if nudges.get(key, 0) < NUDGE_BUDGET:
                    nudges[key] = nudges.get(key, 0) + 1
                    last_seen_output[key] = last or ""
                    idle_count[seat] = 0  # 给探针一个观察窗
                    nudge(seat, task, stall_why)
                    print(f"nudge#{nudges[key]}: {key} ({stall_why})", file=log, flush=True)
                else:
                    escalations.append(f"预算烧穿({NUDGE_BUDGET} 探针无效): {seat}(任务 {task}) {stall_why}")
        # T3 修正（实战缺陷 2026-08-09）：短 turn 工作的席位在采样点常非 BUSY，但输出在前进；
        # "有任一在途席位输出前进"即全局有进展，与 BUSY 同等清零 T3 计数。
        progress = any(
            (agents.get(s) or {}).get("last_output_at") not in (None, last_seen_output.get(f"{s}:{t}"))
            for s, t in owe.items()
        )
        for s, t in owe.items():
            a = agents.get(s)
            if a and a.get("last_output_at"):
                last_seen_output[f"{s}:{t}"] = a["last_output_at"]
        if busy_total == 0 and owe and not progress:
            global_count += 1
            if global_count >= GLOBAL_SAMPLES:
                escalations.append(f"T3 全局停滞: 0 BUSY 无输出前进 且在途 {list(owe.values())}")
        else:
            global_count = 0
        # v3.2①：live 工作席（w- 前缀）却无在途 intent = 看门狗盲区，逐轮曝光供 leader 对账
        blind = [s for s in agents if s.startswith("w-") and s not in owe]
        print(f"{now:%H:%M:%S} busy={busy_total} inflight={list(owe.values())} blind={blind} idle={idle_count} nudges={nudges} g={global_count}", file=log, flush=True)
        if escalations:
            print("STALL_ESCALATION\n" + "\n".join(escalations))
            sys.exit(1)
    except Exception as e:  # 采样失败不许静默死亡：记日志，连续失败也要能触发
        print(f"sample_error: {e}", file=log, flush=True)
    time.sleep(INTERVAL)

print("WATCHDOG_HEARTBEAT_6H")
sys.exit(0)
