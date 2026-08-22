#!/usr/bin/env python3
# //!
# //! purpose: 账本收口机器人 —— 判据绿 → 封版 → 推分支 → 开远端 PR → 判者 pass → 并线 → 推 main
# //! contract:
# //!   provides:
# //!     - name: autopr
# //!       what: 轮询账本，把每一格的收口动作跑完，使远端 PR 列表成为「一事一PR一闭环」的证明
# //! boundary:
# //!   - ⛔ 不解冲突（解冲突是判断不是自动化）：冲突即 park 该格并记录，其余格继续
# //!   - ⛔ 不改判据、不改账本、不动席位；只做 leader 侧的 seal/push/pr/land/push-main
# //!   - ⛔ 判者未 pass 的格不许 land（铁律②：判据过了才并线）
# //!   - 全程非交互：任何子进程都带超时与 BatchMode，⛔ 不许停下来等密码
# //! maturity: wired
#
# 用法：
#   python3 tools/autopr.py .team/ledgers/perfbase-v1.json            # 常驻，每 60s 一轮
#   python3 tools/autopr.py .team/ledgers/perfbase-v1.json --once     # 跑一轮就退
#
# 两阶段（对应 CLAUDE.md「一事一PR一闭环」）：
#   A 阶段 seal+PR：本格 state=succeeded（= 它自己的机械判据全绿）⇒ 封版、推分支、开远端 PR。
#                   **在评审派单之前就开 PR**，这样评审是在 PR 上发生的。
#   B 阶段 land+推main：管本格的判者格 pass ⇒ 并进 main，并立刻推 main 让该 PR 显示 merged。
#                   land 之后才推 = PR 变 closed 而不是 merged，等于流程没发生过。
import json
import os
import re
import subprocess
import sys
import time

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
STATE_PATH = os.path.join(REPO, ".team", "nodes", "_driver", "autopr-state.json")
LOG_PATH = os.path.join(REPO, ".team", "ledgers", "autopr.log")
VERDICT_DIR = os.path.join(REPO, ".team", "nodes", "_driver", "verdicts")

# 非交互环境：git/ssh/gh 任何一处弹提示都会挂死整夜，这里把所有交互入口关死。
ENV = dict(os.environ)
ENV.update({
    "GIT_TERMINAL_PROMPT": "0",
    "GIT_ASKPASS": "/usr/bin/false",
    "SSH_ASKPASS": "/usr/bin/false",
    "GIT_SSH_COMMAND": "ssh -o BatchMode=yes -o ConnectTimeout=20 -o StrictHostKeyChecking=accept-new",
    "GH_PROMPT_DISABLED": "1",
    "GH_NO_UPDATE_NOTIFIER": "1",
})


def log(msg):
    line = "[autopr %s] %s" % (time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), msg)
    print(line, flush=True)
    with open(LOG_PATH, "a", encoding="utf-8") as f:
        f.write(line + "\n")


