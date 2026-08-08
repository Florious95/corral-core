#!/usr/bin/env python3
"""build_wiki.py — 架构维基现算生成器（arch-wiki 工装）。

从源码现算架构图与判据，输出 docs/wiki/。禁止人工维护架构文档：
本工具输出全部可重生成且幂等（重跑无 diff）。

输入面（只读，绝不写入）：
  * server/   Go module：`go list -json ./...` 最稳；失败时回退源码轻解析。
  * app/      Kotlin 源码：正则/轻解析 import 与 KDoc 即可，不上完整解析器。

输出面：
  * docs/wiki/README.md —— 判据结果 + mermaid 总依赖图 + 每包一张架构卡。

判据（--check 模式，违反 exit 非 0）：
  T1-1  internal 包环依赖
  T1-2  包缺 doc 注释（Go: doc.go/包注释；Kotlin: 模块 KDoc）

判据准入纪律：每条判据必须自带红测 fixture（testdata/），写不出红测的不准入。
本文件注释即外骨骼标注：结构化、机器可读，供后续判据（如围栏 YAML 解析）直接消费。
"""

import argparse
import json
import os
import re
import shutil
import subprocess
import sys

# ---------------------------------------------------------------------------
# 常量：目录形状、判据标识
# ---------------------------------------------------------------------------

MODULE_PREFIX = "github.com/remote-agent/agentmirror"  # server/go.mod 的 module 行
GO_SUBDIR = "server"          # Go module 在仓库内的相对目录
KT_SEARCH = ("app/app/src/main/java",)  # Kotlin 源码根候选（相对仓库根）
WIKI_SUBDIR = "docs/wiki"     # 生成物输出目录

CRITERIA = [
    ("T1-1", "internal 包环依赖"),
    ("T1-2", "包缺 doc 注释"),
]

# 预留判据（只留接口，不实现、不进 --check）。准入纪律：落地前必须先配红测。
FUTURE_CRITERIA = [
    ("T2-1", "零消费者包", "没有任何包引用它 → 未落地，仅预留"),
    ("T2-2", "孤儿子图", "从进程入口无法到达的包子图 → 未落地，仅预留"),
]


# ---------------------------------------------------------------------------
# 小工具
# ---------------------------------------------------------------------------

def read_text(path):
    """读取 UTF-8 文本，宽容坏字节（源码解析场景，坏字节不应让整个工具崩掉）。"""
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        return fh.read()


def first_sentence(text, limit=140):
    """取 doc 注释的首句作为架构卡"职责"列。

    以句号/问号/感叹号后随空白为句子边界；无标点时截断到 limit。
    """
    if not text:
        return ""
    flat = " ".join(text.split())
    # 中英句号都算句子边界；中文 doc 注释首句应停在「。」。
    for m in re.finditer(r"[.!?。！？](?=\s|$)", flat):
        sentence = flat[: m.end()]
        if len(sentence) > 6:  # 跳过 "e.g." 这类缩写尾巴（首句不可能是 7 字符内）
            return sentence.strip()
    return flat[:limit]


# ---------------------------------------------------------------------------
# Go 侧采集
# ---------------------------------------------------------------------------

# 单行 import：`import "path"`（忽略别名/点/下划线 import，只取引号内路径）。
_GO_SINGLE_IMPORT = re.compile(r'\bimport\s+"([^"]+)"')
# import 块：`import ( ... )`，块内逐行取引号内路径。
_GO_BLOCK_IMPORT = re.compile(r"\bimport\s*\(([^)]*)\)")
# 顶层导出声明：`func/type/var/const Foo`（大写开头 = 导出）。
_GO_TOP_DECL = re.compile(r"^(?:func|type|var|const)\s+([A-Z]\w*)", re.MULTILINE)
# 块内导出成员：const/var 块里的 `Foo = ...`。
_GO_BLOCK_MEMBER = re.compile(r"^\s*([A-Z]\w*)\s*=\s*", re.MULTILINE)


