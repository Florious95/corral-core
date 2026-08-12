# CLAUDE.md — AI 工程源码研究知识库 Schema (v0.2)

> 本文件是这个 wiki 的运行时入口。任何 AI agent 进入本目录都先读这份文件。
> Human 与 AI 共同维护这份 schema。规则不合身就改它，不要硬撑着用。

---

## 1. 目标

- **领域**：AI 工程源码研究笔记（推理 / 训练 / 系统 / kernel / 架构）
- **用途**：
  1. 个人复习——回到一个概念能立刻找到自己当时的理解和原始材料
  2. 提问获取灵感——对 wiki 整体提问，让 AI 综合多源回答
  3. **构造创新点**——把"提问 → 答案 → 衍生灵感 → 创新假设"全部回填，并通过 `ideas/failure_reason` 防止反复探索已否决方向

**北极星指标**：半年后能否通过 `wiki/index.md` + `wiki/hot.md` 在 5 分钟内重建当时的思考脉络。

---

## 2. 三层架构

```
raw/         源材料层  · IMMUTABLE · AI 只读不写
wiki/        知识层    · AI 全权所有 · Human 只读，要改通过对话改
CLAUDE.md    协议层    · Human 与 AI 共同演化
```

`raw/` 是真理之源，**一个字都不能改**。要修订观点就到 wiki 写新版并 link 回 raw 来源。

---

## 3. wiki 目录

```
wiki/
├── index.md                # 内容目录（每次 ingest 必更新）
├── log.md                  # append-only 时间线
├── hot.md                  # ⭐ 最近 7-14 天活跃上下文，~500 字，每次 ingest/query 后刷新
├── concepts/               # 概念：attention、kv-cache、tokenization…
├── techniques/             # 算法/技巧：flash-attention、speculative-decoding、paged-attention…
├── architectures/          # 模型/架构：llama-3、mamba、moe-routing…
├── projects/               # 源码项目：vllm、sglang、llama-cpp（一个 repo 一页）
├── patterns/               # 代码/工程模式：tensor-parallel、cuda-kernel-style…
├── questions/              # 我的提问 + AI 回答 + 衍生想法
├── ideas/                  # ⭐ 从 questions 提炼的创新点候选（带生命周期 + failure_reason）
└── claims/                 # （可选）明确命题与证据，带 confidence 0.0-1.0
```

**何时建新页 vs 更新已有页**：
- entity（具体模型/算法/项目）独立成页
- concept 第一次出现就建页，后续每次有新源补充该概念时**更新已有页**
- 不确定时先 grep `index.md`

**何时建 `_context.md`**：任何子目录页面数 ≥ 10 时，AI 必须在该目录写一份 `_context.md`（150 字摘要：这个文件夹装什么、什么时候来这里找）——查询时第一站读它，决定要不要深入。

---

## 4. 命名约定（这条规则保证图谱不会碎）

- **所有文件名**：全小写、连字符、英文优先  
  `flash-attention.md`、`kv-cache.md`、`paged-attention.md`
- **中文概念**：用英文 slug + 在 frontmatter `aliases:` 列出所有中文叫法  
  例：`attention.md` 的 aliases: `[注意力, 注意力机制, Attention]`
- 文件路径不写在 wikilink 里，**只用 slug**：`[[flash-attention]]` 而不是 `[[techniques/flash-attention]]`（Obsidian 会自动解析，目录无关）
- slug 一旦定下不要改名——改名会断掉所有反向链接。要换名优先走"加 alias"

---

## 5. 页面通用模板

```markdown
---
type: concept | technique | architecture | project | pattern | question | idea | claim
slug: flash-attention
aliases: [FlashAttention, Flash Attention v3, FA3]
created: 2026-05-02
updated: 2026-05-02
sources: [raw/xxx.md, raw/yyy.pdf]
tags: [#layer/technique, #domain/inference, #status/draft]
importance: 4    # 1=小众 2=有用 3=领域标准 4=有影响力 5=里程碑
status: stub | draft | mature
---

# 标题

## TL;DR
（≤3 句话）

## 核心要点
- 要点 1（带源引用 [^1]）
- 要点 2

## 与其它概念的关系
- 上位：[xxx](../concepts/xxx.md)
- 同位/对比：[yyy](yyy.md)
- 下位/实例：[zzz](../techniques/zzz.md)

## 我的疑问与想法
（AI 可留白让 human 补，或 AI 主动提问）

## Connections                ← 必有，至少 1 条 markdown link
- [xxx](../concepts/xxx.md) — 关系说明

## Contradictions             ← 出现冲突时才有，但出现就必须写
- 与 [yyy](yyy.md) 在 X 问题上观点相反：…

## 源
[^1]: raw/具体文件.md，第 X 节
```

