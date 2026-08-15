#!/usr/bin/env python3
"""账本驱动的编排 driver（远程Agent安卓适配版）。

语义照抄 reference：~/.claude/skills/ledger-orchestration/reference/ledger-driver.py。
账本是唯一真理：派单正文、写盘范围、读取范围、机械判据命令全部从账本派生，
driver 不做账本没写的决定。

一轮 = ledger-eval 算前沿 → 对每个可动任务派单 → 等席位干完 → 跑机械判据 →
判据过则把 state 置 succeeded、重算 sha、进下一轮。

用法（在 leader 自己的 owner pane 里）:
  cd /Volumes/nvme/Projects/远程Agent安卓
  nohup python3 tools/ledger-driver.py > driver.stdout 2>&1 &
日志: .team/ledgers/driver.log

⚠️ 适配约束（leader 2026-08-15 msg_0b3440a9e458 交代，逐条照做）:
  - 一律走 .team/ta（净化包装器，unset 代理环境变量），不用裸 team-agent。
  - 不抄 reference 的 wait_seat()（IDLE_CONFIRM/POLL 未定义，死代码）。
  - ROLE_SEAT 全：r.advisor/r.dev-state/r.control/r.dev-app。
  - REPO 写死仓库根（判据 cwd 是相对路径，进程工作目录必须是仓库根）。
  - 只做 reference 语义的适配，不加功能：不写轮询、不写事件面、不写重试。
    「停」不是缺陷，是把决定权交回给 leader。
"""
import json, hashlib, subprocess, sys, time, os, re, glob

REPO = "/Volumes/nvme/Projects/远程Agent安卓"
LEDGER = (sys.argv[1] if len(sys.argv) > 1 and sys.argv[1].endswith(".json")
          else os.path.join(REPO, ".team/ledgers/state-detection-v1.json"))
# ↑ 允许传账本路径：一个驱动器跑多张账本，不必改常量重开一份
LOG = os.path.join(REPO, ".team/ledgers/driver.log")
TA = os.path.join(REPO, ".team/ta")   # 净化包装器，禁裸 team-agent
TEAM = "remote-agent-android"
ROLE_SEAT = {
    "r.advisor": "advisor",
    "r.dev-state": "dev-state",
    "r.control": "control",
    "r.dev-app": "dev-keybar",
}
MAX_WAIT = 3600    # 单任务最长等待秒数（墙钟兜底，wait 本身无 --timeout）
WAIT_EACH = 1800   # 每个候选 id 各自的墙钟上限（两个候选合计不超过 MAX_WAIT）


def log(msg):
    line = f"[{time.strftime('%H:%M:%S')}] {msg}"
    print(line, flush=True)
    with open(LOG, "a") as f:
        f.write(line + "\n")


def load():
    return json.load(open(LEDGER))


def save(l):
    body = json.dumps({k: v for k, v in l.items() if k != "content_sha256"},
                      ensure_ascii=False, sort_keys=True)
    l["content_sha256"] = "sha256:" + hashlib.sha256(body.encode()).hexdigest()
    json.dump(l, open(LEDGER, "w"), ensure_ascii=False, indent=1)


def eval_ledger():
    r = subprocess.run(["ledger-eval", LEDGER], capture_output=True, text=True)
    return r.returncode, (r.stdout or "") + (r.stderr or "")


def frontier(out):
    """从 ledger-eval 输出里取「现在可以动的任务」。"""
    ids, on = [], False
    for line in out.splitlines():
        if "现在可以动的任务" in line:
            on = True
            continue
        if on:
            if line.startswith("=="):
                break
            m = re.match(r"-\s+(\S+)\s", line)
            if m:
                ids.append(m.group(1))
    return ids