def _go_imports_of_text(text):
    """轻解析一段 Go 源码的全部 import 路径（不做语法树）。

    覆盖两种形态：单行 import 与 import( ... ) 块。块内注释行无引号，
    自然被忽略；路径中不含括号，块内捕获到第一个 ) 即止，足够可靠。
    """
    imports = set()
    for m in _GO_SINGLE_IMPORT.finditer(text):
        imports.add(m.group(1))
    for m in _GO_BLOCK_IMPORT.finditer(text):
        for line in m.group(1).splitlines():
            q = re.search(r'"([^"]+)"', line)
            if q:
                imports.add(q.group(1))
    return imports


def _non_test_go_files(pkg_dir):
    """目录下非测试 .go 文件（doc.go 也在内，其无 import 不影响）。"""
    return sorted(
        os.path.join(pkg_dir, f)
        for f in os.listdir(pkg_dir)
        if f.endswith(".go") and not f.endswith("_test.go")
    )


def _go_package_doc_from_files(pkg_dir, pkg_name):
    """Go 包注释提取：优先 doc.go，否则找 package 子句紧邻注释块。

    只认紧邻 `package X` 上方的连续 `//` 注释（Go 官方 doc comment 约定），
    中间隔空行则视为非包注释。
    """
    doc_go = os.path.join(pkg_dir, "doc.go")
    if os.path.isfile(doc_go):
        doc = _go_package_comment(read_text(doc_go), pkg_name)
        if doc:
            return doc
    for f in _non_test_go_files(pkg_dir):
        doc = _go_package_comment(read_text(f), pkg_name)
        if doc:
            return doc
    return ""


def _go_package_comment(text, pkg_name):
    """从文本中提取紧邻 `package <pkg_name>` 上方的 // 注释块。

    按行号定位 package 声明后向上扫描；遇空行或非注释行立即截断，
    保证 doc 注释与 package 子句之间不含空行（Go 官方 doc comment 约定）。
    """
    lines = text.splitlines()
    pkg_re = re.compile(r"^\s*package\s+" + re.escape(pkg_name) + r"\b")
    pkg_idx = next((i for i, ln in enumerate(lines) if pkg_re.match(ln)), None)
    if pkg_idx is None:
        return ""
    doc_lines = []
    i = pkg_idx - 1
    while i >= 0:
        s = lines[i].strip()
        if s.startswith("//"):
            doc_lines.append(s[2:].strip())
        else:
            break  # 空行/非注释行截断：doc 必须紧邻 package
        i -= 1
    return "\n".join(reversed(doc_lines)).strip()


def _go_exports(pkg_dir, is_main):
    """Go 包导出面：顶层导出符号（大写开头）；package main 特判显示入口。"""
    exports = set()
    for f in _non_test_go_files(pkg_dir):
        text = read_text(f)
        exports.update(n for n in _GO_TOP_DECL.findall(text))
        exports.update(n for n in _GO_BLOCK_MEMBER.findall(text))
    if is_main:
        exports.add("main")  # 进程入口
    return sorted(exports)


def collect_go_source(root, module_prefix):
    """源码模式采集 Go 包（不依赖 go 工具链）。

    供 (a) 无 go 命令环境回退；(b) 判据红测 fixture（go list 无法编译环依赖
    假包，而源码模式可以解析出环）。包键 = 相对 module 根的目录路径。
    """
    server_root = os.path.join(root, GO_SUBDIR)
    go_mod = os.path.join(server_root, "go.mod")
    if os.path.isfile(go_mod):
        m = re.search(r"^\s*module\s+(\S+)", read_text(go_mod), re.MULTILINE)
        module_prefix = m.group(1) if m else module_prefix

    pkgs = {}
    for dirpath, dirnames, files in os.walk(server_root):
        dirnames[:] = [d for d in dirnames if d not in ("vendor", ".git")]
        go_files = _non_test_go_files(dirpath)
        if not go_files:
            continue
        rel = os.path.relpath(dirpath, server_root).replace(os.sep, "/")
        if rel == ".":
            continue  # module 根无包文件（go.mod 不算包）
        pkg_name = os.path.basename(dirpath)
        imports = set()
        for f in go_files:
            imports.update(_go_imports_of_text(read_text(f)))
        deps = sorted(
            i[len(module_prefix) + 1 :]
            for i in imports
            if i.startswith(module_prefix + "/")
        )
        doc = _go_package_doc_from_files(dirpath, pkg_name)
        is_main = any("package main" in read_text(f) for f in go_files)
        pkgs[rel] = {
            "kind": "go",
            "name": rel,
            "full": module_prefix + "/" + rel,
            "doc": doc,
            "summary": first_sentence(doc),
            "exports": _go_exports(dirpath, is_main),
            "deps": deps,
            "doc_ok": bool(doc.strip()),
            "is_main": is_main,
        }
    return pkgs


