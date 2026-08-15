#!/usr/bin/env python3
"""账本驱动的编排 driver。

账本是唯一真理：派单正文、写盘范围、读取范围、机械判据命令全部从账本派生，
driver 不做账本没写的决定。

一轮 = ledger-eval 算前沿 → 对每个可动任务派单 → 等席位干完 → 跑机械判据 →
判据过则把 state 置 succeeded、重算 sha、进下一轮。

用法: python3 ledger-driver.py [--once]
日志: 见 LOG 常量
"""
import json, hashlib, subprocess, sys, time, os, re, glob

REPO = "/Volumes/nvme/Projects/远程Agent安卓"        # 改我
LEDGER = (sys.argv[1] if len(sys.argv) > 1 and sys.argv[1].endswith(".json")
          else os.path.join(REPO, ".team/ledgers/passthrough-input-v3.json"))   # 改我
LOG = os.path.join(REPO, ".team/ledgers/driver.log")       # 改我
TEAM = "remote-agent-android"                # 改我：runtime key，不是显示名
ROLE_SEAT = {"r.advisor": "advisor", "r.dev-state": "dev-state",
             "r.control": "control", "r.dev-app": "dev-keybar"}   # 改我：账本 owner.role → 席位名
TA = os.path.join(REPO, ".team/ta")   # 改我：本工程强制走净化包装器（禁裸 team-agent）
MAX_WAIT = 3600    # 单任务最长等待秒数
WAIT_EACH = 60     # wait 只当省电，短试即可
POLL = 20
IDLE_CONFIRM = 3


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
        f"账本: {LEDGER}（revision {l['revision']}）。**账本是唯一真理**，下面每一项都从它派生。",
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
        "- 🔴 **干完不要调 report_result，直接停在那儿就行。**",
        "  理由：驱动器不采信任何自报——完成与否由它自己跑机械判据拿退出码决定。",
        "  而 report_result 会让框架另发一条「结果已入库」通知**强制打到 leader 屏上**",
        "  （2026-08-15 两队实证：`presentation={\"sink\":\"casefile\"}` 压得住消息本体，"
        "**压不住这条通知**）。",
        "  ⇒ 调它对判定零贡献，却必然打断 leader 一次。**唯一的作用是制造噪音，所以不要调。**",
        "  你的完成凭据只有两样：**机械判据的退出码**和**落盘物**。不用向任何人证明你做完了。",
        "- 🔴 **只有一种情况才发消息给 leader：硬卡住、且卡点需要 leader 裁定**",
        "  （任务定义要改、判据本身是错的、要动 write_paths 之外的文件）。",
        "  那时用 `send_message(to=\"leader\", ...)`，不带 presentation（走默认 sink，就是要它上屏）。",
        "  **这条通道故意留得很窄：窄到你用它时，leader 被打断是应该的。**",
        "- 🔴 有疑问但**不需要裁定** ⇒ 问顾问席，不要问 leader：",
        "  `send_message(to=\"advisor\", content=..., presentation={\"sink\":\"casefile\",\"class\":\"message\"})`。",
        "  顾问写了 <顾问产出的基底文档>，先读它，文档里没有的才问。",
        "",
        "**不要回「已收到」**——驱动器靠校验你的转录判送达，不靠你回话；回一句只会白打断 leader 一次。",
        "收到就直接开工，同一回合干完。",
    ]
    return "\n".join(lines)


def seat_state(seat):
    r = subprocess.run([TA, "status", "--json", "--team", TEAM],
                       capture_output=True, text=True, cwd=REPO)
    try:
        return json.loads(r.stdout)["agents"][seat]["worker_state"]
    except Exception:
        return "UNKNOWN"


def send(seat, text, tid):
    """派单必须带 --task <账本任务 id>。

    `--task` 在 `send --help` 的 usage 行里**没有**，但实现里一直有（cli/emit.rs:864）。
    缺它时席位 report_result 落成 task_id="manual"、被框架判 invalid_results，
    `wait --task` 永远等不到——**而活其实干完了**。所以派单侧带上它，
    同时在正文里要求席位自报同一个 id，两边对得上才算数。

    ⚠️ 2026-08-15 起 `--task` 会打 deprecation 警告（sunset: next compatibility release），
    官方建议改用「位置参数 TO + 返回的 message id」。**现在还能用，但要盯着换。**

    🔴 无论 --task 带没带，`ok: True` **都不是送达凭据**：消息可能整条卡在输入框、
    或尾部被截断。送达凭据只有 token_landed()。wait 只当省电，正确性押在机械判据上。
    """
    r = subprocess.run([TA, "send", "--task", tid,
                        f"{REPO}::{TEAM}/{seat}", text],
                       capture_output=True, text=True, cwd=REPO)
    out = r.stdout or ""
    if "ok: True" not in out:
        return None
    m = re.search(r"message_id:\s*(\S+)", out)
    return m.group(1) if m else None


