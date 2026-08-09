#!/usr/bin/env python3
"""基底编译器（taskbook-orchestration base-compilers.md 的本工程实现）。

基底 = f(影响闭包)：CLAUDE.md 主体由算法产物拼装，手填素材只允许进现场基
（.team/nodes/<task>/FIELD.md 独立载体，编译时引用指针，不混入正文）。

用法：python3 tools/basegen.py <task-id> [--pkgs 包id逗号列表]
  - 任务信封：taskbook.yaml 该条目五栏原文（机械抽取，不转写）
  - 架构基：docs/wiki/README.md 现算闭包（正向=消费契约/反向=波及面）+ 闭包内架构卡全文内联
  - 需求基：goal 文本中的 (见 NNN)/entries/NNN 引用 + librarian 撞库回执（.team/nodes/<task>/LIBRARIAN.md 若存在）
  - 经验基：先例库 .team/precedents.md 全文指针 + 通用纪律
  - 现场基：FIELD.md 指针（leader 手填取证素材唯一合法区）
包集合默认从 write_scope 路径推导（app 包按目录名映射，server 按 internal/<pkg>）；--pkgs 显式覆盖。
"""
import re, sys, os

WS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.chdir(WS)

def task_entry(tid: str) -> str:
    src = open('taskbook.yaml', encoding='utf-8').read()
    m = re.search(rf'(  - id: {re.escape(tid)}\n(?:    .*\n|\n(?=    ))*)', src)
    if not m:
        sys.exit(f"task {tid} not found in taskbook.yaml")
    return m.group(1).rstrip()

def wiki_cards():
    wiki = open('docs/wiki/README.md', encoding='utf-8').read()
    cards = {m.group(1): m.group(0).strip()
             for m in re.finditer(r'### (?:Go|Kotlin) · (\S+)\n(.*?)(?=\n### |\Z)', wiki, re.S)}
    edges = re.findall(r'(\S+)\s*-->\s*(\S+)', wiki)
    return cards, edges

def node_id(pkg: str) -> str:
    # wiki mermaid 节点 id：kt_dev_agentmirror_app_conn / go_internal_api
    if pkg.startswith('dev.'):
        return 'kt_' + pkg.replace('.', '_')
    return 'go_' + pkg.replace('/', '_')

def pkgs_from_scope(entry):
    pkgs = []
    for m in re.finditer(r'"([^"]+)"', entry.split('write_scope:')[1].split('\n')[0]):
        p = m.group(1)
        if p.startswith('server/internal/'):
            pkgs.append('internal/' + p.split('server/internal/')[1].strip('/'))
        elif p.startswith('server/cmd/'):
            pkgs.append('cmd/agentmirrord')
        elif 'src/main/java/dev/agentmirror/app/' in p:
            tail = p.split('src/main/java/dev/agentmirror/app/')[1].strip('/')
            pkgs.append('dev.agentmirror.app' + ('.' + tail.replace('/', '.') if tail else ''))
        elif p.startswith('app/terminal'):
            pkgs.append('terminal')  # :terminal 模块卡名以 wiki 为准
    return [p for p in pkgs if p]

def compile_base(tid, pkgs):
    entry = task_entry(tid)
    cards, edges = wiki_cards()
    pkgs = pkgs or pkgs_from_scope(entry)
    ids = {node_id(p) for p in pkgs}
    fwd = sorted({d for s, d in edges if s in ids and d not in ids})
    rev = sorted({s for s, d in edges if d in ids and s not in ids})
    # 闭包 = scope 包 + 波及包，全部内联架构卡
    def pkg_of(nid):
        for name in cards:
            if node_id(name) == nid:
                return name
        return None
    closure_pkgs = [p for p in pkgs if p in cards] + [q for q in (pkg_of(n) for n in rev) if q]
    refs = sorted(set(re.findall(r'(?:见 |entries/)(\d{3})', entry)))
    nd = f'.team/nodes/{tid}'
    os.makedirs(nd, exist_ok=True)
    lib = f'{nd}/LIBRARIAN.md'
    fld = f'{nd}/FIELD.md'
    parts = [
        f"# 知识基底 · {tid}（tools/basegen.py 编译产物——手工编辑无效，重编请改素材源后重跑）",
        "\n## 1. 任务信封（taskbook.yaml 原文，机械抽取）\n```yaml\n" + entry + "\n```",
        "\n## 2. 架构基（build_wiki.py 现算影响闭包）",
        f"- write_scope 包：{', '.join(pkgs)}",
        f"- 正向依赖（你消费的契约，只读）：{', '.join(fwd) or '无'}",
        f"- **反向依赖（波及面=回归自查范围）**：{', '.join(rev) or '无'}",
        "\n### 闭包架构卡内联（职责/导出面/依赖边）\n",
        "\n\n".join(cards[p] for p in closure_pkgs) or "（无卡命中——报 leader）",
        "\n## 3. 需求基",
        f"- goal 引用条目：{', '.join('requirement-base/entries/' + r + '*' for r in refs) or '（goal 无编号引用）'}",
        f"- librarian 撞库回执：{lib}（先完整读）" if os.path.exists(lib) else "- librarian 撞库：无回执文件（leader 未查或无命中）",
        "- 修订记录 requirement-base/REVISIONS.md 必读（被推翻结论不回改条目）",
        "\n## 4. 经验基（通用纪律+先例）",
        "- 红测先行；每次落盘保持整模块可编译（共享编译单元互阻三次实案）；编译被他人半成品阻断→直接 send 文件主人（附文件+行号+错误原文），主人最高优先修复回执，不经 leader",
        "- 测试净化前缀 env -u TEAM_AGENT_*；tmux 只用自建隔离 socket；杀进程只 scoped kill 自己命名空间（w-fix-statewire 险案）",
        "- 代码必须带注释（设计决策写为什么）；禁止 git push；本地不 commit；report_result 恰好一次带 tests",
        "\n## 5. 现场基（leader 手填取证素材——唯一手填合法区）",
        f"- {fld}（先完整读；含真机实证/失败现场/裁定）" if os.path.exists(fld) else "- 无现场素材文件",
        "",
    ]
    open(f'{nd}/CLAUDE.md', 'w', encoding='utf-8').write('\n'.join(parts))
    print(f"{tid}: cards={len(closure_pkgs)} fwd={len(fwd)} rev={len(rev)} refs={refs} field={'yes' if os.path.exists(fld) else 'no'} librarian={'yes' if os.path.exists(lib) else 'no'}")

if __name__ == '__main__':
    tid = sys.argv[1]
    pkgs = sys.argv[sys.argv.index('--pkgs') + 1].split(',') if '--pkgs' in sys.argv else None
    compile_base(tid, pkgs)