def dispatch_text(l, tid):
    """派单正文全部从账本派生——账本没写的东西不许出现在派单里。"""
    t = l["tasks"][tid]
    res = t["resources"]
    mech = t["acceptance"].get("mechanical", [])
    lines = [
        f"[账本任务 {tid}] {t['title']}",
        "",
        f"账本: {LEDGER}。**账本是唯一真理**，下面每一项都从它派生。",
        "",
        "## 只准写这些路径",
    ] + [f"- {p}" for p in res.get("write_paths", [])] + [
        "",
        "## 你需要读的",
    ] + [f"- {p}" for p in res.get("read_paths", [])] + [""]
    if mech:
        lines += ["## 机械判据（做完你自己跑一遍，把实际退出码写进回报）"]
        for m in mech:
            lines += [f"- `{' '.join(m['argv'])}`  (cwd={m['cwd']}, 期望退出码 {m['expected_exit_code']})"]
        lines += [""]
    lines += [
        "## 硬约束",
        "- 不跑 git commit / push；不发 team-agent send。",
        "- 卡住不要绕过去也不要猜：把「卡在哪、试了什么、缺什么」写进回报，同一回合继续做能做的部分。",
        "- **做不到于是改了任务定义，必须显式报出来，不许静默改。**",
        "- 🔴 report_result 必须带结构化参数 presentation={\"sink\":\"casefile\",\"class\":\"stage_result\"}，",
        "  且 task_id 填本任务的账本 id（如 " + tid + "）。",
        "  中途进度用 send_message 带 presentation={\"sink\":\"casefile\",\"class\":\"progress\"}。",
        "  presentation 是**参数**不是正文文字，写进正文不生效。只有硬卡住需要 leader 当场介入才用默认 sink（class=\"blocking\"）。",
        "- 🔴 完成凭据是机械判据退出码和落盘物，不用向 leader 证明你做完了。",
        "- 🔴 有疑问**直接问顾问席**，不要问 leader：先读 .team/nodes/state-oracle/判据基底摘要.md（如已存在），",
        "  文档里没有的才 team_orchestrator.send_message(to=\"advisor\", content=..., presentation={\"sink\":\"casefile\",\"class\":\"message\"})。",
        "  只有「任务定义要改」或「判据本身是错的」才直达 leader。",
        "",
        "收到**不要**向 leader 回「已收到」——那句会默认注入他的屏幕，每个任务漏一次。"
        "要回就带 presentation={\"sink\":\"casefile\",\"class\":\"progress\"}。然后不要停，同一回合干完。",
    ]
    return "\n".join(lines)


def send(seat, text, tid):
    """返回 message_id（**仅作投递凭据，不是 wait 的键**——见 main 里的说明）。
    走 .team/ta 净化包装器，收件人用绝对路径 FQN。
    注意：不传 --json——reference 对 send 用文本输出，检查 ok: True / message_id: 文本。
    （传 --json 会输出 "ok": true，和 ok: True 对不上，send 会误判失败——实发 2026-08-15。）"""
    fqn = f"{REPO}::{TEAM}/{seat}"
    # --task 是框架强制的注册键（0.5.66 起就有，只是没进 --help；cli/emit.rs:864）。
    # 不带时席位 report_result 会以 task_id="manual" 落库、被判 invalid，wait 永远等不到。
    # ⚠️ 派单正文里「要求席位自报 task_id」**同时保留**：框架强制 + 席位守约互为兜底，
    #    任一条失效另一条还在（2026-08-15 双方共识）。
    r = subprocess.run([TA, "send", "--task", tid, fqn, text, "--workspace", REPO],
                       capture_output=True, text=True, cwd=REPO)
    out = r.stdout or ""
    if "ok: True" not in out:
        log(f"  !! send 失败: {out[-200:]}")
        return None
    m = re.search(r"message_id:\s*(\S+)", out)
    return m.group(1) if m else None


def token_landed(seat, token):
    """派单是否已作为 user turn 进了席位转录——这是**消费判据，不是送达判据**。

    🔴 **送达 ≠ 消费**（2026-08-15 框架队到现场纠正，我原先把它当送达判据用错了）。
       Claude Code 在忙时会**收下并排队**：消息成功送达、tmux 层 Enter 全发出 rc=0，
       但要等当前这一轮长任务跑完才作为 user turn 落进转录。
       ⇒ 席位 BUSY 时，一条**正常排队**的消息**必然**判 False。
       ⇒ 不加 BUSY 门的话，这个函数会把**每一条发给忙席位的消息**都报成卡住。
       实证：我据此误报过一次「卡在输入框」，框架队黑匣子显示该条与对照条
       paste/Enter 时序逐毫秒一致（+2.144/+3.939/+5.677 vs +2.144/+3.890/+5.549），
       且席位输入框当时是空的。
       ⇒ 只有**席位 IDLE 且转录无 token** 才是真没送到。判 False + BUSY 一律当排队。

    `send` 返回 ok 不是送达，框架侧记 delivered 也不是：实测存在整条派单
    **原样躺在席位输入框里、席位一个字没收到**，而两处都显示成功
    （ledger-orchestration 队 2026-08-15 靠这一层拦下一次真阳性）。

    🔴 判 False 就**停下交人，绝不重发**。重发会把两条挤进同一个输入框，
       粘连比滞留难查得多。滞留那条晚点被按键放出去，最多是"做了一份过期活"。
    🔴 只数 type == "user" 的行：assistant 引用 token 的行会让计数虚高，
       那是"它提到了这条消息"，不是"它收到了这条消息"。
    ⚠️ 只对 provider 为 claude/claude_code 的席位成立（转录是 claude 的 jsonl）。
       本工程五席全是 claude_code，成立。
    （实现取自 ledger-orchestration reference driver，2026-08-15。）
    """
    root = os.path.join(REPO, ".team/runtime/provider-config", seat, "claude/projects")
    for f in glob.glob(os.path.join(root, "**", "*.jsonl"), recursive=True):
        try:
            with open(f, encoding="utf-8", errors="replace") as fh:
                for line in fh:
                    if token not in line:
                        continue
                    try:
                        o = json.loads(line)
                    except Exception:
                        continue
                    if o.get("type") == "user":
                        return True
        except OSError:
            continue
    return False


