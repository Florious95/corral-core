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

T3 判据（阶段一「注释即契约」验收定义先行，arch-criteria-t3）：
  T3-1  符号级 doc 覆盖——非测试导出符号（Go 顶层导出 decl / Kotlin 顶层 public 声明）
        必须有紧邻 doc/KDoc。T1-2 只到包级，这条升到符号级。
  T3-2  引用真实性——doc/KDoc/外骨骼标签文本里提到的符号名、仓库文件路径、CLI flag
        必须在仓库中真实存在。判定保守到不误报为止（宁可漏也不能吵）：
        只判三种明确形状——反引号包裹且大写开头的标识符、含 / 且以已知扩展名结尾的
        路径串、--flag 形式的串（仅 Go 侧 doc，daemon CLI 表面所在）；自然语言普通词不判。

T3 判据（阶段二「补契约」验收定义先行，arch-criteria-t3-contract）：
  T3-3  契约标签完备——凡标了 @contract 的符号，四标签 @pre / @post / @err / @inv 必须齐全。
        允许显式写 none（表示「确无此项」），但不许缺项；缺项即「契约半成品」。
  T3-4  跨层声明一致——@consumes 声明的包必须真在该包的 import 图里；反之，跨层 import 了
        却没声明的判架构漂移。import 图用既有采集结果（go_pkgs/kt_pkgs 的 deps），不重新解析。

        边界诚实（承 arch-criteria-t3 教训，不得重犯）：T3-3 只验标签**齐不齐**，
        T3-4 只验声明与 import 图**一致不一致**——@post 写的内容是不是真的、@err 描述的
        错误语义对不对，属语义事实，静态判据判不了，那一面由用例覆盖。报告与 HANDBOOK
        必须明写这条边界，不许暗示判据有它不具备的保护力。

        分级开关：默认报告模式列清单不改退出码；--strict-t3 才计入退出码；
        --pkg <包名> 单包硬判（阶段一/二逐包收口时每包的 acceptance）。

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

MODULE_PREFIX = "github.com/agentmirror/agentmirror"  # server/go.mod 的 module 行（naming 转正，2026-08-09）
GO_SUBDIR = "server"          # Go module 在仓库内的相对目录
# Kotlin 源码根候选：app/<模块>/src/main/ 下的源码目录名。既有模块（app/app）用 java，
# 新模块（app/terminal）用 kotlin——两种形态都要覆盖，按形状发现不写死模块名。
KT_SEARCH = ("java", "kotlin")
WIKI_SUBDIR = "docs/wiki"     # 生成物输出目录

# 脚本所在位置的仓库根（本脚本位于 <root>/tools/archwiki/，三级上级即仓库根）。
DEFAULT_REPO_ROOT = os.path.dirname(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
)

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
# T3 判据常量（arch-criteria-t3，阶段一验收定义先行）
# ---------------------------------------------------------------------------

# T3-2 路径引用只认这些已知源码/文档/资产扩展名结尾的串（含 / 且以扩展名结尾）。
T3_PATH_EXTENSIONS = (
    "go", "kt", "md", "py", "yaml", "yml", "json", "sh", "xml", "txt", "toml",
    "gradle", "kts", "proto", "c", "h", "rs", "js", "ts", "html", "css", "aar",
)
_T3_PATH_EXT_PATTERN = "|".join(sorted(T3_PATH_EXTENSIONS))

# T3-2 反引号引用：大写开头的标识符才判，且排除协议/工具类全大写词（非仓库符号，
# 宁可漏也不能吵——见 HANDBOOK.md 判据边界）。
T3_NONSYMBOL_TOKENS = frozenset(
    ("POST", "GET", "PUT", "PATCH", "DELETE", "HEAD",
     "HTTP", "HTTPS", "WS", "WSS", "URL", "URI",
     "JSON", "YAML", "XML", "SQL", "README")
)

# 路径引用：至少一个目录段 + 末段 + 已知扩展名。前向负断言排除 //、URL 前缀
# （//192.168.1.5、ws://host/… 这类串不得被当成仓库文件路径）。
_T3_PATH_REF = re.compile(
    r"(?<![A-Za-z0-9_/])"
    r"((?:[A-Za-z0-9_~.-]+/)+[A-Za-z0-9_~.-]+\.(?:" + _T3_PATH_EXT_PATTERN + r"))"
    r"(?![\w])"
)
# CLI flag 引用：--flag 形式，前后不接词字符/连字符。
_T3_FLAG_REF = re.compile(r"(?<![\w-])--[a-z][a-z0-9-]*(?![\w-])")
# 反引号包裹的标识符（含短语/表格，短语在候选提取时排除）。
_T3_BACKTICK_REF = re.compile(r"`([^`]+)`")
# Go flag 注册行：fs.String("name", …) / fs.Bool / fs.Int / fs.Duration 等。
_GO_FLAG_REG = re.compile(
    r'fs\.(?:String|Bool|Int|Int64|Uint|Uint64|Float64|Duration|Var)\("([a-z][a-z0-9-]*)"'
)

# 外部引用白名单（宁可漏不可吵）：这些前缀/flag 明确指向仓库之外的东西，
# 不是本仓库的符号/路径/CLI，判据不验。
#   * src/detect/manifests/claude.toml — herdr 合规注记（外部参考实现，见 adapters.go）
#   * --tests — gradle 验收 flag，不是 daemon CLI
T3_EXTERNAL_PATH_PREFIXES = ("herdr/", "src/detect/manifests/")
T3_EXTERNAL_FLAGS = frozenset(("tests",))


# ---------------------------------------------------------------------------
# T3-3/T3-4 常量（arch-criteria-t3-contract，阶段二验收定义先行）
# ---------------------------------------------------------------------------