`questions/` 和 `ideas/` 的专属模板见 §8。

---

## 6. 链接铁律（PyCharm Ctrl+Click 原生跳转）

### 6.0 链接格式：标准 Markdown Link，**不要用 `[[wikilink]]`** ⭐

**本项目主用 PyCharm，必须用标准 markdown link 格式让 `Ctrl+Click` 原生跳转**：

| ✅ 正确 | ❌ 错误（Obsidian 原生 wikilink，PyCharm 点不动） |
|---|---|
| `[flash-attention](../techniques/flash-attention.md)` | `[[flash-attention]]` |
| `[paged-attention](paged-attention.md)` | `[[paged-attention]]` |
| `[claude-code](../architectures/claude-code.md)` | `[[claude-code]]` |

**路径规则**：
- **同目录** → `[label](sibling.md)`
- **跨目录** → 用相对路径 `[label](../concepts/xxx.md)`
- **绝对路径不用**——破坏可移植性

`build_graph.py` 同时支持两种解析所以图谱不会断。但 **AI 写文件时只产出 markdown link**，不产 `[[]]`。

### 6.1 正文链接强制规则
- **任何在 wiki 中已有页面的概念，正文第一次出现必须加链接**；同一页内第二次起可不重复
- AI 写新页时**先扫一遍 index.md**，识别哪些词需要加链接
- alias 命中也算触发——例如正文写"Flash Attention"，AI 知道它 alias 到 `flash-attention.md`，必须写成 `[Flash Attention](../techniques/flash-attention.md)`

### 6.2 双向链接表（forward link 同步写 reverse）

| 写出 forward | 必须同时执行 reverse |
|---|---|
| `concepts/A.md` 的 `Connections` 写 `[B](../techniques/B.md)` | `techniques/B.md` 的 `Connections` 追加 `[A](../concepts/A.md)` |
| `projects/P.md` 正文链 `[K](../concepts/K.md)` | `concepts/K.md` 的 `Connections` 段确保有 `[P](../projects/P.md)` |
| `questions/Q.md` 的 frontmatter `related: [[X]]`（YAML 里仍可用 wikilink 简写） | `X` 页底部 `## Linked questions` 段追加 `[Q](../questions/Q.md)` |
| `ideas/I.md` 的 `derived_from: [[Q]]`（YAML） | `Q` 页 `## Promoted to idea` 段写 `[I](../ideas/I.md)` |
| `claims/C.md` 的 `evidence: [[P]]`（YAML） | `P` 页的 `## Supports claims` 追加 `[C](../claims/C.md)` |

**注意**：frontmatter（YAML 块）里的 `related: / sources:` 等字段仍可写 `[[slug]]` 简写——那是给 lint 和 build_graph 解析的元数据，不是给人点的。**只有正文（markdown body）里的链接必须是标准 link 格式**。

**任何时候忘了写反向链接，下次 lint 会列为必修。**

### 6.3 Anchor / Block 引用
- 跳到段：`[label](../techniques/flash-attention.md#内存层级)`（标准 markdown anchor）
- 引用具体一段：在源段落末尾加 `^claim-1`，引用方写 `[label](../papers/paper.md#^claim-1)`
- AI 在 ingest 时给重要论断主动加 block-id

---

## 7. 三个核心操作

### Ingest（入库）
触发语：`ingest raw/xxx` / `把 raw 里新的内容编进 wiki`

1. 读源材料，2-3 句话向 human 总结并确认理解
2. grep `index.md`，列出本次会触碰的页面（新建 vs 更新），**先报计划再动手**
3. 创建/更新所有相关页面（一次 ingest 触碰 5-15 页是正常的）
4. 严格遵守 §6 链接铁律——**正文双链 + 反向链接全写**
5. 出现与现有 wiki 内容冲突的论断 → 写到 `## Contradictions`，**两份并存，不静默覆盖**
6. 更新 `index.md`（带 `(count)`）、刷新 `hot.md`、append `log.md`：`## [YYYY-MM-DD] ingest | 标题`

