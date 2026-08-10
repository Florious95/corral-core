#!/usr/bin/env python3
"""全自动编排引擎（确定性状态机，非 LLM）。

设立背景（用户裁定 2026-08-10）：全自动编排 = 跑在基础设施上的脚本自动把任务一棒棒交接下去，
有问题才转裁定席。把 leader 绑定交给一个 codex pane 让它人肉推进，是换岗不是自动化。

链路契约（框架队 leader 2026-08-10 给的实现口径，源码级约束，勿自造）：
  1. 派单用 `ta send --json`，**拿回的 message_id 就是 case_id**，全链唯一关联键。
  2. 席位收工必须调 report_result(presentation={"sink":"silent","class":"stage_result",
     "case_id":"<那个 message_id>"})。class 非 stage_result 会被强制投 leader；
     sink=silent 是「照样落库、只是不 live 注入」，不是丢弃；case_id 空则 missing_case_id。
  3. 结构化产物**不塞 envelope**（闭合 schema，自定义键静默丢弃）——先把证据 JSON 写盘，
     再 report_result，把证据路径放进 artifacts。本工程的证据文件 .team/evidence/<task>.json
     就充当这个信封，不另立第二处。
  4. 引擎取件是纯 pull：`ta results --case <case_id> --json` 只用来判「报没报」，
     真数据一律从证据文件读。leader pane 因此完全不参与推进。
  5. **no_envelope 必须带宽限期**：框架实测出现过「结果已落库、信封 78 秒后才写完」的竞态，
     宽限 ≥90 秒（见 GRACE）。

驱动方式与框架队不同：他们是 while True + sleep(30) 轮询，本工程工程常识红线 1（静默经济）
禁止常驻进程定频派生子进程，故改为**事件驱动**——哑 pane（.team/leader-sink.py）收到框架注入后
写 fifo 推醒本引擎，引擎平时阻塞在 select 上，空闲零 CPU；另留一个长兜底超时防止漏事件。

判不出一律转裁定席（halt 是默认）；只有「编排本身要改」才升级人工（四类升级件）。

用法：
  python3 .team/orchestrator.py plan          # 只读：当前该干什么
  python3 .team/orchestrator.py verify <task> # 只复跑某任务的 acceptance
  python3 .team/orchestrator.py run --apply   # 执行一轮
  python3 .team/orchestrator.py loop --apply  # 常驻，事件驱动
"""
import json, os, re, select, subprocess, sys, time

WS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.chdir(WS)
TA = os.path.join(WS, ".team", "ta")          # 净化包装器，禁止直调 team-agent
STATE = ".team/orchestrator-state.json"
ESCALATE = ".team/escalations-for-human.md"
WAKE = ".team/orch.wake"
ADJUDICATOR = "judge"
GRACE = 120                                   # 报了但证据未落盘的宽限（框架实测 78s，留余量）
FALLBACK = int(os.environ.get("ORCH_FALLBACK", "1800"))   # 事件漏了的兜底唤醒
MAX_ATTEMPTS = 2                              # 同一任务自动返工上限，超了升级人工（防无限重试烧额度）
ALIVE_AFTER = 180                             # 派单多久后核真活性
STALL = 1200                                  # 判停摆的静止时长（配合 CPU 活性，见 seat_cpu）
CPU_EPS = 1.0                                 # 子树累计 CPU 秒的前进阈值

# 席位的免审批环境：以前由「leader 是带 bypass 的 Claude Code 会话」隐式提供，
# leader 换成本引擎后必须显式给，否则席位会卡在 codex 审批提示上一动不动
# （实证 2026-08-10：三席各卡 6 小时，status 一直显示空闲，屏幕停在「Yes, proceed (y)」）。
for _k, _v in {
    "TEAM_AGENT_LEADER_BYPASS": "1",
    "TEAM_AGENT_LEADER_BYPASS_FLAG": "--dangerously-bypass-approvals-and-sandbox",
    "TEAM_AGENT_LEADER_BYPASS_PROVIDER": "codex",
    "TEAM_AGENT_LEADER_BYPASS_SOURCE": "leader_process",
    "TEAM_AGENT_MCP_AUTO_APPROVE": "team_orchestrator",
    "TEAM_AGENT_MCP_AUTO_APPROVE_SOURCE": "leader_bypass",
}.items():
    os.environ.setdefault(_k, _v)