# 契约标签集（docs/next-round-plan-20260810.md §3.1，本工程自定）：
#   * 声明标签：@contract（符号有契约）、@consumes / @produces（跨层依赖声明）。
#   * 内容标签：@pre / @post / @err / @inv（@contract 符号必须齐全的四项）。
# Go 写在 doc 注释里，Kotlin 写同名 KDoc 标签；@label 后跟冒号或空白。
T3_CONTRACT_TAGS = ("contract", "consumes", "produces", "pre", "post", "err", "inv")
# @contract 符号必须齐全的四标签。
T3_CONTRACT_REQUIRED = ("pre", "post", "err", "inv")
# 显式写 none 表示「确无此项」，属于合法齐全（不是缺项）——T3-3 只查标签**在不在**，
# 值是什么（含 none）不影响判定，none 语义仅作 HANDBOOK 口径文档。
T3_TAG_NONE_WORDS = ("none", "无", "n/a", "na", "-", "—", "--")


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
    """从文本中提取紧邻 package 声明上方的 // 注释块。

    按行号定位 package 声明后向上扫描；遇空行或非注释行立即截断，
    保证 doc 注释与 package 子句之间不含空行（Go 官方 doc comment 约定）。

    pkg_name 是**目录名**。Go 命令包目录（cmd/<名>）声明 `package main`，目录名与
    包名不一致，此时照 `collect_go_source()` 的 is_main 口径（286 行）把 `package main`
    也归属本目录（命令包 doc 必须能被读到）。库包目录名 == 包名，行为不变。
    """
    lines = text.splitlines()
    pkg_re = re.compile(r"^\s*package\s+" + re.escape(pkg_name) + r"\b")
    pkg_idx = next((i for i, ln in enumerate(lines) if pkg_re.match(ln)), None)
    if pkg_idx is None:
        main_re = re.compile(r"^\s*package\s+main\s*(?:$|/\*|//)")
        pkg_idx = next((i for i, ln in enumerate(lines) if main_re.match(ln)), None)
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
    """定位 Kotlin 源码根：按 `app/<模块>/src/main/{java,kotlin}` 形状跨模块发现。

    KT_SEARCH 是 src/main/ 下的源码目录名候选（java 与 kotlin 两种形态）。既有模块
    app/app 用 java、新模块 app/terminal 用 kotlin，将来新增模块只需照此形状放源码。
    不写死模块名——`app/*/src/main/{java,kotlin}` 一律进采集。
    """
    app_dir = os.path.join(root, "app")
    if not os.path.isdir(app_dir):
        return []
    roots = []
    for mod in sorted(os.listdir(app_dir)):
        for sub in KT_SEARCH:
            full = os.path.join(app_dir, mod, "src", "main", sub)
            if os.path.isdir(full):
                roots.append(full)
    return roots


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
# T3 判据实现（arch-criteria-t3：阶段一「注释即契约」的验收定义先行）
# ---------------------------------------------------------------------------
# T3-1 符号级 doc 覆盖：非测试导出符号必须有紧邻 doc/KDoc（复用 _GO_TOP_DECL /
#       _KT_EXPORT_DECL，不另起炉灶）。T3-2 引用真实性：全部注释形态（KDoc + 普通 // + /* */ +
#       行尾注释）里提到的符号名、仓库文件路径、CLI flag 必须在仓库中真实存在。
# 准入纪律：每条判据先配红测 fixture（testdata/ 下的 missingdoc-symbol/ 与
#       lying-ref/ 必红；documented-symbol/ 与 truthful-ref/ 必绿）。


def _go_decl_has_doc(lines, decl_lineno):
    """Go 顶层声明是否紧邻 doc：上一行是注释行（// 或 /* */）即算达标。

    多行 /* */ 块以 `*/` 结尾紧邻声明、单行 /* */ 紧邻声明都覆盖。
    Go 官方 doc 约定要求 doc 与声明之间无空行，故只查紧邻上一行。
    """
    if decl_lineno <= 1:
        return False
    prev = lines[decl_lineno - 2].strip()
    return prev.startswith("//") or prev.startswith("/*") or prev.endswith("*/")


def _all_comment_lines(text, lang):
    """字符串感知的注释行提取器，返回 [(行号, 注释原始内容)]。

    覆盖全部注释形态：// 行注释、/* */ 块注释（含 Kotlin KDoc /** */）、行尾注释。
    字符串字面量感知：双引号串、单引号字符、Go 反引号 raw string、Kotlin 三引号串
    里的内容**不**当注释扫（否则 `"// not a comment"` 会误判）。

    T3-2 判据边界（宁可漏不可吵）：带形状的引用无论写在哪种注释形态里都判，
    但字符串里的"//"、"/*"不是注释，不判。
    """
    out = []
    n = len(text)
    i = 0
    line = 1
    state = "NORMAL"  # NORMAL/LINE/BLOCK/STR/CHAR/RAW/TRIPLE
    buf = []
    buf_start = None
    buf_is_block = False

    def flush():
        nonlocal buf, buf_start
        if buf and buf_start is not None:
            joined = "".join(buf).split("\n")
            for off, piece in enumerate(joined):
                out.append((buf_start + off, piece))
        buf = []
        buf_start = None

    while i < n:
        c = text[i]
        if state == "NORMAL":
            if c == "\n":
                line += 1
                i += 1
                continue
            if c == "/" and i + 1 < n and text[i + 1] == "/":
                state = "LINE"
                buf = []
                buf_start = line
                i += 2
                continue
            if c == "/" and i + 1 < n and text[i + 1] == "*":
                state = "BLOCK"
                buf = []
                buf_start = line
                i += 2
                continue
            if c == '"':
                state = "STR"
                i += 1
                continue
            if c == "'":
                state = "CHAR"
                i += 1
                continue
            if lang == "go" and c == "`":
                state = "RAW"
                i += 1
                continue
            if (lang == "kotlin" and c == '"' and i + 2 < n
                    and text[i + 1] == '"' and text[i + 2] == '"'):
                state = "TRIPLE"
                i += 3
                continue
            i += 1
            continue
        elif state == "LINE":
            if c == "\n":
                flush()
                state = "NORMAL"
                line += 1
                i += 1
                continue
            buf.append(c)
            i += 1
            continue
        elif state == "BLOCK":
            if c == "*" and i + 1 < n and text[i + 1] == "/":
                buf.append("*/")
                flush()
                state = "NORMAL"
                i += 2
                continue
            if c == "\n":
                # 块内换行必须推进 line 计数——否则连续两个块注释时第二个块的行号
                # 会回卷错乱（返工 #1 w-t3c-verify 实证的隐藏缺陷：影响 T3-3 的
                # 块邻接判定与所有块注释的行号报告）。piece 行号 = buf_start + off
                # 依赖 buf 内 `\n` 数 == line 推进数，两者同时 +1 保持一致。
                line += 1
            buf.append(c)
            i += 1
            continue
        elif state == "STR":
            if c == "\\":
                i += 1
            elif c == '"':
                state = "NORMAL"
            elif c == "\n":
                line += 1
            i += 1
            continue
        elif state == "CHAR":
            if c == "\\":
                i += 1
            elif c == "'":
                state = "NORMAL"
            elif c == "\n":
                line += 1
            i += 1
            continue
        elif state == "RAW":  # Go backtick raw string
            if c == "`":
                state = "NORMAL"
            elif c == "\n":
                line += 1
            i += 1
            continue
        elif state == "TRIPLE":  # Kotlin triple-quoted string
            if c == '"' and i + 2 < n and text[i + 1] == '"' and text[i + 2] == '"':
                state = "NORMAL"
                i += 3
                continue
            if c == "\n":
                line += 1
            i += 1
            continue
    flush()
    return out


