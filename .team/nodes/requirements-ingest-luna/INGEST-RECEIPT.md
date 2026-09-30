# 入库回执：主机自动发现与 TS 优先路由

日期：2026-09-05
席位：requirements-ingest-luna（新需求入库管理员）

## 范围与边界

- 输入正本只读：[2026-09-05-host-auto-discovery-source.md](../../artifacts/requirements/2026-09-05-host-auto-discovery-source.md)。已读取原话及最后更正。
- 按用户豁免直接入库：未撞库、未查重、未检索旧需求冲突；仅读 `requirement-wiki/CLAUDE.md` 及待改索引以确定 schema、编号和排版。
- 未扩写发现协议、扫码载荷、探测阈值、轮询、退避、凭据存储或右上角具体外观。
- 未修改输入正本、已有原始需求条目或 `requirement-wiki/raw/`；未修改产品代码，未运行构建、设备验收或生产操作。

## 产物

- 新建 `requirement-base/entries/101-主机自动发现与TS优先路由.md`：完整保存输入正本中的用户原话、最终整理、路由状态空间和更正边界；追加维基入口。
- 修改 `requirement-base/INDEX.md`：追加 101 的可达条目。该索引原有 001–060 与现存更高编号条目不一致，本次未做全库治理。
- 新建 `requirement-wiki/wiki/concepts/host-auto-discovery-routing.md`：完整 frontmatter、有效规则、TS 优先且不抢占的路由表、撤回说明、标准 Markdown 来源链接。
- 修改 `requirement-wiki/wiki/index.md`：总页数 84→85、Concepts 24→25、追加新概念入口。
- 刷新 `requirement-wiki/wiki/hot.md`：写入 2026-09-05 活跃上下文。
- 追加 `requirement-wiki/wiki/log.md`：记录直接入库豁免、产物和撤回边界。
- 新建本回执：`.team/nodes/requirements-ingest-luna/INGEST-RECEIPT.md`。

原始记录与概念页互相链接；来源 INDEX、维基 index/hot/log 均可达新概念。新增正文链接均为标准 Markdown 相对链接，未在新增正文使用 wikilink。

## 核验命令与实际结果

### 0. 首次核验漏检与本次修正（保留记录）

首次回执把 `frontmatter_check=PASS` 建立在 Python 文本字段存在性检查上，回执中的命令还是注释占位，未真正调用 YAML 解析器，因此漏检了 `tags` 流式序列中未加引号的 `#`。leader 复核用系统 Ruby Psych 实际解析时得到：

```text
did not find expected comma or closing bracket while parsing a flow sequence at line 8 column 7
```

本次只修正 `requirement-wiki/wiki/concepts/host-auto-discovery-routing.md` 的格式：将三个 `#layer/concept`、`#domain/system`、`#status/draft` 标签加 YAML 字符串引号，未改变需求语义。

### 1. 链接、Psych frontmatter、源原话完整性、索引可达性与撤回语义

命令（可直接从项目根目录复跑）：