# ---------- taskbook 解析（受限子集，机械抽取，沿用 tools/basegen.py 做法，不引 pyyaml） ----------

def load_tasks():
    src = open("taskbook.yaml", encoding="utf-8").read()
    out = []
    for tid, body in re.findall(r"^  - id: (\S+)\n((?:    .*\n|\n(?=    ))*)", src, re.M):
        ev = _strings(_field(body, "evidence") or "")
        # 排期约束常写在 goal 散文里（如「排期：P0 波次收口后派」），deps 里看不见。
        # 引擎不猜它指哪些前置——命中即转裁定，由裁定席决定放行还是补 deps。
        hold = re.search(r"排期[:：]([^\n]{0,60})", body)
        out.append({
            "id": tid,
            "deps": _strings(_field(body, "deps") or ""),
            "acceptance": _strings(_field(body, "acceptance") or ""),
            "evidence": ev[0] if ev else "",
            "contention": (_field(body, "contention") or "").strip(),
            "hold": hold.group(1).strip() if hold else "",
        })
    return out


def _field(body, name):
    m = re.search(rf"^    {name}:(.*(?:\n      .*)*)$", body, re.M)
    return m.group(1) if m else None


def _strings(raw):
    return [s.replace('\\"', '"').replace("\\\\", "\\")
            for s in re.findall(r'"((?:[^"\\]|\\.)*)"', raw)]


# ---------- 基础设施调用 ----------

def run(argv, **kw):
    return subprocess.run(argv, capture_output=True, text=True, **kw)


def ta_json(args):
    r = run([TA] + args + ["--workspace", ".", "--json"])
    try:
        return json.loads(r.stdout)
    except Exception:
        return {"ok": False, "raw": (r.stdout + r.stderr)[-300:]}


def live_seats():
    seats = {}
    for line in run([TA, "status", "--workspace", "."]).stdout.splitlines():
        if "," in line and not line.startswith("To wait"):
            name, st = line.rsplit(",", 1)
            if st.strip() != "错误":
                seats[name.strip()] = st.strip()
    return seats


# ---------- 状态 ----------

def state_load():
    if os.path.exists(STATE):
        try:
            return json.load(open(STATE, encoding="utf-8"))
        except Exception:
            pass
    return {"cases": {}, "pending_adjudication": {}}


def state_save(s):
    json.dump(s, open(STATE, "w", encoding="utf-8"), ensure_ascii=False, indent=2)


def evidence_of(t):
    p = t["evidence"]
    if not p or not os.path.exists(p):
        return None
    try:
        return json.load(open(p, encoding="utf-8"))
    except Exception:
        return {"status": "坏json"}


def classify(tasks, s):
    seats = live_seats()
    ev = {t["id"]: evidence_of(t) for t in tasks}
    done = {tid for tid, e in ev.items() if e and e.get("status") == "pass"}
    case_by_task = {c["task"]: cid for cid, c in s["cases"].items()
                    if c.get("state") != "processed"}
    out = {}
    for t in tasks:
        tid = t["id"]
        e = ev[tid]
        cid = case_by_task.get(tid)
        seat = s["cases"].get(cid, {}).get("seat") if cid else None
        if not seat:                      # 引擎重启或他人派的单：认盘上的 intent，防双派
            ip = (t["evidence"] or "").replace(".json", ".intent.json")
            if os.path.exists(ip):
                try:
                    seat = json.load(open(ip, encoding="utf-8")).get("dispatched_to")
                except Exception:
                    seat = None
        if e and e.get("status") == "pass":
            out[tid] = ("DONE", "")
        elif e:
            out[tid] = ("DELIVERED", f"status={e.get('status')} seat={seat}")
        elif cid and seat in seats:
            out[tid] = ("IN_FLIGHT", f"{seat}={seats[seat]} case={cid}")
        elif cid:
            out[tid] = ("SEAT_GONE", f"{seat} 已不在且无证据 case={cid}")
        elif not set(t["deps"]) <= done:
            out[tid] = ("BLOCKED_DEPS", ",".join(sorted(set(t["deps"]) - done)))
        else:
            out[tid] = ("READY", "")
    return out, ev


