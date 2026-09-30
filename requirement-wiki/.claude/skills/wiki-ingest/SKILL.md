---
name: wiki-ingest
description: 把 raw/ 里的源材料按 CLAUDE.md schema 编入 wiki/。当用户说「ingest」「入库」「编进 wiki」「把 raw 整理成 wiki」「ingest raw/xxx」或类似意图时立刻触发。这是本知识库三大核心操作之一（ingest / query / lint），承担把原始素材消化为结构化 wiki 页面的工作。**必须先报计划等用户确认再动手**。
---

# wiki-ingest

把 `raw/` 中的源材料按 `CLAUDE.md` 编入 `wiki/`，遵循三层架构（raw 不写、wiki AI 写、CLAUDE.md 协议）。

## 触发后第一件事

读 `CLAUDE.md` 全文（特别是 §3 目录、§4 命名、§5 模板、§6 链接铁律、§7 Ingest 流程、§11 hard rules）。如果 `CLAUDE.md` 不在当前目录，停下问用户当前 cwd 是不是知识库根目录。

## 参数解析

用户可能这样说：
- 不带参数 → ingest 所有 **New + Modified** 的 raw 文件（按 §Diff 检测协议）
- `ingest raw/xxx.md` → 单文件（强制 ingest，无论状态）
- `ingest raw/某子目录/` → 该目录下所有 New + Modified
- `ingest --dry-run` → 只报状态与计划，不动手
- `ingest --force` → 全量重做（连 Unchanged 也重处理，慎用，会造成大量无意义改动）
- `ingest --status` → 只报告四类状态分布，不进入计划阶段

## Diff 检测协议（每次触发后第 0 步必跑）

判断哪些文件需要 ingest，**不要靠记忆或字面比对 log.md**，按下面流程算：

### 步骤
1. **A** = 列出 `raw/` 下所有支持的源文件（递归，排除 `raw/assets/` 或纯图片/二进制）
2. **B** = 扫描 `wiki/**/*.md` 所有 frontmatter，把每个页的 `sources:` 数组并起来 —— 这是"已消化集合"
3. **W** = 同时记录每个 raw 文件被哪些 wiki 页引用，以及那些 wiki 页的 `updated:` 字段最大值

### 四态判定

| 状态 | 条件 | 处理 |
|---|---|---|
| **New** | `f ∈ A` 且 `f ∉ B` | 默认 ingest |
| **Modified** | `f ∈ A ∩ B` 且 `mtime(f) > max(updated of wiki pages citing f)` | 默认 ingest（增量更新模式：只补差异） |
| **Unchanged** | `f ∈ A ∩ B` 且 `mtime(f) ≤ max(updated)` | 跳过（除非 `--force`） |
| **Dangling** | `f ∈ B` 且 `f ∉ A` | ⚠️ 报警：wiki 引用了已不存在的 raw 文件，让 human 决定改链/删页/恢复源 |

### 输出（计划阶段先报这个）

```
=== Diff Status ===
New (3):
  - raw/papers/flash-attention-v3.pdf
  - raw/notes/2026-04-vllm-reading.md
  - raw/web/karpathy-tweet.md

Modified (1):
  - raw/notes/sglang-deep-dive.md
    └─ 引用页 [[sglang]] (updated 2026-04-15)，源文件 mtime 2026-05-01 ⚠️ 落后
       计划：增量补充新增章节，不重写整页

Unchanged (12):
  - 跳过（用 --force 强制重做）

Dangling (1):
  ⚠️ raw/notes/old-cuda-kernel.md 已不在文件系统，但被 [[cuda-patterns]] 引用
     选项：
       (a) human 恢复源文件
       (b) 从 [[cuda-patterns]] 移除该 source 引用
       (c) 把整页归档到 wiki/_archive/

请选择如何处理 Dangling，再确认 New + Modified 的执行计划。
```

### 实现提示
- 用 grep + Read 读 frontmatter；frontmatter 必有 `sources:` 字段（CLAUDE.md §11 hard rule）
- mtime 用 `stat` 或 `ls -l --time-style=full-iso` 取
- 对超过 50 个 raw 文件的库，先输出汇总数字（`New 3 / Modified 1 / Unchanged 47 / Dangling 1`），再列具体文件，避免输出爆炸

## 工作流（严格按序）

### 0. Diff 检测（见上节）—— 每次必跑

跑完得到 New / Modified / Unchanged / Dangling 四态。Dangling 必须先与 human 解决再进下一步。

### 1. 报计划阶段（不动手）

只对 New + Modified 文件，对每个输出：

```
=== raw/xxx.md ===
核心主题：（一句话）
importance：1-5（按 §5 frontmatter 标准估）
本次将触碰的 wiki 页面：
  新建：[[slug-1]] (concepts), [[slug-2]] (techniques)
  更新：[[existing-slug]] (补 §X 节)
冲突预警：与 [[yyy]] 在 Z 论断上可能冲突 → 计划写 ## Contradictions
```