```sh
ruby -rpsych -rdate -e '
p=ARGV.fetch(0)
text=File.read(p)
parts=text.split(/^---\s*$\n?/, 3)
raise "missing frontmatter fences" unless parts.length == 3
fm=Psych.safe_load(parts[1], permitted_classes: [Date], permitted_symbols: [], aliases: false)
raise "frontmatter is not a mapping" unless fm.is_a?(Hash)
%w[type slug created updated sources].each { |k| raise "missing #{k}" unless fm.key?(k) }
raise "tags are not strings" unless fm.fetch("tags").all? { |v| v.is_a?(String) }
puts "psych_frontmatter=PASS"
' requirement-wiki/wiki/concepts/host-auto-discovery-routing.md && \
python3 - <<'PY'
from pathlib import Path
import re, sys
new = [Path("requirement-base/entries/101-主机自动发现与TS优先路由.md"), Path("requirement-wiki/wiki/concepts/host-auto-discovery-routing.md")]
changed = new + [Path("requirement-base/INDEX.md"), Path("requirement-wiki/wiki/index.md"), Path("requirement-wiki/wiki/hot.md"), Path("requirement-wiki/wiki/log.md")]
source = Path(".team/artifacts/requirements/2026-09-05-host-auto-discovery-source.md")
errors = []
for p in changed:
    if not p.exists(): errors.append(f"missing: {p}")
    for target in re.findall(r"\[[^\]]*\]\(([^)]+)\)", p.read_text()):
        target = target.split("#", 1)[0]
        if target and "://" not in target and not (p.parent / target).resolve().exists():
            errors.append(f"broken link: {p} -> {target}")
src = source.read_text(); entry = new[0].read_text(); start = "## 用户原话（按先后顺序）\n"
if src[src.index(start):] != entry[entry.index(start):entry.index("\n## 维基入口\n")]:
    errors.append("source completeness: original/final body differs")
concept = new[1].read_text(); valid = concept[concept.index("## 核心要点"):concept.index("## 与其它概念的关系")]
if "那就提示" in valid or "提示主机暂不可达" in valid:
    errors.append("withdrawn prompt leaked into effective rules")
if "concepts/host-auto-discovery-routing.md" not in Path("requirement-wiki/wiki/index.md").read_text():
    errors.append("wiki index: new page not reachable")
if "entries/101-主机自动发现与TS优先路由.md" not in Path("requirement-base/INDEX.md").read_text():
    errors.append("source index: new entry not reachable")
print("link_check=" + ("PASS" if not errors else "FAIL"))
print("source_completeness=" + ("PASS" if not any("source completeness:" in e for e in errors) else "FAIL"))
print("index_reachability=" + ("PASS" if not any("index:" in e for e in errors) else "FAIL"))
print("withdrawn_rule_check=" + ("PASS" if not any("withdrawn prompt" in e for e in errors) else "FAIL"))
if errors:
    print("\n".join(errors)); sys.exit(1)
PY
```

实际结果：

```text
psych_frontmatter=PASS
link_check=PASS
source_completeness=PASS
index_reachability=PASS
withdrawn_rule_check=PASS
```

TS 入网失败不提示、两网不可用工作区为空且不额外提示只作为最终有效规则；撤回说法仅保留在原话/更正说明。

### 2. 已跟踪文件 whitespace

命令：

```sh
git diff --check -- requirement-base/INDEX.md
```

实际结果：`diff_check_exit=0`。

### 3. 新增/被忽略 wiki 文件 whitespace

命令（可直接从项目根目录复跑）：

```sh
python3 - <<'PY'
from pathlib import Path
files=[Path("requirement-base/entries/101-主机自动发现与TS优先路由.md"),Path("requirement-wiki/wiki/concepts/host-auto-discovery-routing.md"),Path("requirement-wiki/wiki/index.md"),Path("requirement-wiki/wiki/hot.md"),Path("requirement-wiki/wiki/log.md"),Path(".team/nodes/requirements-ingest-luna/INGEST-RECEIPT.md")]
bad=[]
for p in files:
    for n,line in enumerate(p.read_text().splitlines(),1):
        if line.endswith((" ", "\t")): bad.append(f"{p}:{n}")
print("whitespace_check=" + ("PASS" if not bad else "FAIL"))
if bad:
    print("\n".join(bad)); raise SystemExit(1)
PY
```

实际结果：

```text
whitespace_check=PASS
```

### 4. 改动范围

命令（可直接从项目根目录复跑）：

```sh
git diff --name-status -- requirement-base requirement-wiki/wiki .team/nodes/requirements-ingest-luna
git status --short --untracked-files=all -- requirement-base requirement-wiki/wiki .team/nodes/requirements-ingest-luna .team/artifacts/requirements
```

实际结果（本次范围相关）：

```text
M  requirement-base/INDEX.md
?? .team/artifacts/requirements/2026-09-05-host-auto-discovery-source.md
?? .team/nodes/requirements-ingest-luna/INGEST-RECEIPT.md
?? requirement-base/entries/101-主机自动发现与TS优先路由.md
```

`requirement-wiki/wiki/**` 被 `requirement-wiki/.gitignore` 的个人 wiki 规则忽略，因此不会出现在父仓库 git diff/status；已直接检查文件存在、链接、frontmatter 和 whitespace。输入正本在开始检查时已是未跟踪文件，本次只读未改。发现的旧索引编号不一致属于既有边界，本次未修。

## 结论

本次新需求已按用户豁免完成最少必要入库，未实施产品需求，也未将未实施内容表述为实现或测试通过。等待 leader 核验；暂不提交、推送或合并。