def collect_go_go_list(root, module_prefix):
    """go list 模式采集 Go 包（默认、最稳路径）。

    `go list -json ./...` 一次给出 ImportPath/Doc/GoFiles/Imports，
    比手写解析更抗语法演进；仍自行读源文件算导出面。
    """
    server_root = os.path.join(root, GO_SUBDIR)
    proc = subprocess.run(
        ["go", "list", "-json", "./..."],
        cwd=server_root,
        capture_output=True,
        text=True,
        timeout=120,
    )
    if proc.returncode != 0:
        return None  # 由调用方回退到源码模式

    pkgs = {}
    buf = ""
    for line in proc.stdout.splitlines(keepends=True):
        buf += line
        try:
            p = json.loads(buf)
        except json.JSONDecodeError:
            continue
        buf = ""
        full = p.get("ImportPath", "")
        if not full.startswith(module_prefix):
            continue  # 只收本 module 包，绝不让外部依赖进图
        rel = full[len(module_prefix) + 1 :]
        deps = sorted(
            i[len(module_prefix) + 1 :]
            for i in p.get("Imports", [])
            if i.startswith(module_prefix + "/")
        )
        pkg_dir = p.get("Dir", "")
        pkg_name = p.get("Name", "")
        doc = (p.get("Doc") or "").strip()
        exports = _go_exports(pkg_dir, pkg_name == "main") if pkg_dir else []
        pkgs[rel] = {
            "kind": "go",
            "name": rel,
            "full": full,
            "doc": doc,
            "summary": first_sentence(doc),
            "exports": exports,
            "deps": deps,
            "doc_ok": bool(doc),
            "is_main": pkg_name == "main",
        }
    return pkgs


def collect_go(root, force_source=False):
    """Go 侧总入口：默认 go list，失败或无 go 命令时回退源码模式。"""
    if not force_source and shutil.which("go"):
        pkgs = collect_go_go_list(root, MODULE_PREFIX)
        if pkgs is not None:
            return pkgs
    return collect_go_source(root, MODULE_PREFIX)


# ---------------------------------------------------------------------------
# Kotlin 侧采集
# ---------------------------------------------------------------------------

_KT_PACKAGE_DECL = re.compile(r"^\s*package\s+([\w.]+)", re.MULTILINE)
_KT_KDOC_BLOCK = re.compile(r"/\*\*.*?\*/", re.S)
_KT_EXPORT_DECL = re.compile(
    r"^(?:public\s+)?(?:fun|class|interface|object|enum class|data class|"
    r"sealed class|val|var|typealias)\s+([A-Z]\w*)",
    re.MULTILINE,
)


def _kotlin_kdocs(text):
    """提取一段 Kotlin 源码内的全部 KDoc 块（返回纯文本正文）。"""
    return [_kdoc_text(b) for b in _KT_KDOC_BLOCK.findall(text) if b.strip()]


