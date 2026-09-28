---
name: wiki-query
description: 对 wiki/ 提问，AI 综合多页给出带引用的答案，并追问要不要把答案沉淀回 questions/。当用户在本知识库目录下提任何关于 wiki 内容的问题、说「query: xxx」「问一下」「wiki 里 X 怎么说」「综合一下 X」时触发。这是三大核心操作之一（ingest / query / lint），承担"提问获取灵感、构造创新点"的目标。
---

# wiki-query

按 CLAUDE.md §7 Query 流程作答。

## 触发后第一件事

读 `CLAUDE.md`（重点 §6 链接、§7 Query、§8 questions/ideas 模板）。

## 工作流

1. **读 hot.md** 取最近活跃上下文
2. 读 `wiki/index.md` 找候选页面
3. 命中文件夹若有 `_context.md` 先读它再决定要不要深入
4. 读相关 wiki 页面（**优先于读 raw**，wiki 是已消化层）
5. 综合作答，每条结论标 `[[wikilink]]` 引用
6. 答完**默认追问**：「这个答案要不要存成 `wiki/questions/YYYY-MM-DD-短-slug.md`？」
   - 用户同意 → 按 CLAUDE.md §8.1 模板写
   - 默认倾向于存——本知识库目标之一就是积累问题与灵感
7. 若用户答案中冒出可证伪的创新假设 → 追问：「要不要 promote 成 `ideas/` 页面？」按 §8.2 模板
8. append log.md：`## [YYYY-MM-DD] query | 问题`
9. **链 wiki-graph（条件调用）**：仅当本轮在 `wiki/questions/` 或 `wiki/ideas/` 落盘了新文件时，调用 `wiki-graph` skill 刷新 `_graph.html`。纯口头答复未沉淀则跳过。

## 硬规则

- 找不到答案就说找不到，不要从训练数据里编
- 引用必须落到具体 wiki 页（最好到 anchor）
- 触发新动作（如建议跑实验）→ 写到 question 的 `# 后续 action`