全部列完之后**停下来**，向用户确认：「以上计划是否动手？需要调整哪一条？」

### 2. 执行阶段（用户确认后）

按 CLAUDE.md §7 Ingest 流程：
1. 读源文件
2. 创建/更新所有规划的页面，遵守 §5 模板（frontmatter 必填、`## Connections` 必有、importance 必标）
3. **§6 链接铁律必须执行**：
   - **⭐ 链接格式**：正文里所有内部链接**必须用标准 markdown link** `[label](relative/path.md)`，**不要用 `[[wikilink]]`**——本项目主战场是 PyCharm，markdown link 才能 `Ctrl+Click` 原生跳转。
     - 同目录：`[label](sibling.md)`
     - 跨目录：`[label](../concepts/xxx.md)`
     - 例外：frontmatter（YAML）里的 `related: sources: derived_from:` 等元数据字段可以写 `[[slug]]` 简写，因为那是给 lint/build_graph 读的，不是给人点的
   - **应链未链强制**：正文中命中其它已有 slug 或 alias 的概念，第一次出现必须加链接
   - **双向同步**：写 forward link 时按 §6.2 表立刻补 reverse
   - 给重要论断加 `^block-id`，引用方写 `[label](path.md#^block-id)`
4. 冲突 → `## Contradictions` 段，两份并存绝不静默覆盖
5. 更新 `wiki/index.md`（每节 `(count)` 同步）
6. 刷新 `wiki/hot.md`（≤500 字，覆盖本次新增的活跃概念）
7. append `wiki/log.md`：

```markdown
## [YYYY-MM-DD] ingest | 标题
- 新建 [[xxx]] [[yyy]]
- 更新 [[zzz]]，补 X 节
- 反向链接同步 N 条
- 冲突 1 处：见 [[zzz#Contradictions]]
```

### 3. 收尾

向用户报告：
- 触碰了多少页面（新 X、更新 Y）
- 是否有需要 human 决策的悬而未决项（concept 该归哪一类、命名歧义等）
- 提示下一步：可以 `wiki-query` 或 `wiki-lint`

### 4. 链 wiki-graph

ingest 必然改了 wiki，**调用 `wiki-graph` skill 刷新 `_graph.html`**。报输出节点数/边数，让用户感知图谱已最新。

### 5. 确保 wiki-open-server 在跑（自动后台启动）⭐

用户跑 ingest = 在认真用知识库，所以**默认就该让点节点跳 PyCharm 这条链路工作**。不要只通知用户"server 没跑"——主动起一个。

**5.1 健康检查**：
```bash
PORT=$(cat .wiki-runtime/port 2>/dev/null || echo 7777)
HEALTH=$(curl -s -m 2 -o /dev/null -w "%{http_code}" "http://127.0.0.1:${PORT}/health" 2>/dev/null || echo 000)
echo "health: $HEALTH (port $PORT)"
```

**5.2 如果 HEALTH ≠ 200，后台启动**：

用 Python `subprocess.Popen` 跨平台 detach（不依赖 nohup/setsid，Windows 也能跑）：

```bash
python3 - <<'PY'
import os, subprocess, sys
SCRIPT = ".claude/skills/wiki-graph/scripts/wiki_open_server.py"
flags = {"stdin": subprocess.DEVNULL, "stdout": subprocess.DEVNULL, "stderr": subprocess.DEVNULL}
if os.name == "nt":
    flags["creationflags"] = subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP
else:
    flags["start_new_session"] = True
subprocess.Popen([sys.executable, SCRIPT], **flags)
print("spawned wiki-open-server")
PY
```

如果 `python3` 不可用，依次降级到 `python` / `py`。

**5.3 验证**：等 1.5 秒，重做 health check：

```bash
sleep 1.5
PORT=$(cat .wiki-runtime/port 2>/dev/null || echo 7777)
curl -s -m 2 "http://127.0.0.1:${PORT}/health"
```

返回 `ok` → 启动成功，把端口报给用户。
非 200 → 说明启动失败，告诉用户去手动跑一次（保持原 fallback）。

**5.4 不要做的事**：
- ❌ 不要前台 `python3 wiki_open_server.py`（会卡住整个会话）
- ❌ 不要假定 server 已经在跑就跳过 5.2（必须先 health check 验证）
- ❌ 不要重复启动多个实例（5.1 已在跑就不要再起）
- ❌ **不要主动调 `pycharm64.exe` / `pycharm` / `idea` 等 IDE 启动命令**——这会关掉用户已开的 PyCharm 窗口，造成数据丢失。**`wiki_open_server.py` 是被动监听服务，启动它是安全的；主动启动 IDE 不安全**

## 硬规则提醒

- raw/ 一字不改
- 不确定就问用户，不要编内容
- 中文专有名词写英文 slug + 中文 alias
- 报计划阶段不要写文件，只说话