### Query（提问）
触发语：直接提问 / `query: xxx`

1. 先读 `hot.md` → `index.md` → 命中文件夹的 `_context.md` → 候选页面
2. 读 wiki 页面**优先于读 raw**（wiki 是已消化层）
3. 综合作答，每条结论标注 `[[wikilink]]` 引用
4. 答完默认追问：「这个答案要不要存成 `questions/YYYY-MM-DD-标题.md`？」  
   倾向于存——本知识库的核心目标之一就是积累问题与灵感
5. log: `## [YYYY-MM-DD] query | 问题`

### Lint（体检）
触发语：`lint` / `lint --fix` / `lint --suggest`

**模式**：
- 默认 **report-only**：列出所有问题不动手
- `--fix`：自动修可确定性修复的（缺失的反向链接、缺省 frontmatter 字段、index 漏列）
- `--suggest`：对非确定性问题（合并？拆分？升级 status？）给出建议

**10 项检查**：
1. `index.md` 漏列 / 列已删页
2. **孤儿页**（无任何反向链接）—— 强制修复
3. **应链未链**：正文裸文字命中其它页 slug 或 alias —— 强制修复
4. **断链**：`[[xxx]]` 指向不存在的页
5. 矛盾页：同事实多页陈述不一致
6. 过时：新源已修正但老页未更新（`updated < newest contributing source`）
7. stub 状态 > 30 天未升级
8. 重复：两页讲同一件事，建议合并
9. 子目录 ≥10 页缺 `_context.md`
10. `questions/` 中已闭环的问题是否回填到对应概念页

---

## 8. questions/ 与 ideas/ —— 创新点闭环

### 8.1 questions/ 模板
文件名：`YYYY-MM-DD-短-slug.md`

```markdown
---
type: question
slug: 2026-05-02-paged-attention-block-size
created: 2026-05-02
status: open | answered | followup | crystallized
related: [[paged-attention]], [[vllm]]
---

# 问题
（原始提问）

# 答案
（AI 综合 wiki 给出，带 [[wikilink]] 引用）

# 衍生想法
- 这让我想到 …
- 如果 X，会不会 …

# 后续 action
- [ ] 找 X 论文确认
- [ ] 跑实验：…

# 演化记录
- 2026-05-02 初问
- 2026-05-10 看了 X 后修订：…

# Promoted to idea         ← status=crystallized 时填
- [[idea-xxx]]
```

