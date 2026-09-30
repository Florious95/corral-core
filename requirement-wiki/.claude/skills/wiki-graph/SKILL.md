---
name: wiki-graph
description: 重新生成 wiki/_graph.html 交互图谱。当用户说「graph」「图谱」「刷图」「rebuild graph」「更新图谱」「画一下关系图」时触发。也被 wiki-ingest / wiki-query / wiki-lint 在末尾按需调用——任何会让 wiki 文件落盘或修改的操作完成后，都应调本 skill 让图同步。
---

# wiki-graph

**唯一职责**：跑 `scripts/build_graph.py`，重新产出 `wiki/_graph.html`。

不解析 wiki、不写文档——所有逻辑在脚本里，skill 只负责「何时跑」和「报告结果」。

## 工作流

1. 确认 cwd 是项目根（有 `CLAUDE.md` 和 `.claude/skills/wiki-graph/scripts/build_graph.py`）。否则停下问用户。
2. 跑（按顺序尝试，第一个成功就停）：
   ```bash
   python3 .claude/skills/wiki-graph/scripts/build_graph.py
   python  .claude/skills/wiki-graph/scripts/build_graph.py
   py      .claude/skills/wiki-graph/scripts/build_graph.py
   ```
3. 解析输出，向用户报：
   - 节点数 / 边数 / 端口
   - 输出文件路径 `wiki/_graph.html`
   - 首次提示：在 PyCharm 内右键该文件 → Open in → Built-in Browser
4. 检查 wiki-open-server 是否在跑（可选）：
   ```bash
   PORT=$(cat .wiki-runtime/port 2>/dev/null || echo 7777)
   curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:${PORT}/health"
   ```
   返回 200 → 服务在跑，节点点击会跳 PyCharm
   非 200 → 提醒用户：「点节点跳转需要先启动 wiki_open_server，建议在 PyCharm Run Configuration 里跑 `.claude/skills/wiki-graph/scripts/wiki_open_server.py`」

## 失败兜底

- 脚本不存在 → 告知用户 `.claude/skills/wiki-graph/scripts/` 下文件缺失，让用户检查项目结构
- 脚本报错 → 把 stderr 原样转给用户，不要自行解读 / 修脚本（除非用户明确请求）
- wiki/ 为空（首次 ingest 之前）→ 脚本会自己跳过，skill 把这条信息透传给用户
- 所有 python 命令都失败（系统没装 Python） → 提示用户装 Python 3.8+ 并加到 PATH

## 调用方时机判断（被 ingest/query/lint 链时）

- `wiki-ingest` 末尾：**总是调用**（ingest 必然改 wiki）
- `wiki-query` 末尾：**仅当本次新增了 questions/ 或 ideas/ 文件时调用**；纯提问无沉淀则跳过
- `wiki-lint --fix` 末尾：**仅当 --fix 实际修改了文件时调用**；report-only 不调
- `wiki-lint` 默认（report-only）：**不调**