def _kdoc_text(raw):
    """把 KDoc 原始块剥成纯文本正文。

    先整体剥掉首尾的 /** 与 */，再逐行剥行首 * 装饰。
    兼容单行 KDoc（"/** 品牌色 */" → "品牌色"）与多行块。
    """
    body = raw.strip()
    if body.startswith("/**"):
        body = body[3:]
    if body.endswith("*/"):
        body = body[:-2]
    out = []
    for ln in body.splitlines():
        s = ln.strip()
        if s.startswith("*"):
            s = s[1:].lstrip()
        out.append(s)
    return "\n".join(out).strip()


def _kotlin_exports(text):
    """Kotlin 包导出面：无 private/internal/protected 修饰的顶层声明。

    _KT_EXPORT_DECL 正则（含 \\s 空白类，raw 串编译）逐行匹配。

    逐行判断：@ 注解行跳过（归下一声明），修饰符遮挡的声明天然不匹配
    `^(?:public\s+)?(?:fun|...)` 模式（正则字面用 raw 串），从而被排除。
    """
    exports = []
    for line in text.splitlines():
        s = line.strip()
        if not s or s.startswith("@"):
            continue
        m = _KT_EXPORT_DECL.match(s)
        if m:
            exports.append(m.group(1))
    return sorted(set(exports))


def _find_kotlin_roots(root):
    """定位 Kotlin 源码根（默认 app/app/src/main/java）。"""
    for cand in KT_SEARCH:
        full = os.path.join(root, cand)
        if os.path.isdir(full):
            return [full]
    # 兜底：任何 `*/src/main/java`（模块目录命名可能演进）。
    app_dir = os.path.join(root, "app")
    if os.path.isdir(app_dir):
        return sorted(
            os.path.join(d, "src", "main", "java")
            for d in os.listdir(app_dir)
            if os.path.isdir(os.path.join(d, "src", "main", "java"))
        )
    return []


def collect_kotlin(root):
    """采集 Kotlin 包：按声明的 package 分组，逐文件记录 KDoc 与 import。

    T1-2 的 Kotlin 侧规则（模块 KDoc）：
      1) 包内存在 PackageDoc.kt 且含 KDoc 块 → 达标，模块 doc 取该块；
      2) 否则包内每个 .kt 文件都必须含至少一个 KDoc 块（文件级兜底，
         兼容 dev.agentmirror.app / ui.theme 这种无 PackageDoc.kt 的包）。
    """
    pkgs = {}
    for src_root in _find_kotlin_roots(root):
        for dirpath, dirnames, files in os.walk(src_root):
            dirnames[:] = [d for d in dirnames if d != "build"]
            kt_files = sorted(f for f in files if f.endswith(".kt"))
            if not kt_files:
                continue
            # 以第一个文件的 package 声明定包名（Kotlin 目录内包名一致）。
            pkg_name = ""
            for f in kt_files:
                m = _KT_PACKAGE_DECL.search(read_text(os.path.join(dirpath, f)))
                if m:
                    pkg_name = m.group(1)
                    break
            if not pkg_name:
                continue
            if pkg_name in pkgs:
                continue  # 同一包只收首个目录（防御性去重）

            file_records = []
            has_pkg_doc_file = False
            pkg_doc_kdoc = ""
            exports = set()
            deps = set()
            for f in kt_files:
                text = read_text(os.path.join(dirpath, f))
                kdocs = _kotlin_kdocs(text)
                file_records.append({"file": f, "has_kdoc": bool(kdocs)})
                if f == "PackageDoc.kt" and kdocs:
                    has_pkg_doc_file = True
                    pkg_doc_kdoc = kdocs[0]
                exports.update(_kotlin_exports(text))
                for imp in re.findall(r"^import\s+([\w.]+)", text, re.MULTILINE):
                    deps.add(imp)
            deps.discard(pkg_name)  # 同包 import 不算边

            doc = pkg_doc_kdoc or ""
            if not doc:
                # 兜底模块 doc：取包内首个文件的第一个 KDoc（root/theme 情形）。
                for f in kt_files:
                    kd = _kotlin_kdocs(read_text(os.path.join(dirpath, f)))
                    if kd:
                        doc = kd[0]
                        break
            doc_ok = has_pkg_doc_file or all(r["has_kdoc"] for r in file_records)

            pkgs[pkg_name] = {
                "kind": "kotlin",
                "name": pkg_name,
                "full": pkg_name,
                "doc": doc,
                "summary": first_sentence(doc),
                "exports": sorted(exports),
                "deps": set(),  # 待 resolve_kotlin_deps 填
                "doc_ok": doc_ok,
                "is_main": False,
            }
            # 记录原始 import 集合，稍后解析为包间依赖。
            pkgs[pkg_name]["_raw_deps"] = deps
    resolve_kotlin_deps(pkgs)
    return pkgs