def seat_busy(seat):
    """席位此刻是否真的在干活。凭据取 team-agent status 的 worker_state。

    用途有两个，都是 2026-08-15 实发：
    ① **不朝正在干活的席位重复派单**。driver.log 里 t.contract 被连派三次，
       就是这个病：wait 等不到 → 落判据 → 判据没过 → 重启 → 再派一次。
       重复派单会把半条正文粘进席位输入框，比不派单更糟。
    ② **wait 墙钟到点时，先问席位在不在干**。在干就继续等；
       不在干才落到判据。否则驱动器会在席位还在编译 app/ 的时候
       自己也去跑 `gradlew --rerun-tasks`，两边抢同一个 Gradle 锁。

    查不到状态一律返回 False（宁可多等一轮判据，也不要凭猜挂死）。
    """
    try:
        r = subprocess.run([TA, "status", seat, "--json", "--workspace", REPO],
                           capture_output=True, text=True, cwd=REPO, timeout=60)
        st = json.loads(r.stdout).get("agents", {}).get(seat, {})
        return st.get("worker_state") == "BUSY"
    except Exception:
        return False


def wait_task(msg_id, tid, seat=None):
    """事件驱动等待（DS-01，0.5.65 起）。**等不到不算失败。**

    🔴 wait --task 认的是**席位 report_result 自报的账本任务 id**，
    不保证等于 send 返回的 message_id——两者不等价时 wait 永不返回，
    而 wait 本身没有 --timeout，**失败形态是挂死不是报错**（实发 2026-08-15）。
    所以两个 id 都试、各自套墙钟；都没等到也**不当失败**，直接落到机械判据去判。

    依据是这套东西自己的铁律：**判据是唯一凭据，wait 只是省电。**
    等待机制猜错最多浪费一次墙钟，不该让整条流水线挂死。
    （改法采自 ledger-orchestration reference，2026-08-15 同步。）
    """
    for cand in [tid, msg_id]:
        if not cand:
            continue
        while True:
            try:
                r = subprocess.run([TA, "wait", "--task", cand, "--workspace", REPO, "--json"],
                                   capture_output=True, text=True, cwd=REPO, timeout=WAIT_EACH)
                if r.returncode == 0:
                    log(f"  wait 命中 task={cand}")
                    return True
                break
            except subprocess.TimeoutExpired:
                # 墙钟到点 ≠ 席位没在干。先问一句再决定，否则会在人家
                # 还在编译的时候去跑判据（抢 Gradle 锁 + 必然判未过 → 白停一次）。
                if seat and seat_busy(seat):
                    log(f"  wait 超时 task={cand}（{WAIT_EACH}s），但席位 {seat} 仍 BUSY → 继续等")
                    continue
                log(f"  wait 超时 task={cand}（{WAIT_EACH}s），席位不忙 → 换下一个候选")
                break
    log("  两个 id 都没等到，落到机械判据判定")
    return False


def provision_seats(l):
    """席位供给：账本 roles 里声明的席位若不存在则 add-agent。
    必须在 owner pane 跑（owner gate 只认持有绑定的 pane）——驱动器就是 owner pane 子进程。"""
    # 角色 → 席位名 → 角色文件（席位名即角色文件名的 stem）
    seat_role_file = {
        "advisor": ".team/current/agents/advisor.md",
        "dev-state": ".team/current/agents/dev-state.md",
        "control": ".team/current/agents/control.md",
        "dev-keybar": ".team/current/agents/dev-keybar.md",
    }
    # 从账本 roles 声明的角色算出需要的席位
    needed = set()
    for rk in l.get("roles", {}):
        if rk in ROLE_SEAT:
            needed.add(ROLE_SEAT[rk])
    if not needed:
        return True
    # 查现有席位
    r = subprocess.run([TA, "status", "--json", "--workspace", REPO],
                       capture_output=True, text=True, cwd=REPO)
    try:
        existing = set(json.loads(r.stdout).get("agents", {}).keys())
    except Exception:
        existing = set()
    missing = needed - existing
    for seat in missing:
        role_file = seat_role_file.get(seat)
        if not role_file:
            log(f"  !! 席位 {seat} 缺角色文件，停。")
            return False
        # 同 send()：不传 --json，reference 用文本输出判 ok: True（实发 2026-08-15 同类 bug）
        rr = subprocess.run([TA, "add-agent", seat, "--role-file", role_file,
                             "--workspace", REPO],
                            capture_output=True, text=True, cwd=REPO)
        out = rr.stdout or ""
        if "ok: True" not in out:
            log(f"  !! add-agent {seat} 失败: {out[-200:]}")
            return False
        log(f"  add-agent {seat} → ok")
    return True