def _go_doc_lines(text):
    """Go 全部注释行的序列（行号, 剥掉 // 前导与行首空白后的行）。T3-2 引用扫描用。

    注意：_all_comment_lines 对 // 行返回的是 **// 之后**的原始内容，行首带一个空格
    （` // Foo` 里的 `// Foo` 后是 ` Foo`）。不能再用 `^//` 去剥——它早已剥过。这里
    统一剥掉行首空白，保证 T3-3 的 _group_comment_blocks 拿到的行首没有那个伪缩进。
    """
    return [
        (ln, raw.strip())
        for ln, raw in _all_comment_lines(text, "go")
    ]


def _kt_kdoc_end_lines(text):
    """Kotlin 全部 KDoc 块的结束行号集合（供声明 doc 邻接判定）。"""
    ends = set()
    for m in _KT_KDOC_BLOCK.finditer(text):
        ends.add(text[:m.end()].count("\n") + 1)
    return ends


def _kt_kdoc_lines(text):
    """Kotlin 全部注释行的序列（行号, 剥掉 // 与 KDoc 行首 * 后的行）。"""
    out = []
    for ln, raw in _all_comment_lines(text, "kotlin"):
        s = raw.strip()
        if s.startswith("//"):
            s = s[2:].lstrip()
        elif s.startswith("*"):
            s = s[1:].lstrip()
        out.append((ln, s))
    return out


def _kt_decl_has_doc(lines, decl_lineno, kdoc_ends):
    """Kotlin 顶层声明是否紧邻 KDoc：向上跳过 @ 注解行，遇空行即断。

    标准 Kotlin 约定是「KDoc → 注解 → 声明」三层，故注解行是合法的 doc 间隔。
    """
    idx = decl_lineno - 2
    while idx >= 0:
        s = lines[idx].strip()
        if not s:
            return False
        if s.startswith("@"):
            idx -= 1
            continue
        return (idx + 1) in kdoc_ends
    return False


def collect_go_flags(root):
    """扫描 server/ 下全部非测试 Go 文件里 flag.FlagSet 注册的 flag 名。

    flag 包自动注册 -h/--help，算作真实存在。ts-authkey 是 env-only（config_test
    明文：必须保持未知 flag），不算 flag。排 _test.go：测试私有 FlagSet 不算 daemon CLI。
    """
    flags = {"help"}
    server_root = os.path.join(root, GO_SUBDIR)
    for dirpath, dirnames, files in os.walk(server_root):
        dirnames[:] = [d for d in dirnames if d not in ("vendor", ".git")]
        for f in files:
            if not f.endswith(".go") or f.endswith("_test.go"):
                continue
            flags.update(_GO_FLAG_REG.findall(read_text(os.path.join(dirpath, f))))
    return flags


def _build_symbol_index(go_pkgs, kt_pkgs):
    """仓库导出符号全集（Go+Kotlin），供反引号引用真实性查表。"""
    idx = set()
    for pkg in list(go_pkgs.values()) + list(kt_pkgs.values()):
        idx.update(pkg.get("exports", ()))
    return idx


def _build_basename_index(root):
    """仓库内全部文件基名集合，供路径引用兜底解析。

    排除生成/缓存目录（.git/build/.gradle/artifacts 等），这些不是 doc 引用目标。
    docs/wiki/ 是生成物输出目录（README.md/t3-report.md 由本工具重写），也排除——
    否则报告文件自身的创建会让基名索引漂移、破坏幂等（本工具的既有契约）。
    """
    index = set()
    skip = (".git", "build", ".gradle", ".idea", "node_modules", "vendor",
            "Pods", "artifacts", ".m2", ".cxx",
            "__pycache__", ".pytest_cache", ".coverage", ".DS_Store")
    wiki_rel = WIKI_SUBDIR.split("/")[0]  # "docs"
    for dirpath, dirnames, files in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in skip]
        rel = os.path.relpath(dirpath, root).replace(os.sep, "/")
        if rel.startswith(WIKI_SUBDIR):
            continue  # 生成物输出目录不参与引用查表
        for f in files:
            index.add(f)
    return index


def _resolve_path_ref(ref, root, basename_index):
    """路径引用真实性：根相对存在，或已知前缀拼接存在，或同名文件存在于仓库。

    前缀集合覆盖 Go 源/Kotlin 源/文档/工具/libs 等常见挂点；基名兜底放行
    `pairing/probe.go` 这种省前缀写法（真实文件存在即不误报，宁可漏也不能吵）。

    外部参考前缀（T3_EXTERNAL_PATH_PREFIXES，如 herdr/ 合规注记的 src/detect/
    manifests/claude.toml）指向仓库外的东西，直接放行不判——宁可漏也不能吵。
    """
    if ref.startswith(T3_EXTERNAL_PATH_PREFIXES):
        return True
    if os.path.isfile(os.path.join(root, ref)):
        return True
    for prefix in (
        "server/internal/", "server/", "app/app/src/main/java/",
        "app/app/src/main/", "app/app/src/", "app/", "tools/",
        "docs/", "e2e/", "internal/", "libs/",
    ):
        if os.path.isfile(os.path.join(root, prefix, ref)):
            return True
    return os.path.basename(ref) in basename_index


def _backtick_symbol_candidates(ref):
    """从反引号内容提取候选符号名；无明确符号形状返回 None（不判）。

    判据边界（见 HANDBOOK.md）：只判**单个、大写开头、标识符形状**的引用。
    含空白（短语/表格/JSON 示例）、小写开头（remember/tsnet/公式）、全大写协议词
    （POST/URL/…）一律不判——宁可漏也不能吵。`Foo.bar()` 取 `Foo`、`Foo<T>` 取 `Foo`。
    """
    s = ref.strip()
    if not s or any(ch.isspace() for ch in s):
        return None
    s = s.rstrip("(),;.。：:）")
    if not s:
        return None
    if "<" in s:
        s = s.split("<", 1)[0]
    if "." in s:
        s = s.split(".", 1)[0]
    s = s.strip()
    if not re.fullmatch(r"[A-Z][A-Za-z0-9_]*", s):
        return None
    if s in T3_NONSYMBOL_TOKENS:
        return None
    return s