def resolve_kotlin_deps(pkgs):
    """把 Kotlin import 解析为包间边。

    import `dev.agentmirror.app.ui.theme.AgentMirrorTheme` 解析为最长匹配的
    已知包前缀（这里是 dev.agentmirror.app.ui.theme），得到一个包间依赖边。
    """
    known = sorted(pkgs, key=len, reverse=True)  # 最长前缀优先匹配
    for pkg in pkgs.values():
        resolved = set()
        for imp in pkg.pop("_raw_deps", ()):
            best = None
            for cand in known:
                if imp == cand or imp.startswith(cand + "."):
                    if best is None or len(cand) > len(best):
                        best = cand
            if best is not None and best != pkg["full"]:
                resolved.add(best)
        pkg["deps"] = sorted(resolved)


# ---------------------------------------------------------------------------
# 判据实现
# ---------------------------------------------------------------------------

def find_cycle(graph):
    """DFS 三色法检测有向图环，返回环路径列表或 None。

    白=未访问，灰=在栈中（发现灰节点即环），黑=已出栈。
    """
    WHITE, GRAY, BLACK = 0, 1, 2
    color = {n: WHITE for n in graph}
    stack = []

    def dfs(node):
        color[node] = GRAY
        stack.append(node)
        for dep in sorted(graph.get(node, ())):
            if dep not in color:
                continue  # 外部节点已在采集时过滤
            if color[dep] == GRAY:
                idx = stack.index(dep)
                return stack[idx:] + [dep]
            if color[dep] == WHITE:
                found = dfs(dep)
                if found:
                    return found
        stack.pop()
        color[node] = BLACK
        return None

    for node in sorted(graph):
        if color[node] == WHITE:
            found = dfs(node)
            if found:
                return found
    return None


def check_t1_1_cycles(go_pkgs, _kt_pkgs=None):
    """T1-1 internal 包环依赖。返回 (通过?, 描述)。"""
    graph = {name: pkg["deps"] for name, pkg in go_pkgs.items()}
    cycle = find_cycle(graph)
    if cycle:
        return False, "检测到环: " + " -> ".join(cycle)
    return True, "无环（%d 个 Go 包）" % len(go_pkgs)


def check_t1_2_docs(go_pkgs, kt_pkgs):
    """T1-2 包缺 doc 注释。返回 (通过?, 描述)。"""
    missing = []
    for name, pkg in go_pkgs.items():
        if not pkg["doc_ok"]:
            missing.append("go:" + name)
    for name, pkg in kt_pkgs.items():
        if not pkg["doc_ok"]:
            missing.append("kt:" + name)
    if missing:
        return False, "缺 doc 注释的包: " + ", ".join(missing)
    return True, "Go %d 包、Kotlin %d 包均有模块 doc" % (len(go_pkgs), len(kt_pkgs))


def run_criteria(go_pkgs, kt_pkgs):
    """运行全部判据，返回 [(判据id, 通过?, 描述)]。"""
    results = []
    for cid, fn in (("T1-1", check_t1_1_cycles), ("T1-2", check_t1_2_docs)):
        ok, desc = fn(go_pkgs, kt_pkgs)
        results.append((cid, ok, desc))
    return results


# ---------------------------------------------------------------------------
# 预留接口（只留接口不实现）
# ---------------------------------------------------------------------------