# ---------- 动作 ----------

def verify(t):
    res = []
    for cmd in t["acceptance"]:
        r = run(["bash", "-lc", cmd])
        res.append((cmd, r.returncode, "\n".join((r.stdout + r.stderr).strip().splitlines()[-8:])))
    return all(rc == 0 for _, rc, _ in res), res


def to_adjudicator(tid, why, s):
    key = f"{tid}:{why[:40]}"
    if s["pending_adjudication"].get(key):
        return
    r = ta_json(["send", ADJUDICATOR,
                 f"[编排引擎·自动转裁定] 任务 {tid}：{why} "
                 f"裁定后把结论写回 .team/evidence/{tid}.json（status 只允许 pass/red/blocked），"
                 f"引擎只认证据文件，不读回复。"])
    if r.get("ok"):
        s["pending_adjudication"][key] = True
    else:
        # 静默失败是最坏的形态（实证：owner gate 拒绝时引擎照常打日志却什么也没做）
        print(f"!! 转裁定失败 {tid}: {r.get('reason') or r.get('error') or r}", flush=True)


def escalate(text):
    with open(ESCALATE, "a", encoding="utf-8") as f:
        f.write(text.rstrip() + "\n\n")


ROLE_TMPL = """---
name: {seat}
role: {tid} 承办
provider: codex
auth_mode: subscription
permission_mode: auto_approve
profile: codex-default
model: gpt-5.6-sol
tools:
  - fs_read
  - fs_list
  - fs_write
  - execute_bash
  - mcp_team
  - provider_builtin
---

你承办任务 `{tid}`。**一次性席位，交件即退役。**
知识基底（开工前完整读）：`{base}`

## 验收（编排引擎会原样复跑，不看你的自报）
{acc}

## 交件契约（三个值一字不改，源码级约束）
1. **先把证据写盘** `{ev}`：`status` 只允许 `pass`/`red`/`blocked` 三值，
   带 `tests`（argv+rc 原文）、`changes`、`deviation`（无则空数组）。
2. **再**调恰好一次：
   `report_result(..., presentation={{"sink":"silent","class":"stage_result","case_id":"{case}"}})`
   —— `class` 非 `stage_result` 会被框架强制投 leader；`sink=silent` 是「照样落库、只是不打扰」；
   `case_id` 缺失直接 `missing_case_id`。artifacts 里放证据文件路径。
3. 结构化数据一律写证据文件，**不要塞进 result envelope**（闭合 schema，自定义键会被静默丢弃）。

## 纪律
- 写入范围严格限于 taskbook 该条 write_scope，越界即退件。
- 只向 `judge` 投消息，**严禁向 leader 发任何消息**。
- 红线继承 CLAUDE.md：密钥/profile 原文禁读；配对 token 与 TS authkey 不落日志、不上屏、
  不入截图，只经 `TS_AUTHKEY` 环境变量（**严禁 argv flag**）；禁 git push；
  绝不触碰生产 daemon 与用户真实 tmux；测试一律 `env -u TEAM_AGENT_*` 且自建隔离环境、用后零残留。
- 判不出就停下问 `adjudicator`，不许猜。
"""


def redispatch(tid, t, s, notes):
    """返工回路：status=red 的任务归档旧证据后弃 id 重派，返工要点随派单带过去。
    （没有这一环时 red 只会一直转裁定，任务永远回不到执行面。）"""
    tries = s.setdefault("attempts", {}).get(tid, 0) + 1
    s["attempts"][tid] = tries
    if tries > MAX_ATTEMPTS:
        print(f"!! {tid} 已自动返工 {tries - 1} 次仍不过，停止重试并升级人工", flush=True)
        escalate(f"- 日期：{time.strftime('%F')}\n"
                 f"  决定：任务 `{tid}` 连续 {tries - 1} 次自动返工均未通过，编排器停止重试"
                 f"（防止无限重试烧额度）。最近一次结论：{notes[:200]}\n"
                 f"  所需人工动作：请定夺——继续攻这个根因、改验收口径、还是把该项移出自动链。")
        for cid2, c2 in s["cases"].items():
            if c2["task"] == tid and c2["state"] != "processed":
                run([TA, "stop-agent", c2["seat"], "--workspace", "."])
                c2["state"] = "processed"
        return
    os.makedirs(".team/evidence/archive", exist_ok=True)
    if t["evidence"] and os.path.exists(t["evidence"]):
        os.rename(t["evidence"],
                  f".team/evidence/archive/{tid}-{time.strftime('%Y%m%dT%H%M%S')}.json")
    ip = (t["evidence"] or "").replace(".json", ".intent.json")
    if os.path.exists(ip):
        os.remove(ip)
    for cid, c in s["cases"].items():
        if c["task"] == tid:
            if c["state"] != "processed":
                run([TA, "stop-agent", c["seat"], "--workspace", "."])   # 重派前退役旧席位，防席位泄漏
            c["state"] = "processed"
    dispatch(tid, t, s, rework=notes)