def _scan_t3_2_doc_lines(doc_lines, kind, pkg_key, file, symbols, flags,
                         basename_index, root, out):
    """对一组注释行扫描三类引用（符号/路径/flag），不存在的记违规。

    T3-2 扫描面（arch-criteria-t3 返工 #1）：**全部注释形态**都扫——普通 // 注释、
    /* */ 块注释、行尾注释、KDoc，Go 与 Kotlin 两侧对齐。带形状的引用无论写在
    哪种注释里都必须被判（宁可漏不可吵的"漏"不指注释形态，只指引用形状）。
    CLI flag Go 与 Kotlin 两侧都判；外部工具 flag（gradle --tests）走
    T3_EXTERNAL_FLAGS 白名单放行，不误报。
    """
    for lineno, text in doc_lines:
        for m in _T3_BACKTICK_REF.finditer(text):
            cand = _backtick_symbol_candidates(m.group(1))
            if cand and cand not in symbols:
                out.append({
                    "cid": "T3-2", "kind": kind, "pkg": pkg_key, "file": file,
                    "line": lineno, "ref": m.group(1),
                    "reason": "注释引用的符号不存在: " + cand,
                })
        for m in _T3_PATH_REF.finditer(text):
            ref = m.group(1)
            if not _resolve_path_ref(ref, root, basename_index):
                out.append({
                    "cid": "T3-2", "kind": kind, "pkg": pkg_key, "file": file,
                    "line": lineno, "ref": ref,
                    "reason": "注释引用的仓库文件路径不存在: " + ref,
                })
        for m in _T3_FLAG_REF.finditer(text):
            name = m.group(0)[2:]
            if name in T3_EXTERNAL_FLAGS:
                continue  # 外部工具 flag（gradle --tests），不误报
            if name not in flags:
                out.append({
                    "cid": "T3-2", "kind": kind, "pkg": pkg_key, "file": file,
                    "line": lineno, "ref": m.group(0),
                    "reason": "注释引用的 CLI flag 未注册: " + m.group(0),
                })


def _group_comment_blocks(doc_lines):
    """把 (行号, 剥壳文本) 的注释行序列聚合成符号级注释块。

    一条 Go doc / Kotlin KDoc 常常跨多行，且 T3-3 要求契约标签都在「同一符号的
    注释块」里才作数。聚合规则（宁漏不吵）：
      * **行号相邻**（gap==1）归入同一块——同一注释块内的行天然连续（Go `//`
        连续行、Kotlin KDoc 内 `*` 行），行号跳跃即中间隔了代码或空源行，属于
        不同注释块，切块；
      * 空内容行（裸 `//` / KDoc 内部空行）行号相邻则继续前块（段落分隔不是
        符号分隔）。
    这版修复（返工 #1，w-t3c-verify 实证）：原实现用「行首缩进」判续接，但
    _all_comment_lines 对 // 行返回的是 // **之后**的内容（行首带一个空格），
    Go 侧每个注释行都被当成「续接」，同文件所有注释合并成 mega-block，跨符号
    union 标签，前一个完整 @contract 的 @err/@inv 掩盖了后一个残缺符号的缺失。
    改为行号相邻判块后，Go 每个符号的 doc 块天然被函数/空行隔开，四标签只在
    它自己所属的注释块里查找，不再跨块串味。
    """
    blocks = []
    cur = []
    prev_ln = None
    for ln, text in doc_lines:
        if cur and prev_ln is not None and ln > prev_ln + 1:
            blocks.append(cur)
            cur = []
        cur.append((ln, text))
        prev_ln = ln
    if cur:
        blocks.append(cur)
    return blocks


def _extract_tags(block_lines):
    """从注释块提取契约标签（@contract/@pre/@post/@err/@inv/@consumes/@produces）。

    返回 (标签名→值文本 dict, 块文本)。行首 `@label` 识别；`@label` 后面跟 `:`
    或空白再跟值。Kotlin 行首 `* @label` 由 _kt_kdoc_lines 已剥掉 `*`。

    **重复标签**：同一标签多条取值**全部保留**（存 list）。@consumes/@produces 是
    可重复的跨层声明——命令包/库包的包级 doc 常在一个注释块里连续写多条
    `@consumes x`，单值 dict 覆盖会让前面的全部静默丢读，T3-4 把已声明的包误判成
    「import 了却未声明」。@contract/@pre/@post/@err/@inv 语义上单值，首条即留
    （多写重叠时首条为准），消费方按 str 或 list 兼容取值。
    """
    tags = {}
    texts = []
    for _ln, text in block_lines:
        s = text.strip()
        if s.startswith("@"):
            m = re.match(r"^@([A-Za-z][\w-]*)\b", s)
            if m and m.group(1) in T3_CONTRACT_TAGS:
                key = m.group(1)
                val = s[m.end():].strip()
                if key in tags:
                    if not isinstance(tags[key], list):
                        tags[key] = [tags[key]]  # 第二条起转 list，保留全部取值
                    tags[key].append(val)
                else:
                    tags[key] = val
                continue
        texts.append(s)
    return tags, "\n".join(texts)


def _check_t3_3_go(go_pkgs, root, pkg_filter, out):
    """T3-3 Go 侧：@contract 符号四标签必须齐全（含显式 none）。"""
    server_root = os.path.join(root, GO_SUBDIR)
    for dirpath, dirnames, files in os.walk(server_root):
        dirnames[:] = [d for d in dirnames if d not in ("vendor", ".git")]
        pkg_key = os.path.relpath(dirpath, server_root).replace(os.sep, "/")
        if pkg_filter and pkg_key != pkg_filter:
            continue
        for f in sorted(files):
            if not f.endswith(".go") or f.endswith("_test.go"):
                continue
            relpath = os.path.relpath(os.path.join(dirpath, f), root)
            text = read_text(os.path.join(dirpath, f))
            for blk in _group_comment_blocks(_go_doc_lines(text)):
                tags, _ = _extract_tags(blk)
                if "contract" not in tags:
                    continue
                missing = [t for t in T3_CONTRACT_REQUIRED if t not in tags]
                if missing:
                    out.append({
                        "cid": "T3-3", "kind": "go", "pkg": pkg_key,
                        "file": relpath, "line": blk[0][0],
                        "ref": "@contract",
                        "missing": missing,
                        "reason": "缺契约标签: " + ", ".join("@%s" % t for t in missing),
                    })