def parse_exoskeleton_fences(source_text):
    """外骨骼 YAML 围栏标注解析（预留接口，未实现）。

    # [exto-interface] 未来判据 T2-3：解析源码内 ```yaml ... ``` 围栏中的
    # 结构化标注（接口契约、迁移红线），并入架构卡"契约"列。
    # 准入纪律：落地时必须先配红测 fixture（见 testdata/ 惯例），否则保持未实现。
    """
    return []


# ---------------------------------------------------------------------------
# 渲染输出（mermaid 总图 + 架构卡）
# ---------------------------------------------------------------------------

def _node_id(kind, name):
    """mermaid 节点 id：只能含字母数字下划线。"""
    base = ("go_" if kind == "go" else "kt_") + name.replace("/", "_").replace(".", "_")
    return re.sub(r"[^A-Za-z0-9_]", "_", base)


def render_mermaid(go_pkgs, kt_pkgs):
    """总依赖图（mermaid flowchart LR）。外部依赖不画，只画本 module 包间边。"""
    lines = ["flowchart LR"]
    for pkg in list(go_pkgs.values()) + list(kt_pkgs.values()):
        nid = _node_id(pkg["kind"], pkg["name"])
        lines.append('    %s["%s"]' % (nid, pkg["name"]))
    for pkg in list(go_pkgs.values()) + list(kt_pkgs.values()):
        nid = _node_id(pkg["kind"], pkg["name"])
        for dep in pkg["deps"]:
            dep_node = _node_id_for_dep(dep, go_pkgs, kt_pkgs)
            if dep_node:
                lines.append("    %s --> %s" % (nid, dep_node))
    return "\n".join(lines)


def _node_id_for_dep(dep_full, go_pkgs, kt_pkgs):
    """按完整路径找依赖对应的节点 id（Go 用 rel 路径，Kotlin 用 fq 名）。"""
    if dep_full in go_pkgs:
        return _node_id("go", dep_full)
    if dep_full in kt_pkgs:
        return _node_id("kotlin", dep_full)
    return None


def render_readme(go_pkgs, kt_pkgs):
    """渲染 docs/wiki/README.md 全文。输出确定性：全排序、无时间戳，保幂等。"""
    all_pkgs = list(go_pkgs.values()) + list(kt_pkgs.values())
    total_edges = sum(len(p["deps"]) for p in all_pkgs)
    results = run_criteria(go_pkgs, kt_pkgs)

    out = []
    out.append("# 架构维基（自动生成）\n")
    out.append(
        "> ⚠️ **生成物，勿手改。** 本文件由 "
        "`tools/archwiki/build_wiki.py` 从源码现算生成，"
        "改源码后重跑 `python3 tools/archwiki/build_wiki.py` 刷新；"
        "重跑无 diff（幂等）。人工改动会被覆盖。\n"
    )
    out.append(
        "扫描 **%d** 个包（Go %d + Kotlin %d）、**%d** 条包间依赖边。\n"
        % (len(all_pkgs), len(go_pkgs), len(kt_pkgs), total_edges)
    )

    out.append("## 判据结果\n")
    out.append("| 判据 | 说明 | 结果 |")
    out.append("|---|---|---|")
    for cid, ok, desc in results:
        out.append("| %s | %s | %s |" % (cid, desc, "✅ 通过" if ok else "❌ 违反"))
    out.append(
        "| _预留_ | 零消费者 / 孤儿子图 | 未落地（进 --check 前须配红测） |"
    )

    out.append("\n## 总依赖图\n")
    out.append("```mermaid")
    out.append(render_mermaid(go_pkgs, kt_pkgs))
    out.append("```\n")

    out.append("## 包架构卡\n")
    sections = []
    for pkg in all_pkgs:
        sections.append(_render_card(pkg))
    out.append("\n\n".join(sections))
    out.append("")  # 结尾换行
    return "\n".join(out)