**status**：
- `open` 答案未定 / `answered` 满意答案 / `followup` 触发了新动作 / `crystallized` ⭐ 升华成创新点候选 → **promote 到 ideas/**

### 8.2 ideas/ 模板（创新点候选 + 反重复记忆）
文件名：`YYYY-MM-DD-短-slug.md`

```markdown
---
type: idea
slug: 2026-05-02-async-kv-prefetch
created: 2026-05-02
lifecycle: hypothesis | testing | validated | failed | shelved
derived_from: [[2026-05-02-paged-attention-block-size]]
related: [[paged-attention]], [[kv-cache]]
confidence: 0.4
---

# 假设
（一句话，可证伪）

# 动机
（为什么觉得有戏，引哪些 wiki 页面）

# 验证路径
- 实验：…
- 反例预期：…

# 演化日志
- 2026-05-02 提出
- 2026-05-15 跑了实验，结果 …

# failure_reason         ← lifecycle=failed/shelved 时必填 ⭐
（具体为什么失败/搁置——这是反重复记忆，下次想到类似方向时第一站读这里）
```

**为什么 ideas/ 单独存在而不是放 questions/**：questions 是"我当时想了什么"的快照（思考过程），ideas 是"被提炼出来的可证伪假设"（产物）。failure_reason 是这个知识库给你的最大复利价值——避免你 3 个月后重新发明同一个已经死过的轮子。

---

## 9. index.md / log.md / hot.md 格式

### index.md
```markdown
# 知识库索引
最近更新：YYYY-MM-DD · 总页数：N

## Concepts (12)
- [[attention]] — 注意力机制综述 · 5 个源 · importance 5
- ...

## Techniques (8)
- ...

## Ideas (3)
- [[2026-05-02-async-kv-prefetch]] — lifecycle: hypothesis · confidence 0.4
- ...
```
每节带 `(count)`，扫到 50 页之后还能用。

### log.md
```markdown
## [2026-05-02] ingest | flash-attention v3 论文笔记
- 新建 [[flash-attention]]
- 更新 [[attention]]，补 4.2 节
- 在 [[2026-04-21-...]] 追加新答案

## [2026-05-03] query | 为什么 paged-attention 用 16 块
- 沉淀为 [[2026-05-03-paged-attention-block-size]] (status: followup)

## [2026-05-04] lint --fix
- 补反向链接 7 条 / 重命名 1 / 列出 3 个待 human 决策
```

### hot.md（≤500 字）
最近 7-14 天最活跃的概念、悬而未决的 questions、近期 crystallized 的 ideas。**每次 ingest/query 后由 AI 刷新**。这是跨会话最便宜的"我们刚才聊到哪"的入口。

---

## 10. Tags 体系（Dataview 友好）

固定一组顶层标签，**别让 AI 自由发挥造一堆同义标签**：

- 层级：`#layer/concept` `#layer/technique` `#layer/architecture` `#layer/project` `#layer/pattern`
- 领域：`#domain/inference` `#domain/training` `#domain/system` `#domain/kernel` `#domain/data`
- 状态：`#status/stub` `#status/draft` `#status/mature`（与 frontmatter `status:` 同步）
- ideas：`#idea/hypothesis` `#idea/testing` `#idea/validated` `#idea/failed`

Obsidian 装 Dataview 后即可：「列出所有 `#idea/validated` 且 confidence ≥ 0.7」。

---

## 11. Hard rules（不可违反）

1. **`raw/` 不可写**——一字不改
2. **没有静默覆盖**——冲突 = `## Contradictions` 两份并存
3. **每页必有 `## Connections`**，至少一条 `[[]]`
4. **正文双链 + 反向链接同步写**（§6）
5. **frontmatter 必填**：`type / slug / created / updated / sources`  
   `sources:` 是 ingest diff 检测的**唯一可信来源**（见 wiki-ingest skill）。漏写或写错会直接导致重复入库或漏入库，所以宁可冗余别遗漏。
6. **index.md 每次 ingest 必更**；log.md 只能 append
7. **AI 不确定就显式说不确定**——不要编

---

## 12. 个人偏好

### 12.1 语言风格：说人话 ⭐

**所有交互、问答、回复、写入 wiki 的内容都用大白话**。这条优先级高，AI 默认会偏向"工程化文风"，必须主动收着。

**要做到的**：
- 能用一句日常话说清的，就不要堆术语
- 解释概念**先打比方再上术语**——比如解释 paged-attention 别上来就说"分块的 KV 内存管理"，先说"像操作系统给程序分页内存那样把 KV cache 切成块"
- 长句拆短，**段落不超过 3 行**，否则读起来累
- 列表优于长段落
- **不要装腔作势的开场**："让我们一起来看看…"、"接下来我会为您详细介绍…" 一律删掉，直接说事
- 不要"端到端 / 链路 / 落地 / 闭环 / 抓手 / 心智 / 复用"这类**互联网黑话**，能换就换

**反例**（AI 容易写成这样）：
> 这套系统通过端到端的工程化链路实现了知识资产的复利沉淀，形成可演进的认知闭环。

**正例**（说人话）：
> 你扔进新材料 → AI 把它编进 wiki → 下次提问 wiki 越来越厚。所以越用越聪明。

### 12.2 其它格式偏好

- 中文为主；专有名词、API、代码片段保留英文
- 数学用 KaTeX
- 引源材料给具体位置（章节号 / 行号 / 时间戳）
- 不确定就说"不确定"，不要硬编

---

## 13. Schema 演化

每跑 5 次 ingest 或每次 lint，AI 主动问：「schema 哪条规则不顺手？要不要改 CLAUDE.md？」  
修改 schema 时在 log.md 记：`## [日期] schema | 改动说明`