def _check_t3_3_kt(kt_pkgs, root, pkg_filter, out):
    """T3-3 Kotlin 侧：@contract 符号四标签必须齐全（含显式 none）。"""
    for src_root in _find_kotlin_roots(root):
        for dirpath, dirnames, files in os.walk(src_root):
            dirnames[:] = [d for d in dirnames if d != "build"]
            kt_files = sorted(f for f in files if f.endswith(".kt"))
            if not kt_files:
                continue
            pkg_key = ""
            for f in kt_files:
                m = _KT_PACKAGE_DECL.search(read_text(os.path.join(dirpath, f)))
                if m:
                    pkg_key = m.group(1)
                    break
            if not pkg_key or (pkg_filter and pkg_key != pkg_filter):
                continue
            for f in kt_files:
                relpath = os.path.relpath(os.path.join(dirpath, f), root)
                text = read_text(os.path.join(dirpath, f))
                for blk in _group_comment_blocks(_kt_kdoc_lines(text)):
                    tags, _ = _extract_tags(blk)
                    if "contract" not in tags:
                        continue
                    missing = [t for t in T3_CONTRACT_REQUIRED if t not in tags]
                    if missing:
                        out.append({
                            "cid": "T3-3", "kind": "kotlin", "pkg": pkg_key,
                            "file": relpath, "line": blk[0][0],
                            "ref": "@contract",
                            "missing": missing,
                            "reason": "缺契约标签: " + ", ".join("@%s" % t for t in missing),
                        })


def _go_file_belongs_to_dir(text, pkg_name):
    """该 Go 文件是否属于 `pkg_name`（目录名）这个包。

    守卫：目录名 == package 名（防同一目录混入别包文件时误归属）。精确特判：
    命令包目录（cmd/<名>）声明 `package main`，目录名 != 包名，照 collect_go_source()
    的 is_main 口径（286 行）把 `package main` 也归属本目录。除 `package main`
    外不作任何放宽——声明别的包名的文件仍被拒（防串味守卫保留）。
    """
    if re.search(r"^\s*package\s+" + re.escape(pkg_name) + r"\b", text, re.MULTILINE):
        return True
    return re.search(r"^\s*package\s+main\b", text, re.MULTILINE) is not None


def _consumes_values(tags):
    """把 _extract_tags 的 @consumes 值（str 单条或 list 多条）归一成包名迭代。

    每个值取第一个空白分隔 token（包名）；容忍行内尾注（`@consumes internal/config  # 理由`）。
    """
    raw = tags.get("consumes")
    if not raw:
        return ()
    values = raw if isinstance(raw, list) else [raw]
    out = []
    for v in values:
        v = (v or "").strip().strip("\"'`")
        if not v:
            continue
        out.append(v.split()[0])
    return out


def _declared_consumes(root, pkg_filter):
    """全仓库 @consumes 声明：包名 → 消费的目标包名集合（注释文本里读的）。

    范围：Go 包 doc（package 子句上方 doc 注释块）与 Kotlin 包级 KDoc 块——
    跨层声明是**包级**声明（哪个包消费哪个包），不是符号级。`internal/config` 这种
    相对写法与 `dev.agentmirror.app.conn` 完整写法都收。
    """
    declared = {}
    server_root = os.path.join(root, GO_SUBDIR)
    for dirpath, dirnames, files in os.walk(server_root):
        dirnames[:] = [d for d in dirnames if d not in ("vendor", ".git")]
        pkg_key = os.path.relpath(dirpath, server_root).replace(os.sep, "/")
        if pkg_filter and pkg_key != pkg_filter:
            continue
        pkg_name = os.path.basename(dirpath)
        for f in sorted(f for f in files if f.endswith(".go") and not f.endswith("_test.go")):
            text = read_text(os.path.join(dirpath, f))
            if not _go_file_belongs_to_dir(text, pkg_name):
                continue  # 只有包所属文件才看包 doc（防御；命令包 `package main` 精确特判）
            for blk in _group_comment_blocks(_go_doc_lines(text)):
                tags, _ = _extract_tags(blk)
                for val in _consumes_values(tags):
                    declared.setdefault(pkg_key, set()).add(val)
    for src_root in _find_kotlin_roots(root):
        for dirpath, dirnames, files in os.walk(src_root):
            dirnames[:] = [d for d in dirnames if d != "build"]
            kt_files = sorted(f for f in files if f.endswith(".kt"))
            if not kt_files:
                continue
            pkg_key = ""
            for f in kt_files:
                m = _KT_PACKAGE_DECL.search(read_text(os.path.join(dirpath, f)))
                if m:
                    pkg_key = m.group(1)
                    break
            if not pkg_key or (pkg_filter and pkg_key != pkg_filter):
                continue
            for f in kt_files:
                text = read_text(os.path.join(dirpath, f))
                for blk in _group_comment_blocks(_kt_kdoc_lines(text)):
                    tags, _ = _extract_tags(blk)
                    for val in _consumes_values(tags):
                        declared.setdefault(pkg_key, set()).add(val)
    return declared


def _check_t3_4(go_pkgs, kt_pkgs, root, pkg_filter, out, declared=None):
    """T3-4 跨层声明一致：@consumes 必须真在 import 图里；import 了没声明判漂移。

    声明面 = _declared_consumes()（调用方算一次传入）；import 图 = 既有采集结果的 deps
    （不重新解析）。Go 声明键用相对包路径（`internal/api`），Kotlin 用 fq 包名
    （`dev.agentmirror.app.conn`）——两侧各自归一后统一比对。相对写法的目标名
    （`internal/config`）在两侧已知包键集合里精确匹配；无法解析的目标名放行（宁漏）。
    """
    declared = declared or _declared_consumes(root, pkg_filter)
    go_known = set(go_pkgs)
    kt_known = set(kt_pkgs)
    for name, pkg in list(go_pkgs.items()) + list(kt_pkgs.items()):
        if pkg_filter and name != pkg_filter:
            continue
        known = go_known if pkg["kind"] == "go" else kt_known
        imports = set(pkg.get("deps", ()))
        decl = declared.get(name, set())

        for target in sorted(decl):
            if target in known and target not in imports:
                out.append({
                    "cid": "T3-4", "kind": pkg["kind"], "pkg": name,
                    "file": "", "line": 0, "ref": target,
                    "reason": "声明 @consumes 但 import 图没有该包",
                })
        for dep in sorted(imports):
            if dep not in decl:
                out.append({
                    "cid": "T3-4", "kind": pkg["kind"], "pkg": name,
                    "file": "", "line": 0, "ref": dep,
                    "reason": "import 了却未声明 @consumes（架构漂移）",
                })


