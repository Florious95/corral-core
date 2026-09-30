#!/usr/bin/env python3
"""账本版基底编译器：从账本任务的 write_paths 现算影响闭包，产出知识基底。

为什么要它：tools/basegen.py 的闭包算法可复用，但它的任务信封只认 taskbook.yaml，
而我们现在走 ledger.v2。这里只替换「信封来源」，闭包算法整段复用，
⛔ 不复制第二份闭包实现——两份会静默漂移（同 063 单一语料那条）。

用法：python3 tools/basegen_ledger.py <账本.json> <任务id>
产出：.team/nodes/<任务id 去掉 t. 前缀>/BASE.md
"""
import json, os, re, sys

WS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.chdir(WS)
sys.path.insert(0, os.path.join(WS, 'tools'))
import basegen  # 复用 wiki_cards / node_id / pkgs 映射

def pkgs_from_paths(paths):
    pkgs = []
    for p in paths:
        if p.startswith('server/internal/'):
            pkgs.append('internal/' + p.split('server/internal/')[1].strip('/'))
        elif p.startswith('server/cmd/'):
            pkgs.append('cmd/agentmirrord')
        elif p.startswith('server/'):
            pkgs.append('internal/api')          # 整个 server/ 被写 ⇒ 以 api 为锚
        elif 'src/main/java/dev/agentmirror/app/' in p:
            tail = p.split('src/main/java/dev/agentmirror/app/')[1].strip('/')
            pkgs.append('dev.agentmirror.app' + ('.' + tail.replace('/', '.') if tail else ''))
        elif p.startswith('app/terminal'):
            pkgs.append('terminal')
        elif p.startswith('app/'):
            pkgs.append('dev.agentmirror.app')
    return sorted(set(p for p in pkgs if p))

def main():
    if len(sys.argv) < 3:
        sys.exit("用法: basegen_ledger.py <账本.json> <任务id>")
    led = json.load(open(sys.argv[1], encoding='utf-8'))
    tid = sys.argv[2]
    task = led['tasks'][tid]
    res = task.get('resources', {})
    pkgs = pkgs_from_paths(res.get('write_paths', []))
    # 验收/探针格的 write_paths 只有产物目录（.team/nodes/xxx/），命不中任何代码包 ⇒
    # 闭包算出来是空的，基底退化成一张只有纪律的白纸。这类格恰恰最需要知道波及面。
    # ⇒ 写范围命不中就按**读范围**算闭包：它验的是那些代码。
    if not pkgs:
        pkgs = pkgs_from_paths(res.get('read_paths', []))
    cards, edges = basegen.wiki_cards()
    ids = {basegen.node_id(p) for p in pkgs}
    fwd = sorted({d for s, d in edges if s in ids and d not in ids})
    rev = sorted({s for s, d in edges if d in ids and s not in ids})

    def pkg_of(nid):
        for name in cards:
            if basegen.node_id(name) == nid:
                return name
        return None

    closure = [p for p in pkgs if p in cards] + [q for q in (pkg_of(n) for n in rev) if q]
    refs = sorted(set(re.findall(r'(?:见 |entries/)(\d{3})', task.get('title', ''))))
    out_dir = f".team/nodes/{tid.replace('t.', '')}"
    os.makedirs(out_dir, exist_ok=True)
    parts = [
        f"# 知识基底 · {led['ledger_id']} / {tid}（tools/basegen_ledger.py 编译产物，手工编辑无效）",
        "\n## 1. 任务信封（账本原文，机械抽取）",
        "```\n" + task.get('title', '') + "\n```",
        f"\n- write_paths: {', '.join(res.get('write_paths', [])) or '（无）'}",
        f"- read_paths: {', '.join(res.get('read_paths', [])) or '（无）'}",
        f"- 判据: {', '.join(a['acceptance_id'] for a in task.get('acceptance', {}).get('mechanical', []))}",
        "\n## 2. 架构基（wiki 现算影响闭包）",
        f"- 写作用域包：{', '.join(pkgs) or '（未命中已知包，报 leader）'}",
        f"- 正向依赖（你消费的契约，只读）：{', '.join(fwd) or '无'}",
        f"- **反向依赖（波及面 = 回归自查范围）**：{', '.join(rev) or '无'}",
        "\n### 闭包架构卡内联\n",
        "\n\n".join(cards[p] for p in closure) or "（无卡命中——报 leader，不要猜）",
        "\n## 3. 需求基",
        "- 标题引用条目：" + (', '.join('requirement-base/entries/' + r + '*' for r in refs) or '（无编号引用）'),
        "- requirement-base/REVISIONS.md 必读（被推翻的结论不回改条目）",
        "\n## 4. 纪律（本工程通用，违反即返工）",
        "- 判据要断言「世界变了」，不是「东西在那儿」。写完先拿它去跑坏状态，判不红就是白写。",
        "- 单测绿 ≠ 功能通。凡是用户能点到的东西，必须模拟器实测截图（见 .claude/skills/emulator-manual-test）。",
        "- ⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux（默认 socket）。",
        "- 干完调一次 report_result，不要传 task_id 参数。不给 leader 发进度消息。",
    ]
    path = f"{out_dir}/BASE.md"
    open(path, 'w', encoding='utf-8').write("\n".join(parts) + "\n")
    print(path)

if __name__ == '__main__':
    main()