def dispatch(tid, t, s, rework=""):
    """basegen → role file → add-agent → send --json 取 case_id → 落 intent。"""
    base = f".team/nodes/{tid}/CLAUDE.md"
    r = run(["python3", "tools/basegen.py", tid])
    if r.returncode != 0 or not os.path.exists(base):
        return to_adjudicator(tid, f"basegen 失败 rc={r.returncode}：{(r.stderr or r.stdout)[-200:]}", s)

    used = {c.get("seat") for c in s["cases"].values()}
    stem = "w-" + re.sub(r"^(fix|feat|test|audit)-", "", tid)[:24]
    seat, n = stem, 1
    while seat in used:                              # A-24：死 id 不复用
        n += 1
        seat = f"{stem}{n}"

    # case_id 要先于 role file 存在，但它来自派单 send —— 故 role 里用占位，派单消息里给准值
    role = f"agents/{seat}.md"
    open(role, "w", encoding="utf-8").write(ROLE_TMPL.format(
        seat=seat, tid=tid, base=base, ev=t["evidence"], case="见派单消息中的 case_id",
        acc="\n".join(f"- `{a}`" for a in t["acceptance"]) or "- （taskbook 未给 acceptance，禁止自拟）"))
    r = run([TA, "add-agent", seat, "--role-file", role, "--workspace", "."])
    if "ok: True" not in r.stdout:
        return to_adjudicator(tid, f"add-agent 失败：{(r.stdout + r.stderr)[-200:]}", s)

    sent = ta_json(["send", seat,
                    f"任务 `{tid}`｜知识基底 `{base}`｜验收由引擎复跑：" +
                    " / ".join(t["acceptance"]) +
                    f"｜证据先写 `{t['evidence']}`（status 只允许 pass/red/blocked），"
                    f"再 report_result(presentation={{\"sink\":\"silent\",\"class\":\"stage_result\","
                    f"\"case_id\":\"<本条消息的 message_id>\"}})｜"
                    f"只向 adjudicator 投消息，严禁向 leader 发消息。**开工。**"])
    cid = sent.get("message_id")
    if not cid:
        return to_adjudicator(tid, f"派单 send 未回 message_id：{str(sent)[:200]}", s)
    # case_id 派单时才知道，回填进派单消息（A-09：补发必须是明确指令，不是普通回复）
    run([TA, "send", seat,
         f"[补充·同一任务 {tid}] 你的 case_id = `{cid}`，report_result 的 presentation.case_id "
         f"必须原样用它。继续执行，不必回复本条。", "--workspace", "."])
    s["cases"][cid] = {"task": tid, "seat": seat, "at": time.time(),
                       "state": "in_flight", "alive_checked": False}
    json.dump({"task_id": tid, "dispatched_to": seat, "base": base, "case_id": cid,
               "at": time.strftime("%Y-%m-%d %T"), "note": "编排引擎自动派单"},
              open(t["evidence"].replace(".json", ".intent.json"), "w", encoding="utf-8"),
              ensure_ascii=False)