def _render_card(pkg):
    """渲染一张架构卡：职责、导出面、依赖边。"""
    kind = "Go" if pkg["kind"] == "go" else "Kotlin"
    lines = ["### %s · %s" % (kind, pkg["name"]), ""]
    lines.append("- **职责**：" + (pkg["summary"] or "（无 doc 注释）"))
    exports = ", ".join(pkg["exports"]) if pkg["exports"] else "（无导出符号）"
    lines.append("- **导出面**：" + exports)
    deps = ", ".join(pkg["deps"]) if pkg["deps"] else "（无）"
    lines.append("- **依赖边**：" + deps)
    if pkg["doc"] and pkg["summary"] != pkg["doc"].strip():
        lines.append("- **doc 全文**：" + " ".join(pkg["doc"].split()))
    return "\n".join(lines)


def generate_wiki(go_pkgs, kt_pkgs, out_dir):
    """写出 docs/wiki/。单文件 README.md，先建目录保证可重生成。"""
    os.makedirs(out_dir, exist_ok=True)
    readme = render_readme(go_pkgs, kt_pkgs)
    with open(os.path.join(out_dir, "README.md"), "w", encoding="utf-8") as fh:
        fh.write(readme)


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def build_model(root, force_go_source=False):
    """采集 Go + Kotlin 两侧，合并为统一模型。"""
    go_pkgs = collect_go(root, force_source=force_go_source)
    kt_pkgs = collect_kotlin(root)
    return go_pkgs, kt_pkgs


def run_check(go_pkgs, kt_pkgs, out=sys.stdout):
    """--check 执行：打印判据结果，返回退出码（0 过 / 1 违 / 2 空扫描）。"""
    all_pkgs = list(go_pkgs.values()) + list(kt_pkgs.values())
    total_edges = sum(len(p["deps"]) for p in all_pkgs)
    n = len(all_pkgs)

    print("== 架构维基判据检查（--check）==", file=out)
    # 阳性对照铁律：空扫描视为失败，不视为健康。
    print("扫描 %d 包，%d 条依赖边" % (n, total_edges), file=out)
    if n == 0:
        print("结果：空扫描，失败（exit 2）", file=out)
        return 2

    failed = 0
    for cid, ok, desc in run_criteria(go_pkgs, kt_pkgs):
        mark = "PASS" if ok else "FAIL"
        if not ok:
            failed += 1
        print("%s %-16s : %s — %s" % (cid, " ", mark, desc), file=out)

    if failed:
        print("结果：未通过（exit 1）", file=out)
        return 1
    print("结果：通过（exit 0）", file=out)
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(
        prog="build_wiki.py",
        description="从 Go/Kotlin 源码现算架构维基与判据。"
        "默认生成 docs/wiki/README.md；--check 只判据不写盘。",
        epilog="判据：T1-1 internal 包环依赖；T1-2 包缺 doc 注释。"
        "判据准入纪律：每条判据必须自带红测 fixture（见 testdata/）。",
    )
    parser.add_argument(
        "--root",
        default=os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
        help="仓库根目录（默认：本脚本上级两级，即 tools/ 的父目录）。",
    )
    parser.add_argument(
        "--out",
        default=None,
        help="wiki 输出目录（默认 <root>/docs/wiki）。",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="只跑判据不写盘；违反判据或空扫描时 exit 非 0。",
    )
    parser.add_argument(
        "--go-source",
        action="store_true",
        help="强制 Go 走源码轻解析（跳过 go list）。供无 go 环境与红测 fixture 使用。",
    )
    args = parser.parse_args(argv)

    root = os.path.abspath(args.root)
    go_pkgs, kt_pkgs = build_model(root, force_go_source=args.go_source)

    if args.check:
        return run_check(go_pkgs, kt_pkgs)

    out_dir = args.out or os.path.join(root, WIKI_SUBDIR)
    generate_wiki(go_pkgs, kt_pkgs, out_dir)
    print("已生成 %s/README.md（%d 包，%d 边）"
          % (os.path.abspath(out_dir), len(go_pkgs) + len(kt_pkgs),
             sum(len(p["deps"]) for p in list(go_pkgs.values()) + list(kt_pkgs.values()))))
    return 0


if __name__ == "__main__":
    sys.exit(main())
