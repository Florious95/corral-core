---
name: wiki-lint
description: 对 wiki/ 跑健康检查，找孤儿页、断链、应链未链、矛盾、过时、缺失反向链接等。当用户说「lint」「体检」「健康检查」「检查 wiki」或类似意图时触发。这是三大核心操作之一（ingest / query / lint），定期跑（建议每 10-15 次 ingest 一次）防止知识库腐烂。
---

# wiki-lint

按 CLAUDE.md §7 Lint 工作流执行 10 项检查。

## 触发后第一件事

读 `CLAUDE.md` §6 链接铁律 + §7 Lint。

## 模式

解析参数：
- 默认 `report-only`：只报告不改
- `lint --fix`：自动修可确定性修复（缺失反向链接、缺省 frontmatter 字段、index 漏列）
- `lint --suggest`：对非确定性问题（合并？拆分？升级 status？）给建议
- `lint --fix --dry-run`：预览将修复什么但不写

## 10 项检查

1. `index.md` 漏列页 / 列已删页
2. **孤儿页**（无任何反向 `[[]]`）—— 强制修复级别
3. **应链未链**：grep wiki 正文裸文字命中其它页 slug 或 frontmatter aliases —— 强制修复
4. **断链**：`[[xxx]]` 指向不存在页
5. **矛盾页**：同事实在多页有不一致陈述
6. **过时**：页面 `updated` 早于其 `sources` 中最新源的时间
7. **stub 滞留**：status=stub 超过 30 天
8. **重复**：两页讲同一件事（用 frontmatter slug + aliases + TL;DR 相似度判断）
9. **子目录 ≥10 页缺 `_context.md`**
10. **questions 闭环未回填**：status=answered/crystallized 的 question，对应概念页是否已纳入其要点
11. **Dangling sources**：wiki 页 frontmatter 的 `sources:` 引用了 raw/ 下已不存在的文件 —— 强制让 human 决策（恢复/解引用/归档）

## 输出格式

按严重度分组：

```markdown
# Lint Report — YYYY-MM-DD

## 🔴 Must fix (强制级)
- 孤儿页: [[xxx]]（无反向链接）
- 应链未链: [[yyy]] 第 12 行裸写"FlashAttention"应改为 [[flash-attention]]
- 断链: [[zzz]] 不存在

## 🟡 Should fix
- 矛盾: [[a]] vs [[b]] 在 X 论断上不一致
- 过时: [[c]] 的 sources 里 raw/new.md 比 updated 新

## 🔵 Suggestions
- 合并候选: [[d]] 与 [[e]] 的 TL;DR 相似度 > 0.8
- stub 升级: [[f]] 已存在 35 天，可考虑升 draft
```

## --fix 模式额外动作

执行后 append log.md：

```markdown
## [YYYY-MM-DD] lint --fix
- 补反向链接 N 条：[[a]]→[[b]], [[c]]→[[d]], ...
- 修缺省 frontmatter N 处
- 修 index.md 漏列 N 条
- 列出 K 个待 human 决策项（见 lint-report-YYYY-MM-DD.md）
```

非 --fix 模式仅在用户同意后写 `wiki/lint-report-YYYY-MM-DD.md`。

## 链 wiki-graph（条件调用）

- **`lint --fix` 实际修改了文件** → 调用 `wiki-graph` 刷 `_graph.html`
- **report-only 模式 / --fix 但无修改 / --suggest** → 不调用