def check_alive(cid, s):
    """核真活性：codex rollout 里必须出现 reasoning/custom_tool_call；status=工作 不作数
    （实证：死代理导致的哑火席位会长期假 BUSY，见 .team/ta 头注释）。"""
    import glob
    c = s["cases"][cid]
    if c.get("alive_checked") or time.time() - c["at"] < ALIVE_AFTER:
        return
    c["alive_checked"] = True
    for f in glob.glob(os.path.expanduser("~/.codex/sessions/*/*/*/*.jsonl")):
        if os.path.getmtime(f) <= c["at"]:
            continue
        with open(f, encoding="utf-8", errors="ignore") as fh:
            if any('"reasoning"' in l or '"custom_tool_call"' in l for l in fh):
                return
    to_adjudicator(c["task"], f"新席位 {c['seat']} 起后 {ALIVE_AFTER}s 零模型产出（哑火形态，"
                              f"参考 .team/ta 头注释的死代理实案），建议弃 id 重派", s)


def seat_cpu(seat):
    """席位 pane 进程子树的累计 CPU 秒。判活性必须看真活动，不能看「有没有写证据」——
    证据是终态产物，长任务中途必然没有，拿它当停摆信号会把正在干活的席位打断
    （实证 2026-08-10：引擎 stop+start 打断了两个正在跑的席位）。做法同 .team/watchdog.py v4.3。"""
    r = run(["tmux", "-S", os.environ.get("TMUX_SOCK", "/private/tmp/tmux-501/ta-b7cc1c640ccf"),
             "list-panes", "-t", f"team-remote-agent-android:{seat}", "-F", "#{pane_pid}"])
    root = r.stdout.strip().splitlines()
    if not root:
        return None                                   # 窗口没了 = 另一回事，交给 SEAT_GONE
    ps = run(["ps", "-axo", "pid=,ppid=,time="]).stdout
    kids, secs = {}, {}
    for line in ps.splitlines():
        f = line.split()
        if len(f) >= 3:
            kids.setdefault(f[1], []).append(f[0])
            t = f[2].split(":")
            secs[f[0]] = int(t[-2]) * 60 + float(t[-1]) if len(t) >= 2 else 0.0
    total, stack = 0.0, [root[0]]
    while stack:
        pid = stack.pop()
        total += secs.get(pid, 0.0)
        stack.extend(kids.get(pid, []))
    return total


def revive_if_stalled(cid, s):
    """在途但长时间无证据 = 停摆（卡审批、卡重连、跑飞）。先救一次（stop+start，
    会话保留、环境按当前 leader 重取），再停摆就转裁定，不无限重试。"""
    c = s["cases"][cid]
    cpu = seat_cpu(c["seat"])
    if cpu is not None and cpu - c.get("cpu", -1) > CPU_EPS:      # 在真干活 → 清零停摆计时
        c["cpu"], c["last_progress"] = cpu, time.time()
        return
    age = time.time() - c.get("last_progress", c["at"])
    if age < STALL or c.get("revived_at"):
        if age >= STALL and c.get("revived_at") and time.time() - c["revived_at"] > STALL:
            print(f"救不活，弃 id 重派 {c['task']}（席位 {c['seat']}）", flush=True)
            run([TA, "stop-agent", c["seat"], "--workspace", "."])
            c["state"] = "processed"
            tasks = {x["id"]: x for x in load_tasks()}
            dispatch(c["task"], tasks[c["task"]], s,
                     rework=f"前席 {c['seat']} 停摆无产出（{int(age / 60)} 分钟），本轮从头做")
        return
    print(f"救停摆席位 {c['seat']}（{int(age/60)} 分钟无证据）", flush=True)
    run([TA, "stop-agent", c["seat"], "--workspace", "."])
    time.sleep(2)
    r = run([TA, "start-agent", c["seat"], "--workspace", "."])
    c["revived_at"] = time.time()
    if "ok: True" not in r.stdout:
        to_adjudicator(c["task"], f"席位 {c['seat']} 停摆且 start-agent 失败："
                                  f"{(r.stdout + r.stderr)[-200:]}", s)