def wait_task(task_id, tid):
    """事件驱动等待（DS-01，0.5.65 起）。

    🔴 wait --task 认的是**席位 report_result 自报的账本任务 id**，
    不保证等于派单返回的 message_id——两者不等价时 wait 永不返回，
    而 wait 本身没有 --timeout，失败形态是**挂死不是报错**。
    所以：两个 id 都试，各自套墙钟；都没等到也**不当失败**，
    直接落到机械判据去判——判据才是唯一凭据，wait 只是省电。
    """
    for cand in [tid, task_id]:
        if not cand:
            continue
        try:
            r = subprocess.run([TA, "wait", "--task", cand,
                                "--workspace", REPO, "--json"],
                               capture_output=True, text=True, cwd=REPO,
                               timeout=WAIT_EACH)
            if r.returncode == 0:
                log(f"  wait 命中 task={cand}")
                return True
        except subprocess.TimeoutExpired:
            log(f"  wait 超时 task={cand}（{WAIT_EACH}s）")
    log("  两个 id 都没等到，落到机械判据判定")
    return False


def token_landed(seat, token):
    """token 是否真的作为 user turn 进了席位转录。

    🔴 2026-08-15 取证（标准修复/投递取证-20260815.md）：注入是「粘贴 → 等 2000ms → Enter」，
    负载高时 Enter 落在粘贴将完成而未完成之际，**消息尾部被截断**，而 token 恰在尾部。
    后果：席位收到一份没有 token 的正文，framework 侧记 delivered，这边看到「活干完了、账本不动」。
    27 条投递里 6 条畸形（22%），切口都在极靠后（丢 1.7%–9%）——**余量只剩百分之几**。

    所以「wait 超时」有两种完全不同的原因，必须分开：
      token 进去了 ⇒ 席位在干活或已干完，**重发只会把两条挤进同一个输入框造成粘连**；
      token 没进去 ⇒ 这条根本没送到，**必须重发**。
    以前不分，一律重发——那正是粘连的原料，把偶发问题变成必发问题。
    """
    root = f"{REPO}/.team/runtime/provider-config/{seat}/claude/projects"
    for f in glob.glob(f"{root}/**/*.jsonl", recursive=True):
        for line in open(f, encoding="utf-8", errors="replace"):
            if token not in line:
                continue
            try:
                o = json.loads(line)
            except Exception:
                continue
            if o.get("type") == "user":
                return True
    return False


def wait_seat(seat):
    """席位状态兜底：先等它转 BUSY，再等连续 IDLE_CONFIRM 次非 BUSY。"""
    t0, saw, idle = time.time(), False, 0
    while time.time() - t0 < MAX_WAIT:
        s = seat_state(seat)
        if s == "BUSY":
            saw, idle = True, 0
        elif saw:
            idle += 1
            if idle >= IDLE_CONFIRM:
                return True
        time.sleep(POLL)
    log(f"  !! {seat} 超时 {MAX_WAIT}s，saw_busy={saw}")
    return False


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
        for tid in todo:
            role = l["tasks"][tid]["owner"]["role"]
            seat = ROLE_SEAT.get(role)
            if not seat:
                log(f"{tid}: 角色 {role} 没有对应席位，停。")
                return 2
            # 🔴 席位 BUSY 就不要再派（远程Agent安卓 team 的做法，2026-08-15 我方采纳）：
            # BUSY 说明这任务已经在它手上，再派一条只会挤进同一个输入框造成粘连。
            # 我方实证：driver 重启后对 BUSY 的 fixer2 重复派了一次 t.fix-exitcode。
            if seat_state(seat) == "BUSY":
                log(f"跳过派单：{seat} 仍 BUSY，认定 {tid} 已在手上，直接等")
                wait_seat(seat)
                l = load()
                if check_required(l, tid):
                    l["tasks"][tid]["state"] = "succeeded"; l["revision"] += 1; save(l)
                    log(f"{tid}: 判据通过 → succeeded（revision {l['revision']}）")
                    continue
                log(f"{tid}: 判据未过，停。")
                return 5
            log(f"派 {tid} → {seat}")
            task_id = send(seat, dispatch_text(l, tid), tid)
            if not task_id:
                log(f"{tid}: 投递失败，停。")
                return 3
            # 🔴 先验这条到底送进去没有，再谈等。见 token_landed 的注释。
            time.sleep(5)  # 给注入留出上屏时间，否则查早了必然查不到
            if token_landed(seat, task_id):
                log(f"  ✅ {task_id} 已进 {seat} 转录（user turn）")
            else:
                log(f"  🔴 {task_id} **未进** {seat} 转录 ⇒ 疑似尾部截断，这条没送到。"
                    f"不重发（重发会造成粘连），停下交人。")
                return 6
            log(f"  等 {seat} 干完（wait 短试 + 席位状态兜底）")
            wait_task(task_id, tid)
            wait_seat(seat)
            l = load()
            if check_required(l, tid):
                l["tasks"][tid]["state"] = "succeeded"
                l["revision"] += 1
                save(l)
                log(f"{tid}: 判据通过 → succeeded（revision {l['revision']}）")
            else:
                log(f"{tid}: 判据未过，停。这不是 driver 能自己决定的事。")
                return 5
        if once:
            return 0


if __name__ == "__main__":
    sys.exit(main())