def check_required(l, tid):
    """按 required 逐条求值——**静默跳过是唯一不可接受的行为**。

    机械判据由驱动器自己跑命令拿退出码（不采信席位自报）。
    人裁判据驱动器**无权代判**：遇到就停下交人，并显式打印未求值条数。
    只遍历 mechanical、不读 required，会在有人裁的账本上产生假绿
    （外部 team 实测：日志「判据通过 → succeeded」而 required 里那条裁定一字未提）。
    """
    acc = l["tasks"][tid]["acceptance"]
    required = acc.get("required", [])
    mech = {m["acceptance_id"]: m for m in acc.get("mechanical", [])}
    judg = {j["acceptance_id"]: j for j in acc.get("judgment", [])}
    pending = []
    for aid in required:
        if aid in mech:
            m = mech[aid]
            r = subprocess.run(m["argv"], cwd=m["cwd"], capture_output=True, text=True)
            ok = r.returncode == m["expected_exit_code"]
            log(f"  机械判据 {aid}: exit={r.returncode} 期望={m['expected_exit_code']} {'通过' if ok else '未过'}")
            if not ok:
                return False
        elif aid in judg:
            pending.append(aid)
            log(f"  人裁判据 {aid}: **驱动器无权代判**，judge_role={judg[aid].get('judge_role')}")
        else:
            log(f"  !! required 里的 {aid} 在 mechanical/judgment 里都找不到")
            return False
    if pending:
        log(f"  ⇒ {len(pending)} 条人裁判据未求值，任务不置 succeeded，交人：{pending}")
        return False
    return True


def main():
    once = "--once" in sys.argv
    while True:
        code, out = eval_ledger()
        if code != 0:
            log(f"ledger-eval exit={code}，停。\n{out[:600]}")
            return 1
        todo = frontier(out)
        if not todo:
            log("前沿为空。")
            log(out[out.find("== 计划"):][:800])
            return 0
        log(f"前沿: {todo}")
        l = load()
        if not provision_seats(l):
            log("席位供给失败，停。")
            return 6
        for tid in todo:
            role = l["tasks"][tid]["owner"]["role"]
            seat = ROLE_SEAT.get(role)
            if not seat:
                log(f"{tid}: 角色 {role} 没有对应席位，停。")
                return 2
            if seat_busy(seat):
                # 席位已经在干这一单了（多半是上一轮派过、驱动器被重启）。
                # 再派一次等于把半条正文粘进它的输入框——重复派单比不派更糟。
                log(f"跳过派单：{seat} 仍 BUSY，认定 {tid} 已在手上，直接等")
                msg_id = None
            else:
                log(f"派 {tid} → {seat}")
                msg_id = send(seat, dispatch_text(l, tid), tid)
                if not msg_id:
                    log(f"{tid}: 投递失败，停。")
                    return 3
                time.sleep(5)   # 给上屏留时间，太快查会假阴
                if not token_landed(seat, msg_id):
                    # 判 False 有两种，必须先分清，否则每条发给忙席位的消息都成假阳。
                    if seat_busy(seat):
                        log(f"  {tid}: 转录暂无 token，但 {seat} 仍 BUSY ⇒ "
                            f"**排队中，不是丢失**。继续等，不重发。")
                    else:
                        log(f"{tid}: {seat} 已 IDLE 且转录无 token ⇒ 真没送到。"
                            f"停下交人，**不重发**（重发会粘连）。")
                        return 7
            # 🔴 wait 的键是【账本任务 id】，不是 send 返回的 message_id。
            # 席位 report_result 带的 task_id 就是账本 id，wait --task 匹配的正是它。
            # reference 的 send() 注释说「返回 message_id（即 task id）」——这个等价是错的。
            # 实发 2026-08-15：驱动器卡在 wait --task msg_d5f761155aa8 上永不醒，
            # 而同一时刻 wait --task t.oracle 立即返回 res_c052d53f5738 completed。
            log(f"  投递 {msg_id}；等 {tid} / {msg_id}（事件驱动，非轮询）")
            wait_task(msg_id, tid, seat)   # 等不到也不停——判据才是唯一凭据
            l = load()
            if check_required(l, tid):
                l["tasks"][tid]["state"] = "succeeded"
                l["revision"] = l.get("revision", 0) + 1
                save(l)
                log(f"{tid}: 判据通过 → succeeded（revision {l['revision']}）")
            else:
                log(f"{tid}: 判据未过或有人裁未求值，停。这不是 driver 能自己决定的事。")
                return 5
        if once:
            return 0


if __name__ == "__main__":
    sys.exit(main())