def harvest(cid, s, tasks_by_id):
    """按框架口径取件：results --case 只判报没报，真数据读证据文件，缺件留宽限。"""
    c = s["cases"][cid]
    if c["state"] == "processed":
        return
    res = ta_json(["results", "--case", cid])
    reported = bool(res.get("results") or res.get("collected_results") or res.get("ok") and res.get("result"))
    if not reported:
        return
    c["state"] = "reported"
    t = tasks_by_id[c["task"]]
    if not (t["evidence"] and os.path.exists(t["evidence"])):
        if time.time() - c["at"] > GRACE:
            to_adjudicator(c["task"], f"case {cid} 已报但证据文件 {t['evidence']} 超 {GRACE}s 未落盘"
                                      f"（no_envelope）", s)
        return                                        # 宽限内不判，等下次唤醒
    e = evidence_of(t) or {}
    if e.get("status") == "red":
        c["state"] = "processed"
        return redispatch(c["task"], t, s, str(e.get("notes") or "见归档证据")[:300])
    if e.get("status") != "pass" or e.get("deviation") or e.get("ui_review"):
        to_adjudicator(c["task"], f"交件 status={e.get('status')}，deviation={bool(e.get('deviation'))}，"
                                  f"ui_review={bool(e.get('ui_review'))}", s)
        c["state"] = "processed"
        return
    green, res_ = verify(t)
    if not green:
        to_adjudicator(c["task"], "引擎复跑 acceptance 未全绿：" +
                       "; ".join(f"rc={rc} {cmd[:60]}" for cmd, rc, _ in res_ if rc), s)
        c["state"] = "processed"
        return
    run(["git", "add", "-A"])
    run(["git", "commit", "-q", "-m", f"{c['task']}: 引擎复跑验收全绿，自动销账 [orchestrator]"])
    run([TA, "stop-agent", c["seat"], "--workspace", "."])
    c["state"] = "processed"


def cycle(apply_):
    tasks = load_tasks()
    by_id = {t["id"]: t for t in tasks}
    s = state_load()
    log = []
    if apply_:
        for cid in list(s["cases"]):
            harvest(cid, s, by_id)
    st, _ = classify(tasks, s)
    for tid, (kind, note) in st.items():
        if kind == "READY":
            t = by_id[tid]
            if t["hold"]:
                log.append(f"→裁定 {tid}（goal 带排期约束「{t['hold']}」，引擎不猜前置）")
                if apply_:
                    to_adjudicator(tid, f"deps 已满足但 goal 写着排期约束「{t['hold']}」，"
                                        f"请裁定：现在放行就回一句放行并把前置补进 taskbook 的 deps；"
                                        f"否则说明还等什么", s)
                continue
            log.append(f"派单 {tid}")
            if apply_:
                dispatch(tid, t, s)
        elif kind == "IN_FLIGHT":
            log.append(f"在途 {tid}（{note}）")
            if apply_:
                cid = note.split("case=")[-1]
                if cid in s["cases"]:
                    check_alive(cid, s)
                    revive_if_stalled(cid, s)
        elif kind == "DELIVERED" and (evidence_of(by_id[tid]) or {}).get("status") == "red":
            log.append(f"返工重派 {tid}")
            if apply_:
                e = evidence_of(by_id[tid]) or {}
                redispatch(tid, by_id[tid], s, str(e.get("notes") or "见归档证据")[:300])
        elif kind in ("DELIVERED", "SEAT_GONE"):
            log.append(f"→裁定 {tid}（{note}）")
            if apply_:
                to_adjudicator(tid, note, s)
    if apply_:
        alive = live_seats()
        keep = {c["seat"] for c in s["cases"].values() if c["state"] != "processed"}
        for c in s["cases"].values():
            if c["state"] == "processed" and c["seat"] in alive and c["seat"] not in keep:
                print(f"回收残留席位 {c['seat']}", flush=True)
                run([TA, "stop-agent", c["seat"], "--workspace", "."])
        state_save(s)
    return st, log


# ---------- 事件驱动主循环（不轮询：阻塞在 fifo 上被推醒） ----------

def _stdin_sink():
    """本进程就跑在 leader 绑定的那个 pane 里，框架的注入会以按键形式进本进程 stdin。
    只落盘 + 推醒，不解析不回复（判断全在主循环）。
    **必须与引擎同进程**：owner gate 只认持有绑定的那个 pane 发出的管理命令——
    实证：引擎跑在别的 pane 时 send/add-agent 全被 team_owner_mismatch 静默拒。"""
    inbox = ".team/leader-inbox.log"
    with open(inbox, "a", encoding="utf-8") as f:
        for line in sys.stdin:                        # 阻塞，空闲零 CPU
            f.write(f"{time.strftime('%F %T')} {line}")
            f.flush()
            try:
                fd = os.open(WAKE, os.O_WRONLY | os.O_NONBLOCK)
                os.write(fd, b"1")
                os.close(fd)
            except OSError:
                pass


