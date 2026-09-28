# 自建知识库 · LLM Wiki

> 基于 [Karpathy 的 LLM Wiki 思想](https://gist.github.com/karpathy/442a6bf555914893e9891c11519de94f) 的开箱即用实现 —— 用 Claude Code 把原始材料编译成会自我维护、可视化的个人 wiki，让知识利滚利。
>
> 替代传统 RAG。原材料 → AI 编译成结构化双链 wiki → 提问、提炼、产出创新点 → 全部回填 → 知识不再阅后即焚。

---

## ✨ 这个仓库给你什么

- 一份成熟的 **`CLAUDE.md` schema**（页面类型学、双链铁律、命名约定、lint 规则）
- 四个 **Claude Code skills**：`wiki-ingest` / `wiki-query` / `wiki-lint` / `wiki-graph`，开 Claude Code 就自动加载
- 一个 **D3 力导向图谱**（`_graph.html`），节点单击直接跳到 PyCharm 编辑器对应文件
- 一个**跨平台自适应** HTTP 中转服务（自动找 PyCharm、自动选端口）
- 三层架构：`raw/`（不可写真理之源）+ `wiki/`（AI 维护知识层）+ `CLAUDE.md`（AI 协议）

---

## 🚀 快速开始（5 分钟）

### 0. 你需要

| 必须 | 可选（强烈建议） |
|---|---|
| Claude Code（`claude` CLI）| PyCharm（用作可视化编辑器 + 单击图谱跳转） |
| Python 3.8+ | Obsidian（也能用，但 PyCharm 体验更好） |
| Git（仅用于 clone） | |

### 1. Clone

```bash
git clone https://github.com/<your-fork>/自建知识库.git my-kb
cd my-kb
```

### 2. 第一次跑 Claude Code

```bash
claude
```

启动后第一句话验证 skills 加载：

```
列出当前加载到的 skill
```

应该看到 `wiki-ingest / wiki-query / wiki-lint / wiki-graph` 四个。

### 3. 喂第一批材料

把你的研究材料（.md / .pdf / 文章剪藏 ...）丢进 `raw/`，然后：

```
ingest
```

`wiki-ingest` 会扫 `raw/` → 报新增/修改/跳过/失联四态 → 等你确认 → 按 `CLAUDE.md` 写入 wiki，最后自动调 `wiki-graph` 刷图。

### 4. 启 wiki-open-server（让图谱节点能跳）

新开一个终端（或在 PyCharm Run Configuration 里），跑：

```bash
python3 .claude/skills/wiki-graph/scripts/wiki_open_server.py
```

预期输出：
```
✅ wiki-open-server   http://127.0.0.1:7777
   PROJECT_ROOT      /path/to/my-kb
   PyCharm           /Applications/PyCharm.app/Contents/MacOS/pycharm   ← 自动找到
   Platform          darwin
   Port file         /path/to/my-kb/.wiki-runtime/port
```

如果没自动找到 PyCharm：
```bash
export PYCHARM_PATH=/your/explicit/path/to/pycharm[64.exe|.sh]
python3 .claude/skills/wiki-graph/scripts/wiki_open_server.py
```

### 5. 看图、跳转

PyCharm 打开 `wiki/_graph.html` → 右键 → Open in → Browser → Built-in（或用 Markdown Preview pane）。

点节点 → 服务转发 → PyCharm 自动跳到对应文件 tab。**单窗口、近原生体验。**

### 6. 之后的日常

```
ingest                            # 有新材料就扫
（直接提问）                       # AI 综合 wiki 给答案，可沉淀为 question/idea
lint                              # 每 10-15 次 ingest 跑一次体检
lint --fix                        # 自动修可确定性问题
```

---

## 🏗️ 架构

```
my-kb/
├── CLAUDE.md                     ← 协议层：schema、链接铁律、lint 规则
├── README.md                     ← 你正在看的这个
├── .gitignore                    ← 排除你的个人内容 + 生成物
│
├── .claude/
│   └── skills/
│       ├── wiki-ingest/SKILL.md       ← 入库（自动诊断 New/Modified/Unchanged/Dangling）
│       ├── wiki-query/SKILL.md        ← 提问（答完追问要不要沉淀）
│       ├── wiki-lint/SKILL.md         ← 健康检查（report/--fix/--suggest 三档）
│       └── wiki-graph/
│           ├── SKILL.md               ← 触发：graph / 图谱 / 刷图
│           └── scripts/
│               ├── build_graph.py     ← 解析 wiki，输出 _graph.html
│               └── wiki_open_server.py ← localhost 服务，跳转 PyCharm
│
├── raw/                          ← 源材料层（IMMUTABLE，AI 只读）
│   └── （你的论文、笔记、剪藏 ...）
│
└── wiki/                         ← 知识层（AI 全权维护）
    ├── index.md                  ← 目录
    ├── log.md                    ← append-only 时间线
    ├── hot.md                    ← 最近活跃上下文（≤500 字）
    ├── concepts/                 ← 概念
    ├── techniques/               ← 算法/技巧
    ├── architectures/            ← 模型/架构
    ├── projects/                 ← 源码项目
    ├── patterns/                 ← 工程模式
    ├── questions/                ← 你的提问 + AI 答案 + 衍生想法
    ├── ideas/                    ← 创新点候选（带 failure_reason 反重复记忆）
    ├── claims/                   ← 命题 + confidence + 证据（可选）
    └── _graph.html               ← 生成物，PyCharm 内置浏览器看
```

**三层心智模型**（来自 Karpathy）：
> Obsidian 是 IDE，Claude Code 是程序员，wiki 是代码库，CLAUDE.md 是编程规范，raw 是需求文档。

---

## 🎯 核心理念

### vs 传统 RAG

| 维度 | RAG | 本方案 |
|---|---|---|
| 知识形态 | 阅后即焚的 chunk 检索 | 持续整理的 wiki 文章 |
| 增量 | 每次问从零开始 | 每次问/读都让 wiki 更厚 |
| 矛盾处理 | 无 | `## Contradictions` 段并存 |
| 创新点跟踪 | 无 | `ideas/` + `failure_reason` 反重复 |

### 双链 + 类型学 + Agent 三层支柱

1. **双链格式 `[[]]` + `[label](path.md)`**：连接组织
2. **页面类型学**（concept / technique / architecture / project / pattern / question / idea / claim）：让图谱有形状
3. **Agent skill 协议**（CLAUDE.md + .claude/skills/*）：自动化纪律

---

## ⚙️ 配置

### 环境变量

| 变量 | 作用 | 默认 |
|---|---|---|
| `PYCHARM_PATH` | PyCharm 可执行文件路径，覆盖自动探测 | 自动探测 |
| `WIKI_OPEN_PORT` | 服务监听端口 | 7777（被占自动选空闲） |

### 不用 PyCharm？

`wiki_open_server.py` 是为 PyCharm 设计的，但你可以改 `subprocess.Popen` 那行调 VSCode、Sublime、`code --reuse-window` 等。或者直接不跑这个服务，把图谱当只读视图——单击不跳，但 D3 交互、hover、搜索全在。

### 不喜欢这套 schema？

`CLAUDE.md` 是给 AI 的契约，**改它就改了全部**。你完全可以把页面类型从 8 类改 4 类、把 §6 链接铁律调松、把 lint 项删掉。Schema 设计要为你的领域服务，不是教条。

---

## 🤝 灵感来源

- **思想**：[Andrej Karpathy — LLM Wiki gist](https://gist.github.com/karpathy/442a6bf555914893e9891c11519de94f)
- **实现参考**：
  - [skyllwt/OmegaWiki](https://github.com/skyllwt/OmegaWiki) — 双向链接表 + ideas/failure_reason
  - [Pratiyush/llm-wiki](https://github.com/Pratiyush/llm-wiki) — `## Connections` / `## Contradictions` 硬规则
  - [AgriciDaniel/claude-obsidian](https://github.com/AgriciDaniel/claude-obsidian) — `hot.md` 最近上下文

---

## 📝 License

MIT —— 拿走改、商用都行。如果你做了有意思的扩展欢迎 PR 回来。