def scan_t3(go_pkgs, kt_pkgs, root, pkg_filter=None):
    """扫描 T3 全部判据，返回 ([T3-1], [T3-2], [T3-3], [T3-4]) 违规列表。

    违规记录 dict：cid / kind / pkg / file / line / symbol|ref / reason。
    pkg_filter 非空时只扫该包（Go 用相对包路径键如 internal/api，
    Kotlin 用包名键如 dev.agentmirror.app.conn）——阶段一/二逐包收口用的单包硬判。
    """
    symbols = _build_symbol_index(go_pkgs, kt_pkgs)
    flags = collect_go_flags(root)
    basename_index = _build_basename_index(root)
    v1, v2 = [], []
    pkg_filter = pkg_filter or ""

    server_root = os.path.join(root, GO_SUBDIR)
    for dirpath, dirnames, files in os.walk(server_root):
        dirnames[:] = [d for d in dirnames if d not in ("vendor", ".git")]
        pkg_key = os.path.relpath(dirpath, server_root).replace(os.sep, "/")
        if pkg_filter and pkg_key != pkg_filter:
            continue
        for f in sorted(files):
            if not f.endswith(".go") or f.endswith("_test.go"):
                continue
            path = os.path.join(dirpath, f)
            relpath = os.path.relpath(path, root)
            text = read_text(path)
            lines = text.splitlines()
            for m in _GO_TOP_DECL.finditer(text):
                lineno = text[:m.start()].count("\n") + 1
                if not _go_decl_has_doc(lines, lineno):
                    v1.append({
                        "cid": "T3-1", "kind": "go", "pkg": pkg_key,
                        "file": relpath, "line": lineno, "symbol": m.group(1),
                        "reason": "顶层导出声明缺紧邻 doc",
                    })
            _scan_t3_2_doc_lines(_go_doc_lines(text), "go", pkg_key, relpath,
                                 symbols, flags, basename_index, root, v2)

    for src_root in _find_kotlin_roots(root):
        for dirpath, dirnames, files in os.walk(src_root):
            dirnames[:] = [d for d in dirnames if d != "build"]
            kt_files = sorted(f for f in files if f.endswith(".kt"))
            if not kt_files:
                continue
            pkg_key = ""
            for f in kt_files:
                m = _KT_PACKAGE_DECL.search(read_text(os.path.join(dirpath, f)))
                if m:
                    pkg_key = m.group(1)
                    break
            if not pkg_key:
                continue
            if pkg_filter and pkg_key != pkg_filter:
                continue
            for f in kt_files:
                path = os.path.join(dirpath, f)
                relpath = os.path.relpath(path, root)
                text = read_text(path)
                lines = text.splitlines()
                ends = _kt_kdoc_end_lines(text)
                for i, line in enumerate(lines):
                    if line[:1] in (" ", "\t"):
                        continue  # 缩进=嵌套声明，非顶层
                    s = line.strip()
                    if not s or s.startswith("@"):
                        continue
                    m = _KT_EXPORT_DECL.match(s)
                    if not m:
                        continue
                    if not _kt_decl_has_doc(lines, i + 1, ends):
                        v1.append({
                            "cid": "T3-1", "kind": "kotlin", "pkg": pkg_key,
                            "file": relpath, "line": i + 1, "symbol": m.group(1),
                            "reason": "顶层 public 声明缺紧邻 KDoc",
                        })
                _scan_t3_2_doc_lines(_kt_kdoc_lines(text), "kotlin", pkg_key,
                                     relpath, symbols, flags, basename_index, root, v2)

    v1.sort(key=lambda r: (r["pkg"], r["file"], r["line"], r["symbol"]))
    v2.sort(key=lambda r: (r["pkg"], r["file"], r["line"], r["ref"]))

    v3 = []
    _check_t3_3_go(go_pkgs, root, pkg_filter, v3)
    _check_t3_3_kt(kt_pkgs, root, pkg_filter, v3)
    v3.sort(key=lambda r: (r["pkg"], r["file"], r["line"]))

    v4 = []
    _check_t3_4(go_pkgs, kt_pkgs, root, pkg_filter, v4)
    v4.sort(key=lambda r: (r["pkg"], r["ref"]))
    return v1, v2, v3, v4


def _print_t3_results(v1, v2, v3, v4, strict_t3, pkg, out):
    """打印 T3 判据结果；默认报告模式（列清单），--strict-t3 才计入退出码。"""
    mode = "严格（计入退出码）" if strict_t3 else "报告（不计退出码）"
    scope = "全部包" if not pkg else "单包 %s" % pkg
    print("", file=out)
    print("== T3 判据（%s，%s） ==" % (scope, mode), file=out)
    for cid, viols, empty_desc in (
        ("T3-1", v1, "符号级 doc 覆盖"),
        ("T3-2", v2, "引用真实性"),
        ("T3-3", v3, "契约标签完备"),
        ("T3-4", v4, "跨层声明一致"),
    ):
        ok = not viols
        mark = "PASS" if ok else "FAIL"
        desc = "%s：%d 条违规" % (empty_desc, len(viols)) if viols else "%s：无违规" % empty_desc
        print("%s %-16s : %s — %s" % (cid, " ", mark, desc), file=out)
        for r in viols:
            if cid == "T3-1":
                print("  [%s] %s  %s:%d  `%s` — %s"
                      % (cid, r["pkg"], r["file"], r["line"], r["symbol"], r["reason"]), file=out)
            else:
                print("  [%s] %s  %s:%d  `%s` — %s"
                      % (cid, r["pkg"], r["file"], r["line"], r["ref"], r["reason"]), file=out)
    if not strict_t3:
        print("（T3 报告模式：违规列清单，不计入退出码。--strict-t3 才硬判。）", file=out)


