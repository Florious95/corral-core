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

v4 增量（2026-08-09，w-test-appseams 停空提示符而 status 全绿案）：框架两个官方信号
双双失真——last_output_at 被 UI 噪声（spinner/状态栏刷新）污染，live 席位精确同刻
"永远在动"；activity=provider_jsonl:assistant_in_flight 在响应中断 jsonl 未闭合时永卡
BUSY。T1/T2 因此对这类停摆全盲（已直报框架 leader）。新增 T4 主力信号：
tmux capture-pane 取 pane **正文**（剥尾部 UI 区 TAIL_STRIP 行），md5 连续
HASH_SAMPLES 次不变即判停滞——真停摆时正文完全静止，UI 动画剥除后免疫噪声。
只做 hash 不解析语义，不违"不读 worker 终端原文"纪律。复活判定同款：hash 变即清零。

v4.3 增量（2026-08-09，用户实证误报过多案）：v4.1 把窗收紧后，"安静但在干活"的席位
被成批误判——①长编译/长测试期间 pane 正文静止 6 分钟即中 T4；②刚结束 turn 等回执的
席位采样点非 BUSY 3 分钟即中 T2，预算 2 次烧穿还假升级 leader。修法：新增客观活性信号
**pane 进程子树累计 CPU 秒**（tmux pane_pid 起走子树求和）——CPU 前进>CPU_EPS 即在干活，
清零 T2/T4 计数与探针账；真停摆（空提示符/死流）CPU 静止，照抓。窗口回调至中间值
（IDLE 1→3、HASH 2→3，约 9 分钟出针）：误报的代价是探针噪声+假升级打断 leader，
已被用户实证为真代价，不再视为"无害"。
"""
import json, subprocess, time, sys, glob, os, hashlib, re
from datetime import datetime, timezone

WS = "/Volumes/nvme/Projects/远程Agent安卓"
INTERVAL = 180          # 采样间隔（秒）
BUSY_STALE_SEC = 900    # T1：BUSY 但无输出的停滞阈值（15 分钟）
IDLE_SAMPLES = 3        # T2：非 BUSY 采样数（v4.3 回调 1→3：v4.1 的"误发无害"被用户实证推翻——等回执的席位 3 分钟即中针+假升级；配合 CPU 活性信号，真停摆约 9 分钟出针）
GLOBAL_SAMPLES = 3      # T3：全局 0 BUSY 采样数
MAX_SAMPLES = 120       # 6 小时心跳
NUDGE_BUDGET = 2        # v3：每席每任务自动探针预算，烧穿才升级 leader
HASH_SAMPLES = 3        # T4：pane 正文 hash 连续不变的采样数（v4.3 回调 2→3；长编译静默由 CPU 信号豁免）
CPU_EPS = 1.0           # v4.3：采样间子树 CPU 前进超此秒数即判"在干活"
TAIL_STRIP = 15         # T4：剥掉 pane 尾部 UI 区行数（输入框/状态栏/任务列表/spinner）


SESSION = "team-remote-agent-android"  # 本工程 team session 名（固定；socket 名 restart 后会变）


def team_tmux() -> tuple[str, str]:
    """发现承载本工程 session 的私有 tmux socket（宿主机可能同时跑多个工程的 team，
    必须按 SESSION 精确匹配，绝不能按 mtime 猜——实测猜会命中别家 team）。"""
    for sock in glob.glob("/private/tmp/tmux-501/ta-*"):
        r = subprocess.run(["tmux", "-S", sock, "list-sessions", "-F", "#{session_name}"],
                           capture_output=True, text=True, timeout=10)
        if SESSION in r.stdout.split():
            return sock, SESSION
    return "", ""


def pane_hash(sock: str, session: str, seat: str) -> str:
    """T4 信号源：窗口名=席位名；正文（剥尾部 UI 区）md5。取不到返回空串（不参与判定）。

    v4.2（w-fix-pairpump 停滞 22 分钟未被抓案）：spinner 计时行（"Sautéed for 22m 39s"、
    "(34s · thinking more)"）出现在**正文区**而非尾部 UI 区，每采样都在变 → 正文 hash
    "永远在动"，T4 失明。修法：hash 前把所有数字序列归一化为 #——计时/耗时/token 计数的
    跳动不再影响指纹，而真实新输出必含非数字变化。副作用（纯数字进度行更新被视作静止）
    仅导致误发中性探针，无害。"""
    r = subprocess.run(["tmux", "-S", sock, "capture-pane", "-p", "-t", f"{session}:{seat}"],
                       capture_output=True, text=True, timeout=10)
    if r.returncode != 0:
        return ""
    body = "\n".join(r.stdout.rstrip().splitlines()[:-TAIL_STRIP])
    return hashlib.md5(re.sub(r"\d+", "#", body).encode()).hexdigest()


def pane_cpu(sock: str, session: str, seat: str) -> float:
    """v4.3 活性信号：pane 进程子树累计 CPU 秒。长编译/长测试期间正文静止但 CPU 在走，
    据此把"安静干活"与"真停摆"分开。天数段(dd-)按 60 进位折算不精确，但只用于
    采样间差值比较，单调性足够。取不到返回 -1（不参与判定）。"""
    r = subprocess.run(["tmux", "-S", sock, "display", "-p", "-t", f"{session}:{seat}", "#{pane_pid}"],
                       capture_output=True, text=True, timeout=10)
    root = r.stdout.strip()
    if r.returncode != 0 or not root.isdigit():
        return -1.0
    ps = subprocess.run(["ps", "-axo", "pid=,ppid=,time="], capture_output=True, text=True, timeout=10)
    kids: dict[str, list[str]] = {}
    times: dict[str, str] = {}
    for line in ps.stdout.splitlines():
        parts = line.split()
        if len(parts) == 3:
            kids.setdefault(parts[1], []).append(parts[0])
            times[parts[0]] = parts[2]
    total, stack = 0.0, [root]
    while stack:
        p = stack.pop()
        stack.extend(kids.get(p, []))
        secs = 0.0
        for part in times.get(p, "0").replace("-", ":").split(":"):
            secs = secs * 60 + float(part)
        total += secs
    return total

os.chdir(WS)
idle_count: dict[str, int] = {}
nudges: dict[str, int] = {}          # v3：seat -> 已发探针数（按 seat:task 键）
last_seen_output: dict[str, str] = {}  # v3：seat -> 上次采样的 last_output_at（复活判定）
pane_hashes: dict[str, str] = {}     # v4：seat -> 上次正文 hash
hash_still: dict[str, int] = {}      # v4：seat -> 连续不变采样数
pane_cpus: dict[str, float] = {}     # v4.3：seat -> 上次子树累计 CPU 秒
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
        sock, session = team_tmux()  # 每轮动态发现（restart 后 socket 名会变）
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
            # v4 主力信号：pane 正文 hash（UI 噪声免疫；框架 last_output_at/BUSY 已证失真）
            h = pane_hash(sock, session, seat) if sock else ""
            if h:
                if h == pane_hashes.get(seat):
                    hash_still[seat] = hash_still.get(seat, 0) + 1
                else:
                    hash_still[seat] = 0
                    # v4 复活判定：正文前进即视为活，探针计数清零
                    if nudges.get(key):
                        print(f"revived: {key} (正文 hash 前进)", file=log, flush=True)
                        nudges[key] = 0
                pane_hashes[seat] = h
            # v4.3 活性豁免：子树 CPU 前进 = 在干活（长编译/长测试正文静止不误报）
            cpu = pane_cpu(sock, session, seat) if sock else -1.0
            if cpu >= 0:
                prev_cpu = pane_cpus.get(seat)
                if prev_cpu is not None and cpu - prev_cpu > CPU_EPS:
                    hash_still[seat] = 0
                    idle_count[seat] = 0
                    if nudges.get(key):
                        print(f"revived: {key} (CPU 前进 {cpu - prev_cpu:.1f}s)", file=log, flush=True)
                        nudges[key] = 0
                pane_cpus[seat] = cpu
            # v3 复活判定：探针发出后 last_output_at 前进 → 计数清零（信号已知可能失真，仅作辅助）
            if nudges.get(key) and last and last != last_seen_output.get(key):
                pass  # v4 起不再凭此清零：last_output_at 被 UI 噪声污染，会把死席误判复活
            stall_why = None
            if hash_still.get(seat, 0) >= HASH_SAMPLES:
                stall_why = f"T4 正文静止 {hash_still[seat]} 采样（约 {hash_still[seat]*INTERVAL//60} 分钟）"
            elif state == "BUSY":
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
                    hash_still[seat] = 0  # v4 同款观察窗
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
        print(f"{now:%H:%M:%S} busy={busy_total} inflight={list(owe.values())} blind={blind} still={hash_still} idle={idle_count} nudges={nudges} g={global_count}", file=log, flush=True)
        if escalations:
            print("STALL_ESCALATION\n" + "\n".join(escalations))
            sys.exit(1)
    except Exception as e:  # 采样失败不许静默死亡：记日志，连续失败也要能触发
        print(f"sample_error: {e}", file=log, flush=True)
    time.sleep(INTERVAL)

print("WATCHDOG_HEARTBEAT_6H")
sys.exit(0)