def _arm_kqueue():
    """监听 .team/evidence/ 目录与其中每个 json 的写入。
    这才是真正要等的事件：席位交件 = 写证据文件。此前唤醒源只有框架注入，而席位按契约
    用 sink=silent 上报（不注入 leader），于是证据写完没人推醒引擎——所谓事件驱动实际退化成
    30 分钟兜底轮询，实测造成交件后 25 分钟无人接单（2026-08-10 用户当场指出）。"""
    import glob
    kq = select.kqueue()
    fds, evs = [], []
    for path in [".team/evidence"] + glob.glob(".team/evidence/*.json"):
        try:
            fd = os.open(path, os.O_RDONLY)
        except OSError:
            continue
        fds.append(fd)
        evs.append(select.kevent(fd, filter=select.KQ_FILTER_VNODE,
                                 flags=select.KQ_EV_ADD | select.KQ_EV_ENABLE | select.KQ_EV_CLEAR,
                                 fflags=select.KQ_NOTE_WRITE | select.KQ_NOTE_EXTEND
                                 | select.KQ_NOTE_ATTRIB | select.KQ_NOTE_RENAME))
    kq.control(evs, 0, 0)
    return kq, fds


def loop(apply_):
    import threading
    if not os.path.exists(WAKE):
        os.mkfifo(WAKE, 0o600)
    fd = os.open(WAKE, os.O_RDONLY | os.O_NONBLOCK)   # 常开读端，写方才不会 ENXIO
    threading.Thread(target=_stdin_sink, daemon=True).start()
    print(f"引擎就位（兼 leader 接收端）：事件驱动，兜底 {FALLBACK}s；wake={WAKE}", flush=True)
    mtime = os.path.getmtime(__file__)
    while True:
        if os.path.getmtime(__file__) != mtime:
            print("引擎源码已变更，原地重启加载新逻辑", flush=True)
            os.execv(sys.executable, [sys.executable] + sys.argv)
        _, log = cycle(apply_)
        print(time.strftime("%H:%M:%S"), "|", "; ".join(log) or "无动作", flush=True)
        kq, kfds = _arm_kqueue()                      # 每轮重挂，覆盖新建的证据文件
        r, _, _ = select.select([fd, kq.fileno()], [], [], FALLBACK)   # 空闲零 CPU
        if fd in r:
            os.read(fd, 4096)
        if kq.fileno() in r:
            kq.control(None, 8, 0)                    # 排空事件
            time.sleep(3)                             # 让写方把整份 json 落完再读
        kq.close()
        for k in kfds:
            os.close(k)


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "plan"
    apply_ = "--apply" in sys.argv
    if cmd == "verify":
        t = {x["id"]: x for x in load_tasks()}[sys.argv[2]]
        green, res = verify(t)
        for c, rc, tail in res:
            print(f"rc={rc} :: {c}\n{tail}\n")
        sys.exit(0 if green else 1)
    if cmd == "rescue":
        s = state_load()
        tasks = {x["id"]: x for x in load_tasks()}
        for cid, c in list(s["cases"].items()):
            if c["state"] != "in_flight":
                continue
            print(f"弃 id 重派 {c['task']}（旧席位 {c['seat']}）", flush=True)
            run([TA, "stop-agent", c["seat"], "--workspace", "."])
            c["state"] = "processed"
            dispatch(c["task"], tasks[c["task"]], s,
                     rework="前席因缺免审批环境卡在 codex 审批提示、零产出，本轮从头做")
        state_save(s)
        return
    if cmd == "loop":
        return loop(apply_)
    st, log = cycle(apply_)
    from collections import Counter
    print("== 状态分布 ==", dict(Counter(k for k, _ in st.values())))
    for tid, (kind, note) in st.items():
        if kind != "DONE":
            print(f"  {kind:<13} {tid} {note}")
    print("== 本轮动作 ==")
    for l in log:
        print(" ", l)


if __name__ == "__main__":
    main()