def _scan_coverage(go_pkgs, kt_pkgs, root):
    """T3 扫描覆盖统计（阳性对照铁律：扫描量必须 > 0，空扫描≠健康）。

    阶段二（arch-criteria-t3-contract）增三项覆盖量数字——那个 0 必须自证：
      * T3-3 扫描到的 @contract 符号总数（真仓库尚未标注 @contract，须为 0，
        但要让读者能区分「真没有」与「没扫到」：fixture 同法扫描能扫到就是没扫到）；
      * @consumes 声明总数（T3-4 声明面）；
      * 参与 T3-4 比对的 import 边数（Go+Kotlin 包间依赖边总数）。
    """
    n_symbols = len(_build_symbol_index(go_pkgs, kt_pkgs))
    n_flags = len(collect_go_flags(root))
    n_basenames = len(_build_basename_index(root))
    n_go_doc = n_kt_doc = 0
    n_contract = 0
    n_consumes = 0
    n_import_edges = sum(len(p.get("deps", ())) for p in list(go_pkgs.values()) + list(kt_pkgs.values()))
    server_root = os.path.join(root, GO_SUBDIR)
    for dirpath, dirnames, files in os.walk(server_root):
        dirnames[:] = [d for d in dirnames if d not in ("vendor", ".git")]
        for f in files:
            if not f.endswith(".go") or f.endswith("_test.go"):
                continue
            path = os.path.join(dirpath, f)
            text = read_text(path)
            n_go_doc += len(_go_doc_lines(text))
            for blk in _group_comment_blocks(_go_doc_lines(text)):
                tags, _ = _extract_tags(blk)
                n_contract += 1 if "contract" in tags else 0
                n_consumes += 1 if "consumes" in tags else 0
    for src_root in _find_kotlin_roots(root):
        for dirpath, dirnames, files in os.walk(src_root):
            dirnames[:] = [d for d in dirnames if d != "build"]
            for f in files:
                if not f.endswith(".kt"):
                    continue
                path = os.path.join(dirpath, f)
                text = read_text(path)
                n_kt_doc += len(_kt_kdoc_lines(text))
                for blk in _group_comment_blocks(_kt_kdoc_lines(text)):
                    tags, _ = _extract_tags(blk)
                    n_contract += 1 if "contract" in tags else 0
                    n_consumes += 1 if "consumes" in tags else 0
    return {
        "symbols": n_symbols,
        "flags": n_flags,
        "basenames": n_basenames,
        "go_doc_lines": n_go_doc,
        "kt_doc_lines": n_kt_doc,
        "contract_symbols": n_contract,
        "consumes_decls": n_consumes,
        "import_edges": n_import_edges,
    }


def write_t3_report(v1, v2, v3, v4, go_pkgs, kt_pkgs, out_path, coverage=None):
    """写 T3 报告（幂等：全排序、无时间戳）。落 docs/wiki/t3-report.md。

    coverage 为 _scan_coverage() 的统计 dict，随报告打印扫描量——
    阳性对照铁律：扫描量必须 > 0，防止「没扫到」被当成「很干净」。
    阶段二（arch-criteria-t3-contract）：报告含 T3-3 / T3-4 两节 + 覆盖量数字。
    """
    n_go, n_kt = len(go_pkgs), len(kt_pkgs)
    lines = ["# T3 判据报告（自动生成）", ""]
    lines.append(
        "> ⚠️ **生成物，勿手改。** 由 `tools/archwiki/build_wiki.py --check --t3-report` "
        "从源码现算生成，重跑无 diff（幂等）。人工改动会被覆盖。"
    )
    lines.append("")
    lines.append("扫描 **%d** 个包（Go %d + Kotlin %d）。" % (n_go + n_kt, n_go, n_kt))
    if coverage:
        lines.append("")
        lines.append("## T3 扫描覆盖（阳性对照：扫描量必须 > 0）")
        lines.append("")
        lines.append("| 项 | 数量 |")
        lines.append("|---|---|")
        lines.append("| 导出符号索引（Go+Kotlin） | %d |" % coverage["symbols"])
        lines.append("| Go CLI flag 索引 | %d |" % coverage["flags"])
        lines.append("| 仓库文件基名索引 | %d |" % coverage["basenames"])
        lines.append("| T3-2 扫描的 Go doc 行 | %d |" % coverage["go_doc_lines"])
        lines.append("| T3-2 扫描的 Kotlin KDoc 行 | %d |" % coverage["kt_doc_lines"])
        lines.append("| **T3-3 扫描到的 `@contract` 符号总数** | %d |" % coverage["contract_symbols"])
        lines.append("| **T3-4 `@consumes` 声明总数** | %d |" % coverage["consumes_decls"])
        lines.append("| **T3-4 参与比对的 import 边数** | %d |" % coverage["import_edges"])
        lines.append("")
    lines.append("## T3-1 符号级 doc 覆盖")
    lines.append("")
    if v1:
        lines.append("导出符号缺紧邻 doc/KDoc，共 **%d** 条：" % len(v1))
        lines.append("")
        lines.append("| 包 | 语言 | 文件 | 行 | 符号 | 原因 |")
        lines.append("|---|---|---|---|---|---|")
        for r in v1:
            lines.append("| %s | %s | %s | %d | `%s` | %s |"
                         % (r["pkg"], r["kind"], r["file"], r["line"], r["symbol"], r["reason"]))
    else:
        lines.append("无违规：全部非测试导出符号均有紧邻 doc/KDoc。")
    lines.append("")
    lines.append("## T3-2 引用真实性")
    lines.append("")
    lines.append("扫描**全部注释形态**（KDoc/doc 注释 + 函数体内普通 `//` 注释 + `/* */` 块注释 + "
                 "行尾注释，Go 与 Kotlin 两侧对齐，含 Kotlin 侧 `--flag` 判定）。")
    lines.append("")
    lines.append("> **诚实边界**：T3-2 只验证**引用形状可判者**——反引号包裹的大写符号、含 `/` 且"
                 "带已知扩展名的路径、`--flag`。**不验证语义事实**：自然语言断言（如\"设置里有重配"
                 "按钮\"）没有可判形状，静态判据解析不出\"某组件里有没有某按钮\"，这类行为性断言由"
                 "用例覆盖（如 PairingUxTest 的重配入口可达性断言），不在此列。注释里指认代码实体时"
                 "务必写成反引号符号或真实路径，让引用变成判据可验的形状。")
    lines.append("")
    if v2:
        lines.append("注释引用的符号/路径/CLI flag 不存在，共 **%d** 条：" % len(v2))
        lines.append("")
        lines.append("| 包 | 语言 | 文件 | 行 | 引用 | 原因 |")
        lines.append("|---|---|---|---|---|---|")
        for r in v2:
            lines.append("| %s | %s | %s | %d | `%s` | %s |"
                         % (r["pkg"], r["kind"], r["file"], r["line"], r["ref"], r["reason"]))
    else:
        lines.append("无违规：注释引用的符号名/仓库文件路径/CLI flag 均真实存在。")
    lines.append("")
    lines.append("## T3-3 契约标签完备")
    lines.append("")
    lines.append("凡标了 `@contract` 的符号，四标签 `@pre` / `@post` / `@err` / `@inv` 必须齐全；"
                 "允许显式写 `none`（表示「确无此项」），但不许缺项。缺项即「契约半成品」——"
                 "它比没有契约更坏，因为读者会以为契约已经定好了。")
    lines.append("")
    lines.append("> **诚实边界**：T3-3 只验标签**齐不齐**，**不验契约内容是否描述正确**——"
                 "`@post` 写的是不是真的、`@err` 描述的错误语义对不对，属语义事实，静态判据判不了，"
                 "那一面由用例覆盖。判据不保护「内容撒谎的齐全契约」。")
    lines.append("")
    if v3:
        lines.append("`@contract` 符号缺契约标签，共 **%d** 条：" % len(v3))
        lines.append("")
        lines.append("| 包 | 语言 | 文件 | 行 | 缺失标签 | 原因 |")
        lines.append("|---|---|---|---|---|---|")
        for r in v3:
            lines.append("| %s | %s | %s | %d | %s | %s |"
                         % (r["pkg"], r["kind"], r["file"], r["line"],
                            ", ".join("@%s" % t for t in r["missing"]), r["reason"]))
    else:
        lines.append("无违规：扫描到的 `@contract` 符号四标签齐全（含显式 `none`）。")
    lines.append("")
    lines.append("## T3-4 跨层声明一致")
    lines.append("")
    lines.append("`@consumes` 声明的包必须真在该包的 import 图里；反之，跨层 import 了却没声明的"
                 "判架构漂移。import 图由 build_wiki 既有采集结果现算（不重新解析）。")
    lines.append("")
    lines.append("> **诚实边界**：T3-4 只验声明与 import 图**一致不一致**。它保证架构维基能从代码"
                 "现算真依赖、防止「声明了没 import / import 了没声明」的漂移；但**不验 `@consumes` "
                 "写的是不是业务上真该依赖**——那是设计语义，静态判据判不了。")
    lines.append("")
    if v4:
        lines.append("`@consumes` 与 import 图不一致，共 **%d** 条：" % len(v4))
        lines.append("")
        lines.append("| 包 | 语言 | 目标包 | 原因 |")
        lines.append("|---|---|---|---|")
        for r in v4:
            lines.append("| %s | %s | `%s` | %s |" % (r["pkg"], r["kind"], r["ref"], r["reason"]))
    else:
        lines.append("无违规：`@consumes` 声明与 import 图一致。")
    lines.append("")
    os.makedirs(os.path.dirname(out_path) or ".", exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines))


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


