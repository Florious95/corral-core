#!/usr/bin/env python3
"""账本开跑前的准备：① 每格一个新席位 ② 每格编译知识基底并写进任务书。

用户 2026-08-19 两条纪律：
  - **派单不得重复席位**：一格一席，避免上一格的上下文污染下一格的判断。
  - **知识基底自动编译**：模块影响闭包随单下发，不靠席位自己猜架构。

用法：python3 tools/prep_ledger.py <账本.json> [--team grok-l2] [--template .team/grok/agents/dev-app.md]

做四件事（幂等，可重复跑）：
  1. 对每个任务算 BASE.md（调 basegen_ledger）
  2. 为每个任务建专属席位 <ledger短名>-<任务短名>，角色文件从模板复制并改 name
  3. 行为自证：让新席位真写一个文件出来，写不出就报错退出（存活自证不算数——
     clone-agent 会静默降级成只读，实案见 ledger-orchestration skill）
  4. 把 seat 写回账本对应角色，并把 BASE.md 路径塞进该任务的 read_paths

⛔ teardown 不在这里做：preflight 会校验**已终态任务**的收件席位，
   中途销毁会锁死整张账本（框架已知未修）。销毁推迟到整张账本终态之后。
"""
import json, os, shutil, subprocess, sys, time

WS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.chdir(WS)

def sh(*args):
    return subprocess.run(args, capture_output=True, text=True)

MARK = "## 知识基底（已内联）"

def _inline_base(task, base_path):
    """基底前置内联（findings F-12：read_paths 被框架静默吞掉，只能靠正文）。"""
    if MARK in task["title"]:
        return
    body = open(base_path, encoding="utf-8").read()
    task["title"] = (
        f"{MARK} —— tools/basegen_ledger.py 现算的模块影响闭包，"
        f"**正向依赖=你消费的契约，反向依赖=你的回归自查范围**。"
        f"⛔ 不看它就动手 = 凭空猜架构。原件 {base_path}。\n\n"
        f"{body}\n\n---\n以上是基底，以下是任务。\n\n" + task["title"])


def main():
    if len(sys.argv) < 2:
        sys.exit("用法: prep_ledger.py <账本.json> [--team T] [--template 角色文件]")
    path = sys.argv[1]
    team = "grok-l2"
    # 模板会被席位清理顺手删掉（2026-08-19 实撞：清 20 个退役席位时把它一并删了，
    # 下一张账本 prep 当场炸，而驱动器已经把单派给了那个已停用的占位席位）。
    # ⇒ 用一份不随清理消失的常驻角色文件当模板。
    # ⚠️ 模板必须是**工具齐全**的施工席角色文件：advisor.md 的 tools 只有 fs_* + execute_bash，
    # 缺 mcp_team ⇒ 新席位连 report_result 都调不了，行为自证当场失败（2026-08-19 实撞两次）。
    # dev-app.md 已纳入版本控制，清理退役席位时⛔不要删它。
    template = ".team/grok/agents/dev-app.md"
    suffix = ""
    reuse = {}
    for i, a in enumerate(sys.argv):
        if a == "--team":     team = sys.argv[i + 1]
        if a == "--template": template = sys.argv[i + 1]
        # 返工轮要真换一张新脸：同名 = 同一席位 = 上一轮的上下文还在
        if a == "--suffix":   suffix = sys.argv[i + 1]

    led = json.load(open(path, encoding="utf-8"))
    short = led["ledger_id"].replace("ledger.", "").replace(".", "-")
    changed = False

    for tid, task in led["tasks"].items():
        role = task["owner"]["role"]
        if led["roles"].get(role, {}).get("provider") == "human":
            continue
        # 终态格不重 prep：它的活已经验过了，再去戳它的席位只会浪费一次行为自证，
        # 席位没响应还会把整条准备流程卡死（实撞一次）。
        if task.get("state") in ("succeeded", "failed_terminal", "not_applicable"):
            continue
        # ① 基底
        base = subprocess.run(
            [sys.executable, "tools/basegen_ledger.py", path, tid],
            capture_output=True, text=True)
        base_path = base.stdout.strip()
        if base.returncode != 0 or not base_path:
            sys.exit(f"基底编译失败 {tid}: {base.stderr[-400:]}")

        # ② 专属席位
        reused = tid in reuse
        seat = reuse[tid] if reused else f"{short}-{tid.replace('t.', '')}{suffix}"[:48]
        role_file = f".team/grok/agents/{seat}.md"
        if not reused and not os.path.exists(role_file):
            src = open(template, encoding="utf-8").read()
            name_line = [l for l in src.splitlines() if l.startswith("name:")][0]
            open(role_file, "w", encoding="utf-8").write(src.replace(name_line, f"name: {seat}", 1))
        r = sh("true") if reused else sh("team-agent", "add-agent", seat, "--role-file", role_file, "--workspace", ".")
        if "ok: True" not in r.stdout and "already" not in (r.stdout + r.stderr).lower():
            sys.exit(f"建席失败 {seat}: {(r.stdout + r.stderr)[-400:]}")
        sh("team-agent", "start-agent", seat, "--workspace", ".")

        # ③ 行为自证：能写盘才算有手
        probe = f".team/nodes/{tid.replace('t.', '')}/_hands.txt"
        if reused:
            led["roles"][role]["seat"] = {"agent": seat, "team": team}
            rp = task.setdefault("resources", {}).setdefault("read_paths", [])
            if base_path not in rp: rp.append(base_path)
            _inline_base(task, base_path)
            changed = True; print(f"{tid}: seat={seat}（复用） base={base_path}"); continue
        if os.path.exists(probe):
            os.remove(probe)
        sh("team-agent", "send", seat,
           f"行为自证：只执行 `echo ok > {os.path.join(WS, probe)}`，然后停手。"
           f"⛔ 不要调 report_result、不要回复、不要给任何人发消息 —— "
           f"文件写出来本身就是回执（用户令：节点禁止给 leader 发消息）。")
        for _ in range(20):
            if os.path.exists(probe):
                break
            time.sleep(6)
        if not os.path.exists(probe):
            sys.exit(f"⛔ {seat} 建起来了但写不了盘（只会说话不会干活）——"
                     f"检查角色文件 tools 是否被降级。存活自证不算数。")

        # ④ 写回账本
        led["roles"][role]["seat"] = {"agent": seat, "team": team}
        rp = task.setdefault("resources", {}).setdefault("read_paths", [])
        if base_path not in rp:
            rp.append(base_path)
        # 🔴 基底**前置且内联**，不是末尾丢一个路径。
        # 实测（findings F-12）：账本的 resources.read_paths 被框架静默吞掉，
        # 派单里那节 `## 你需要读的` 永远是空的 ⇒ 结构化那条路是断的。
        # 而追加在任务书末尾的一句「去读 <path>」会被埋在纪律堆里，
        # 且只是**给路径**——席位读不读没有任何保证。
        # ⇒ 直接把闭包内容内联到任务书最前面：给内容，不给路径。
        _inline_base(task, base_path)
        changed = True
        print(f"{tid}: seat={seat} base={base_path}")

    if changed:
        led["revision"] = led.get("revision", 1) + 1
        json.dump(led, open(path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
        print(f"账本已更新 revision -> {led['revision']}")

if __name__ == "__main__":
    main()