def run(argv, cwd=REPO, timeout=900):
    """跑一条命令并返回 (rc, 合并输出)。超时按失败处理，⛔ 不重试（重试可能重复外部动作）。"""
    try:
        p = subprocess.run(argv, cwd=cwd, env=ENV, timeout=timeout,
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        return p.returncode, p.stdout.decode("utf-8", "replace")
    except subprocess.TimeoutExpired:
        return 124, "超时 %ss：%s（非交互环境下超时通常= 在等凭据）" % (timeout, " ".join(argv))
    except OSError as e:
        return 127, "起不来：%s" % e


def load_state():
    try:
        with open(STATE_PATH, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def save_state(st):
    os.makedirs(os.path.dirname(STATE_PATH), exist_ok=True)
    tmp = STATE_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(st, f, ensure_ascii=False, indent=1, sort_keys=True)
    os.replace(tmp, STATE_PATH)  # 原子：中途死也不会留半份状态


# ── 账本读取（只读；驱动器在并发写，靠它的原子 rename 保证我们读到的是完整的一份） ──

def load_ledger(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def ancestors(deps, node):
    """沿 dependencies 边（requires_success）向上取全部祖先。转移边不算依赖。"""
    parents = {}
    for e in deps:
        parents.setdefault(e["to"], []).append(e["from"])
    seen, stack = set(), list(parents.get(node, []))
    while stack:
        n = stack.pop()
        if n in seen:
            continue
        seen.add(n)
        stack.extend(parents.get(n, []))
    return seen


def judges_and_gates(led):
    """判者格 = 词表里有 pass 的格。gated(J) = J 的祖先中，不被更靠上的判者认领的那些。"""
    tasks = led["tasks"]
    deps = led.get("dependencies", [])
    judges = [tid for tid, t in tasks.items()
              if any(s.get("name") == "pass" for s in (t.get("statuses") or []))]
    anc = {j: ancestors(deps, j) for j in judges}
    gates = {}
    for j in judges:
        claimed = set()
        for other in judges:
            if other != j and other in anc[j]:
                claimed |= anc[other] | {other}
        for t in anc[j] - claimed:
            gates[t] = j
    return gates


def judge_verdict(led, jid):
    """读判者格的裁定书，返回 (状态, 裁定书路径)。⛔ 只认文件里的 status= 行，不猜。

    ⚠️ 判者格判 pass 之后**不是** succeeded：引擎会因为它路由去了下游而「拒关成功」
    （`route_contradicts_success`），把它标成 blocked。所以门禁不能看 state，
    要看**账本记下来的 status_record.status**——那才是判者真说过的话。"""
    t = led["tasks"].get(jid) or {}
    said = ((t.get("status_record") or {}).get("status"))
    if said is None or t.get("state") == "planned":
        return None, None

    # 裁定书住在**判者自己的 worktree** 里（席位不在仓根干活）。⛔ 先找仓根会读到旧抄本——
    # 实撞：仓根那份是上一轮的 rework，会把已经 pass 的链子永远卡住。
    wid = (t.get("resources") or {}).get("worktree_id")
    roots = ([os.path.join(REPO, ".worktrees", wid)] if wid else []) + [REPO]
    for root in roots:
        for p in (t.get("resources") or {}).get("write_paths") or []:
            cand = os.path.join(root, p.rstrip("/"), "裁定.md")
            if os.path.isfile(cand):
                with open(cand, encoding="utf-8") as f:
                    m = re.search(r"^status=(pass|rework|inconclusive)$", f.read(), re.M)
                onfile = m.group(1) if m else None
                # 账本里引擎记下的 status_record 是判者的原话，裁定书是它的证据。
                # 两者不一致 ⇒ ⛔ 不放行（说不清以哪个为准的东西不许并线）。
                if onfile != said:
                    log("%s 门禁不一致：账本 status_record=%r 但 %s 写的是 %r ⇒ ⛔ 不 land"
                        % (jid, said, cand, onfile))
                    return None, cand
                return said, cand
    log("%s 判者说 %r 但找不到裁定书 ⇒ ⛔ 不 land" % (jid, said))
    return None, None


# ── 收口三步 ──────────────────────────────────────────────────────────────

def branch_of(led, tid):
    """分支名跟着 **worktree** 走，不跟任务 id 走。
    worktree 才是隔离单位：同一格换新树重做（如 t.core 换 wt-pb-core2 在新 main 上重切）时，
    旧树还占着按任务 id 推出来的那个分支名，`git switch -c` 会 fatal: already exists。
    按树命名后两者天然不撞，旧分支/旧 PR 作为被取代的那次尝试原样留着。"""
    wid = (led["tasks"][tid].get("resources") or {}).get("worktree_id") or tid
    return "pr/" + wid.replace("wt-", "", 1).replace(".", "-")


def faces(led, tid):
    """本格碰了哪张产品面 ⇒ 决定推哪个远端仓。只碰证据/文档的格不开远端 PR。"""
    wp = (led["tasks"][tid].get("resources") or {}).get("write_paths") or []
    out = []
    if any(p.startswith("app/") or p == "app" for p in wp):
        out.append("core")
    if any(p.startswith("server/") or p == "server" for p in wp):
        out.append("serve")
    return out


def worktree_fingerprint(led, tid):
    """本格 worktree 的当前内容指纹 = HEAD + 未提交改动清单的哈希。
    返修回环第二轮的改动只体现在这里，账本的 state 两轮都是 succeeded，分不出新旧。"""
    wid = (led["tasks"][tid].get("resources") or {}).get("worktree_id")
    if not wid:
        return None
    wt = os.path.join(REPO, ".worktrees", wid)
    if not os.path.isdir(wt):
        return None
    rc1, head = run(["git", "-C", wt, "rev-parse", "HEAD"], timeout=60)
    rc2, dirty = run(["git", "-C", wt, "status", "--porcelain"], timeout=120)
    if rc1 != 0 or rc2 != 0:
        return None
    # ⛔ 判据自己写的日志不算「席位交了新东西」：judge-*.sh 把输出落在
    # .team/nodes/<格>/tmp/ 下,每跑一次判据就把 worktree 弄脏,会让指纹无谓地变、
    # 触发对**已 merged 分支**的重复封版与重推。实撞：t.app 的 green-run.log。
    lines = [ln for ln in dirty.splitlines() if "/tmp/" not in ln]
    import hashlib
    return head.strip()[:12] + ":" + hashlib.sha256("\n".join(lines).encode()).hexdigest()[:12]


def ensure_branch(wt, br):
    """驱动器建的 worktree 是 detached HEAD，而 seal-pr.sh 见「脏 + 不在目标分支」就拒绝 checkout
    （那条守卫防的是「把树从正在干活的席位手里夺走」）。这里只在**确实安全**时先把分支建出来：
    HEAD 是 detached、且目标分支还不存在 ⇒ `git switch -c` 只移动 ref，一个文件都不动。
    ⛔ 已在别的分支上（席位跑偏了）或分支已被别处占用 ⇒ 不动，交给 seal-pr 的守卫拦下并 park。"""
    rc, cur = run(["git", "-C", wt, "branch", "--show-current"], timeout=60)
    if rc != 0:
        return False, cur.strip()
    if cur.strip() == br:
        return True, "已在目标分支"
    if cur.strip() != "":
        return False, "worktree 在分支 %r 上（不是 detached），⛔ 不自动切" % cur.strip()
    rc, out = run(["git", "-C", wt, "switch", "-c", br], timeout=120)
    return rc == 0, out.strip()[-300:]


def do_seal(led, tid):
    wt = os.path.join(REPO, ".worktrees", (led["tasks"][tid]["resources"] or {})["worktree_id"])
    br = branch_of(led, tid)
    if not os.path.isdir(wt):
        return False, "worktree 不存在：%s" % wt
    ok, msg = ensure_branch(wt, br)
    if not ok:
        return False, "建分支失败：" + msg
    rc, out = run(["bash", os.path.join(REPO, "tools/gate/seal-pr.sh"), wt, br], cwd=REPO, timeout=600)
    return rc == 0, out.strip()[-800:]


def do_pr(led, tid):
    """推分支 + 开远端 PR。mirror-pr* 会把 gh pr create 的失败吞掉（仍 exit 0），
    所以这里**自己再核一次 PR 到底在不在**——⛔ 不采信脚本的退出码。"""
    br = branch_of(led, tid)
    fs = faces(led, tid)
    if not fs:
        return True, "本格无产品面改动，跳过远端 PR（仍会 land 进 main 留证据）"
    msgs = []
    for face in fs:
        script = "tools/mirror-pr.sh" if face == "core" else "tools/mirror-pr-serve.sh"
        rc, out = run(["bash", os.path.join(REPO, script), br], cwd=REPO, timeout=900)
        repo = "Florious95/corral-core" if face == "core" else "Florious95/corral-serve"
        vrc, vout = run(["gh", "pr", "view", br, "--repo", repo, "--json", "url,state"], timeout=120)
        if vrc != 0:
            return False, "%s：脚本 rc=%d，但 gh pr view 查不到 PR（开 PR 很可能被吞了）\n%s\n%s" % (
                face, rc, out.strip()[-600:], vout.strip()[-300:])
        msgs.append("%s %s" % (face, vout.strip()))
    return True, " | ".join(msgs)


def write_verdict(led, tid, jid, jstatus, jpath):
    """把账本事实翻译成 land-pr.sh 认的 verdict 文件。⛔ 这不是「代判者签字」——
    首行 supports 的依据全部写在正文里，可逐条回溯到账本与裁定书。"""
    os.makedirs(VERDICT_DIR, exist_ok=True)
    t = led["tasks"][tid]
    passed = [r.split("=")[-1] for a in (t.get("attempts") or [])
              for r in (a.get("artifact_refs") or []) if r.startswith("acceptance_success.acceptance_id")]
    p = os.path.join(VERDICT_DIR, tid + ".verdict")
    with open(p, "w", encoding="utf-8") as f:
        f.write("VERDICT: supports\n")
        f.write("# 本文件由 tools/autopr.py 依据账本事实生成，不是人写的裁定；依据如下，可逐条回溯。\n")
        f.write("账本=%s revision=%s\n" % (led["ledger_id"], led.get("revision")))
        f.write("本格=%s state=%s 通过的判据=%s\n" % (tid, t.get("state"), passed or "无（本格无机械判据）"))
        if jid:
            f.write("判者=%s state=succeeded status=%s 裁定书=%s\n" % (jid, jstatus, jpath))
        else:
            f.write("判者=无（本格是文书/证据格，不经异源评审；其内容本身即证据）\n")
    return p


def do_land(led, tid, jid, jstatus, jpath):
    br = branch_of(led, tid)
    v = write_verdict(led, tid, jid, jstatus, jpath)
    rc, out = run(["bash", os.path.join(REPO, "tools/gate/land-pr.sh"), br, v], cwd=REPO, timeout=900)
    if rc != 0:
        # ⛔ 冲突不自动解：park 本格，其余格继续。
        return False, out.strip()[-800:]
    msgs = []
    for face in faces(led, tid) or []:
        script = "tools/mirror-pr.sh" if face == "core" else "tools/mirror-pr-serve.sh"
        prc, pout = run(["bash", os.path.join(REPO, script)], cwd=REPO, timeout=900)  # 无参=只推 main
        msgs.append("推 main(%s) rc=%d" % (face, prc))
        if prc != 0:
            msgs.append(pout.strip()[-400:])
    return True, (out.strip()[-300:] + " | " + " ".join(msgs)).strip()


# ── 主循环 ────────────────────────────────────────────────────────────────

def tick(ledger_path, st):
    led = load_ledger(ledger_path)
    gates = judges_and_gates(led)
    changed = False

    # 仓根必须在 main：land-pr.sh 不做 checkout，它并进「当前 HEAD」。
    rc, cur = run(["git", "branch", "--show-current"], timeout=60)
    if cur.strip() != "main":
        log("停手：仓根当前分支是 %r 而不是 main，land 会并错地方" % cur.strip())
        return False

    # 本链多个格共用一棵 worktree（wt-ca），封版/开 PR 是**按分支**的动作，做一次就够。
    # ⛔ 不去重的话：任一格提交 ⇒ 三个格的指纹同时变 ⇒ 同一分支被连封三次、gh 被调三次。
    done_seal = {}
    # 🔴 一棵 worktree 上还有在飞的格 ⇒ 席位正在往里写，指纹每分钟都在变：
    # 封了也白封，只会把半成品一层层提交上去。等这棵树静下来再收口。
    # ⚠️ 实撞 2026-08-22：在飞的格在账本里仍写着 planned（派单态由驱动器在内存里持有，
    # 不落盘）⇒ ⛔ 不能靠 state 认在飞。判据改成「这棵树上还有没收口的格就别动」——
    # 同树的格本来就共用一条分支，等全树静下来一次封版，与逐格封版产出相同。
    busy_wt = {(t.get("resources") or {}).get("worktree_id")
               for t in led["tasks"].values() if t.get("state") != "succeeded"}
    busy_wt.discard(None)
    for tid, t in sorted(led["tasks"].items()):
        rec = st.setdefault(tid, {})
        if t.get("state") != "succeeded":
            continue
        if (t.get("resources") or {}).get("worktree_id") in busy_wt:
            continue

        # 返修回环会让同一格再跑一轮，交付物随之变化。⛔ 不能拿「上轮封过版」当已收口——
        # 那会让第二轮的修复悄悄漏出 PR。用「worktree 指纹变了就重来一遍」判增量。
        fp = worktree_fingerprint(led, tid)
        if fp and rec.get("fp") != fp:
            if rec.get("fp"):
                log("%s worktree 指纹变了（返修新一轮），重新封版+推 PR" % tid)
            rec["fp"] = fp
            rec["sealed"] = False
            rec["pr"] = False

        key = (branch_of(led, tid), fp)
        if not rec.get("sealed"):
            if key in done_seal:
                ok, msg = done_seal[key]["seal"]
                log("%s 与 %s 同分支同指纹，复用其封版结果" % (tid, done_seal[key]["by"]))
            else:
                ok, msg = do_seal(led, tid)
                log("%s 封版 %s：%s" % (tid, "OK" if ok else "红", msg.replace("\n", " / ")[:300]))
                done_seal.setdefault(key, {"by": tid})["seal"] = (ok, msg)
            rec["sealed"] = ok
            rec["seal_msg"] = msg
            changed = True
            if not ok:
                continue

        if not rec.get("pr"):
            if key in done_seal and "pr" in done_seal[key]:
                ok, msg = done_seal[key]["pr"]
                log("%s 与 %s 同分支同指纹，复用其 PR 结果" % (tid, done_seal[key]["by"]))
            else:
                ok, msg = do_pr(led, tid)
                log("%s 开 PR %s：%s" % (tid, "OK" if ok else "红", msg.replace("\n", " / ")[:300]))
                done_seal.setdefault(key, {"by": tid})["pr"] = (ok, msg)
            rec["pr"] = ok
            rec["pr_msg"] = msg
            changed = True
            if not ok:
                continue

        if rec.get("landed"):
            continue
        # park 是终态判断，不是重试队列：同一份内容撞过一次冲突，60s 后还会撞同一次。
        # ⛔ 不许每分钟重试（刷屏 + 反复 merge/abort），指纹变了才重新试。
        if rec.get("parked_fp") == fp:
            continue
        jid = gates.get(tid)
        if jid:
            jstatus, jpath = judge_verdict(led, jid)
            if jstatus != "pass":
                continue  # 判者还没 pass ⇒ ⛔ 不许并线（铁律②）
        else:
            jstatus = jpath = None
        ok, msg = do_land(led, tid, jid, jstatus, jpath)
        rec["landed"] = ok
        rec["land_msg"] = msg
        if not ok:
            rec["parked_fp"] = fp   # 记下「就是这份内容撞的」，换了内容才再试
        changed = True
        log("%s 并线 %s：%s" % (tid, "OK" if ok else "红(park，不自动解冲突)", msg.replace("\n", " / ")[:300]))

    return changed


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    once = "--once" in sys.argv
    ledger_path = args[0] if args else os.path.join(REPO, ".team/ledgers/coreapp-v1.json")
    interval = 60
    os.makedirs(os.path.dirname(LOG_PATH), exist_ok=True)
    log("起：账本=%s once=%s 间隔=%ss" % (ledger_path, once, interval))
    while True:
        try:
            st = load_state()
            if tick(ledger_path, st):
                save_state(st)
        except Exception as e:  # 单轮出错不许拖垮整夜
            log("本轮异常（下轮继续）：%r" % e)
        if once:
            return 0
        time.sleep(interval)


if __name__ == "__main__":
    sys.exit(main())