def run_check(go_pkgs, kt_pkgs, out=sys.stdout, root=None,
              strict_t3=False, pkg=None, t3_report=None):
    """--check 执行：打印判据结果，返回退出码（0 过 / 1 违 / 2 空扫描）。

    T3 分级开关（arch-criteria-t3）：
      * 默认报告模式——T3 列清单、不改变退出码（真仓库 18 包尚未刷注释，硬判会阻塞入库）；
      * --strict-t3——T3 违规计入退出码；
      * --pkg <包名>——T3 只扫该包（阶段一逐包收口时每包的 acceptance）。
    """
    all_pkgs = list(go_pkgs.values()) + list(kt_pkgs.values())
    total_edges = sum(len(p["deps"]) for p in all_pkgs)
    n = len(all_pkgs)
    if root is None:
        root = os.getcwd()

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

    v1, v2, v3, v4 = scan_t3(go_pkgs, kt_pkgs, root, pkg_filter=pkg)
    if strict_t3 and (v1 or v2 or v3 or v4):
        failed += 1
    _print_t3_results(v1, v2, v3, v4, strict_t3, pkg, out)
    if t3_report:
        coverage = _scan_coverage(go_pkgs, kt_pkgs, root) if not pkg else None
        write_t3_report(v1, v2, v3, v4, go_pkgs, kt_pkgs, t3_report, coverage=coverage)

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
        epilog="判据：T1-1 internal 包环依赖；T1-2 包缺 doc 注释；"
        "T3-1 符号级 doc 覆盖；T3-2 引用真实性；T3-3 契约标签完备；"
        "T3-4 跨层声明一致。判据准入纪律：每条判据必须自带红测 fixture（见 testdata/）。",
    )
    parser.add_argument(
        "--root",
        default=DEFAULT_REPO_ROOT,
        help="仓库根目录（默认：本脚本三级上级，即仓库根）。",
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
    parser.add_argument(
        "--strict-t3",
        action="store_true",
        help="T3 判据计入退出码（默认报告模式只列清单不改退出码）。",
    )
    parser.add_argument(
        "--pkg",
        default=None,
        metavar="包名",
        help="T3 只扫该包（Go 用相对包路径键如 internal/api；Kotlin 用包名键）。"
        "阶段一逐包收口时每包的 acceptance 就是 --check --strict-t3 --pkg <该包>。",
    )
    parser.add_argument(
        "--t3-report",
        default=None,
        metavar="路径",
        help="把 T3 违规清单写成 markdown 报告（默认：<root>/docs/wiki/t3-report.md）。",
    )
    args = parser.parse_args(argv)

    root = os.path.abspath(args.root)
    go_pkgs, kt_pkgs = build_model(root, force_go_source=args.go_source)

    if args.check:
        t3_report = args.t3_report
        if args.t3_report is None and not args.pkg and root == DEFAULT_REPO_ROOT:
            t3_report = os.path.join(root, WIKI_SUBDIR, "t3-report.md")
        return run_check(go_pkgs, kt_pkgs, root=root,
                         strict_t3=args.strict_t3, pkg=args.pkg, t3_report=t3_report)

    out_dir = args.out or os.path.join(root, WIKI_SUBDIR)
    generate_wiki(go_pkgs, kt_pkgs, out_dir)
    print("已生成 %s/README.md（%d 包，%d 边）"
          % (os.path.abspath(out_dir), len(go_pkgs) + len(kt_pkgs),
             sum(len(p["deps"]) for p in list(go_pkgs.values()) + list(kt_pkgs.values()))))
    return 0


if __name__ == "__main__":
    sys.exit(main())
